package com.ktu.aigaleri.ui

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ktu.aigaleri.domain.SearchResult
import com.ktu.aigaleri.ui.index.IndexingBanner
import com.ktu.aigaleri.ui.index.TAG_INDEXING_BANNER
import com.ktu.aigaleri.ui.search.SearchContent
import com.ktu.aigaleri.ui.search.SearchStatus
import com.ktu.aigaleri.ui.search.SearchUiState
import com.ktu.aigaleri.ui.search.TAG_RESULT_GRID
import com.ktu.aigaleri.ui.search.TAG_SEARCH_BUTTON
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Durumsuz arama ekranı testleri. Emülatör yok: bu görevde yalnızca DERLENİR, ÇALIŞTIRILMADI.
 */
class SearchScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun show(
        state: SearchUiState,
        onSearch: () -> Unit = {},
        indexing: IndexingBanner? = null,
        onOpenIndexStatus: () -> Unit = {},
    ) = rule.setContent {
        SearchContent(
            state,
            onQueryChange = {},
            onSearch = onSearch,
            onOpenPhoto = {},
            onOpenIndexStatus = onOpenIndexStatus,
            indexing = indexing,
        )
    }

    @Test
    fun indexingBanner_hiddenWhenNull() {
        show(SearchUiState())
        rule.onNodeWithTag(TAG_INDEXING_BANNER).assertDoesNotExist()
    }

    @Test
    fun indexingBanner_showsProgress_andClickOpensIndexStatus() {
        var opened = false
        show(SearchUiState(), indexing = IndexingBanner(3, 10), onOpenIndexStatus = { opened = true })
        rule.onNodeWithTag(TAG_INDEXING_BANNER).assertExists()
        rule.onNodeWithText("3 / 10", substring = true).assertExists()
        rule.onNodeWithTag(TAG_INDEXING_BANNER).performClick()
        assertTrue(opened)
    }

    @Test
    fun indexingBanner_withoutCounts_showsNeutralText() {
        show(SearchUiState(), indexing = IndexingBanner(null, null))
        rule.onNodeWithText("sonuçlar eksik olabilir", substring = true).assertExists()
    }

    @Test
    fun blankQuery_disablesSearchButton() {
        show(SearchUiState(query = ""))
        rule.onNodeWithTag(TAG_SEARCH_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun query_enablesSearchButton_andClickSearches() {
        var searched = false
        show(SearchUiState(query = "kedi"), onSearch = { searched = true })
        rule.onNodeWithTag(TAG_SEARCH_BUTTON).assertIsEnabled().performClick()
        assertTrue(searched)
    }

    @Test
    fun emptyStatus_showsExplanation() {
        show(SearchUiState(query = "kedi", status = SearchStatus.Empty))
        rule.onNodeWithText("Eşleşen fotoğraf bulunamadı", substring = true).assertExists()
    }

    @Test
    fun errorStatus_showsExplanation() {
        show(SearchUiState(query = "kedi", status = SearchStatus.Error))
        rule.onNodeWithText("Arama sırasında bir hata oluştu", substring = true).assertExists()
    }

    @Test
    fun loadingStatus_showsProgress_andDisablesSearch() {
        show(SearchUiState(query = "kedi", status = SearchStatus.Loading))
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertExists()
        rule.onNodeWithTag(TAG_SEARCH_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun successStatus_showsResultGrid() {
        val results = listOf(
            SearchResult(1L, "content://media/external/images/media/1", 0.9f),
            SearchResult(2L, "content://media/external/images/media/2", 0.5f),
        )
        show(SearchUiState(query = "kedi", status = SearchStatus.Success(results)))
        rule.onNodeWithTag(TAG_RESULT_GRID).assertExists()
    }
}
