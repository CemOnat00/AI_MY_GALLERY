package com.ktu.aigaleri.ui.index

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ktu.aigaleri.R
import com.ktu.aigaleri.ui.IndexRunState
import com.ktu.aigaleri.ui.TURKISH
import com.ktu.aigaleri.ui.formatCount
import com.ktu.aigaleri.ui.formatDateTime
import com.ktu.aigaleri.ui.theme.AiGaleriDimens
import com.ktu.aigaleri.ui.theme.AppIcons
import java.util.Locale

const val TAG_REINDEX_BUTTON = "reindex_button"
const val TAG_RETRY_BUTTON = "retry_button"
const val TAG_RUN_STATE = "index_run_state"
const val TAG_BACK_BUTTON = "index_back_button"
const val TAG_LICENSES_ROW = "index_licenses_row"

/** "6 Eki 2026 14:32" biçimi (tarih orta, saat kısa). */
internal fun formatLastRun(epochMillis: Long, locale: Locale = TURKISH): String = formatDateTime(epochMillis, locale)

/** Yüzde (0..100, aşağı yuvarlanır: yalnızca tamamlanınca %100). */
internal fun percentOf(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 100).toInt()

/**
 * Durum kartı başlığı. İş çalışıyor/sırada ise o durum öncelikli; boştaysa son çalışmanın sonucu: tamamlandı
 * ([IndexStatusUiState.Data.fraction] == 1), yarım ya da hiç çalışmadı. Yükleniyor/hata için null (başlık yok).
 */
@StringRes
internal fun statusHeadline(state: IndexStatusUiState, runState: IndexRunState): Int? = when {
    state == IndexStatusUiState.Loading || state == IndexStatusUiState.Error -> null
    runState == IndexRunState.RUNNING -> R.string.index_headline_running
    runState == IndexRunState.WAITING -> R.string.index_headline_waiting
    state is IndexStatusUiState.Data -> if (state.fraction >= 1f) R.string.index_headline_done else R.string.index_headline_partial
    else -> R.string.index_never_run
}

private fun Modifier.runStateSemantics(): Modifier =
    semantics { liveRegion = LiveRegionMode.Polite }.testTag(TAG_RUN_STATE)

/**
 * Durumsuz indeks durumu ekranı (T-022 tasarımı): üst çubuk (geri + başlık), durum kartı (işlenen/toplam, yüzde,
 * ilerleme çizgisi, çalışma durumu), dizin ayrıntıları, "Yeniden indeksle" ve açıklama. Uzun içerik kaydırılır.
 */
@Composable
fun IndexStatusContent(
    state: IndexStatusUiState,
    reindexAvailable: Boolean,
    reindexInProgress: Boolean,
    onReindex: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    reindexError: Boolean = false,
    runState: IndexRunState = IndexRunState.IDLE,
    onOpenLicenses: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        TopBar(onBack)
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = AiGaleriDimens.Margin),
            verticalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceMd),
        ) {
            if (state == IndexStatusUiState.Error) {
                RunStateLine(runState)
                ErrorCard(onRetry)
            } else {
                StatusCard(state, runState)
            }
            if (state is IndexStatusUiState.Data || state == IndexStatusUiState.NeverRun) DetailsCard(state)
            FilledTonalButton(
                onClick = onReindex,
                enabled = reindexAvailable && !reindexInProgress,
                shape = RoundedCornerShape(AiGaleriDimens.ButtonCorner),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = AiGaleriDimens.MinTouchTarget).testTag(TAG_REINDEX_BUTTON),
            ) {
                Icon(AppIcons.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text(
                    stringResource(R.string.reindex_button),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = AiGaleriDimens.SpaceSm),
                )
            }
            if (reindexError) InlineError(stringResource(R.string.reindex_failed))
            Hint(state, runState, reindexAvailable)
            LicensesRow(onOpenLicenses)
            Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)).size(AiGaleriDimens.SpaceMd))
        }
    }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = AiGaleriDimens.TopBarHeight).padding(horizontal = AiGaleriDimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag(TAG_BACK_BUTTON)) {
            Icon(
                AppIcons.ArrowBack,
                contentDescription = stringResource(R.string.back_button),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            stringResource(R.string.index_status_open),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = AiGaleriDimens.SpaceSm).semantics { heading() },
        )
    }
}

@Composable
private fun RunStateLine(runState: IndexRunState) {
    val text = when (runState) {
        IndexRunState.RUNNING -> stringResource(R.string.index_run_state_running)
        IndexRunState.WAITING -> stringResource(R.string.index_run_state_waiting)
        IndexRunState.IDLE -> return
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.runStateSemantics(),
    )
}

/** Ana durum kartı: "Cihaz içi işlem" etiketi, başlık, işlenen/toplam, yüzde ve ilerleme çizgisi. */
@Composable
private fun StatusCard(state: IndexStatusUiState, runState: IndexRunState) {
    val colors = MaterialTheme.colorScheme
    val headline = statusHeadline(state, runState)
    Surface(color = colors.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AiGaleriDimens.SpaceLg),
            verticalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceMd),
        ) {
            if (headline == null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceSm),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.index_overline),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.primary,
                    )
                    Text(
                        stringResource(headline),
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onSurface,
                        modifier = Modifier.padding(top = AiGaleriDimens.SpaceXs),
                    )
                }
                StatusIcon(if (state is IndexStatusUiState.Data && state.fraction >= 1f && runState == IndexRunState.IDLE) AppIcons.Check else AppIcons.Refresh)
            }
            if (state is IndexStatusUiState.Data) {
                ProgressBlock(state)
            }
            RunStateLine(runState)
        }
    }
}

@Composable
private fun StatusIcon(icon: ImageVector) {
    Box(
        modifier = Modifier.size(AiGaleriDimens.IconCircleSmall).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun ProgressBlock(state: IndexStatusUiState.Data) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceXs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceSm),
        ) {
            Text(
                stringResource(R.string.index_processed_counts, formatCount(state.processed), formatCount(state.total)),
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onSurface,
            )
            Text(
                stringResource(R.string.index_processed_unit),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(bottom = AiGaleriDimens.SpaceXs),
            )
            Text(
                stringResource(R.string.index_percent, percentOf(state.fraction)),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { state.fraction },
            modifier = Modifier.fillMaxWidth().heightIn(max = AiGaleriDimens.ProgressLine),
            color = colors.primary,
            trackColor = colors.surfaceVariant,
            strokeCap = StrokeCap.Round,
            gapSize = AiGaleriDimens.ProgressGap,
            drawStopIndicator = {},
        )
    }
}

/** Dizin ayrıntıları: son çalışma, yerel model adı, depolama konumu (dizin boyutu için veri kaynağı yok, gösterilmez). */
@Composable
private fun DetailsCard(state: IndexStatusUiState) {
    val lastRunAt = (state as? IndexStatusUiState.Data)?.lastRunAt
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                stringResource(R.string.index_details_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(start = AiGaleriDimens.SpaceMd, top = AiGaleriDimens.SpaceMd, end = AiGaleriDimens.SpaceMd, bottom = AiGaleriDimens.SpaceXs)
                    .semantics { heading() },
            )
            DetailRow(
                stringResource(R.string.index_detail_last_run),
                lastRunAt?.let { formatLastRun(it) } ?: stringResource(R.string.index_detail_last_run_none),
            )
            DetailRow(stringResource(R.string.index_detail_model), stringResource(R.string.index_detail_model_value))
            DetailRow(stringResource(R.string.index_detail_storage), stringResource(R.string.index_detail_storage_value))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = AiGaleriDimens.MinTouchTarget)
            .padding(horizontal = AiGaleriDimens.SpaceMd, vertical = AiGaleriDimens.SpaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceMd),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Durum okunamadı: hata kapsayıcısı ve "Yeniden dene". */
@Composable
private fun ErrorCard(onRetry: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.errorContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AiGaleriDimens.SpaceMd), verticalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceSm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceSm), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                Icon(AppIcons.ErrorOutline, contentDescription = null, tint = colors.error)
                Text(
                    stringResource(R.string.index_status_error),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = onRetry,
                    shape = RoundedCornerShape(AiGaleriDimens.ButtonCorner),
                    modifier = Modifier.heightIn(min = AiGaleriDimens.MinTouchTarget).testTag(TAG_RETRY_BUTTON),
                ) { Text(stringResource(R.string.retry_button), style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

/** "Lisanslar" satırı (T-037, K-12): üçüncü taraf lisans metinleri ve değiştirilmiş model bildirimi ekranına gider. */
@Composable
private fun LicensesRow(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        color = colors.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().heightIn(min = AiGaleriDimens.MinTouchTarget).testTag(TAG_LICENSES_ROW),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AiGaleriDimens.SpaceMd, vertical = AiGaleriDimens.SpaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceMd),
        ) {
            Icon(AppIcons.Info, contentDescription = null, tint = colors.secondary)
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.licenses_title), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                Text(stringResource(R.string.licenses_row_summary), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Icon(AppIcons.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun InlineError(message: String) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.errorContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(AiGaleriDimens.SpaceMd).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceSm),
        ) {
            Icon(AppIcons.ErrorOutline, contentDescription = null, tint = colors.error)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.onErrorContainer, modifier = Modifier.weight(1f))
        }
    }
}

/** Düğme altı açıklama: bağlı değilse nedeni, çalışırken sonuçların eksik olabileceği, bitince yeni fotoğraflar. */
@Composable
private fun Hint(state: IndexStatusUiState, runState: IndexRunState, reindexAvailable: Boolean) {
    val text = when {
        !reindexAvailable -> R.string.reindex_unavailable
        runState == IndexRunState.RUNNING -> R.string.index_running_note
        runState == IndexRunState.IDLE && state is IndexStatusUiState.Data && state.fraction >= 1f -> R.string.index_done_note
        else -> return
    }
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = AiGaleriDimens.SpaceMd),
    )
}
