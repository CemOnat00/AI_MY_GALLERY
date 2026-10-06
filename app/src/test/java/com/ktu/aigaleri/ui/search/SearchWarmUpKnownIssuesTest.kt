package com.ktu.aigaleri.ui.search

import androidx.work.WorkInfo
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
 * T-008 QA: SearchWarmUp ek sınır durumları ve reviewer'ın bilinen bilgi notları (KNOWN_ISSUE: MEVCUT davranışı sabitler;
 * davranış düzeltilirse bu testler bilerek güncellenmelidir).
 */
class SearchWarmUpKnownIssuesTest {
    private var hasIndex = true
    private var indexing = false
    private var warmCalls = 0

    private fun policy(warm: suspend () -> Unit = { warmCalls++ }) =
        SearchWarmUp({ hasIndex }, { indexing }, warm)

    /** KNOWN_ISSUE (a): isActive ENQUEUED/BLOCKED'i da aktif sayar; pil kısıtıyla kuyrukta takılan iş ısınmayı süresiz erteler. */
    @Test
    fun KNOWN_ISSUE_enqueuedButNotRunningWork_countsAsIndexing_andDefersWarmUpIndefinitely() = runBlocking {
        val p = SearchWarmUp(
            hasSearchableIndex = { true },
            isIndexing = { IndexWork.isActive(listOf(WorkInfo.State.ENQUEUED)) }, // AppDependencies'teki isRunning ile aynı eşleme
            warm = { warmCalls++ },
        )
        repeat(10) { assertFalse(p.run()) }
        assertEquals(0, warmCalls)
        // BLOCKED de aynı
        val blocked = SearchWarmUp({ true }, { IndexWork.isActive(listOf(WorkInfo.State.BLOCKED)) }, { warmCalls++ })
        assertFalse(blocked.run())
        assertEquals(0, warmCalls)
    }

    /** KNOWN_ISSUE (b): ısınma çıkarımı sürerken oturum bırakılırsa `warmed` yine true olur (bayat bayrak). */
    @Test
    fun KNOWN_ISSUE_releaseDuringWarmUp_leavesWarmedFlagTrue() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val p = policy { warmCalls++; started.complete(Unit); gate.await() }
        val first = async(Dispatchers.Default) { p.run() }
        started.await()
        p.onSessionReleased() // trim, çıkarım sürerken geldi
        gate.complete(Unit)
        assertTrue(first.await())
        // Beklenen doğru davranış: oturum bırakıldığı için yeniden ısıtmak (warmCalls == 2). Mevcut: ısınmış sayılır.
        assertTrue(p.run())
        assertEquals(1, warmCalls)
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
