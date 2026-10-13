package com.ktu.aigaleri.ui.search

import androidx.work.WorkInfo
import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.ml.SingleSessionSlot
import com.ktu.aigaleri.ui.IndexLauncher
import com.ktu.aigaleri.work.IndexWork
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** T-016 QA: ısınma politikası ek kenar durumları (WorkInfo karışımları, iptal, eşzamanlılık, release hatası, slot yeniden girişi). */
class SearchWarmUpT016QaTest {
    private val warmCalls = AtomicInteger()

    private fun launcherFor(states: List<WorkInfo.State>) = object : IndexLauncher {
        override val runState = flowOf(IndexWork.runState(states))
        override fun launch(mode: IndexMode) = Unit
    }

    private fun warmUpFor(states: List<WorkInfo.State>) =
        SearchWarmUp({ true }, { launcherFor(states).isIndexingNow() }, { warmCalls.incrementAndGet(); Unit })

    /** WorkInfo -> ısınma kararı: yalnızca RUNNING içeren listede ertelenir. */
    @Test
    fun deferral_onlyWhenAnyWorkIsRunning() = runBlocking {
        val cases = mapOf(
            emptyList<WorkInfo.State>() to true,
            listOf(WorkInfo.State.BLOCKED) to true,
            listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED) to true,
            listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED) to true,
            listOf(WorkInfo.State.RUNNING) to false,
            listOf(WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED) to false,
            listOf(WorkInfo.State.CANCELLED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING) to false,
        )
        for ((states, shouldWarm) in cases) {
            warmCalls.set(0)
            assertEquals("$states", shouldWarm, warmUpFor(states).run())
            assertEquals("$states", if (shouldWarm) 1 else 0, warmCalls.get())
        }
    }

    /** Ertelenen ısınma bayrağı true yapmaz; iş WAITING'e (pil kısıtı) geçince ısınır. */
    @Test
    fun deferredRun_thenWaiting_warms() = runBlocking {
        val state = AtomicReference(listOf(WorkInfo.State.RUNNING))
        val p = SearchWarmUp({ true }, { launcherFor(state.get()).isIndexingNow() }, { warmCalls.incrementAndGet() })
        assertFalse(p.run())
        state.set(listOf(WorkInfo.State.ENQUEUED))
        assertTrue(p.run())
        assertEquals(1, warmCalls.get())
    }

    /** İptal edilen ısınmadan sonra: hemen yeniden ısınma, sonra bırakma + yeniden ısınma. */
    @Test
    fun cancelledWarmUp_thenRewarm_thenReleaseThenRewarm() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        var first = true
        val p = SearchWarmUp({ true }, { false }, {
            warmCalls.incrementAndGet()
            if (first) {
                first = false
                started.complete(Unit)
                never.await()
            }
        })
        val job = launch(Dispatchers.Default) { p.run() }
        started.await()
        p.onSessionReleased() // iptalle birlikte bırakma da gelir
        job.cancelAndJoin()
        assertTrue(p.run())
        assertEquals(2, warmCalls.get())
        assertTrue(p.run())
        assertEquals(2, warmCalls.get())
        p.onSessionReleased()
        assertTrue(p.run())
        assertEquals(3, warmCalls.get())
    }

    /** warm istisna atarsa: istisna yayılır, bayrak true olmaz; bırakma ile birleşse de sonraki run yeniden dener. */
    @Test
    fun warmThrows_afterConcurrentRelease_leavesNotWarm_andRetries() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var first = true
        val p = SearchWarmUp({ true }, { false }, {
            warmCalls.incrementAndGet()
            if (first) {
                first = false
                started.complete(Unit)
                gate.await()
                throw IllegalStateException("ort")
            }
        })
        val run = async(Dispatchers.Default) { runCatching { p.run() } }
        started.await()
        p.onSessionReleased()
        gate.complete(Unit)
        assertTrue(run.await().exceptionOrNull() is IllegalStateException)
        assertTrue(p.run())
        assertEquals(2, warmCalls.get())
    }

    /**
     * Bırakma, hasSearchableIndex kontrolü sürerken (warm başlamadan) gelirse: nesil kontrolden önce yakalandığı için o run
     * "ısınmış" demez (güvenli taraf, en çok bir fazladan ısınma); sonraki çağrı ısıtır.
     */
    @Test
    fun releaseWhileChecksRunning_runIsNotMarkedWarmed_nextRunWarms() = runBlocking {
        val inCheck = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var first = true
        val p = SearchWarmUp(
            hasSearchableIndex = {
                if (first) {
                    first = false
                    inCheck.complete(Unit)
                    gate.await()
                }
                true
            },
            isIndexing = { false },
            warm = { warmCalls.incrementAndGet() },
        )
        val run = async(Dispatchers.Default) { p.run() }
        inCheck.await()
        p.onSessionReleased()
        gate.complete(Unit)
        assertFalse(run.await())
        assertTrue(p.run())
        assertEquals(2, warmCalls.get())
    }

    /** Eşzamanlı run() + bırakma fırtınası; sonunda tutarlılık: son bırakmadan sonra tam bir yeniden ısınma. */
    @Test
    fun concurrentRunsAndReleases_endConsistent() = runBlocking {
        val p = SearchWarmUp({ true }, { false }, { warmCalls.incrementAndGet(); yield() })
        (1..64).map { i ->
            async(Dispatchers.Default) {
                repeat(50) { if (i % 2 == 0) p.run() else p.onSessionReleased() }
            }
        }.awaitAll()
        p.onSessionReleased()
        val before = warmCalls.get()
        assertTrue(p.run())
        assertEquals(before + 1, warmCalls.get())
        assertTrue(p.run())
        assertEquals(before + 1, warmCalls.get())
    }

    /** onSessionReleased idempotent: art arda çok çağrı tek yeniden ısınma gerektirir. */
    @Test
    fun onSessionReleased_isIdempotent() = runBlocking {
        val p = warmUpFor(emptyList())
        assertTrue(p.run())
        repeat(1000) { p.onSessionReleased() }
        assertTrue(p.run())
        assertEquals(2, warmCalls.get())
        assertTrue(p.run())
        assertEquals(2, warmCalls.get())
    }

    /**
     * Slot geri çağrısı (onClosed, slot kilidi altında) ile warm() (slot kilidini alır) aynı anda çekişse de kilitlenme
     * olmaz: SearchWarmUp kendi kilidini warm() boyunca tutmaz.
     */
    @Test
    fun slotCallbackAndWarmUp_noDeadlock_underContention() = runBlocking {
        val slot = SingleSessionSlot()
        lateinit var p: SearchWarmUp
        p = SearchWarmUp({ true }, { false }, {
            slot.withSession("text", open = { AutoCloseable { } }, onClosed = { p.onSessionReleased() }) { }
        })
        withTimeout(20_000) {
            (1..32).map { i ->
                async(Dispatchers.Default) {
                    repeat(40) {
                        when (i % 3) {
                            0 -> p.run()
                            1 -> slot.withSession("image", open = { AutoCloseable { } }) { } // metni evict eder
                            else -> slot.close("text")
                        }
                    }
                }
            }.awaitAll()
        }
        slot.closeAll()
        assertTrue(p.run())
    }

    /** onSessionReleased slot kilidi altında kısa kalır: slot sonrasında hemen kullanılabilir. */
    @Test
    fun onSessionReleasedInsideSlotCallback_doesNotBlockSlot() = runBlocking {
        val slot = SingleSessionSlot()
        val p = SearchWarmUp({ true }, { false }, { warmCalls.incrementAndGet() })
        assertTrue(p.run())
        slot.withSession("text", open = { AutoCloseable { } }, onClosed = { p.onSessionReleased() }) { }
        withTimeout(5_000) { slot.withSession("image", open = { AutoCloseable { } }) { } } // evict -> onClosed -> bayrak sıfır
        withTimeout(5_000) { slot.withSession("image", open = { AutoCloseable { } }) { } }
        assertTrue(p.run())
        assertEquals(2, warmCalls.get())
        slot.closeAll()
    }

    /** Oturum close() hatası: onClosed yine çağrılır (bayrak sıfırlanır), istisna yayılır, slot sonra kullanılabilir. */
    @Test
    fun sessionCloseThrows_stillResetsWarmFlag_andSlotRecovers() = runBlocking {
        val slot = SingleSessionSlot()
        val p = SearchWarmUp({ true }, { false }, { warmCalls.incrementAndGet() })
        assertTrue(p.run())
        slot.withSession("text", open = { AutoCloseable { throw IllegalStateException("close") } }, onClosed = { p.onSessionReleased() }) { }
        try {
            slot.close("text")
            fail()
        } catch (_: IllegalStateException) {
        }
        assertTrue(p.run())
        assertEquals(2, warmCalls.get())
        withTimeout(5_000) { slot.withSession("image", open = { AutoCloseable { } }) { } }
        slot.closeAll()
    }

    /** İki run() üst üste biner, biri bırakmayla geçersizleşir: ikisi de "ısınmış" demez, sonraki çağrı ısıtır. */
    @Test
    fun twoOverlappingRuns_withRelease_bothReportNotWarm_thenRewarm() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val p = SearchWarmUp({ true }, { false }, { warmCalls.incrementAndGet(); gate.await() })
        val a = async(Dispatchers.Default) { p.run() }
        val b = async(Dispatchers.Default) { p.run() }
        while (warmCalls.get() < 2) yield()
        p.onSessionReleased()
        gate.complete(Unit)
        assertFalse(a.await())
        assertFalse(b.await())
        assertTrue(p.run())
        assertEquals(3, warmCalls.get())
    }
}
