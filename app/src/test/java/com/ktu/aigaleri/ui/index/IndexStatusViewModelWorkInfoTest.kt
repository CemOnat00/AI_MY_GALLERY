package com.ktu.aigaleri.ui.index

import androidx.work.WorkInfo
import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.ui.IndexLauncher
import com.ktu.aigaleri.work.IndexWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
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

/**
 * T-006 (QA): IndexStatusViewModel.reindexInProgress, WorkManager `WorkInfo` durum geçişlerinden türetilir.
 * Launcher, `WorkManagerIndexLauncher.isRunning` ile aynı dönüşümü kullanır (IndexWork.isActive + distinctUntilChanged);
 * WorkManager sınıfı örneklenmez.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IndexStatusViewModelWorkInfoTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private class WorkInfoLauncher : IndexLauncher {
        /** getWorkInfosForUniqueWorkFlow taklidi: benzersiz işin tüm WorkInfo durumları. */
        val infos = MutableStateFlow<List<WorkInfo.State>>(emptyList())
        val modes = mutableListOf<IndexMode>()
        override val isRunning: Flow<Boolean> = infos.map { IndexWork.isActive(it) }.distinctUntilChanged()
        override fun launch(mode: IndexMode) { modes += mode }
    }

    private fun TestScopeVm(l: WorkInfoLauncher) = IndexStatusViewModel(MutableStateFlow(null), l)

    @Test
    fun fullLifecycle_enqueued_running_succeeded_followsWorkState() = runTest {
        val l = WorkInfoLauncher()
        val vm = TestScopeVm(l)
        runCurrent()
        assertFalse(vm.reindexInProgress.value)
        vm.reindex()
        runCurrent()
        assertTrue(vm.reindexInProgress.value) // yerel bayrak
        l.infos.value = listOf(WorkInfo.State.ENQUEUED)
        runCurrent()
        assertTrue(vm.reindexInProgress.value)
        advanceTimeBy(IndexStatusViewModel.LAUNCH_GRACE_MS * 3) // bayrak düştü, kilidi iş durumu tutar
        runCurrent()
        assertTrue(vm.reindexInProgress.value)
        l.infos.value = listOf(WorkInfo.State.RUNNING)
        runCurrent()
        vm.reindex()
        assertEquals(1, l.modes.size)
        l.infos.value = listOf(WorkInfo.State.SUCCEEDED)
        runCurrent()
        assertFalse(vm.reindexInProgress.value)
        vm.reindex()
        assertEquals(listOf(IndexMode.FULL, IndexMode.FULL), l.modes)
    }

    @Test
    fun retryBackoff_runningToEnqueuedAndBack_staysLocked_untilFinalState() = runTest {
        val l = WorkInfoLauncher()
        val vm = TestScopeVm(l)
        vm.reindex()
        for (s in listOf(WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING)) {
            l.infos.value = listOf(s)
            runCurrent()
            assertTrue(s.name, vm.reindexInProgress.value)
            vm.reindex()
        }
        assertEquals(1, l.modes.size)
        l.infos.value = listOf(WorkInfo.State.FAILED) // 3 deneme sonrası kalıcı hata
        runCurrent()
        assertFalse(vm.reindexInProgress.value)
        vm.reindex()
        assertEquals(2, l.modes.size)
    }

    @Test
    fun replacedWork_cancelledPlusNew_staysLocked_blockedCountsAsActive() = runTest {
        val l = WorkInfoLauncher()
        val vm = TestScopeVm(l)
        l.infos.value = listOf(WorkInfo.State.CANCELLED, WorkInfo.State.ENQUEUED) // REPLACE sonrası
        runCurrent()
        assertTrue(vm.reindexInProgress.value)
        l.infos.value = listOf(WorkInfo.State.CANCELLED, WorkInfo.State.BLOCKED)
        runCurrent()
        assertTrue(vm.reindexInProgress.value)
        l.infos.value = listOf(WorkInfo.State.CANCELLED, WorkInfo.State.SUCCEEDED)
        runCurrent()
        assertFalse(vm.reindexInProgress.value)
    }

    @Test
    fun workFinishesFasterThanObserved_flagClearsAfterGrace_notStuck() = runTest {
        val l = WorkInfoLauncher()
        val vm = TestScopeVm(l)
        vm.reindex()
        runCurrent()
        l.infos.value = listOf(WorkInfo.State.SUCCEEDED) // hiç ENQUEUED/RUNNING gözlenmedi
        runCurrent()
        assertTrue(vm.reindexInProgress.value) // henüz grace süresi içinde
        advanceTimeBy(IndexStatusViewModel.LAUNCH_GRACE_MS + 1)
        runCurrent()
        assertFalse(vm.reindexInProgress.value)
    }

    @Test
    fun alreadyActiveAtStart_ignoresTap_andLaterSucceededUnlocks() = runTest {
        val l = WorkInfoLauncher()
        l.infos.value = listOf(WorkInfo.State.RUNNING) // uygulama açılışında INCREMENTAL sürüyor
        val vm = TestScopeVm(l)
        runCurrent()
        vm.reindex()
        assertTrue(l.modes.isEmpty())
        l.infos.value = listOf(WorkInfo.State.SUCCEEDED)
        runCurrent()
        vm.reindex()
        assertEquals(listOf(IndexMode.FULL), l.modes)
    }

    @Test
    fun rapidStateFlicker_neverLeavesVmInconsistent() = runTest {
        val l = WorkInfoLauncher()
        val vm = TestScopeVm(l)
        repeat(50) { i ->
            l.infos.value = listOf(if (i % 2 == 0) WorkInfo.State.RUNNING else WorkInfo.State.ENQUEUED)
            runCurrent()
        }
        assertTrue(vm.reindexInProgress.value)
        l.infos.value = emptyList()
        runCurrent()
        assertFalse(vm.reindexInProgress.value)
    }
}
