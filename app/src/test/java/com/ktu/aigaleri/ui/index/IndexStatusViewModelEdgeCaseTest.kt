package com.ktu.aigaleri.ui.index

import com.ktu.aigaleri.data.IndexState
import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.ui.IndexLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class RecordingLauncher(
    override val isAvailable: Boolean = true,
    var failure: RuntimeException? = null,
) : IndexLauncher {
    val running = MutableStateFlow(false)
    override val isRunning: kotlinx.coroutines.flow.Flow<Boolean> get() = running
    val modes = mutableListOf<IndexMode>()
    override fun launch(mode: IndexMode) {
        modes += mode
        failure?.let { throw it }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class IndexStatusViewModelEdgeCaseTest {
    private val period = IndexStatusViewModel.SAMPLE_PERIOD_MS
    private val grace = IndexStatusViewModel.LAUNCH_GRACE_MS

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.collectIn(vm: IndexStatusViewModel) = backgroundScope.launch { vm.state.collect { } }

    /** launcher.launch istisna fırlatırsa bayrak geri alınır, istisna sızmaz, hata bayrağı set edilir. */
    @Test
    fun reindex_launcherThrows_flagIsReset_errorShown_andRetryWorks() = runTest {
        val launcher = RecordingLauncher(failure = IllegalStateException("work enqueue failed"))
        val vm = IndexStatusViewModel(MutableStateFlow(null), launcher)
        vm.reindex()
        assertFalse(vm.reindexInProgress.value)
        assertTrue(vm.reindexError.value)
        launcher.failure = null
        vm.reindex()
        assertEquals(2, launcher.modes.size)
        assertFalse(vm.reindexError.value)
        runCurrent()
        assertTrue(vm.reindexInProgress.value)
    }

    @Test
    fun reindex_graceBoundary_blockedJustBefore_allowedAfter_whenWorkNeverAppears() = runTest {
        val launcher = RecordingLauncher()
        val vm = IndexStatusViewModel(MutableStateFlow(null), launcher)
        vm.reindex()
        advanceTimeBy(grace - 1)
        runCurrent()
        assertTrue(vm.reindexInProgress.value)
        vm.reindex()
        assertEquals(1, launcher.modes.size)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(vm.reindexInProgress.value)
        vm.reindex()
        assertEquals(2, launcher.modes.size)
    }

    @Test
    fun reindex_tappedManyTimes_launchesOnce_thenAgainAfterEachWorkCycle() = runTest {
        val launcher = RecordingLauncher()
        val vm = IndexStatusViewModel(MutableStateFlow(null), launcher)
        repeat(50) { vm.reindex() }
        assertEquals(1, launcher.modes.size)
        repeat(3) {
            launcher.running.value = true
            runCurrent()
            repeat(5) { vm.reindex() }
            launcher.running.value = false
            runCurrent()
            assertFalse(vm.reindexInProgress.value)
            repeat(5) { vm.reindex() }
        }
        assertEquals(4, launcher.modes.size)
        assertTrue(launcher.modes.all { it == IndexMode.FULL })
    }

    @Test
    fun reindex_launcherFlowFails_isTreatedAsNotRunning() = runTest {
        val launcher = object : IndexLauncher {
            val modes = mutableListOf<IndexMode>()
            override val isRunning: kotlinx.coroutines.flow.Flow<Boolean> = flow { throw IllegalStateException("work db") }
            override fun launch(mode: IndexMode) { modes += mode }
        }
        val vm = IndexStatusViewModel(MutableStateFlow(null), launcher)
        runCurrent()
        assertFalse(vm.reindexInProgress.value)
        vm.reindex()
        assertEquals(1, launcher.modes.size)
    }

    @Test
    fun reindex_unavailable_manyTaps_neverLaunches_neverStartsCooldown() = runTest {
        val launcher = RecordingLauncher(isAvailable = false)
        val vm = IndexStatusViewModel(MutableStateFlow(null), launcher)
        repeat(10) { vm.reindex() }
        assertTrue(launcher.modes.isEmpty())
        assertFalse(vm.reindexInProgress.value)
        assertFalse(vm.reindexAvailable)
    }

    @Test
    fun error_retry_thenErrorAgain_thenRecovers() = runTest {
        var attempts = 0
        val source = flow<IndexState?> {
            attempts++
            if (attempts <= 2) throw IllegalStateException("db $attempts")
            emit(IndexState(total = 8, processed = 8, lastRunAt = 99L))
            awaitCancellation()
        }
        val vm = IndexStatusViewModel(source, RecordingLauncher())
        collectIn(vm)
        runCurrent()
        assertEquals(IndexStatusUiState.Error, vm.state.value)
        vm.retry()
        runCurrent()
        assertEquals(IndexStatusUiState.Error, vm.state.value)
        vm.retry()
        runCurrent()
        advanceTimeBy(period + 1)
        assertEquals(IndexStatusUiState.Data(8, 8, 99L), vm.state.value)
        assertEquals(3, attempts)
    }

    @Test
    fun rowAbsent_isNeverRun_afterFirstSample() = runTest {
        val vm = IndexStatusViewModel(MutableStateFlow<IndexState?>(null), RecordingLauncher())
        collectIn(vm)
        runCurrent()
        assertEquals(IndexStatusUiState.Loading, vm.state.value)
        advanceTimeBy(period + 1)
        assertEquals(IndexStatusUiState.NeverRun, vm.state.value)
    }

    @Test
    fun rowAppears_thenIsDeleted_followsRoom() = runTest {
        val source = MutableStateFlow<IndexState?>(null)
        val vm = IndexStatusViewModel(source, RecordingLauncher())
        collectIn(vm)
        advanceTimeBy(period + 1)
        assertEquals(IndexStatusUiState.NeverRun, vm.state.value)
        source.value = IndexState(total = 3, processed = 1, lastRunAt = null)
        advanceTimeBy(period + 1)
        assertEquals(IndexStatusUiState.Data(3, 1, null), vm.state.value)
        source.value = null
        advanceTimeBy(period + 1)
        assertEquals(IndexStatusUiState.NeverRun, vm.state.value)
    }

    @Test
    fun totalZero_emptyGallery_viaViewModel() = runTest {
        val source = MutableStateFlow<IndexState?>(IndexState(total = 0, processed = 0, lastRunAt = 10L))
        val vm = IndexStatusViewModel(source, RecordingLauncher())
        collectIn(vm)
        advanceTimeBy(period + 1)
        val data = vm.state.value as IndexStatusUiState.Data
        assertEquals(0, data.total)
        assertEquals(1f, data.fraction, 0f)
        // Hiç çalışmamış ve total=0: bölme hatası yok, 0f.
        source.value = IndexState(total = 0, processed = 0, lastRunAt = null)
        advanceTimeBy(period + 1)
        assertEquals(0f, (vm.state.value as IndexStatusUiState.Data).fraction, 0f)
    }

    @Test
    fun fraction_extremeValues_neverNaNOrOutOfRange() {
        val cases = listOf(
            IndexStatusUiState.Data(Int.MAX_VALUE, Int.MAX_VALUE, null),
            IndexStatusUiState.Data(Int.MAX_VALUE, 0, null),
            IndexStatusUiState.Data(1, Int.MAX_VALUE, null),
            IndexStatusUiState.Data(10, -5, null),
            IndexStatusUiState.Data(0, 7, null),
            IndexStatusUiState.Data(-3, 2, 1L),
            IndexStatusUiState.Data(1_000_000, 1, 5L),
        )
        for (c in cases) {
            assertTrue("$c -> ${c.fraction}", !c.fraction.isNaN() && c.fraction in 0f..1f)
        }
        assertEquals(1f, cases[0].fraction, 0f)
        assertEquals(0f, cases[3].fraction, 0f)
    }

    @Test
    fun retry_whileHealthy_restartsWithLoading_thenData() = runTest {
        val source = MutableStateFlow<IndexState?>(IndexState(total = 5, processed = 5, lastRunAt = 1L))
        val vm = IndexStatusViewModel(source, RecordingLauncher())
        collectIn(vm)
        advanceTimeBy(period + 1)
        assertEquals(IndexStatusUiState.Data(5, 5, 1L), vm.state.value)
        vm.retry()
        runCurrent()
        assertEquals(IndexStatusUiState.Loading, vm.state.value)
        advanceTimeBy(period + 1)
        assertEquals(IndexStatusUiState.Data(5, 5, 1L), vm.state.value)
    }
}
