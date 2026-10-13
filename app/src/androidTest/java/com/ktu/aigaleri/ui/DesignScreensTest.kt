package com.ktu.aigaleri.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.ktu.aigaleri.domain.InvalidQueryException
import com.ktu.aigaleri.domain.SearchResult
import com.ktu.aigaleri.ui.index.IndexStatusContent
import com.ktu.aigaleri.ui.index.IndexStatusUiState
import com.ktu.aigaleri.ui.index.TAG_BACK_BUTTON
import com.ktu.aigaleri.ui.index.TAG_REINDEX_BUTTON
import com.ktu.aigaleri.ui.index.TAG_RETRY_BUTTON
import com.ktu.aigaleri.ui.search.SearchContent
import com.ktu.aigaleri.ui.search.SearchStatus
import com.ktu.aigaleri.ui.search.SearchSuggestions
import com.ktu.aigaleri.ui.search.SearchUiState
import com.ktu.aigaleri.ui.search.TAG_EMPTY_INDEX_LINK
import com.ktu.aigaleri.ui.search.TAG_ERROR_RETRY
import com.ktu.aigaleri.ui.search.TAG_SEARCH_BUTTON
import com.ktu.aigaleri.ui.theme.AiGaleriTheme
import com.ktu.aigaleri.ui.viewer.PhotoViewerScreen
import com.ktu.aigaleri.ui.viewer.TAG_VIEWER_NOT_FOUND
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * T-022 tasarım ekranları testleri (temalı). Emülatör yok: bu görevde yalnızca DERLENİR, ÇALIŞTIRILMADI.
 */
class DesignScreensTest {
    @get:Rule val rule = createComposeRule()

    private fun search(
        state: SearchUiState,
        onQueryChange: (String) -> Unit = {},
        onSearch: () -> Unit = {},
        onOpenIndexStatus: () -> Unit = {},
    ) = rule.setContent {
        AiGaleriTheme {
            SearchContent(state, onQueryChange, onSearch, onOpenPhoto = {}, onOpenIndexStatus = onOpenIndexStatus)
        }
    }

    @Test
    fun idle_showsFiveSuggestions_andTapFillsQueryAndSearches() {
        var query: String? = null
        var searched = false
        search(SearchUiState(), onQueryChange = { query = it }, onSearch = { searched = true })
        for (s in SearchSuggestions.ALL) rule.onNodeWithText(s).assertExists()
        rule.onNodeWithText("kırmızı araba").performClick()
        assertEquals("kırmızı araba", query)
        assertTrue(searched)
    }

    @Test
    fun results_showCounterRow() {
        val results = List(14) { SearchResult(it + 1L, "content://media/external/images/media/${it + 1}", 0.5f) }
        search(SearchUiState("kedi", SearchStatus.Success(results)))
        rule.onNodeWithText("En yakın eşleşmeler").assertExists()
        rule.onNodeWithText("14 fotoğraf").assertExists()
    }

    @Test
    fun loading_showsSearchingLabel() {
        search(SearchUiState("kedi", SearchStatus.Loading))
        rule.onNodeWithText("Aranıyor...").assertExists()
    }

    @Test
    fun empty_showsMessage_andLinkOpensIndexStatus() {
        var opened = false
        search(SearchUiState("kedi", SearchStatus.Empty), onOpenIndexStatus = { opened = true })
        rule.onNodeWithText("Eşleşen fotoğraf bulunamadı.").assertExists()
        rule.onNodeWithTag(TAG_EMPTY_INDEX_LINK).assertHeightIsAtLeast(48.dp).performClick()
        assertTrue(opened)
    }

    @Test
    fun error_showsMessage_andRetrySearchesAgain() {
        var searched = false
        search(SearchUiState("kedi", SearchStatus.Error), onSearch = { searched = true })
        rule.onNodeWithText("Arama şu an yapılamadı.").assertExists()
        rule.onNodeWithTag(TAG_ERROR_RETRY).assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        assertTrue(searched)
    }

    @Test
    fun invalidQuery_showsInlineMessage() {
        search(SearchUiState("", SearchStatus.InvalidQuery(InvalidQueryException.Reason.EMPTY)))
        rule.onNodeWithText("Lütfen bir şey yaz.").assertExists()
    }

    @Test
    fun searchAndIndexStatusButtons_haveMinimumTouchTarget() {
        search(SearchUiState(query = "kedi"))
        rule.onNodeWithTag(TAG_SEARCH_BUTTON).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("İndeks durumu").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }

    private fun permission(state: PermissionState, onGrant: () -> Unit = {}, onSettings: () -> Unit = {}) = rule.setContent {
        AiGaleriTheme { MainContent(MainUiState(permission = state), onGrant, onSettings) }
    }

    @Test
    fun permission_firstOpen_showsExplanation_noSecondaryButtons_andGrantWorks() {
        var granted = false
        permission(PermissionState.NotRequested, onGrant = { granted = true })
        rule.onNodeWithText("Fotoğraflarınıza erişim izni").assertExists()
        rule.onNodeWithText("yüz", substring = true, ignoreCase = true).assertDoesNotExist()
        rule.onNodeWithText("Şimdi değil").assertDoesNotExist()
        rule.onNodeWithText("İzin ver").assertHeightIsAtLeast(48.dp).performClick()
        assertTrue(granted)
    }

    @Test
    fun permission_permanentlyDenied_showsHowTo_andSettingsWorks() {
        var settings = false
        permission(PermissionState.PermanentlyDenied, onSettings = { settings = true })
        rule.onNodeWithText("Galeri izni kapatıldı").assertExists()
        rule.onNodeWithText("Nasıl etkinleştirilir?").assertExists()
        rule.onNodeWithText("Daha sonra").assertDoesNotExist()
        rule.onNodeWithText("Ayarları aç").assertHeightIsAtLeast(48.dp).performClick()
        assertTrue(settings)
    }

    @Test
    fun viewer_invalidId_showsNotFound_andBackReturns() {
        var backs = 0
        rule.setContent { AiGaleriTheme { PhotoViewerScreen(photoId = null, onBack = { backs++ }) } }
        rule.onNodeWithTag(TAG_VIEWER_NOT_FOUND).assertExists()
        rule.onNodeWithText("Fotoğraf bulunamadı").assertExists()
        rule.onNodeWithText("Dosya cihazdan silinmiş veya yeri değiştirilmiş olabilir.").assertExists()
        rule.onNodeWithText("Geri dön").performClick()
        rule.onNodeWithContentDescription("Geri").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(2, backs)
    }

    private fun status(
        state: IndexStatusUiState,
        runState: IndexRunState = IndexRunState.IDLE,
        onBack: () -> Unit = {},
    ) = rule.setContent {
        AiGaleriTheme {
            IndexStatusContent(
                state = state,
                reindexAvailable = true,
                reindexInProgress = runState != IndexRunState.IDLE,
                onReindex = {},
                onRetry = {},
                onBack = onBack,
                runState = runState,
            )
        }
    }

    @Test
    fun indexStatus_running_showsProgressBlock_percent_andCorrectModelRow() {
        status(IndexStatusUiState.Data(total = 1200, processed = 340, lastRunAt = null), IndexRunState.RUNNING)
        rule.onNodeWithText("340 / 1200").assertExists()
        rule.onNodeWithText("%28").assertExists()
        rule.onNodeWithText("öğe işlendi").assertExists()
        rule.onNodeWithText("Fotoğraf kitaplığı taranıyor").assertExists()
        rule.onNodeWithText("CLIP ViT-B/32", substring = true).assertExists()
        rule.onNodeWithText("MobileCLIP", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Dizin boyutu").assertDoesNotExist()
        rule.onNodeWithText("şarjdayken", substring = true).assertDoesNotExist()
    }

    @Test
    fun indexStatus_done_showsUpToDate() {
        status(IndexStatusUiState.Data(total = 10, processed = 10, lastRunAt = 1_790_000_000_000L))
        rule.onNodeWithText("İndeksleme güncel").assertExists()
        rule.onNodeWithText("%100").assertExists()
        rule.onNodeWithTag(TAG_REINDEX_BUTTON).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun indexStatus_error_showsRetry_andBackWorks() {
        var backs = 0
        var retried = false
        rule.setContent {
            AiGaleriTheme {
                IndexStatusContent(
                    IndexStatusUiState.Error, reindexAvailable = true, reindexInProgress = false,
                    onReindex = {}, onRetry = { retried = true }, onBack = { backs++ },
                )
            }
        }
        rule.onNodeWithText("İndeks durumu okunamadı.").assertExists()
        rule.onNodeWithTag(TAG_RETRY_BUTTON).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag(TAG_BACK_BUTTON).assertHeightIsAtLeast(48.dp).performClick()
        assertTrue(retried)
        assertEquals(1, backs)
    }
}
