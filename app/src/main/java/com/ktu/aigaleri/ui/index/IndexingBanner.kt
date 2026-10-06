package com.ktu.aigaleri.ui.index

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.ktu.aigaleri.R

const val TAG_INDEXING_BANNER = "indexing_banner"

/** Arama ekranındaki kısa indeksleme ipucu; [processed]/[total] bilinmiyorsa (henüz taranıyor) null. */
data class IndexingBanner(val processed: Int?, val total: Int?)

/**
 * İndeksleme sürerken ([running]) gösterilecek ipucunu döndürür; sürmüyorsa veya durum okunamadıysa null.
 * Bir önceki çalışmadan kalan sayılar ([total] 0 ise) gösterilmez.
 */
fun indexingBanner(state: IndexStatusUiState, running: Boolean): IndexingBanner? = when {
    !running -> null
    state is IndexStatusUiState.Data && state.total > 0 -> IndexingBanner(state.processed, state.total)
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
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).testTag(TAG_INDEXING_BANNER),
    )
}
