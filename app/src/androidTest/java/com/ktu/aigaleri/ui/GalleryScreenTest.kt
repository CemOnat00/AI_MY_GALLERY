package com.ktu.aigaleri.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.ktu.aigaleri.data.MediaPhoto
import com.ktu.aigaleri.data.MediaPhotoSource
import com.ktu.aigaleri.domain.SearchRepository
import com.ktu.aigaleri.domain.SearchResult
import com.ktu.aigaleri.ui.gallery.GalleryPhotos
import com.ktu.aigaleri.ui.gallery.GalleryUiState
import com.ktu.aigaleri.ui.gallery.GalleryViewModel
import com.ktu.aigaleri.ui.gallery.TAG_GALLERY_EMPTY
import com.ktu.aigaleri.ui.gallery.TAG_GALLERY_ERROR
import com.ktu.aigaleri.ui.gallery.TAG_GALLERY_GRID
import com.ktu.aigaleri.ui.gallery.TAG_GALLERY_LOADING
import com.ktu.aigaleri.ui.gallery.TAG_GALLERY_RETRY
import com.ktu.aigaleri.ui.index.IndexStatusViewModel
import com.ktu.aigaleri.ui.search.SearchContent
import com.ktu.aigaleri.ui.search.SearchStatus
import com.ktu.aigaleri.ui.search.SearchSuggestions
import com.ktu.aigaleri.ui.search.SearchUiState
import com.ktu.aigaleri.ui.search.SearchViewModel
import com.ktu.aigaleri.ui.search.TAG_RESULT_GRID
import com.ktu.aigaleri.ui.search.TAG_SUGGESTION_CHIPS
import com.ktu.aigaleri.ui.stub.StubIndexLauncher
import com.ktu.aigaleri.ui.theme.AiGaleriTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Boş galeri kaynağı (gezinti testlerinde grid içeriği önemsizdir). */
internal val emptyGallerySource = object : MediaPhotoSource {
    override suspend fun count() = 0
    override suspend fun loadPhotos() = emptyList<MediaPhoto>()
}

/**
 * T-036: ilk sayfa galeri grid'i + öneri çipleri. Emülatör yok: yalnızca DERLENİR, ÇALIŞTIRILMADI (çalıştırma qa'da).
 */
class GalleryScreenTest {
    @get:Rule val rule = createComposeRule()

    private val photos = GalleryPhotos(longArrayOf(30L, 20L, 10L), longArrayOf(1_790_000_000_000L, 1_780_000_000_000L, -1L))
    private val ready = GalleryUiState.Ready(photos)

    private fun search(
        state: SearchUiState = SearchUiState(),
        gallery: GalleryUiState = ready,
        onQueryChange: (String) -> Unit = {},
        onSearch: () -> Unit = {},
        onOpenGalleryPhoto: (Long, Long?) -> Unit = { _, _ -> },
        onGalleryRetry: () -> Unit = {},
    ) = rule.setContent {
        AiGaleriTheme {
            SearchContent(
                state = state,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                onOpenPhoto = {},
                onOpenIndexStatus = {},
                gallery = gallery,
                onOpenGalleryPhoto = onOpenGalleryPhoto,
                onGalleryRetry = onGalleryRetry,
            )
        }
    }

    @Test
    fun idle_showsChipsAndGalleryGrid() {
        search()
        rule.onNodeWithTag(TAG_SUGGESTION_CHIPS).assertExists()
        for (s in SearchSuggestions.ALL) rule.onNodeWithText(s).assertExists().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(TAG_GALLERY_GRID).assertExists()
        rule.onNodeWithTag(TAG_RESULT_GRID).assertDoesNotExist()
    }

    @Test
    fun chipTap_fillsQueryAndSearches() {
        var query: String? = null
        var searched = false
        search(onQueryChange = { query = it }, onSearch = { searched = true })
        rule.onNodeWithText("kırmızı araba").performClick()
        assertEquals("kırmızı araba", query)
        assertTrue(searched)
    }

    @Test
    fun galleryItemTap_reportsIdAndDate() {
        var opened: Pair<Long, Long?>? = null
        search(onOpenGalleryPhoto = { id, date -> opened = id to date })
        rule.onNodeWithTag(TAG_GALLERY_GRID).onChildren()[0].performClick()
        assertEquals(30L to 1_790_000_000_000L, opened)
        rule.onNodeWithTag(TAG_GALLERY_GRID).onChildren()[2].performClick()
        assertEquals(10L to null, opened)
    }

    @Test
    fun galleryLoading_showsSkeleton_chipsStay() {
        search(gallery = GalleryUiState.Loading)
        rule.onNodeWithTag(TAG_GALLERY_LOADING).assertExists()
        rule.onNodeWithTag(TAG_SUGGESTION_CHIPS).assertExists()
    }

    @Test
    fun galleryEmpty_showsShortText() {
        search(gallery = GalleryUiState.Empty)
        rule.onNodeWithTag(TAG_GALLERY_EMPTY).assertExists()
        rule.onNodeWithText("Galeride fotoğraf yok").assertExists()
    }

    @Test
    fun galleryError_showsRetry_andRetryCallsBack() {
        var retried = false
        search(gallery = GalleryUiState.Error, onGalleryRetry = { retried = true })
        rule.onNodeWithTag(TAG_GALLERY_ERROR).assertExists()
        rule.onNodeWithTag(TAG_GALLERY_RETRY).assertHeightIsAtLeast(48.dp).performClick()
        assertTrue(retried)
    }

    @Test
    fun searchStatuses_replaceGallery() {
        val results = List(3) { SearchResult(it + 1L, "content://media/external/images/media/${it + 1}", 0.5f) }
        var state by mutableStateOf(SearchUiState("kedi", SearchStatus.Success(results)))
        rule.setContent {
            AiGaleriTheme {
                SearchContent(state, onQueryChange = {}, onSearch = {}, onOpenPhoto = {}, onOpenIndexStatus = {}, gallery = ready)
            }
        }
        rule.onNodeWithTag(TAG_RESULT_GRID).assertExists()
        rule.onNodeWithTag(TAG_GALLERY_GRID).assertDoesNotExist()
        rule.onAllNodesWithTag(TAG_SUGGESTION_CHIPS).assertCountEquals(0)
        // Arama kutusu temizlenince (durum Idle) grid'e dönülür.
        rule.runOnUiThread { state = SearchUiState() }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_GALLERY_GRID).assertExists()
        rule.onNodeWithTag(TAG_RESULT_GRID).assertDoesNotExist()
    }

    @Test
    fun gridState_survivesSearchAndBack() {
        val many = GalleryUiState.Ready(GalleryPhotos(LongArray(300) { 1000L - it }, LongArray(300) { 1_790_000_000_000L - it }))
        var state by mutableStateOf(SearchUiState())
        rule.setContent {
            AiGaleriTheme {
                SearchContent(state, onQueryChange = {}, onSearch = {}, onOpenPhoto = {}, onOpenIndexStatus = {}, gallery = many)
            }
        }
        rule.onNodeWithTag(TAG_GALLERY_GRID).performScrollToIndex(150)
        rule.runOnUiThread { state = SearchUiState("kedi", SearchStatus.Empty) }
        rule.waitForIdle()
        rule.runOnUiThread { state = SearchUiState() }
        rule.waitForIdle()
        // kaydırma konumu 0'a dönmedi
        rule.onNodeWithTag(TAG_GALLERY_GRID).assert(isScrolledDown)
    }

    private val isScrolledDown = SemanticsMatcher("dikey kaydırma konumu > 0") {
        (it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke() ?: 0f) > 0f
    }

    // ---- gezinti: grid öğesi -> viewer rotası (tarih argümanıyla) ----

    private lateinit var navController: NavHostController

    private val repository = object : SearchRepository {
        override suspend fun search(query: String, limit: Int) = emptyList<SearchResult>()
    }

    private fun showNav(galleryViewModel: GalleryViewModel) {
        rule.setContent {
            navController = rememberNavController()
            AppNavHost(
                navController = navController,
                state = MainUiState(permission = PermissionState.Granted),
                searchViewModel = SearchViewModel(repository),
                indexStatusViewModel = IndexStatusViewModel(flowOf(null), StubIndexLauncher()),
                galleryViewModel = galleryViewModel,
                onRequestPermission = {},
                onOpenSettings = {},
            )
        }
        rule.runOnUiThread { galleryViewModel.refresh(true) }
    }

    private val fakeSource = object : MediaPhotoSource {
        override suspend fun count() = 2
        override suspend fun loadPhotos() = listOf(
            MediaPhoto(7L, "content://media/external/images/media/7", 1_790_000_000_000L),
            MediaPhoto(5L, "content://media/external/images/media/5", null),
        )
    }

    @Test
    fun navigation_gridItemOpensViewerRoute_withDate() {
        showNav(GalleryViewModel(fakeSource))
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithTag(TAG_GALLERY_GRID).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag(TAG_GALLERY_GRID).onChildren()[0].performClick()
        rule.waitForIdle()
        val entry = navController.currentBackStackEntry
        assertEquals(Routes.VIEWER, entry?.destination?.route)
        assertEquals(7L, entry?.arguments?.getLong(Routes.PHOTO_ARG))
        assertEquals(1_790_000_000_000L, entry?.arguments?.getLong(Routes.DATE_ARG))
    }

    @Test
    fun navigation_gridItemWithoutDate_opensViewerWithoutDateArgument() {
        showNav(GalleryViewModel(fakeSource))
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithTag(TAG_GALLERY_GRID).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag(TAG_GALLERY_GRID).onChildren()[1].performClick()
        rule.waitForIdle()
        val entry = navController.currentBackStackEntry
        assertEquals(5L, entry?.arguments?.getLong(Routes.PHOTO_ARG))
        assertEquals(Routes.NO_DATE, entry?.arguments?.getLong(Routes.DATE_ARG))
    }
}
