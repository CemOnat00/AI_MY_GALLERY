package com.ktu.aigaleri.ui

import com.ktu.aigaleri.data.FakeMediaPhotoSource
import com.ktu.aigaleri.data.MediaPhoto
import com.ktu.aigaleri.data.MediaPhotoSource
import kotlinx.coroutines.CompletableDeferred
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

/** T-003 QA: MainViewModel izin geçişleri ve hata/yeniden deneme sınır durumları. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelEdgeCaseTest {
    private val photos = listOf(MediaPhoto(1L, "content://x/1", null), MediaPhoto(2L, "content://x/2", 5L))

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun emptyGallery_grantedWithZeroCount_notError() {
        val vm = MainViewModel(FakeMediaPhotoSource(emptyList()))
        vm.onPermissionResult(granted = true, shouldShowRationale = false)
        assertEquals(MainUiState(PermissionState.Granted, 0, loadError = false), vm.state.value)
    }

    @Test
    fun hugeGallery_countIsReported() {
        val huge = List(150_000) { MediaPhoto(it + 1L, "content://x/${it + 1}", null) }
        val vm = MainViewModel(FakeMediaPhotoSource(huge))
        vm.onResume(granted = true)
        assertEquals(150_000, vm.state.value.photoCount)
    }

    @Test
    fun deniedThenGranted_loadsCount() {
        val vm = MainViewModel(FakeMediaPhotoSource(photos))
        vm.onPermissionResult(granted = false, shouldShowRationale = true)
        assertEquals(PermissionState.Denied, vm.state.value.permission)
        vm.onPermissionResult(granted = true, shouldShowRationale = false)
        assertEquals(MainUiState(PermissionState.Granted, 2), vm.state.value)
    }

    @Test
    fun deniedThenPermanentlyDenied() {
        val vm = MainViewModel(FakeMediaPhotoSource(photos))
        vm.onPermissionResult(false, true)
        vm.onPermissionResult(false, false)
        assertEquals(MainUiState(PermissionState.PermanentlyDenied, null), vm.state.value)
    }

    @Test
    fun permanentlyDeniedThenGrantedViaResume_loadsCount() {
        val vm = MainViewModel(FakeMediaPhotoSource(photos))
        vm.onPermissionResult(false, false)
        vm.onResume(granted = true)
        assertEquals(MainUiState(PermissionState.Granted, 2), vm.state.value)
    }

    @Test
    fun resumeWhenNotRequestedAndNotGranted_staysNotRequested_noQuery() {
        val source = FakeMediaPhotoSource(photos)
        val vm = MainViewModel(source)
        vm.onResume(granted = false)
        assertEquals(MainUiState(PermissionState.NotRequested, null), vm.state.value)
        assertEquals(0, source.countCalls)
    }

    @Test
    fun resumeWhileDenied_keepsDenied_noQuery() {
        val source = FakeMediaPhotoSource(photos)
        val vm = MainViewModel(source)
        vm.onPermissionResult(false, true)
        vm.onResume(granted = false)
        assertEquals(PermissionState.Denied, vm.state.value.permission)
        assertEquals(0, source.countCalls)
    }

    @Test
    fun repeatedResumeWhileGranted_queriesEachTime_countStaysConsistent() {
        val source = FakeMediaPhotoSource(photos)
        val vm = MainViewModel(source)
        repeat(3) { vm.onResume(granted = true) }
        assertEquals(3, source.countCalls)
        assertEquals(MainUiState(PermissionState.Granted, 2), vm.state.value)
    }

    @Test
    fun galleryShrinksToEmpty_afterResume_countBecomesZero() {
        val source = FakeMediaPhotoSource(photos)
        val vm = MainViewModel(source)
        vm.onResume(granted = true)
        source.photos = emptyList()
        vm.onResume(granted = true)
        assertEquals(0, vm.state.value.photoCount)
    }

    @Test
    fun genericError_thenSuccess_clearsError_thenErrorAgain() {
        val source = FakeMediaPhotoSource(photos, failWith = IllegalStateException())
        val vm = MainViewModel(source)
        vm.onResume(true)
        assertEquals(true, vm.state.value.loadError)
        source.failWith = null
        vm.onResume(true)
        assertEquals(MainUiState(PermissionState.Granted, 2, false), vm.state.value)
        source.failWith = RuntimeException("x")
        vm.onResume(true)
        assertEquals(MainUiState(PermissionState.Granted, null, true), vm.state.value)
    }

    @Test
    fun errorState_thenPermissionLost_clearsLoadError() {
        val vm = MainViewModel(FakeMediaPhotoSource(photos, failWith = IllegalStateException()))
        vm.onResume(true)
        vm.onResume(false)
        assertEquals(MainUiState(PermissionState.NotRequested, null, false), vm.state.value)
    }

    @Test
    fun errorState_thenPermissionResultDenied_clearsLoadError() {
        val vm = MainViewModel(FakeMediaPhotoSource(photos, failWith = IllegalStateException()))
        vm.onResume(true)
        vm.onPermissionResult(false, false)
        assertEquals(MainUiState(PermissionState.PermanentlyDenied, null, false), vm.state.value)
    }

    @Test
    fun securityException_thenRegrant_retriesSuccessfully() {
        val source = FakeMediaPhotoSource(photos, failWith = SecurityException())
        val vm = MainViewModel(source)
        vm.onResume(true)
        assertEquals(PermissionState.NotRequested, vm.state.value.permission)
        source.failWith = null
        vm.onPermissionResult(true, false)
        assertEquals(MainUiState(PermissionState.Granted, 2), vm.state.value)
    }

    @Test
    fun securityException_afterPreviousCount_resetsCountToNull() {
        val source = FakeMediaPhotoSource(photos)
        val vm = MainViewModel(source)
        vm.onResume(true)
        source.failWith = SecurityException()
        vm.onResume(true)
        assertNull(vm.state.value.photoCount)
        assertEquals(false, vm.state.value.loadError)
    }

    @Test
    fun errorAfterEarlierSuccess_dropsStaleCount() {
        val source = FakeMediaPhotoSource(photos)
        val vm = MainViewModel(source)
        vm.onResume(true)
        source.failWith = IllegalStateException()
        vm.onResume(true)
        assertEquals(MainUiState(PermissionState.Granted, null, true), vm.state.value)
    }

    @Test
    fun pendingCount_isCancelled_whenPermissionRevokedBeforeItCompletes() {
        val gate = CompletableDeferred<Int>()
        val source = object : MediaPhotoSource {
            override suspend fun count(): Int = gate.await()
            override suspend fun loadPhotos(): List<MediaPhoto> = emptyList()
        }
        val vm = MainViewModel(source)
        vm.onResume(true)
        vm.onResume(false)
        gate.complete(42)
        assertEquals(MainUiState(PermissionState.NotRequested, null), vm.state.value)
    }

    @Test
    fun pendingCount_isCancelled_whenResultDeniedBeforeItCompletes() {
        val gate = CompletableDeferred<Int>()
        val source = object : MediaPhotoSource {
            override suspend fun count(): Int = gate.await()
            override suspend fun loadPhotos(): List<MediaPhoto> = emptyList()
        }
        val vm = MainViewModel(source)
        vm.onResume(true)
        vm.onPermissionResult(false, true)
        gate.complete(7)
        assertEquals(MainUiState(PermissionState.Denied, null), vm.state.value)
    }

    @Test
    fun grantedFlow_stateExposesPermissionStatesInOrder() {
        val vm = MainViewModel(FakeMediaPhotoSource(photos))
        val seen = mutableListOf(vm.state.value.permission)
        vm.onPermissionResult(false, true); seen += vm.state.value.permission
        vm.onPermissionResult(false, false); seen += vm.state.value.permission
        vm.onResume(true); seen += vm.state.value.permission
        assertEquals(
            listOf(PermissionState.NotRequested, PermissionState.Denied, PermissionState.PermanentlyDenied, PermissionState.Granted),
            seen,
        )
    }
}
