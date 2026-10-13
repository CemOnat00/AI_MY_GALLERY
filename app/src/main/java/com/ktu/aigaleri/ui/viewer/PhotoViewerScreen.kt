package com.ktu.aigaleri.ui.viewer

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.ktu.aigaleri.R
import com.ktu.aigaleri.ui.image.PhotoImage
import com.ktu.aigaleri.ui.theme.AiGaleriDimens
import com.ktu.aigaleri.ui.theme.AiGaleriExtras
import com.ktu.aigaleri.ui.theme.AppIcons

const val TAG_VIEWER_NOT_FOUND = "viewer_not_found"
const val TAG_VIEWER_BACK = "viewer_back"

/**
 * Fotoğrafın MediaStore URI'si; veri katmanıyla ([com.ktu.aigaleri.data.MediaStorePhotoSource]) aynı
 * biçim: `EXTERNAL_CONTENT_URI/<id>`. Büyük görünüm arama sonuçlarından bağımsız, yalnızca kimlikle açılır.
 */
fun mediaStoreImageUri(photoId: Long): Uri =
    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoId)

/** [photoId] null veya geçersizse (<= 0) fotoğraf aranmaz, doğrudan "bulunamadı" gösterilir. */
internal fun isValidPhotoId(photoId: Long?): Boolean = photoId != null && photoId > 0

/**
 * Tek fotoğraf tam ekran, koyu tuval üzerinde (T-022). Geri oku her durumda görünür. Kimlik geçersizse veya
 * görüntü yüklenemezse (dosya silinmiş/taşınmış) "Fotoğraf bulunamadı" açıklaması ve "Geri dön" gösterilir.
 */
@Composable
fun PhotoViewerScreen(photoId: Long?, onBack: () -> Unit) {
    var loadFailed by remember(photoId) { mutableStateOf(false) }
    val notFound = !isValidPhotoId(photoId) || loadFailed
    Box(modifier = Modifier.fillMaxSize().background(AiGaleriExtras.ViewerCanvas)) {
        if (!notFound && photoId != null) {
            PhotoImage(
                uri = mediaStoreImageUri(photoId),
                contentDescription = stringResource(R.string.photo_content_description),
                contentScale = ContentScale.Fit,
                placeholderColor = AiGaleriExtras.ViewerCanvas,
                onError = { loadFailed = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            NotFound(onBack)
        }
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(AiGaleriDimens.SpaceSm)
                .testTag(TAG_VIEWER_BACK),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = AiGaleriExtras.ViewerScrim,
                contentColor = AiGaleriExtras.OnViewerCanvas,
            ),
        ) {
            Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.back_button))
        }
    }
}

@Composable
private fun NotFound(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = AiGaleriDimens.SpaceLg, vertical = AiGaleriDimens.SpaceXl)
            .testTag(TAG_VIEWER_NOT_FOUND),
        verticalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceSm, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = AiGaleriDimens.ContentMaxWidth),
            verticalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceSm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(AiGaleriDimens.IconCircleMedium).background(AiGaleriExtras.ViewerScrim, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.PhotoLibrary,
                    contentDescription = null,
                    tint = AiGaleriExtras.OnViewerCanvas,
                    modifier = Modifier.size(AiGaleriDimens.IconCircleMediumGlyph),
                )
            }
            Text(
                stringResource(R.string.viewer_not_found),
                style = MaterialTheme.typography.titleMedium,
                color = AiGaleriExtras.OnViewerCanvas,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
            )
            Text(
                stringResource(R.string.viewer_not_found_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = AiGaleriExtras.OnViewerCanvas,
                textAlign = TextAlign.Center,
            )
            TextButton(
                onClick = onBack,
                modifier = Modifier.heightIn(min = AiGaleriDimens.MinTouchTarget),
                colors = ButtonDefaults.textButtonColors(contentColor = AiGaleriExtras.OnViewerCanvas),
            ) { Text(stringResource(R.string.viewer_back_action), style = MaterialTheme.typography.labelLarge) }
        }
    }
}
