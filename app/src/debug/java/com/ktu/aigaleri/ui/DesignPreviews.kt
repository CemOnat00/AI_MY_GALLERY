package com.ktu.aigaleri.ui

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.ktu.aigaleri.domain.InvalidQueryException
import com.ktu.aigaleri.domain.SearchResult
import com.ktu.aigaleri.ui.gallery.GalleryUiState
import com.ktu.aigaleri.data.PhotoTimeline
import com.ktu.aigaleri.ui.index.IndexStatusContent
import com.ktu.aigaleri.ui.index.IndexStatusUiState
import com.ktu.aigaleri.ui.index.IndexingBanner
import com.ktu.aigaleri.ui.license.LicenseComponent
import com.ktu.aigaleri.ui.license.LicenseDocument
import com.ktu.aigaleri.ui.license.LicenseText
import com.ktu.aigaleri.ui.license.LicensesContent
import com.ktu.aigaleri.ui.license.LicensesUiState
import com.ktu.aigaleri.R
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

private val sampleGallery = GalleryUiState.Ready(
    PhotoTimeline(LongArray(60) { 100L - it }, LongArray(60) { 1_790_000_000_000L - it * 86_400_000L }),
)

@Composable
private fun Search(state: SearchUiState, indexing: IndexingBanner? = null, gallery: GalleryUiState = sampleGallery) = Frame {
    SearchContent(
        state = state,
        onQueryChange = {},
        onSearch = {},
        onOpenPhoto = {},
        onOpenIndexStatus = {},
        indexing = indexing,
        gallery = gallery,
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
private fun GalleryLoadingPreview() = Search(SearchUiState(), gallery = GalleryUiState.Loading)

@DesignPreview
@Composable
private fun GalleryEmptyPreview() = Search(SearchUiState(), gallery = GalleryUiState.Empty)

@DesignPreview
@Composable
private fun GalleryErrorPreview() = Search(SearchUiState(), gallery = GalleryUiState.Error)

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

/* T-037: Lisanslar ekranı önizlemeleri (örnek veri; gerçek içerik assets/licenses/ altındadır). */

private val sampleLicenses = LicenseDocument(
    appLicense = listOf("Ai-galeri, KTÜ Mobil Programlama dersi dönem projesidir. Telif © 2026 Cem Onat Satır."),
    modifiedNotice = listOf("Metin modeli DEĞİŞTİRİLMİŞ bir sürümdür: gömme tablosu satır dilimlemesi ve sözlük budaması."),
    components = listOf(
        LicenseComponent("ONNX Runtime", "1.30.0", "MIT", "mit-onnxruntime.txt", "https://github.com/microsoft/onnxruntime", "Telif hakkı (c) Microsoft Corporation."),
        LicenseComponent("Coil", "3.4.0", "Apache-2.0", "apache-2.0.txt", "https://github.com/coil-kt/coil", "Küçük resimleri cihazdan yükler."),
    ),
    texts = listOf(
        LicenseText(R.string.license_text_apache, "apache-2.0.txt", listOf("Apache License Version 2.0, January 2004", "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION")),
    ),
)

@DesignPreview
@Composable
private fun LicensesLoadedPreview() = Frame { LicensesContent(LicensesUiState.Loaded(sampleLicenses), onBack = {}) }

@DesignPreview
@Composable
private fun LicensesLoadingPreview() = Frame { LicensesContent(LicensesUiState.Loading, onBack = {}) }

@DesignPreview
@Composable
private fun LicensesErrorPreview() = Frame { LicensesContent(LicensesUiState.Error, onBack = {}) }
