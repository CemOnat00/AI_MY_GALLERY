package com.ktu.aigaleri.ui.index

import com.ktu.aigaleri.data.IndexState
import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.ui.IndexLauncher
import com.ktu.aigaleri.ui.IndexRunState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** T-016: banner ve durum ekranı IDLE/WAITING/RUNNING ayrımı; geçersiz processed/total davranışı. */
@OptIn(ExperimentalCoroutinesApi::class)
class IndexingBannerRunStateTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private val neutral = IndexingBanner(null, null)
    private val waiting = IndexingBanner(null, null, waiting = true)

    private class Launcher(val run: Flow<IndexRunState>) : IndexLauncher {
        override val runState: Flow<IndexRunState> = run
        override val isRunning: Flow<Boolean> = run.map { it != IndexRunState.IDLE }
        val modes = mutableListOf<IndexMode>()
        override fun launch(mode: IndexMode) { modes += mode }
    }

    @Test
    fun idle_hasNoBanner_whateverTheState() {
        listOf(
            IndexStatusUiState.Loading,
            IndexStatusUiState.NeverRun,
            IndexStatusUiState.Error,
            IndexStatusUiState.Data(10, 3, null),
        ).forEach { assertNull("$it", indexingBanner(it, IndexRunState.IDLE)) }
    }

    @Test
    fun waiting_showsWaitingBanner_withoutCounts() {
        assertEquals(waiting, indexingBanner(IndexStatusUiState.Data(10, 3, null), IndexRunState.WAITING))
        assertEquals(waiting, indexingBanner(IndexStatusUiState.NeverRun, IndexRunState.WAITING))
        assertEquals(waiting, indexingBanner(IndexStatusUiState.Error, IndexRunState.WAITING))
    }

    @Test
    fun running_showsProgress_onlyWhenValid() {
        assertEquals(IndexingBanner(3, 10), indexingBanner(IndexStatusUiState.Data(10, 3, null), IndexRunState.RUNNING))
        assertEquals(IndexingBanner(0, 10), indexingBanner(IndexStatusUiState.Data(10, 0, null), IndexRunState.RUNNING))
        assertFalse(indexingBanner(IndexStatusUiState.Data(10, 3, null), IndexRunState.RUNNING)!!.waiting)
    }

    @Test
    fun running_invalidCounts_showNeutralWithoutNumbers() {
        listOf(
            IndexStatusUiState.Data(10, -1, null), // "-1 / 10" gösterilmez
            IndexStatusUiState.Data(-1, 0, null),
            IndexStatusUiState.Data(0, 0, null),
            IndexStatusUiState.Data(10, 10, 5L), // tamamlanmış önceki tur
            IndexStatusUiState.Data(10, 12, 5L),
            IndexStatusUiState.Data(Int.MIN_VALUE, Int.MIN_VALUE, null),
            IndexStatusUiState.Loading,
            IndexStatusUiState.NeverRun,
            IndexStatusUiState.Error,
        ).forEach { assertEquals("$it", neutral, indexingBanner(it, IndexRunState.RUNNING)) }
    }

    @Test
    fun toUiState_clampsNegativeProcessedToZero() {
        assertEquals(IndexStatusUiState.Data(10, 0, null), IndexState(total = 10, processed = -1, lastRunAt = null).toUiState())
        assertEquals(IndexStatusUiState.Data(10, 4, null), IndexState(total = 10, processed = 4, lastRunAt = null).toUiState())
    }

    private fun TestScope.collect(vm: IndexStatusViewModel) {
        backgroundScope.launch { vm.runState.collect { } }
        backgroundScope.launch { vm.reindexInProgress.collect { } }
    }

    @Test
    fun viewModel_runState_followsLauncherFlow_andBannerFollows() = runTest {
        val run = MutableStateFlow(IndexRunState.IDLE)
        val vm = IndexStatusViewModel(MutableStateFlow(IndexState(total = 10, processed = 3, lastRunAt = null)), Launcher(run))
        collect(vm)
        runCurrent()
        assertEquals(IndexRunState.IDLE, vm.runState.value)
        assertNull(indexingBanner(IndexStatusUiState.Data(10, 3, null), vm.runState.value))

        run.value = IndexRunState.WAITING
        runCurrent()
        assertEquals(IndexRunState.WAITING, vm.runState.value)
        assertEquals(waiting, indexingBanner(IndexStatusUiState.Data(10, 3, null), vm.runState.value))

        run.value = IndexRunState.RUNNING
        runCurrent()
        assertEquals(IndexRunState.RUNNING, vm.runState.value)
        assertEquals(IndexingBanner(3, 10), indexingBanner(IndexStatusUiState.Data(10, 3, null), vm.runState.value))

        run.value = IndexRunState.IDLE
        runCurrent()
        assertEquals(IndexRunState.IDLE, vm.runState.value)
    }

    @Test
    fun viewModel_reindexLock_coversWaitingAndRunning_notIdle() = runTest {
        val run = MutableStateFlow(IndexRunState.WAITING)
        val launcher = Launcher(run)
        val vm = IndexStatusViewModel(MutableStateFlow(null), launcher)
        collect(vm)
        runCurrent()
        assertTrue(vm.reindexInProgress.value) // kuyruktaki iş de kilitler
        vm.reindex()
        assertTrue(launcher.modes.isEmpty())

        run.value = IndexRunState.RUNNING
        runCurrent()
        assertTrue(vm.reindexInProgress.value)

        run.value = IndexRunState.IDLE
        runCurrent()
        assertFalse(vm.reindexInProgress.value)
    }

    @Test
    fun viewModel_runStateFlowError_afterRunning_fallsToIdle_andStaysIdle() = runTest {
        val failAfterRunning = flow {
            emit(IndexRunState.RUNNING)
            throw IllegalStateException("work db")
        }
        val vm = IndexStatusViewModel(MutableStateFlow(null), Launcher(failAfterRunning))
        collect(vm)
        advanceTimeBy(10)
        runCurrent()
        // RUNNING yayıldı, ardından hata: kalıcı IDLE (yeniden deneme yok).
        assertEquals(IndexRunState.IDLE, vm.runState.value)
        assertFalse(vm.reindexInProgress.value)
    }
}
