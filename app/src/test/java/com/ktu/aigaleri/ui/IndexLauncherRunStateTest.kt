package com.ktu.aigaleri.ui

import com.ktu.aigaleri.domain.IndexMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** T-016: [IndexLauncher.runState] varsayılanı, yalnızca [IndexLauncher.isRunning]'i geçersiz kılan launcher'larla uyumludur. */
class IndexLauncherRunStateTest {
    private class RunningOnly(private val value: Boolean) : IndexLauncher {
        override val isRunning: Flow<Boolean> = flowOf(value)
        override fun launch(mode: IndexMode) = Unit
    }

    @Test
    fun default_mapsIsRunningTrueToRunning_andFalseToIdle() = runBlocking {
        assertEquals(IndexRunState.RUNNING, RunningOnly(true).runState.first())
        assertEquals(IndexRunState.IDLE, RunningOnly(false).runState.first())
    }

    @Test
    fun defaultLauncher_isIdle() = runBlocking {
        val l = object : IndexLauncher {
            override fun launch(mode: IndexMode) = Unit
        }
        assertEquals(IndexRunState.IDLE, l.runState.first())
    }
}
