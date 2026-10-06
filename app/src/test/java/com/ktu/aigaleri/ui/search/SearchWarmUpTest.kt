package com.ktu.aigaleri.ui.search

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SearchWarmUpTest {
    private var hasIndex = true
    private var indexing = false
    private var warmCalls = 0
    private var warmFailure: Exception? = null
    private val policy = SearchWarmUp(
        hasSearchableIndex = { hasIndex },
        isIndexing = { indexing },
        warm = { warmCalls++; warmFailure?.let { throw it } },
    )

    @Test
    fun emptyIndex_skipsWarmUp() = runBlocking {
        hasIndex = false
        assertFalse(policy.run())
        assertEquals(0, warmCalls)
    }

    @Test
    fun whileIndexing_defers_thenWarmsWhenIndexingStops() = runBlocking {
        indexing = true
        assertFalse(policy.run())
        assertFalse(policy.run())
        assertEquals(0, warmCalls)
        indexing = false
        assertTrue(policy.run())
        assertEquals(1, warmCalls)
    }

    @Test
    fun alreadyWarm_doesNotRunAgain_untilSessionReleased() = runBlocking {
        assertTrue(policy.run())
        assertTrue(policy.run())
        assertEquals(1, warmCalls)
        indexing = true // ısınmışken indeksleme başlasa da yeniden ısıtma yok
        assertTrue(policy.run())
        assertEquals(1, warmCalls)
        policy.onSessionReleased()
        assertFalse(policy.run()) // indeksleme sürüyor: ertelenir
        indexing = false
        assertTrue(policy.run())
        assertEquals(2, warmCalls)
    }

    @Test
    fun warmFailure_propagates_andIsNotMarkedWarm() = runBlocking {
        warmFailure = IllegalStateException("x")
        try {
            policy.run()
            fail()
        } catch (_: IllegalStateException) {
        }
        warmFailure = null
        assertTrue(policy.run())
        assertEquals(2, warmCalls)
    }

    @Test
    fun indexCheckedBeforeIndexingState() = runBlocking {
        hasIndex = false
        indexing = true
        var indexingQueried = false
        val p = SearchWarmUp({ hasIndex }, { indexingQueried = true; indexing }, { warmCalls++ })
        assertFalse(p.run())
        assertFalse(indexingQueried)
    }
}
