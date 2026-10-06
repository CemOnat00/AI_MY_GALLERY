package com.ktu.aigaleri.ui

import android.content.ComponentCallbacks2
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TextSessionTrimHandlerEdgeTest {
    @Test
    fun levelsBelowRunningLow_neverRelease_includingNegativeAndUndefined() {
        val releases = AtomicInteger()
        val h = TextSessionTrimHandler(CoroutineScope(UnconfinedTestDispatcher()), release = { releases.incrementAndGet() })
        for (l in listOf(Int.MIN_VALUE, -1, 0, 1, 4, ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, 6, 9)) h.onTrimMemory(l)
        assertEquals(0, releases.get())
        h.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) // tam eşik (10)
        assertEquals(1, releases.get())
        h.onTrimMemory(Int.MAX_VALUE)
        assertEquals(2, releases.get())
    }

    @Test
    fun onLowMemory_releasesRegardlessOfLevel_andRunsAfterRelease() {
        val releases = AtomicInteger()
        val afters = AtomicInteger()
        val h = TextSessionTrimHandler(CoroutineScope(UnconfinedTestDispatcher()), { releases.incrementAndGet() }, { afters.incrementAndGet() })
        h.onLowMemory()
        assertEquals(1, releases.get())
        assertEquals(1, afters.get())
    }

    @Test
    fun concurrentCalls_fromManyThreads_allReleaseWithoutCrash() {
        val releases = AtomicInteger()
        val afters = AtomicInteger()
        val done = CountDownLatch(200)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val h = TextSessionTrimHandler(scope, release = { releases.incrementAndGet() }, afterRelease = { afters.incrementAndGet(); done.countDown() })
        val threads = (1..8).map {
            Thread {
                repeat(25) { i -> if (i % 2 == 0) h.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE) else h.onLowMemory() }
            }.also { it.start() }
        }
        threads.forEach { it.join() }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(200, releases.get())
        assertEquals(200, afters.get())
        scope.cancel()
    }

    @Test
    fun cancelledScope_doesNotRelease_andDoesNotThrow() {
        val releases = AtomicInteger()
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val h = TextSessionTrimHandler(scope, release = { releases.incrementAndGet() })
        scope.cancel()
        h.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        h.onLowMemory()
        assertEquals(0, releases.get())
    }

    @Test
    fun shouldRelease_isMonotonicFromThreshold() {
        var seenTrue = false
        for (l in -5..100) {
            val r = TextSessionTrimHandler.shouldRelease(l)
            if (seenTrue) assertTrue("level=$l", r)
            if (r) seenTrue = true
        }
        assertFalse(TextSessionTrimHandler.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW - 1))
        assertTrue(TextSessionTrimHandler.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
    }
}
