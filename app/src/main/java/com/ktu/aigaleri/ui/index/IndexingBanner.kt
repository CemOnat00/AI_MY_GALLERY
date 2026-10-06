package com.ktu.aigaleri.ui.index

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.ktu.aigaleri.R

const val TAG_INDEXING_BANNER = "indexing_banner"

/** Arama ekranındaki kısa indeksleme ipucu; [processed]/[total] bilinmiyorsa (henüz taranıyor) null. */
data class IndexingBanner(val processed: Int?, val total: Int?)

/**
 * İpucunu döndürür; iş kuyrukta/çalışıyor değilse ([running] false) null.
 *
 * Sınır: [running] iş hattının "ENQUEUED/BLOCKED/RUNNING" durumundan türer; kısıtlar (ör. düşük pil) yüzünden iş
 * beklemedeyken de true olabilir. Bu yüzden metin nötrdür ("sürüyor veya sırada"). "X / Y" yalnızca
 * `total > 0 && processed < total` iken gösterilir; aksi halde (önceki çalışmadan kalan tamamlanmış sayılar, veri
 * yok/okunamadı) sayısız metin gösterilir.
 */
fun indexingBanner(state: IndexStatusUiState, running: Boolean): IndexingBanner? = when {
    !running -> null
    state is IndexStatusUiState.Data && state.total > 0 && state.processed < state.total ->
        IndexingBanner(state.processed, state.total)
    else -> IndexingBanner(null, null)
}

/** Dokununca indeks durumu ekranını açan tek satırlık ipucu. */
@Composable
fun IndexingBannerContent(banner: IndexingBanner, onOpen: () -> Unit) {
    val processed = banner.processed
    val total = banner.total
    Text(
        text = if (processed != null && total != null) {
            stringResource(R.string.indexing_progress, processed, total)
        } else {
            stringResource(R.string.indexing_started)
        },
        style = MaterialTheme.typography.bodySmall,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen)
            .testTag(TAG_INDEXING_BANNER),
    )
}
