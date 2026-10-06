package com.ktu.aigaleri.ui

import com.ktu.aigaleri.data.FakeMediaPhotoSource
import com.ktu.aigaleri.data.MediaPhoto
import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.ui.index.IndexStatusUiState
import com.ktu.aigaleri.ui.index.IndexingBanner
import com.ktu.aigaleri.ui.index.indexingBanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

private class RecordingLauncher(
    override val isAvailable: Boolean = true,
    var failWith: Throwable? = null,
) : IndexLauncher {
    val modes = mutableListOf<IndexMode>()

    override fun launch(mode: IndexMode) {
        modes += mode
        failWith?.let { throw it }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelIndexingTest {
    private val photos = listOf(MediaPhoto(1L, "content://x/1", null))

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(launcher: IndexLauncher) = MainViewModel(FakeMediaPhotoSource(photos), launcher)

    @Test
    fun grantedResult_enqueuesIncrementalOnce() {
        val launcher = RecordingLauncher()
        vm(launcher).onPermissionResult(granted = true, shouldShowRationale = false)
        assertEquals(listOf(IndexMode.INCREMENTAL), launcher.modes)
    }

    @Test
    fun alreadyGrantedAtLaunch_onResumeEnqueuesIncremental() {
        val launcher = RecordingLauncher()
        vm(launcher).onResume(granted = true)
        assertEquals(listOf(IndexMode.INCREMENTAL), launcher.modes)
    }

    @Test
    fun repeatedCallsWhileGranted_enqueueOnlyOnce() {
        val launcher = RecordingLauncher()
        val vm = vm(launcher)
        vm.onResume(granted = true)
        vm.onResume(granted = true)
        vm.onPermissionResult(granted = true, shouldShowRationale = false)
        assertEquals(1, launcher.modes.size)
    }

    @Test
    fun withoutPermission_neverEnqueues() {
        val launcher = RecordingLauncher()
        val vm = vm(launcher)
        vm.onResume(granted = false)
        vm.onPermissionResult(granted = false, shouldShowRationale = true)
        vm.onPermissionResult(granted = false, shouldShowRationale = false)
        vm.onResume(granted = false)
        assertEquals(emptyList<IndexMode>(), launcher.modes)
    }

    @Test
    fun grantedLaterAfterDenial_enqueues() {
        val launcher = RecordingLauncher()
        val vm = vm(launcher)
        vm.onPermissionResult(granted = false, shouldShowRationale = false)
        vm.onResume(granted = true)
        assertEquals(listOf(IndexMode.INCREMENTAL), launcher.modes)
    }

    @Test
    fun permissionRevokedThenRegranted_enqueuesAgain() {
        val launcher = RecordingLauncher()
        val vm = vm(launcher)
        vm.onResume(granted = true)
        vm.onResume(granted = false)
        vm.onResume(granted = true)
        assertEquals(2, launcher.modes.size)
    }

    @Test
    fun launchException_doesNotCrash_andRetriesOnNextResume() {
        val launcher = RecordingLauncher(failWith = IllegalStateException())
        val vm = vm(launcher)
        vm.onResume(granted = true)
        assertEquals(PermissionState.Granted, vm.state.value.permission)
        launcher.failWith = null
        vm.onResume(granted = true)
        assertEquals(2, launcher.modes.size)
        vm.onResume(granted = true)
        assertEquals(2, launcher.modes.size)
    }

    @Test
    fun unavailableLauncher_isNotCalled() {
        val launcher = RecordingLauncher(isAvailable = false)
        vm(launcher).onResume(granted = true)
        assertEquals(emptyList<IndexMode>(), launcher.modes)
    }

    @Test(expected = CancellationException::class)
    fun cancellationFromLauncher_isRethrown() {
        vm(RecordingLauncher(failWith = CancellationException())).onResume(granted = true)
    }

    @Test
    fun banner_hiddenWhenNotRunning() {
        assertNull(indexingBanner(IndexStatusUiState.Data(10, 3, null), running = false))
    }

    @Test
    fun banner_showsProgressWhenRunning() {
        assertEquals(IndexingBanner(3, 10), indexingBanner(IndexStatusUiState.Data(10, 3, null), running = true))
    }

    @Test
    fun banner_withoutCounts_whenRunningButNoTotalYet() {
        val none = IndexingBanner(null, null)
        assertEquals(none, indexingBanner(IndexStatusUiState.NeverRun, running = true))
        assertEquals(none, indexingBanner(IndexStatusUiState.Loading, running = true))
        assertEquals(none, indexingBanner(IndexStatusUiState.Data(0, 0, 5L), running = true))
    }
}
