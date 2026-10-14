package com.ktu.aigaleri.ui.license

import android.content.res.AssetManager
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ktu.aigaleri.R
import com.ktu.aigaleri.ui.theme.AiGaleriDimens
import com.ktu.aigaleri.ui.theme.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

const val TAG_LICENSES_LIST = "licenses_list"
const val TAG_LICENSES_BACK = "licenses_back_button"
const val TAG_LICENSES_RETRY = "licenses_retry_button"

/** Lisanslar ekranı durumu: içerik cihazdaki düz metin dosyalarından okunur (ağ yok). */
sealed interface LicensesUiState {
    data object Loading : LicensesUiState
    data object Error : LicensesUiState
    data class Loaded(val document: LicenseDocument) : LicensesUiState
}

/** assets/licenses/ altından okur; asıl iş [LicenseCatalog.load]'dadır. Hata ayrıntısı (yol/içerik) günlüğe yazılmaz. */
internal fun loadLicenses(assets: AssetManager): LicensesUiState =
    try {
        LicensesUiState.Loaded(LicenseCatalog.load { assets.open("${LicenseCatalog.ASSET_DIR}/$it") })
    } catch (_: IOException) {
        LicensesUiState.Error
    } catch (_: IllegalArgumentException) {
        LicensesUiState.Error
    }

/** Durumlu giriş: içeriği ana iş parçacığı dışında okur, "Yeniden dene" ile tekrar yükler. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val assets = LocalContext.current.applicationContext.assets
    var attempt by remember { mutableIntStateOf(0) }
    val state by produceState<LicensesUiState>(LicensesUiState.Loading, attempt) {
        value = LicensesUiState.Loading
        value = withContext(Dispatchers.IO) { loadLicenses(assets) }
    }
    LicensesContent(state = state, onBack = onBack, onRetry = { attempt++ })
}

/** Durumsuz ekran (önizleme ve test için): üst çubuk (geri + başlık) ve kaydırılan içerik. */
@Composable
fun LicensesContent(state: LicensesUiState, onBack: () -> Unit, onRetry: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        TopBar(onBack)
        when (state) {
            LicensesUiState.Loading -> {
                val loading = stringResource(R.string.licenses_loading)
                Box(
                    Modifier.fillMaxSize().semantics { contentDescription = loading; liveRegion = LiveRegionMode.Polite },
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
            LicensesUiState.Error -> ErrorCard(onRetry)
            is LicensesUiState.Loaded -> LicenseList(state.document)
        }
    }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = AiGaleriDimens.TopBarHeight).padding(horizontal = AiGaleriDimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag(TAG_LICENSES_BACK)) {
            Icon(
                AppIcons.ArrowBack,
                contentDescription = stringResource(R.string.back_button),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            stringResource(R.string.licenses_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = AiGaleriDimens.SpaceSm).semantics { heading() },
        )
    }
}

@Composable
private fun ErrorCard(onRetry: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = AiGaleriDimens.Margin),
    ) {
        Column(modifier = Modifier.padding(AiGaleriDimens.SpaceMd), verticalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceSm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceSm), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                Icon(AppIcons.ErrorOutline, contentDescription = null, tint = colors.error)
                Text(
                    stringResource(R.string.licenses_load_error),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = onRetry,
                    shape = RoundedCornerShape(AiGaleriDimens.ButtonCorner),
                    modifier = Modifier.heightIn(min = AiGaleriDimens.MinTouchTarget).testTag(TAG_LICENSES_RETRY),
                ) { Text(stringResource(R.string.retry_button), style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

/** Kaydırılan liste: uzun lisans metinleri paragraf paragraf tembel çizilir (tek dev metin bloğu yok). */
@Composable
private fun LicenseList(document: LicenseDocument) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(TAG_LICENSES_LIST),
        contentPadding = PaddingValues(
            start = AiGaleriDimens.Margin,
            end = AiGaleriDimens.Margin,
            bottom = AiGaleriDimens.SpaceLg,
        ),
        verticalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceSm),
    ) {
        item(key = "intro") {
            Text(
                stringResource(R.string.licenses_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        item(key = "h_app") { SectionTitle(R.string.licenses_section_app) }
        items(document.appLicense.size, key = { "app_$it" }) { BodyParagraph(document.appLicense[it]) }
        item(key = "h_modified") { SectionTitle(R.string.licenses_section_modified) }
        item(key = "modified") {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(AiGaleriDimens.SpaceMd), verticalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceSm)) {
                    document.modifiedNotice.forEach {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
        item(key = "h_components") { SectionTitle(R.string.licenses_section_components) }
        items(document.components.size, key = { "c_$it" }) { ComponentCard(document.components[it]) }
        item(key = "h_texts") { SectionTitle(R.string.licenses_section_texts) }
        document.texts.forEach { text ->
            item(key = "t_${text.fileName}") {
                Text(
                    stringResource(text.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = AiGaleriDimens.SpaceSm).semantics { heading() },
                )
            }
            items(text.paragraphs.size, key = { "t_${text.fileName}_$it" }) { BodyParagraph(text.paragraphs[it]) }
        }
    }
}

@Composable
private fun SectionTitle(@StringRes titleRes: Int) {
    Text(
        stringResource(titleRes),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = AiGaleriDimens.SpaceMd).semantics { heading() },
    )
}

@Composable
private fun BodyParagraph(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Bileşen kartı: ad, sürüm, lisans, kaynak ve not; ekran okuyucu tek öğe olarak okur. */
@Composable
private fun ComponentCard(component: LicenseComponent) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        Column(Modifier.padding(AiGaleriDimens.SpaceMd), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(component.name, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
            Text(stringResource(R.string.licenses_version, component.version), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text(stringResource(R.string.licenses_license, component.license), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text(stringResource(R.string.licenses_source, component.sourceUrl), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text(
                component.note,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurface,
                modifier = Modifier.padding(top = AiGaleriDimens.SpaceXs),
            )
        }
    }
}
