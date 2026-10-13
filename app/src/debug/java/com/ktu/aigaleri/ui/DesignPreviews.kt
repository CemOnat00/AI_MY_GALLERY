package com.ktu.aigaleri.ui

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.ktu.aigaleri.domain.InvalidQueryException
import com.ktu.aigaleri.domain.SearchResult
import com.ktu.aigaleri.ui.index.IndexStatusContent
import com.ktu.aigaleri.ui.index.IndexStatusUiState
import com.ktu.aigaleri.ui.index.IndexingBanner
import com.ktu.aigaleri.ui.search.SearchContent
import com.ktu.aigaleri.ui.search.SearchStatus
import com.ktu.aigaleri.ui.search.SearchUiState
import com.ktu.aigaleri.ui.theme.AiGaleriTheme
import com.ktu.aigaleri.ui.viewer.PhotoViewerScreen

/*
 * T-022: ekran durumları için @Preview'lar (yalnızca debug; sürüm APK'sına girmez). Her durum açık, koyu ve büyük
 * yazı (1.5x) ile görünür. Emülatör/cihaz yok: Android Studio'da elle bakılır; görüntü karşılaştırması yapılmadı.
 */

private const val REFERENCE_WIDTH_DP = 412
private const val REFERENCE_HEIGHT_DP = 915

@Preview(name = "Açık", widthDp = REFERENCE_WIDTH_DP, heightDp = REFERENCE_HEIGHT_DP)
@Preview(name = "Koyu", widthDp = REFERENCE_WIDTH_DP, heightDp = REFERENCE_HEIGHT_DP, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Büyük yazı", widthDp = REFERENCE_WIDTH_DP, heightDp = REFERENCE_HEIGHT_DP, fontScale = 1.5f)
annotation class DesignPreview

/** Önizleme çerçevesi: sistem (açık/koyu) ayarını izleyen tema ve zemin. */
@Composable
private fun Frame(content: @Composable () -> Unit) {
    AiGaleriTheme {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

private val sampleResults = List(14) { SearchResult(it + 1L, "content://media/external/images/media/${it + 1}", 0.5f) }

@DesignPreview
@Composable
private fun PermissionFirstOpenPreview() = Frame {
    MainContent(MainUiState(PermissionState.NotRequested), onRequestPermission = {}, onOpenSettings = {})
}

@DesignPreview
@Composable
private fun PermissionDeniedPreview() = Frame {
    MainContent(MainUiState(PermissionState.Denied), onRequestPermission = {}, onOpenSettings = {})
}

@DesignPreview
@Composable
private fun PermissionPermanentlyDeniedPreview() = Frame {
    MainContent(MainUiState(PermissionState.PermanentlyDenied), onRequestPermission = {}, onOpenSettings = {})
}

@Composable
private fun Search(state: SearchUiState, indexing: IndexingBanner? = null) = Frame {
    SearchContent(
        state = state,
        onQueryChange = {},
        onSearch = {},
        onOpenPhoto = {},
        onOpenIndexStatus = {},
        indexing = indexing,
    )
}

@DesignPreview
@Composable
private fun SearchIdlePreview() = Search(SearchUiState())

@DesignPreview
@Composable
private fun SearchIdleIndexingPreview() = Search(SearchUiState(), IndexingBanner(340, 1200))

@DesignPreview
@Composable
private fun SearchIdleWaitingPreview() = Search(SearchUiState(), IndexingBanner(null, null, waiting = true))

@DesignPreview
@Composable
private fun SearchLoadingPreview() = Search(SearchUiState("plajda gülen çocuk", SearchStatus.Loading))

@DesignPreview
@Composable
private fun SearchResultsPreview() =
    Search(SearchUiState("plajda gülen çocuk", SearchStatus.Success(sampleResults)), IndexingBanner(340, 1200))

@DesignPreview
@Composable
private fun SearchEmptyPreview() = Search(SearchUiState("karda yüzen flamingo", SearchStatus.Empty))

@DesignPreview
@Composable
private fun SearchErrorPreview() = Search(SearchUiState("plajda gülen çocuk", SearchStatus.Error))

@DesignPreview
@Composable
private fun SearchInvalidQueryPreview() =
    Search(SearchUiState("", SearchStatus.InvalidQuery(InvalidQueryException.Reason.EMPTY)))

@Composable
private fun IndexStatus(state: IndexStatusUiState, runState: IndexRunState = IndexRunState.IDLE, reindexError: Boolean = false) =
    Frame {
        IndexStatusContent(
            state = state,
            reindexAvailable = true,
            reindexInProgress = runState != IndexRunState.IDLE,
            onReindex = {},
            onRetry = {},
            onBack = {},
            reindexError = reindexError,
            runState = runState,
        )
    }

@DesignPreview
@Composable
private fun IndexStatusRunningPreview() =
    IndexStatus(IndexStatusUiState.Data(total = 1200, processed = 340, lastRunAt = 1_790_000_000_000L), IndexRunState.RUNNING)

@DesignPreview
@Composable
private fun IndexStatusWaitingPreview() =
    IndexStatus(IndexStatusUiState.Data(total = 1200, processed = 340, lastRunAt = 1_790_000_000_000L), IndexRunState.WAITING)

@DesignPreview
@Composable
private fun IndexStatusDonePreview() =
    IndexStatus(IndexStatusUiState.Data(total = 1200, processed = 1200, lastRunAt = 1_790_000_000_000L))

@DesignPreview
@Composable
private fun IndexStatusNeverRunPreview() = IndexStatus(IndexStatusUiState.NeverRun)

@DesignPreview
@Composable
private fun IndexStatusErrorPreview() = IndexStatus(IndexStatusUiState.Error, reindexError = true)

@DesignPreview
@Composable
private fun ViewerNotFoundPreview() = Frame { PhotoViewerScreen(photoId = null, onBack = {}) }
