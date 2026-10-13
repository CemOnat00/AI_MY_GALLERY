package com.ktu.aigaleri.ui.index

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.ktu.aigaleri.R
import com.ktu.aigaleri.ui.IndexRunState

const val TAG_INDEXING_BANNER = "indexing_banner"

/**
 * Arama ekranındaki kısa indeksleme ipucu. [waiting]: iş kuyrukta ama çalışmıyor (T-016). [processed]/[total]
 * yalnızca çalışırken ve geçerliyse dolu; bilinmiyorsa (henüz taranıyor, geçersiz değer) null.
 */
data class IndexingBanner(val processed: Int?, val total: Int?, val waiting: Boolean = false)

/**
 * İpucunu döndürür; [IndexRunState.IDLE] iken null. [IndexRunState.WAITING] iken sayısız "sırada" metni (kısıt
 * nedeni, ör. pil, iddia edilmez). [IndexRunState.RUNNING] iken "X / Y" yalnızca `processed >= 0 && total > 0 &&
 * processed < total` ise gösterilir; aksi halde (önceki çalışmadan kalan tamamlanmış sayılar, geçersiz değer, veri
 * yok/okunamadı) sayısız metin gösterilir.
 */
fun indexingBanner(state: IndexStatusUiState, runState: IndexRunState): IndexingBanner? = when (runState) {
    IndexRunState.IDLE -> null
    IndexRunState.WAITING -> IndexingBanner(null, null, waiting = true)
    IndexRunState.RUNNING ->
        if (state is IndexStatusUiState.Data && state.processed >= 0 && state.total > 0 && state.processed < state.total) {
            IndexingBanner(state.processed, state.total)
        } else {
            IndexingBanner(null, null)
        }
}

/** Dokununca indeks durumu ekranını açan tek satırlık ipucu. */
@Composable
fun IndexingBannerContent(banner: IndexingBanner, onOpen: () -> Unit) {
    val processed = banner.processed
    val total = banner.total
    Text(
        text = when {
            banner.waiting -> stringResource(R.string.indexing_waiting)
            processed != null && total != null -> stringResource(R.string.indexing_progress, processed, total)
            else -> stringResource(R.string.indexing_running)
        },
        style = MaterialTheme.typography.bodySmall,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.indexing_banner_open_label), onClick = onOpen)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(TAG_INDEXING_BANNER),
    )
}
