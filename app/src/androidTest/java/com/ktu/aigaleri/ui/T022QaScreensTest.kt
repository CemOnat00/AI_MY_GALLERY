package com.ktu.aigaleri.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.ktu.aigaleri.domain.SearchRepository
import com.ktu.aigaleri.domain.SearchResult
import com.ktu.aigaleri.ui.index.IndexStatusContent
import com.ktu.aigaleri.ui.index.IndexStatusUiState
import com.ktu.aigaleri.ui.gallery.GalleryViewModel
import com.ktu.aigaleri.ui.index.IndexStatusViewModel
import com.ktu.aigaleri.ui.index.TAG_REINDEX_BUTTON
import com.ktu.aigaleri.ui.search.SearchContent
import com.ktu.aigaleri.ui.search.SearchStatus
import com.ktu.aigaleri.ui.search.SearchUiState
import com.ktu.aigaleri.ui.search.SearchViewModel
import com.ktu.aigaleri.ui.search.TAG_QUERY_FIELD
import com.ktu.aigaleri.ui.search.TAG_RESULT_GRID
import com.ktu.aigaleri.ui.stub.StubIndexLauncher
import com.ktu.aigaleri.ui.theme.AiGaleriTheme
import com.ktu.aigaleri.ui.viewer.PhotoViewerScreen
import com.ktu.aigaleri.ui.viewer.TAG_VIEWER_DATE
import com.ktu.aigaleri.ui.viewer.TAG_VIEWER_NOT_FOUND
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * T-022 qa: kenar durumlar (rota tarih argümanı, boş/çok büyük galeri, uzun ve Türkçe sorgu, izin
 * durumları, 2x yazı). Emülatör yok: yalnızca DERLENİR, ÇALIŞTIRILMADI.
 */
class T022QaScreensTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var navController: NavHostController

    private val repository = object : SearchRepository {
        override suspend fun search(query: String, limit: Int) = emptyList<SearchResult>()
    }

    private fun show(permission: PermissionState) {
        rule.setContent {
            navController = rememberNavController()
            AppNavHost(
                navController = navController,
                state = MainUiState(permission = permission),
                searchViewModel = SearchViewModel(repository),
                indexStatusViewModel = IndexStatusViewModel(flowOf(null), StubIndexLauncher()),
                galleryViewModel = GalleryViewModel(emptyGallerySource),
                onRequestPermission = {},
                onOpenSettings = {},
            )
        }
    }

    private fun go(route: String) {
        rule.runOnUiThread { navController.navigate(route) }
        rule.waitForIdle()
    }

    // ---- viewer rotası: tarih argümanı ----

    @Test
    fun viewerRoute_negativeDateRoute_isTreatedAsUnknown() {
        show(PermissionState.Granted)
        go("viewer/1?dateTaken=-5") // elle üretilmiş rota: negatif tarih tarih satırı göstermez
        assertEquals(Routes.VIEWER, navController.currentBackStackEntry?.destination?.route)
        rule.onNodeWithTag(TAG_VIEWER_DATE).assertDoesNotExist()
    }

    @Test
    fun viewerRoute_noDateSentinel_hidesDateRow() {
        show(PermissionState.Granted)
        go("viewer/1?dateTaken=${Routes.NO_DATE}")
        rule.onNodeWithTag(TAG_VIEWER_DATE).assertDoesNotExist()
    }

    @Test
    fun viewerRoute_zeroDate_showsDateRow() {
        show(PermissionState.Granted)
        go(Routes.viewer(1L, 0L))
        rule.onNodeWithTag(TAG_VIEWER_DATE).assertExists()
    }

    @Test
    fun viewerRoute_hugeDate_doesNotCrash_andShowsDateRow() {
        show(PermissionState.Granted)
        go(Routes.viewer(1L, Long.MAX_VALUE))
        assertEquals(Routes.VIEWER, navController.currentBackStackEntry?.destination?.route)
        rule.onNodeWithTag(TAG_VIEWER_DATE).assertExists()
    }

    @Test
    fun viewerRoute_zeroOrNegativePhotoId_showsNotFound() {
        show(PermissionState.Granted)
        go("viewer/0")
        rule.onNodeWithTag(TAG_VIEWER_NOT_FOUND).assertExists()
        go("viewer/-7?dateTaken=1790000000000")
        rule.onNodeWithTag(TAG_VIEWER_NOT_FOUND).assertExists()
        rule.onNodeWithText("Fotoğraf bulunamadı").assertExists()
    }

    @Test
    fun viewer_deletedPhoto_keepsDateRowAndBackWorks() {
        var backs = 0
        rule.setContent {
            AiGaleriTheme { PhotoViewerScreen(photoId = 987_654_321L, onBack = { backs++ }, dateTaken = 1_790_000_000_000L) }
        }
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithTagCount(TAG_VIEWER_NOT_FOUND) > 0
        }
        rule.onNodeWithTag(TAG_VIEWER_DATE).assertExists()
        rule.onNodeWithText("Geri dön").performClick()
        assertEquals(1, backs)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodesWithTag(tag).fetchSemanticsNodes().size

    // ---- arama: boş / çok büyük galeri, sorgular ----

    private fun search(state: SearchUiState, onQueryChange: (String) -> Unit = {}) = rule.setContent {
        AiGaleriTheme { SearchContent(state, onQueryChange, onSearch = {}, onOpenPhoto = {}, onOpenIndexStatus = {}) }
    }

    @Test
    fun emptyGallery_emptyStateShown_noGrid_noCounter() {
        search(SearchUiState("kedi", SearchStatus.Empty))
        rule.onNodeWithText("Eşleşen fotoğraf bulunamadı.").assertExists()
        rule.onNodeWithTag(TAG_RESULT_GRID).assertDoesNotExist()
        rule.onNodeWithText("En yakın eşleşmeler").assertDoesNotExist()
    }

    @Test
    fun hugeResultList_countUsesTurkishGrouping() {
        val results = List(1200) { SearchResult(it + 1L, "content://media/external/images/media/${it + 1}", 0.5f) }
        search(SearchUiState("kedi", SearchStatus.Success(results)))
        rule.onNodeWithText("1.200 fotoğraf").assertExists()
        rule.onNodeWithTag(TAG_RESULT_GRID).assertExists()
    }

    @Test
    fun singleResult_showsCounter() {
        search(SearchUiState("kedi", SearchStatus.Success(listOf(SearchResult(1L, "content://media/external/images/media/1", 0.9f)))))
        rule.onNodeWithText("1 fotoğraf").assertExists()
    }

    @Test
    fun veryLongQuery_doesNotCrash_andFieldStaysUsable() {
        val long = "a".repeat(5000)
        search(SearchUiState(long))
        rule.onNodeWithTag(TAG_QUERY_FIELD).assertExists()
    }

    @Test
    fun turkishCharsQuery_isShownAsTyped() {
        val q = "ÇĞİÖŞÜ çğıöşü Iı İi"
        search(SearchUiState(q))
        rule.onNodeWithText(q).assertExists()
    }

    @Test
    fun blankQueryWithWhitespaceOnly_doesNotCrash() {
        search(SearchUiState("   \n\t "))
        rule.onNodeWithTag(TAG_QUERY_FIELD).assertExists()
    }

    // ---- izin ----

    @Test
    fun permissionDenied_showsGrantButton_noSearchField() {
        show(PermissionState.Denied)
        rule.onNodeWithTag(TAG_QUERY_FIELD).assertDoesNotExist()
        rule.onNodeWithText("İzin ver").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun permissionPermanentlyDenied_atBigFont_settingsStaysReachable() {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
                AiGaleriTheme { MainContent(MainUiState(permission = PermissionState.PermanentlyDenied), {}, {}) }
            }
        }
        rule.onNodeWithText("Ayarları aç").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Nasıl etkinleştirilir?").assertExists()
    }

    // ---- indeks durumu: sınır sayılar ----

    @Test
    fun indexStatus_hugeCounts_areGroupedTurkish_andStillReachable() {
        var clicked = false
        rule.setContent {
            AiGaleriTheme {
                IndexStatusContent(
                    IndexStatusUiState.Data(total = 1_000_000, processed = 999_999, lastRunAt = null),
                    reindexAvailable = true, reindexInProgress = false,
                    onReindex = { clicked = true }, onRetry = {}, onBack = {},
                )
            }
        }
        rule.onNodeWithText("999.999 / 1.000.000").assertExists()
        rule.onNodeWithTag(TAG_REINDEX_BUTTON).performClick()
        assertTrue(clicked)
    }
}
