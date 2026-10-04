package com.ktu.aigaleri.ui

import com.ktu.aigaleri.data.FakeMediaPhotoSource
import com.ktu.aigaleri.data.MediaPhoto
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

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val photos = listOf(MediaPhoto(1L, "content://x/1", null), MediaPhoto(2L, "content://x/2", 5L))

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun initialState_isNotRequested_withoutCount() {
        val vm = MainViewModel(FakeMediaPhotoSource(photos))
        assertEquals(MainUiState(PermissionState.NotRequested, null), vm.state.value)
    }

    @Test
    fun grantedResult_loadsPhotoCount() {
        val vm = MainViewModel(FakeMediaPhotoSource(photos))
        vm.onPermissionResult(granted = true, shouldShowRationale = false)
        assertEquals(MainUiState(PermissionState.Granted, 2), vm.state.value)
    }

    @Test
    fun deniedResult_withRationale_isDenied_andDoesNotQuery() {
        val source = FakeMediaPhotoSource(photos)
        val vm = MainViewModel(source)
        vm.onPermissionResult(granted = false, shouldShowRationale = true)
        assertEquals(MainUiState(PermissionState.Denied, null), vm.state.value)
        assertEquals(0, source.countCalls)
    }

    @Test
    fun deniedResult_withoutRationale_isPermanentlyDenied() {
        val vm = MainViewModel(FakeMediaPhotoSource(photos))
        vm.onPermissionResult(granted = false, shouldShowRationale = false)
        assertEquals(PermissionState.PermanentlyDenied, vm.state.value.permission)
    }

    @Test
    fun resumeAfterGrantingInSettings_becomesGrantedWithCount() {
        val vm = MainViewModel(FakeMediaPhotoSource(photos))
        vm.onPermissionResult(granted = false, shouldShowRationale = false)
        vm.onResume(granted = true)
        assertEquals(MainUiState(PermissionState.Granted, 2), vm.state.value)
    }

    @Test
    fun resumeWhileStillDenied_keepsPermanentDenial() {
        val vm = MainViewModel(FakeMediaPhotoSource(photos))
        vm.onPermissionResult(granted = false, shouldShowRationale = false)
        vm.onResume(granted = false)
        assertEquals(PermissionState.PermanentlyDenied, vm.state.value.permission)
    }

    @Test
    fun resumeAfterRevocation_clearsCount() {
        val vm = MainViewModel(FakeMediaPhotoSource(photos))
        vm.onResume(granted = true)
        vm.onResume(granted = false)
        assertEquals(MainUiState(PermissionState.NotRequested, null), vm.state.value)
    }

    @Test
    fun resumeWhenGranted_refreshesCount() {
        val source = FakeMediaPhotoSource(photos)
        val vm = MainViewModel(source)
        vm.onResume(granted = true)
        source.photos = photos + MediaPhoto(3L, "content://x/3", null)
        vm.onResume(granted = true)
        assertEquals(3, vm.state.value.photoCount)
    }

    @Test
    fun securityExceptionWhileCounting_fallsBackToNotRequested() {
        val vm = MainViewModel(FakeMediaPhotoSource(photos, failWith = SecurityException()))
        vm.onResume(granted = true)
        assertEquals(MainUiState(PermissionState.NotRequested, null), vm.state.value)
        assertNull(vm.state.value.photoCount)
    }

    @Test
    fun genericFailureWhileCounting_setsLoadError_keepsGranted() {
        val vm = MainViewModel(FakeMediaPhotoSource(photos, failWith = IllegalStateException()))
        vm.onResume(granted = true)
        assertEquals(MainUiState(PermissionState.Granted, null, loadError = true), vm.state.value)
    }

    @Test
    fun loadError_clearsOnSuccessfulRetry() {
        val source = FakeMediaPhotoSource(photos, failWith = IllegalStateException())
        val vm = MainViewModel(source)
        vm.onResume(granted = true)
        source.failWith = null
        vm.onResume(granted = true)
        assertEquals(MainUiState(PermissionState.Granted, 2, loadError = false), vm.state.value)
    }

    @Test
    fun cancellationWhileCounting_isNotReportedAsError() {
        val vm = MainViewModel(FakeMediaPhotoSource(photos, failWith = CancellationException()))
        vm.onResume(granted = true)
        assertEquals(false, vm.state.value.loadError)
    }
}
