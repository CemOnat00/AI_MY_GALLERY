@file:Suppress("DEPRECATION") // TRIM_MEMORY_* sabitleri Java'da deprecated; test eski API düzeylerindeki eşik davranışını doğrular (T-016)

package com.ktu.aigaleri.ui

import android.content.ComponentCallbacks2
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TextSessionTrimHandlerTest {
    private val releases = AtomicInteger()
    private val afters = AtomicInteger()
    private fun handler(failure: Exception? = null) = TextSessionTrimHandler(
        scope = CoroutineScope(UnconfinedTestDispatcher()),
        release = { releases.incrementAndGet(); failure?.let { throw it } },
        afterRelease = { afters.incrementAndGet() },
    )

    @Test
    fun levels_policy() {
        assertFalse(TextSessionTrimHandler.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertFalse(TextSessionTrimHandler.shouldRelease(0))
        for (l in listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        )) assertTrue("level=$l", TextSessionTrimHandler.shouldRelease(l))
    }

    @Test
    fun trimMemory_releasesAndResetsWarmState_onlyAtOrAboveThreshold() {
        val h = handler()
        h.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        assertEquals(0, releases.get())
        h.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertEquals(1, releases.get())
        assertEquals(1, afters.get())
        h.onLowMemory()
        assertEquals(2, releases.get())
    }

    @Test
    fun releaseFailure_isSwallowed_andAfterReleaseStillRuns() {
        val h = handler(IllegalStateException("x"))
        h.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE) // fırlatmaz
        assertEquals(1, releases.get())
        assertEquals(1, afters.get()) // slot yarı boşalmış olabilir: ısınma durumu yine sıfırlanır
    }

    @Test
    fun afterReleaseFailure_isSwallowed() {
        val h = TextSessionTrimHandler(CoroutineScope(UnconfinedTestDispatcher()), release = { releases.incrementAndGet() },
            afterRelease = { afters.incrementAndGet(); throw IllegalStateException("after") })
        h.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE) // fırlatmaz
        assertEquals(1, afters.get())
    }

    @Test
    fun beforeRelease_runsBeforeRelease_andAfterAfterOrderIsBeforeReleaseAfter() {
        val order = mutableListOf<String>()
        val h = TextSessionTrimHandler(
            CoroutineScope(UnconfinedTestDispatcher()),
            release = { order += "release" },
            afterRelease = { order += "after" },
            beforeRelease = { order += "before" },
        )
        h.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertEquals(listOf("before", "release", "after"), order)
    }

    @Test
    fun cancelledRelease_stillRunsAfterRelease_andRethrows() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val h = TextSessionTrimHandler(scope, release = { releases.incrementAndGet(); gate.await() }, afterRelease = { afters.incrementAndGet() })
        h.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertEquals(0, afters.get())
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        assertEquals(1, afters.get())
    }

    /** Bırakma istisna atar -> afterRelease sayaç artırır -> ısınma bayrağı sıfırlanır, sonraki run() yeniden ısıtır. */
    @Test
    fun releaseThrows_warmUpFlagIsReset_andNextRunRewarms() = kotlinx.coroutines.runBlocking {
        var warmCalls = 0
        val warmUp = com.ktu.aigaleri.ui.search.SearchWarmUp({ true }, { false }, { warmCalls++ })
        assertTrue(warmUp.run())
        val h = TextSessionTrimHandler(
            CoroutineScope(UnconfinedTestDispatcher()),
            release = { throw IllegalStateException("closeLocked") },
            afterRelease = warmUp::onSessionReleased,
        )
        h.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        assertTrue(warmUp.run())
        assertEquals(2, warmCalls)
    }

    @Test
    fun releaseIsLaunchedInScope_notBlockingCaller() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val started = AtomicInteger()
        val h = TextSessionTrimHandler(CoroutineScope(Dispatchers.Default), release = { started.incrementAndGet(); gate.await() })
        h.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) // gate açılmadan döner
        gate.complete(Unit)
    }
}
