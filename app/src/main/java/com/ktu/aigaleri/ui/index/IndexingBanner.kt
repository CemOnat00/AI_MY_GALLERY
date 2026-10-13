package com.ktu.aigaleri.ui.index

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.ktu.aigaleri.R
import com.ktu.aigaleri.ui.IndexRunState
import com.ktu.aigaleri.ui.formatCount
import com.ktu.aigaleri.ui.theme.AiGaleriDimens
import com.ktu.aigaleri.ui.theme.AppIcons

const val TAG_INDEXING_BANNER = "indexing_banner"

/**
 * Arama ekranındaki kısa indeksleme ipucu. [waiting]: iş kuyrukta ama çalışmıyor (T-016). [processed]/[total]
 * yalnızca çalışırken ve geçerliyse dolu; bilinmiyorsa (henüz taranıyor, geçersiz değer) null.
 */
data class IndexingBanner(val processed: Int?, val total: Int?, val waiting: Boolean = false) {
    /** İnce ilerleme çizgisi için 0f..1f; sayılar yoksa (bilinmiyor/bekliyor) null. */
    val fraction: Float?
        get() = if (processed != null && total != null && total > 0) (processed.toFloat() / total).coerceIn(0f, 1f) else null
}

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

/**
 * Dokununca indeks durumu ekranını açan ince bant (T-022 tasarımı): durum noktası, T-016 metni (aynen korunur),
 * "Detay >" ve (yalnızca sayılar geçerliyse) ince ilerleme çizgisi. Tek erişilebilirlik düğümüdür: rol, tıklama
 * etiketi ve liveRegion kapsayıcıdadır; en az 48dp yüksekliktedir.
 */
@Composable
fun IndexingBannerContent(banner: IndexingBanner, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val processed = banner.processed
    val total = banner.total
    val fraction = banner.fraction
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.indexing_banner_open_label), onClick = onOpen)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(TAG_INDEXING_BANNER),
    ) {
        Column(
            modifier = Modifier.heightIn(min = AiGaleriDimens.MinTouchTarget)
                .padding(horizontal = AiGaleriDimens.SpaceMd, vertical = AiGaleriDimens.SpaceSm),
            verticalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceXs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceSm)) {
                Box(
                    Modifier.size(AiGaleriDimens.StatusDot).background(
                        color = if (banner.waiting) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                    ),
                )
                Text(
                    text = when {
                        banner.waiting -> stringResource(R.string.indexing_waiting)
                        processed != null && total != null -> stringResource(R.string.indexing_progress, formatCount(processed), formatCount(total))
                        else -> stringResource(R.string.indexing_running)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.indexing_banner_detail),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(AiGaleriDimens.IconChevron),
                )
            }
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().heightIn(max = AiGaleriDimens.ProgressLineThin),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                    gapSize = AiGaleriDimens.ProgressGap,
                    drawStopIndicator = {},
                )
            }
        }
    }
}
