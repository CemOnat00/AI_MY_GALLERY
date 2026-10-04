package com.ktu.aigaleri.ui

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.ktu.aigaleri.ui.image.ImageLoader
import com.ktu.aigaleri.ui.search.SearchContent
import com.ktu.aigaleri.ui.search.SearchStatus
import com.ktu.aigaleri.ui.search.SearchUiState
import com.ktu.aigaleri.ui.search.TAG_SEARCH_BUTTON
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Durumsuz arama ekranı testi. Emülatör yok; bu görevde yalnızca derlenir, çalıştırılmadı. */
class SearchScreenTest {
    @get:Rule val rule = createComposeRule()

    private val loader = ImageLoader(ApplicationProvider.getApplicationContext<Context>().contentResolver)

    private fun show(state: SearchUiState, onSearch: () -> Unit = {}) = rule.setContent {
        SearchContent(state, loader, onQueryChange = {}, onSearch = onSearch, onOpenPhoto = {}, onOpenIndexStatus = {})
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
}
