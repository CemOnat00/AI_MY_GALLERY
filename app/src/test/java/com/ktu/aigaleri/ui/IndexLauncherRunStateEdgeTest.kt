package com.ktu.aigaleri.ui

import com.ktu.aigaleri.domain.IndexMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** T-016 QA: IndexLauncher varsayılanları ve isIndexingNow kenar durumları. */
class IndexLauncherRunStateEdgeTest {
    private class FromRunState(vararg val seq: IndexRunState) : IndexLauncher {
        override val runState: Flow<IndexRunState> = flow { seq.forEach { emit(it) } }
        override val isRunning: Flow<Boolean> = flowOf(seq.first() != IndexRunState.IDLE)
        override fun launch(mode: IndexMode) = Unit
    }

    private class RunningOnly(v: Boolean) : IndexLauncher {
        override val isRunning: Flow<Boolean> = flowOf(v)
        override fun launch(mode: IndexMode) = Unit
    }

    @Test
    fun isIndexingNow_usesFirstEmission_only() = runBlocking {
        assertEquals(false, FromRunState(IndexRunState.WAITING, IndexRunState.RUNNING).isIndexingNow())
        assertEquals(true, FromRunState(IndexRunState.RUNNING, IndexRunState.IDLE).isIndexingNow())
    }

    /** Yalnızca isRunning'i geçersiz kılan eski launcher: true -> RUNNING (muhafazakâr), isIndexingNow true. */
    @Test
    fun legacyLauncher_isIndexingNow_followsIsRunning() = runBlocking {
        assertEquals(true, RunningOnly(true).isIndexingNow())
        assertEquals(false, RunningOnly(false).isIndexingNow())
    }

    @Test
    fun defaultLauncher_isRunningFalse_isIndexingNowFalse() = runBlocking {
        val l = object : IndexLauncher {
            override fun launch(mode: IndexMode) = Unit
        }
        assertEquals(false, l.isRunning.first())
        assertEquals(false, l.isIndexingNow())
    }

    @Test
    fun enumHasExactlyThreeStates() {
        assertEquals(listOf("IDLE", "WAITING", "RUNNING"), IndexRunState.entries.map { it.name })
    }

    /** Boş runState akışı: first() NoSuchElementException atar (karakterizasyon; gerçek launcher hep en az bir değer yayar). */
    @Test
    fun emptyRunStateFlow_propagatesNoSuchElement() = runBlocking {
        val l = object : IndexLauncher {
            override val runState: Flow<IndexRunState> = flow { }
            override fun launch(mode: IndexMode) = Unit
        }
        try {
            l.isIndexingNow()
            fail()
        } catch (_: NoSuchElementException) {
        }
    }
}
