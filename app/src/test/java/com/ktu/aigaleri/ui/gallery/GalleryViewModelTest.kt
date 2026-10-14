package com.ktu.aigaleri.ui.gallery

import com.ktu.aigaleri.data.FakeMediaPhotoSource
import com.ktu.aigaleri.data.MediaPhoto
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

    private fun GalleryViewModel.photos(): GalleryPhotos = (state.value as GalleryUiState.Ready).photos

    private fun GalleryPhotos.ids() = List(size) { idAt(it) }

    @Test
    fun initialState_isLoading_andDoesNotReadWithoutPermission() {
        val source = FakeMediaPhotoSource(listOf(photo(1, 10)))
        val vm = vm(source)
        assertEquals(GalleryUiState.Loading, vm.state.value)
        vm.refresh(granted = false)
        assertEquals(GalleryUiState.Loading, vm.state.value)
    }

    @Test
    fun granted_listsAllPhotos_newestFirst_unknownDatesLast() {
        val source = FakeMediaPhotoSource(
            listOf(photo(1, 100), photo(2, null), photo(3, 300), photo(4, 200), photo(5, null)),
        )
        val vm = vm(source)
        vm.refresh(granted = true)
        val photos = vm.photos()
        // tarihsizler sonda, eşitlikte kimlik azalan
        assertEquals(listOf(3L, 4L, 1L, 5L, 2L), photos.ids())
        assertEquals(300L, photos.dateAt(0))
        assertNull(photos.dateAt(3))
        assertNull(photos.dateAt(4))
    }

    @Test
    fun sourceAlreadySorted_orderIsKept() {
        val source = FakeMediaPhotoSource(listOf(photo(9, 90), photo(8, 80), photo(7, 70)))
        val vm = vm(source)
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

    @Test
    fun securityException_doesNotCrash_andIsNotShownAsError() {
        val vm = vm(FakeMediaPhotoSource(listOf(photo(1, 10)), failWith = SecurityException("izin")))
        vm.refresh(true)
        assertEquals(GalleryUiState.Loading, vm.state.value)
    }

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
    fun refreshFailure_keepsExistingList() {
        val source = FakeMediaPhotoSource(listOf(photo(1, 10)))
        val vm = vm(source)
        vm.refresh(true)
        source.failWith = IllegalStateException("x")
        vm.refresh(true)
        assertEquals(listOf(1L), vm.photos().ids())
    }

    @Test
    fun refresh_withSameContent_keepsEqualState() {
        val source = FakeMediaPhotoSource(listOf(photo(2, 20), photo(1, 10)))
        val vm = vm(source)
        vm.refresh(true)
        val first = vm.state.value
        vm.refresh(true)
        assertEquals(first, vm.state.value) // içerik eşitliği: gereksiz yeniden çizim yok
    }

    @Test
    fun permissionRevoked_resetsToLoading() {
        val vm = vm(FakeMediaPhotoSource(listOf(photo(1, 10))))
        vm.refresh(true)
        assertTrue(vm.state.value is GalleryUiState.Ready)
        vm.refresh(false)
        assertEquals(GalleryUiState.Loading, vm.state.value)
    }

    @Test
    fun largeGallery_keepsOnlyIdAndDate_andOrdersCorrectly() {
        val n = 50_000
        val source = FakeMediaPhotoSource(List(n) { photo(it + 1L, (it % 1000).toLong() + 1) })
        val vm = vm(source)
        vm.refresh(true)
        val photos = vm.photos()
        assertEquals(n, photos.size)
        for (i in 1 until n) assertTrue(photos.dateAt(i - 1)!! >= photos.dateAt(i)!!)
    }

    @Test
    fun galleryPhotos_requiresSameSizedArrays() {
        val result = runCatching { GalleryPhotos(longArrayOf(1, 2), longArrayOf(1)) }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
}
