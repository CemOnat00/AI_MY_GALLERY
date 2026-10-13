package com.ktu.aigaleri.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.ktu.aigaleri.R
import com.ktu.aigaleri.ui.theme.AiGaleriDimens
import com.ktu.aigaleri.ui.theme.AppIcons

const val TAG_PERMISSION_GRANT = "permission_grant"
const val TAG_PERMISSION_SETTINGS = "permission_settings"
const val TAG_PERMISSION_HOWTO = "permission_howto"

/**
 * İzin ekranı (T-022 tasarımı): ilk açılış / reddedildi (açıklama + "İzin ver") ve kalıcı red ("Ayarları aç" +
 * "Nasıl etkinleştirilir?"). İzin verilmişse (arama/indeks ekranları yerine geçmediği için normalde görünmez)
 * fotoğraf sayısını gösterir. İkincil "ertele" düğmeleri bilerek yoktur (izin akışında karşılığı yok, Cem kararı olmadan eklenmez).
 */
@Composable
fun MainContent(state: MainUiState, onRequestPermission: () -> Unit, onOpenSettings: () -> Unit) {
    when (state.permission) {
        PermissionState.Granted -> GrantedContent(state)
        PermissionState.NotRequested, PermissionState.Denied -> PermissionLayout(
            icon = AppIcons.PhotoLibrary,
            iconContainer = MaterialTheme.colorScheme.secondaryContainer,
            iconTint = MaterialTheme.colorScheme.primary,
            title = stringResource(
                if (state.permission == PermissionState.Denied) R.string.permission_title_denied
                else R.string.permission_title_needed,
            ),
            body = stringResource(
                if (state.permission == PermissionState.Denied) R.string.permission_denied_message
                else R.string.permission_needed_message,
            ),
            extra = { OfflineBadge() },
            actions = {
                Button(
                    onClick = onRequestPermission,
                    shape = RoundedCornerShape(AiGaleriDimens.ButtonCorner),
                    modifier = Modifier.fillMaxWidth().heightIn(min = AiGaleriDimens.MinTouchTarget).testTag(TAG_PERMISSION_GRANT),
                ) {
                    Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text(
                        stringResource(R.string.permission_grant_button),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = AiGaleriDimens.SpaceSm),
                    )
                }
            },
        )
        PermissionState.PermanentlyDenied -> PermissionLayout(
            icon = AppIcons.Lock,
            iconContainer = MaterialTheme.colorScheme.surfaceContainerHighest,
            iconTint = MaterialTheme.colorScheme.secondary,
            title = stringResource(R.string.permission_title_permanently_denied),
            body = stringResource(R.string.permission_permanently_denied_message),
            extra = { HowToPanel() },
            actions = {
                Button(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(AiGaleriDimens.ButtonCorner),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth().heightIn(min = AiGaleriDimens.MinTouchTarget).testTag(TAG_PERMISSION_SETTINGS),
                ) { Text(stringResource(R.string.permission_open_settings_button), style = MaterialTheme.typography.labelLarge) }
            },
        )
    }
}

@Composable
private fun GrantedContent(state: MainUiState) {
    val count = state.photoCount
    Box(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(AiGaleriDimens.SpaceLg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = when {
                state.loadError -> stringResource(R.string.photo_count_error)
                count == null -> stringResource(R.string.permission_granted_loading)
                else -> pluralStringResource(R.plurals.photo_count, count, count)
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Ortak yerleşim: ortalanmış, kaydırılabilir içerik (büyük yazıda taşmaz) ve altta sabit eylem düğmeleri.
 */
@Composable
private fun PermissionLayout(
    icon: ImageVector,
    iconContainer: Color,
    iconTint: Color,
    title: String,
    body: String,
    extra: @Composable () -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
                    .padding(horizontal = AiGaleriDimens.Margin, vertical = AiGaleriDimens.SpaceLg)
                    .widthIn(max = AiGaleriDimens.ContentMaxWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceMd),
            ) {
                Box(
                    modifier = Modifier.size(AiGaleriDimens.IconCircleLarge).background(iconContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(AiGaleriDimens.IconCircleLargeGlyph))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                extra()
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = AiGaleriDimens.Margin).padding(bottom = AiGaleriDimens.SpaceLg)
                .widthIn(max = AiGaleriDimens.ContentMaxWidth).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceXs),
        ) { actions() }
    }
}

/** "Tamamen çevrimdışı ve yerel model" rozeti. */
@Composable
private fun OfflineBadge() {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = CircleShape) {
        Row(
            modifier = Modifier.padding(horizontal = AiGaleriDimens.SpaceMd, vertical = AiGaleriDimens.SpaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceSm),
        ) {
            Box(Modifier.size(AiGaleriDimens.StatusDot).background(MaterialTheme.colorScheme.primary, CircleShape))
            Text(
                stringResource(R.string.permission_badge),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Kalıcı red için "Nasıl etkinleştirilir?" bilgi paneli. */
@Composable
private fun HowToPanel() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag(TAG_PERMISSION_HOWTO),
    ) {
        Row(
            modifier = Modifier.padding(AiGaleriDimens.SpaceMd),
            horizontalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceMd),
        ) {
            Icon(AppIcons.Info, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.permission_howto_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.permission_howto_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AiGaleriDimens.SpaceXs),
                )
            }
        }
    }
}
