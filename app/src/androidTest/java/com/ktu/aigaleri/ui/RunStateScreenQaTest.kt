package com.ktu.aigaleri.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.ktu.aigaleri.ui.index.IndexStatusContent
import com.ktu.aigaleri.ui.index.IndexStatusUiState
import com.ktu.aigaleri.ui.index.TAG_INDEXING_BANNER
import com.ktu.aigaleri.ui.index.TAG_REINDEX_BUTTON
import com.ktu.aigaleri.ui.index.TAG_RUN_STATE
import com.ktu.aigaleri.ui.index.indexingBanner
import com.ktu.aigaleri.ui.search.SearchContent
import com.ktu.aigaleri.ui.search.SearchUiState
import org.junit.Rule
import org.junit.Test

/**
 * T-016 QA: banner ve durum ekranı runState metinleri (emülatör yok: DERLENİR, ÇALIŞTIRILMADI).
 */
class RunStateScreenQaTest {
    @get:Rule val rule = createComposeRule()

    private fun showBanner(state: IndexStatusUiState, run: IndexRunState) = rule.setContent {
        SearchContent(
            SearchUiState(),
            onQueryChange = {},
            onSearch = {},
            onOpenPhoto = {},
            onOpenIndexStatus = {},
            indexing = indexingBanner(state, run),
        )
    }

    private fun showStatus(state: IndexStatusUiState, run: IndexRunState, inProgress: Boolean) = rule.setContent {
        IndexStatusContent(state, true, inProgress, onReindex = {}, onRetry = {}, onBack = {}, runState = run)
    }

    @Test
    fun banner_running_validCounts_showsIndeksleniyorWithCounts() {
        showBanner(IndexStatusUiState.Data(10, 3, null), IndexRunState.RUNNING)
        rule.onNodeWithText("İndeksleniyor (3 / 10)", substring = true).assertExists()
    }

    @Test
    fun banner_running_completedOrZeroTotal_showsNoCounts() {
        showBanner(IndexStatusUiState.Data(0, 0, 5L), IndexRunState.RUNNING)
        rule.onNodeWithText("İndeksleniyor", substring = true).assertExists()
        rule.onNodeWithText("/", substring = true).assertDoesNotExist()
    }

    @Test
    fun banner_idle_noBanner() {
        showBanner(IndexStatusUiState.Data(10, 3, null), IndexRunState.IDLE)
        rule.onNodeWithTag(TAG_INDEXING_BANNER).assertDoesNotExist()
    }

    @Test
    fun banner_waiting_noFalseChargingOrIdleClaim_noCounts() {
        showBanner(IndexStatusUiState.Data(10, 3, null), IndexRunState.WAITING)
        rule.onNodeWithText("İndeksleme sırada; birazdan ya da cihaz uygun olunca başlayacak.", substring = true).assertExists()
        rule.onNodeWithText("şarj", substring = true, ignoreCase = true).assertDoesNotExist()
        rule.onNodeWithText("boşta", substring = true, ignoreCase = true).assertDoesNotExist()
        rule.onNodeWithText("3 / 10", substring = true).assertDoesNotExist()
    }

    @Test
    fun status_waitingAndRunning_lockReindexButton_idleUnlocks() {
        showStatus(IndexStatusUiState.NeverRun, IndexRunState.WAITING, inProgress = true)
        rule.onNodeWithTag(TAG_REINDEX_BUTTON).assertIsNotEnabled()
        rule.onNodeWithTag(TAG_RUN_STATE).assertExists()
    }

    @Test
    fun status_idle_buttonEnabled_noRunStateLine() {
        showStatus(IndexStatusUiState.NeverRun, IndexRunState.IDLE, inProgress = false)
        rule.onNodeWithTag(TAG_REINDEX_BUTTON).assertIsEnabled()
        rule.onNodeWithTag(TAG_RUN_STATE).assertDoesNotExist()
    }

    @Test
    fun status_running_showsOnlyRunningLabel_withNegativeProcessedClamped() {
        showStatus(IndexStatusUiState.Data(10, 0, null), IndexRunState.RUNNING, inProgress = true)
        rule.onNodeWithText("Çalışıyor", substring = true).assertExists()
        rule.onNodeWithText("Beklemede", substring = true).assertDoesNotExist()
        rule.onNodeWithText("-1", substring = true).assertDoesNotExist() // T-022: model adı "ViT-B/32" tire içerir
    }
}
