package com.ktu.aigaleri.ui.gallery

import com.ktu.aigaleri.data.FakeMediaPhotoSource
import com.ktu.aigaleri.data.MediaPhoto
import com.ktu.aigaleri.data.PhotoTimeline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun photo(id: Long, date: Long?) = MediaPhoto(id, "content://media/external/images/media/$id", date)

    private fun vm(source: FakeMediaPhotoSource) = GalleryViewModel(source, dispatcher)

    private fun GalleryViewModel.photos(): PhotoTimeline = (state.value as GalleryUiState.Ready).photos

    private fun PhotoTimeline.ids() = List(size) { idAt(it) }

    @Test
    fun initialState_isLoading_andDoesNotReadBeforeRefresh() {
        val source = FakeMediaPhotoSource(listOf(photo(1, 10)))
        val vm = vm(source)
        assertEquals(GalleryUiState.Loading, vm.state.value)
        assertEquals(0, source.loadCalls)
    }

    @Test
    fun granted_listsAllPhotos_newestFirst_unknownDatesLast() {
        val source = FakeMediaPhotoSource(
            listOf(photo(1, 100), photo(2, null), photo(3, 300), photo(4, 200), photo(5, null)),
        )
        val vm = vm(source)
        vm.refresh(granted = true)
        val photos = vm.photos()
        assertEquals(listOf(3L, 4L, 1L, 5L, 2L), photos.ids())
        assertEquals(300L, photos.dateAt(0))
        assertNull(photos.dateAt(3))
        assertNull(photos.dateAt(4))
    }

    @Test
    fun equalDates_orderedByIdDescending() {
        val vm = vm(FakeMediaPhotoSource(listOf(photo(1, 50), photo(7, 50), photo(3, 50))))
        vm.refresh(true)
        assertEquals(listOf(7L, 3L, 1L), vm.photos().ids())
    }

    @Test
    fun sourceAlreadySorted_orderIsKept() {
        val vm = vm(FakeMediaPhotoSource(listOf(photo(9, 90), photo(8, 80), photo(7, 70))))
        vm.refresh(true)
        assertEquals(listOf(9L, 8L, 7L), vm.photos().ids())
    }

    @Test
    fun emptyGallery_isEmptyState_notError() {
        val vm = vm(FakeMediaPhotoSource(emptyList()))
        vm.refresh(true)
        assertEquals(GalleryUiState.Empty, vm.state.value)
    }

    @Test
    fun readFailure_isError_andRetryRecovers() {
        val source = FakeMediaPhotoSource(listOf(photo(1, 10)), failWith = IllegalStateException("x"))
        val vm = vm(source)
        vm.refresh(true)
        assertEquals(GalleryUiState.Error, vm.state.value)
        source.failWith = null
        vm.retry()
        assertEquals(listOf(1L), vm.photos().ids())
    }

    // ---- izin ----

    @Test
    fun securityException_withoutList_isPermissionRequired_notEndlessSkeleton() {
        val vm = vm(FakeMediaPhotoSource(listOf(photo(1, 10)), failWith = SecurityException("izin")))
        vm.refresh(true)
        assertEquals(GalleryUiState.PermissionRequired, vm.state.value)
    }

    @Test
    fun securityException_dropsExistingList_andRecoversWhenGrantedAgain() {
        val source = FakeMediaPhotoSource(listOf(photo(1, 10)))
        val vm = vm(source)
        vm.refresh(true)
        source.failWith = SecurityException("izin")
        vm.refresh(true)
        assertEquals(GalleryUiState.PermissionRequired, vm.state.value)
        source.failWith = null
        vm.refresh(true)
        assertEquals(listOf(1L), vm.photos().ids())
    }

    @Test
    fun permissionDenied_isPermissionRequired_andDoesNotRead() {
        val source = FakeMediaPhotoSource(listOf(photo(1, 10)))
        val vm = vm(source)
        vm.refresh(granted = false)
        assertEquals(GalleryUiState.PermissionRequired, vm.state.value)
        assertEquals(0, source.loadCalls)
        assertEquals(0, source.tokenCalls)
    }

    @Test
    fun permissionRevoked_dropsList() {
        val vm = vm(FakeMediaPhotoSource(listOf(photo(1, 10))))
        vm.refresh(true)
        assertTrue(vm.state.value is GalleryUiState.Ready)
        vm.refresh(false)
        assertEquals(GalleryUiState.PermissionRequired, vm.state.value)
    }

    // ---- yenileme ----

    @Test
    fun refresh_picksUpNewPhotos_withoutShowingSkeleton() {
        val source = FakeMediaPhotoSource(listOf(photo(1, 10)))
        val vm = vm(source)
        vm.refresh(true)
        source.photos = listOf(photo(1, 10), photo(2, 20))
        vm.refresh(true)
        assertEquals(listOf(2L, 1L), vm.photos().ids())
    }

    @Test
    fun refresh_unchangedGallery_skipsFullRead() {
        val source = FakeMediaPhotoSource(listOf(photo(2, 20), photo(1, 10)))
        val vm = vm(source)
        vm.refresh(true)
        val first = vm.state.value
        vm.refresh(true)
        vm.refresh(true)
        assertEquals(1, source.loadCalls)
        assertEquals(3, source.tokenCalls)
        assertEquals(first, vm.state.value)
    }

    @Test
    fun refresh_emptyGalleryUnchanged_skipsFullRead_thenPicksUpFirstPhoto() {
        val source = FakeMediaPhotoSource(emptyList())
        val vm = vm(source)
        vm.refresh(true)
        vm.refresh(true)
        assertEquals(1, source.loadCalls)
        source.photos = listOf(photo(5, 50))
        vm.refresh(true)
        assertEquals(listOf(5L), vm.photos().ids())
    }

    @Test
    fun refresh_deletedAndAddedSameCount_isDetected() {
        val source = FakeMediaPhotoSource(listOf(photo(1, 10), photo(2, 20)))
        val vm = vm(source)
        vm.refresh(true)
        source.photos = listOf(photo(2, 20), photo(3, 30))
        vm.refresh(true)
        assertEquals(listOf(3L, 2L), vm.photos().ids())
    }

    @Test
    fun overlappingRefresh_coalesces_runningReadIsNotRestarted() {
        val source = FakeMediaPhotoSource(listOf(photo(1, 10)))
        source.gate = CompletableDeferred()
        val vm = vm(source)
        vm.refresh(true) // okuma başladı, geçitte bekliyor
        vm.refresh(true) // onResume + izin callback'i gibi art arda çağrılar
        vm.refresh(true)
        assertEquals(1, source.loadCalls) // yeni okuma başlamadı
        source.gate!!.complete(Unit)
        assertEquals(listOf(1L), vm.photos().ids())
        // Bekleyen çağrılar için yalnızca ucuz kontrol yapıldı; tam okuma tekrarlanmadı.
        assertEquals(1, source.loadCalls)
    }

    @Test
    fun overlappingRefresh_changeDuringRead_isPickedUpByPendingCall() {
        val source = FakeMediaPhotoSource(listOf(photo(1, 10)))
        source.gate = CompletableDeferred()
        val vm = vm(source)
        vm.refresh(true)
        source.photos = listOf(photo(1, 10), photo(2, 20))
        vm.refresh(true)
        source.gate!!.complete(Unit)
        assertEquals(listOf(2L, 1L), vm.photos().ids())
    }

    // ---- hata: eldeki durum korunur ----

    @Test
    fun refreshFailure_keepsReadyList() {
        val source = FakeMediaPhotoSource(listOf(photo(1, 10)))
        val vm = vm(source)
        vm.refresh(true)
        source.failWith = IllegalStateException("x")
        vm.refresh(true)
        assertEquals(listOf(1L), vm.photos().ids())
    }

    @Test
    fun refreshFailure_keepsEmpty_notError() {
        val source = FakeMediaPhotoSource(emptyList())
        val vm = vm(source)
        vm.refresh(true)
        source.failWith = IllegalStateException("x")
        vm.refresh(true)
        assertEquals(GalleryUiState.Empty, vm.state.value)
    }

    @Test
    fun failedFullRead_afterSuccess_isRetriedOnNextRefresh() {
        val source = FakeMediaPhotoSource(listOf(photo(1, 10)))
        val vm = vm(source)
        vm.refresh(true)
        source.photos = listOf(photo(1, 10), photo(2, 20))
        source.failWith = IllegalStateException("x")
        vm.refresh(true)
        source.failWith = null
        vm.refresh(true)
        assertEquals(listOf(2L, 1L), vm.photos().ids())
    }

    @Test
    fun largeGallery_keepsOnlyIdAndDate_andOrdersCorrectly() {
        val n = 50_000
        val vm = vm(FakeMediaPhotoSource(List(n) { photo(it + 1L, (it % 1000).toLong() + 1) }))
        vm.refresh(true)
        val photos = vm.photos()
        assertEquals(n, photos.size)
        for (i in 1 until n) assertTrue(photos.dateAt(i - 1)!! >= photos.dateAt(i)!!)
    }
}
