package com.ktu.aigaleri.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ktu.aigaleri.ui.index.IndexStatusContent
import com.ktu.aigaleri.ui.index.IndexStatusUiState
import com.ktu.aigaleri.ui.index.TAG_REINDEX_BUTTON
import com.ktu.aigaleri.ui.index.TAG_RETRY_BUTTON
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** İndeks durumu ekranı testleri. Emülatör yok: bu görevde yalnızca DERLENİR, ÇALIŞTIRILMADI. */
class IndexStatusScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun show(
        state: IndexStatusUiState,
        available: Boolean = true,
        inProgress: Boolean = false,
        onReindex: () -> Unit = {},
        onRetry: () -> Unit = {},
    ) = rule.setContent {
        IndexStatusContent(state, available, inProgress, onReindex = onReindex, onRetry = onRetry, onBack = {})
    }

    @Test
    fun data_showsProcessedOfTotal_andEnabledReindex() {
        var reindexed = false
        show(IndexStatusUiState.Data(total = 10, processed = 4, lastRunAt = null), onReindex = { reindexed = true })
        rule.onNodeWithText("4 / 10", substring = true).assertExists()
        rule.onNodeWithTag(TAG_REINDEX_BUTTON).assertIsEnabled().performClick()
        assertTrue(reindexed)
    }

    @Test
    fun neverRun_showsExplanation() {
        show(IndexStatusUiState.NeverRun)
        rule.onNodeWithText("İndeksleme henüz çalışmadı", substring = true).assertExists()
    }

    @Test
    fun launcherUnavailable_disablesReindex_andExplains() {
        show(IndexStatusUiState.NeverRun, available = false)
        rule.onNodeWithTag(TAG_REINDEX_BUTTON).assertIsNotEnabled()
        rule.onNodeWithText("henüz bağlı değil", substring = true).assertExists()
    }

    @Test
    fun reindexInProgress_disablesReindex() {
        show(IndexStatusUiState.NeverRun, inProgress = true)
        rule.onNodeWithTag(TAG_REINDEX_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun error_showsRetry() {
        var retried = false
        show(IndexStatusUiState.Error, onRetry = { retried = true })
        rule.onNodeWithTag(TAG_RETRY_BUTTON).performClick()
        assertTrue(retried)
    }
}
