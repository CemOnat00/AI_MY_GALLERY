package com.ktu.aigaleri.ui.search

import androidx.work.WorkInfo
import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.ml.SingleSessionSlot
import com.ktu.aigaleri.ui.IndexLauncher
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import com.ktu.aigaleri.work.IndexWork
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * T-008 QA: SearchWarmUp ek sınır durumları. T-016: eski KNOWN_ISSUE testleri doğru davranışa güncellendi (bekleyen iş
 * ısınmayı ertelemez; ısınma sürerken bırakılan oturum `warmed`'ı true bırakmaz).
 */
class SearchWarmUpKnownIssuesTest {
    private var hasIndex = true
    private var indexing = false
    private var warmCalls = 0

    private fun policy(warm: suspend () -> Unit = { warmCalls++ }) =
        SearchWarmUp({ hasIndex }, { indexing }, warm)

    /** isIndexing, AppDependencies'teki eşlemeyle aynı kod yolu: [IndexLauncher.isIndexingNow] (T-016). */
    private fun launcherFor(vararg states: WorkInfo.State) = object : IndexLauncher {
        override val runState = flowOf(IndexWork.runState(states.toList()))
        override fun launch(mode: IndexMode) = Unit
    }

    private fun indexingFor(vararg states: WorkInfo.State): suspend () -> Boolean = { launcherFor(*states).isIndexingNow() }

    /** Pil kısıtıyla kuyrukta bekleyen (ENQUEUED/BLOCKED) iş ısınmayı ertelemez; ısınma yapılır. */
    @Test
    fun enqueuedOrBlockedWork_doesNotDeferWarmUp() = runBlocking {
        for (s in listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED)) {
            warmCalls = 0
            val p = SearchWarmUp({ true }, indexingFor(s), { warmCalls++ })
            assertTrue(s.name, p.run())
            assertEquals(s.name, 1, warmCalls)
        }
    }

    /** Gerçekten çalışan (RUNNING) iş ısınmayı hâlâ erteler; iş bitince ısınır. */
    @Test
    fun runningWork_defersWarmUp_untilItEnds() = runBlocking {
        val state = java.util.concurrent.atomic.AtomicReference(WorkInfo.State.RUNNING)
        val p = SearchWarmUp({ true }, { launcherFor(state.get()).isIndexingNow() }, { warmCalls++ })
        repeat(3) { assertFalse(p.run()) }
        assertEquals(0, warmCalls)
        state.set(WorkInfo.State.SUCCEEDED)
        assertTrue(p.run())
        assertEquals(1, warmCalls)
    }

    /**
     * KABUL EDİLEN SINIR (SearchWarmUp düzeyi): politika yalnızca kendi bayrağını bilir; iş WAITING iken ısınıp sonra
     * RUNNING olursa tekrar çıkarım yapmaz. Gerçek bağlantıda indeksleme görüntü oturumunu açınca metin oturumu slot'tan
     * çıkar ve `onClosed` geri çağrısı `onSessionReleased`'ı tetikler (bkz. [evictionByImageSession_resetsWarmFlag]).
     */
    @Test
    fun waitingThenRunning_afterWarm_policyAloneDoesNotRewarm() = runBlocking {
        val state = java.util.concurrent.atomic.AtomicReference(WorkInfo.State.ENQUEUED)
        val p = SearchWarmUp({ true }, { launcherFor(state.get()).isIndexingNow() }, { warmCalls++ })
        assertTrue(p.run())
        state.set(WorkInfo.State.RUNNING)
        assertTrue(p.run())
        assertEquals(1, warmCalls)
    }

    /** Isınma çıkarımı sürerken oturum bırakılırsa `warmed` true KALMAZ; sonraki çağrı yeniden ısıtır. */
    @Test
    fun releaseDuringWarmUp_doesNotLeaveWarmedFlagTrue_andNextRunRewarms() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var first = true
        val p = policy {
            warmCalls++
            if (first) {
                first = false
                started.complete(Unit)
                gate.await()
            }
        }
        val firstRun = async(Dispatchers.Default) { p.run() }
        started.await()
        p.onSessionReleased() // trim, çıkarım sürerken geldi
        gate.complete(Unit)
        assertFalse(firstRun.await()) // bırakılan oturum için "ısınmış" denmez
        assertTrue(p.run()) // yeniden ısıtır
        assertEquals(2, warmCalls)
        assertTrue(p.run()) // artık ısınmış
        assertEquals(2, warmCalls)
    }

    /** Bırakma ısınmadan ÖNCE ve sonra gelirse eski davranış korunur. */
    @Test
    fun releaseAfterWarmCompleted_stillRewarms_andReleaseBeforeRunIsHarmless() = runBlocking {
        val p = policy()
        p.onSessionReleased()
        assertTrue(p.run())
        assertEquals(1, warmCalls)
        p.onSessionReleased()
        assertTrue(p.run())
        assertEquals(2, warmCalls)
    }

    /** Slot eviction (görüntü oturumu açılır) -> onClosed -> bayrak sıfırlanır: bayat "ısınmış" kalmaz. */
    @Test
    fun evictionByImageSession_resetsWarmFlag() = runBlocking {
        val slot = SingleSessionSlot()
        lateinit var p: SearchWarmUp
        p = policy {
            warmCalls++
            slot.withSession("text", open = { AutoCloseable { } }, onClosed = { p.onSessionReleased() }) { }
        }
        assertTrue(p.run())
        assertEquals(1, warmCalls)
        slot.withSession("image", open = { AutoCloseable { } }) { } // metin oturumunu çıkarır
        assertTrue(p.run())
        assertEquals(2, warmCalls)
        slot.closeAll()
    }

    /** Isınma sırasında iptal: bayrak değişmez (warmed false), sayaç bozulmaz; sonraki çağrılar doğru çalışır. */
    @Test
    fun cancelDuringWarmUp_leavesFlagFalse_andLaterRunsAndReleasesStillWork() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var blockFirst = true
        val p = policy {
            warmCalls++
            if (blockFirst) {
                blockFirst = false
                started.complete(Unit)
                gate.await()
            }
        }
        val job = launch(Dispatchers.Default) { p.run() }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, warmCalls)
        assertTrue(p.run()) // iptal warmed'ı true yapmadı: yeniden ısıtır
        assertEquals(2, warmCalls)
        p.onSessionReleased()
        assertTrue(p.run())
        assertEquals(3, warmCalls)
    }

    /** A bırakmadan önce, B sonra başlar (A hâlâ sürerken): B ısıtır; A'nın bayat sonucu B'nin bayrağını bozmaz. */
    @Test
    fun runStartedBeforeAndAfterRelease_resultsAreCorrect() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val aStarted = CompletableDeferred<Unit>()
        var first = true
        val p = policy {
            warmCalls++
            if (first) {
                first = false
                aStarted.complete(Unit)
                gate.await()
            }
        }
        val a = async(Dispatchers.Default) { p.run() }
        aStarted.await()
        p.onSessionReleased()
        assertTrue(p.run()) // B: bırakmadan sonra, A sürerken
        assertEquals(2, warmCalls)
        gate.complete(Unit)
        assertTrue(a.await()) // A bittiğinde B'nin ısınması geçerli: oturum ısınmış durumda
        assertTrue(p.run())
        assertEquals(2, warmCalls) // bayrak korunur, ek çıkarım yok
    }

    @Test
    fun warmsImmediately_whenIndexingFinishes_andIndexNowExists() = runBlocking {
        hasIndex = false
        indexing = true
        val p = policy()
        assertFalse(p.run())
        hasIndex = true
        assertFalse(p.run()) // indeksleme sürüyor
        indexing = false
        assertTrue(p.run())
        assertEquals(1, warmCalls)
    }

    @Test
    fun checksThrowing_propagate_andLeaveNotWarm() = runBlocking {
        var fail = true
        val p = SearchWarmUp({ if (fail) throw IllegalStateException("db") else true }, { false }, { warmCalls++ })
        try {
            p.run()
            fail()
        } catch (_: IllegalStateException) {
        }
        fail = false
        assertTrue(p.run())
        assertEquals(1, warmCalls)
    }

    @Test
    fun concurrentRuns_warmAtMostOncePerCaller_andEndWarm() = runBlocking {
        val p = policy()
        val results = (1..16).map { async(Dispatchers.Default) { p.run() } }.awaitAll()
        assertTrue(results.all { it })
        assertTrue(warmCalls in 1..16)
        val before = warmCalls
        assertTrue(p.run())
        assertEquals(before, warmCalls) // ısındıktan sonra yeniden çıkarım yok
    }

    @Test
    fun repeatedReleases_whileNotWarm_areHarmless() = runBlocking {
        val p = policy()
        repeat(5) { p.onSessionReleased() }
        assertTrue(p.run())
        assertEquals(1, warmCalls)
    }
}
