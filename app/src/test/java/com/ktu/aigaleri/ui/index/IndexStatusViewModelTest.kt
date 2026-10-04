package com.ktu.aigaleri.ui.index

import com.ktu.aigaleri.data.IndexState
import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.ui.IndexLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IndexStatusViewModelTest {
    private val period = IndexStatusViewModel.SAMPLE_PERIOD_MS

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.collectIn(vm: IndexStatusViewModel) = backgroundScope.launch { vm.state.collect { } }

    @Test
    fun mapping_nullRow_isNeverRun() {
        assertEquals(IndexStatusUiState.NeverRun, (null as IndexState?).toUiState())
    }

    @Test
    fun mapping_row_isData_withFraction() {
        val data = IndexState(total = 200, processed = 50, lastRunAt = 1_000L).toUiState()
        assertEquals(IndexStatusUiState.Data(200, 50, 1_000L), data)
        assertEquals(0.25f, (data as IndexStatusUiState.Data).fraction, 0f)
    }

    @Test
    fun fraction_emptyGallery_depends_onLastRun() {
        assertEquals(1f, IndexStatusUiState.Data(0, 0, 5L).fraction, 0f)
        assertEquals(0f, IndexStatusUiState.Data(0, 0, null).fraction, 0f)
    }

    @Test
    fun fraction_isClampedToOne() {
        assertEquals(1f, IndexStatusUiState.Data(10, 12, null).fraction, 0f)
    }

    @Test
    fun state_startsLoading_thenShowsRoomRow() = runTest {
        val source = MutableStateFlow<IndexState?>(IndexState(total = 10, processed = 5, lastRunAt = null))
        val vm = IndexStatusViewModel(source, FakeLauncher())
        collectIn(vm)
        runCurrent()
        assertEquals(IndexStatusUiState.Loading, vm.state.value)
        advanceTimeBy(period + 1)
        assertEquals(IndexStatusUiState.Data(10, 5, null), vm.state.value)
    }

    @Test
    fun rapidUpdates_areSampled() = runTest {
        val source = MutableSharedFlow<IndexState?>(extraBufferCapacity = 64)
        val vm = IndexStatusViewModel(source, FakeLauncher())
        collectIn(vm)
        runCurrent()
        for (i in 1..20) source.emit(IndexState(total = 20, processed = i, lastRunAt = null))
        advanceTimeBy(period + 1)
        // Ara değerler atlanır; son değer görünür.
        assertEquals(IndexStatusUiState.Data(20, 20, null), vm.state.value)
    }

    @Test
    fun flowFailure_becomesError() = runTest {
        val vm = IndexStatusViewModel(flow { throw IllegalStateException("db") }, FakeLauncher())
        collectIn(vm)
        runCurrent()
        assertEquals(IndexStatusUiState.Error, vm.state.value)
    }

    @Test
    fun flowFailure_retryRestartsCollection() = runTest {
        var attempts = 0
        val source = flow<IndexState?> {
            if (attempts++ == 0) throw IllegalStateException("db")
            emit(IndexState(total = 4, processed = 2, lastRunAt = null))
            awaitCancellation() // Room akışı gibi tamamlanmaz (sample tamamlanan akışta son değeri atar).
        }
        val vm = IndexStatusViewModel(source, FakeLauncher())
        collectIn(vm)
        runCurrent()
        assertEquals(IndexStatusUiState.Error, vm.state.value)
        vm.retry()
        runCurrent()
        assertEquals(IndexStatusUiState.Loading, vm.state.value)
        advanceTimeBy(period + 1)
        assertEquals(IndexStatusUiState.Data(4, 2, null), vm.state.value)
    }

    @Test
    fun reindex_requestsFullMode() {
        val launcher = FakeLauncher()
        val vm = IndexStatusViewModel(MutableStateFlow(null), launcher)
        vm.reindex()
        assertEquals(listOf(IndexMode.FULL), launcher.modes)
    }

    @Test
    fun reindex_doubleTapIsIgnored_untilCooldownEnds() = runTest {
        val launcher = FakeLauncher()
        val vm = IndexStatusViewModel(MutableStateFlow(null), launcher)
        vm.reindex()
        vm.reindex()
        assertEquals(1, launcher.modes.size)
        assertTrue(vm.reindexInProgress.value)
        advanceTimeBy(IndexStatusViewModel.REINDEX_COOLDOWN_MS + 1)
        assertFalse(vm.reindexInProgress.value)
        vm.reindex()
        assertEquals(2, launcher.modes.size)
    }

    @Test
    fun reindex_whenLauncherUnavailable_doesNothing() {
        val launcher = FakeLauncher(available = false)
        val vm = IndexStatusViewModel(MutableStateFlow(null), launcher)
        assertFalse(vm.reindexAvailable)
        vm.reindex()
        assertTrue(launcher.modes.isEmpty())
        assertFalse(vm.reindexInProgress.value)
    }
}

private class FakeLauncher(available: Boolean = true) : IndexLauncher {
    override val isAvailable = available
    val modes = mutableListOf<IndexMode>()
    override fun launch(mode: IndexMode) { modes += mode }
}
