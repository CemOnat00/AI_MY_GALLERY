package com.ktu.aigaleri.ui.gallery

import android.content.ContentUris
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.ktu.aigaleri.R
import com.ktu.aigaleri.ui.formatDate
import com.ktu.aigaleri.ui.image.PhotoImage
import com.ktu.aigaleri.ui.search.CenteredColumn
import com.ktu.aigaleri.ui.search.IconCircle
import com.ktu.aigaleri.ui.theme.AiGaleriDimens
import com.ktu.aigaleri.ui.theme.AppIcons

const val TAG_GALLERY_GRID = "gallery_grid"
const val TAG_GALLERY_LOADING = "gallery_loading"
const val TAG_GALLERY_EMPTY = "gallery_empty"
const val TAG_GALLERY_ERROR = "gallery_error"
const val TAG_GALLERY_RETRY = "gallery_retry"

private const val SKELETON_CELL_COUNT = 30

/**
 * İlk sayfadaki galeri (T-036): cihazdaki tüm fotoğraflar, 3 sütun, 2dp aralık, kenardan kenara 1:1 grid,
 * en yeniden eskiye. Durumlar: yükleniyor (iskelet), boş, hata ("Tekrar dene") ve liste. Grid, klavye/gezinti çubuğu
 * boşluğunu kendi içerik dolgusuyla (contentPadding) verir; diğer durumlar alt boşluğu kendisi uygular.
 *
 * @param gridState çağıranın tuttuğu kaydırma durumu (`rememberLazyGridState`, kaydedilebilir): yön değişimi, geri
 *   yığını ve arama sonucundan grid'e dönüşte konum korunur.
 * @param onOpenPhoto fotoğraf kimliği ve (biliniyorsa) çekim tarihi ile büyük görünüme gider.
 */
@Composable
fun GalleryContent(
    state: GalleryUiState,
    gridState: LazyGridState,
    onOpenPhoto: (photoId: Long, dateTaken: Long?) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bottomInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
    when (state) {
        GalleryUiState.Loading -> GallerySkeleton(modifier.windowInsetsPadding(bottomInsets))
        GalleryUiState.Empty -> CenteredColumn(modifier.windowInsetsPadding(bottomInsets).testTag(TAG_GALLERY_EMPTY)) {
            IconCircle(AppIcons.PhotoLibrary, size = AiGaleriDimens.IconCircleMedium, glyph = AiGaleriDimens.IconCircleMediumGlyph)
            Text(
                stringResource(R.string.gallery_empty),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.gallery_empty_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        GalleryUiState.Error -> CenteredColumn(modifier.windowInsetsPadding(bottomInsets).testTag(TAG_GALLERY_ERROR)) {
            IconCircle(AppIcons.ErrorOutline, size = AiGaleriDimens.IconCircleMedium, glyph = AiGaleriDimens.IconCircleMediumGlyph)
            Text(
                stringResource(R.string.gallery_error),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                stringResource(R.string.gallery_error_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(AiGaleriDimens.ButtonCorner),
                modifier = Modifier.heightIn(min = AiGaleriDimens.MinTouchTarget).testTag(TAG_GALLERY_RETRY),
            ) { Text(stringResource(R.string.gallery_error_retry), style = MaterialTheme.typography.labelLarge) }
        }
        is GalleryUiState.Ready -> GalleryGrid(state.photos, gridState, onOpenPhoto, modifier)
    }
}

@Composable
private fun GalleryGrid(
    photos: GalleryPhotos,
    gridState: LazyGridState,
    onOpenPhoto: (Long, Long?) -> Unit,
    modifier: Modifier,
) {
    // Hücreler indeks üzerinden üretilir (items(count)): 50 bin fotoğrafta ek nesne listesi oluşmaz. Anahtar kimliktir;
    // liste yenilenip başa yeni fotoğraf eklenince kaydırma konumu aynı fotoğrafta kalır.
    LazyVerticalGrid(
        columns = GridCells.Fixed(AiGaleriDimens.GridColumns),
        state = gridState,
        modifier = modifier.fillMaxSize().testTag(TAG_GALLERY_GRID),
        contentPadding = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues(),
        horizontalArrangement = Arrangement.spacedBy(AiGaleriDimens.Gutter),
        verticalArrangement = Arrangement.spacedBy(AiGaleriDimens.Gutter),
    ) {
        items(count = photos.size, key = { photos.idAt(it) }) { index ->
            val id = photos.idAt(index)
            val date = photos.dateAt(index)
            val uri = remember(id) { ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id) }
            val description = if (date != null) {
                stringResource(R.string.gallery_photo_description, remember(date) { formatDate(date) })
            } else {
                stringResource(R.string.photo_content_description)
            }
            PhotoImage(
                uri = uri,
                contentDescription = description,
                modifier = Modifier.aspectRatio(1f)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.photo_open_label)) { onOpenPhoto(id, date) },
            )
        }
    }
}

/** Soluk iskelet grid'i (kaydırılamaz); ekran okuyucuya tek açıklama verir. */
@Composable
private fun GallerySkeleton(modifier: Modifier) {
    val description = stringResource(R.string.gallery_loading)
    LazyVerticalGrid(
        columns = GridCells.Fixed(AiGaleriDimens.GridColumns),
        modifier = modifier.fillMaxSize().alpha(AiGaleriDimens.SkeletonAlpha).testTag(TAG_GALLERY_LOADING)
            .semantics { contentDescription = description; liveRegion = LiveRegionMode.Polite },
        userScrollEnabled = false,
        horizontalArrangement = Arrangement.spacedBy(AiGaleriDimens.Gutter),
        verticalArrangement = Arrangement.spacedBy(AiGaleriDimens.Gutter),
    ) {
        items(SKELETON_CELL_COUNT) {
            Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainerHigh))
        }
    }
}
