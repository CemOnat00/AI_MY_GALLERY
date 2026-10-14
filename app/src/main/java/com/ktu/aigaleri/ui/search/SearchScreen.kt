package com.ktu.aigaleri.ui.search

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ktu.aigaleri.R
import com.ktu.aigaleri.domain.InvalidQueryException
import com.ktu.aigaleri.ui.formatCount
import com.ktu.aigaleri.ui.gallery.GalleryContent
import com.ktu.aigaleri.ui.gallery.GalleryUiState
import com.ktu.aigaleri.ui.image.PhotoImage
import com.ktu.aigaleri.ui.index.IndexingBanner
import com.ktu.aigaleri.ui.index.IndexingBannerContent
import com.ktu.aigaleri.ui.theme.AiGaleriDimens
import com.ktu.aigaleri.ui.theme.AppIcons

const val TAG_QUERY_FIELD = "query_field"
const val TAG_SEARCH_BUTTON = "search_button"
const val TAG_RESULT_GRID = "result_grid"
const val TAG_CLEAR_BUTTON = "clear_button"
const val TAG_SUGGESTION_CHIPS = "suggestion_chips"
const val TAG_EMPTY_INDEX_LINK = "empty_index_link"
const val TAG_ERROR_RETRY = "search_error_retry"

private const val SKELETON_CELL_COUNT = 12

/**
 * Durumsuz arama ekranı (T-022 tasarımı): pill arama alanı, indeksleme bandı ve duruma göre boşta (öneri çipleri +
 * cihazdaki tüm fotoğrafların galeri grid'i, T-036) / yükleniyor / sonuç grid'i / sonuç yok / hata. Arama
 * tetikleyicisi (TAG_SEARCH_BUTTON) alanın başındaki arama simgesidir; klavyedeki arama tuşu da aynı işi yapar.
 *
 * @param gallery boştaki galeri grid'inin durumu; [onOpenGalleryPhoto] galeri öğesi için kimlik ve çekim tarihi verir.
 */
@Composable
fun SearchContent(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenPhoto: (Long) -> Unit,
    onOpenIndexStatus: () -> Unit,
    indexing: IndexingBanner? = null,
    onQueryFocused: () -> Unit = {},
    gallery: GalleryUiState = GalleryUiState.Loading,
    onOpenGalleryPhoto: (photoId: Long, dateTaken: Long?) -> Unit = { _, _ -> },
    onGalleryRetry: () -> Unit = {},
) {
    // Galeri kaydırma konumu burada tutulur (rememberSaveable): arama sonucuna geçip grid'e dönünce, büyük görünümden
    // dönünce ve yön değişiminde korunur.
    val galleryGridState = rememberLazyGridState()
    Column(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = AiGaleriDimens.Margin, top = AiGaleriDimens.SpaceSm, end = AiGaleriDimens.SpaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchPill(
                state = state,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                onQueryFocused = onQueryFocused,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenIndexStatus) {
                Icon(
                    imageVector = AppIcons.Info,
                    contentDescription = stringResource(R.string.index_status_open),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        InlineValidation(state)
        if (indexing != null) {
            IndexingBannerContent(
                banner = indexing,
                onOpen = onOpenIndexStatus,
                modifier = Modifier.padding(horizontal = AiGaleriDimens.Margin, vertical = AiGaleriDimens.SpaceSm),
            )
        }
        // Klavye (IME) ve gezinti çubuğu içeriği örtmesin; sonuç ve galeri grid'leri kenardan kenara kaydığı için kendi alt
        // boşluklarını (contentPadding) kullanır, bu yüzden boşluk yalnızca grid dışı durumlara uygulanır (boştaki galeri
        // kendi içinde yönetir).
        val bottomInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
        val isIdle = state.status is SearchStatus.Idle || state.status is SearchStatus.InvalidQuery
        val isGrid = state.status is SearchStatus.Success || isIdle
        Box(modifier = Modifier.fillMaxSize().then(if (isGrid) Modifier else Modifier.windowInsetsPadding(bottomInsets))) {
            when (val status = state.status) {
                SearchStatus.Idle, is SearchStatus.InvalidQuery -> Column(modifier = Modifier.fillMaxSize()) {
                    SuggestionChips(
                        onPick = { suggestion ->
                            onQueryChange(suggestion)
                            onSearch()
                        },
                    )
                    GalleryContent(
                        state = gallery,
                        gridState = galleryGridState,
                        onOpenPhoto = onOpenGalleryPhoto,
                        onRetry = onGalleryRetry,
                        modifier = Modifier.weight(1f),
                    )
                }
                SearchStatus.Loading -> LoadingState()
                SearchStatus.Empty -> EmptyState(onOpenIndexStatus)
                SearchStatus.Error -> ErrorState(canRetry = state.canSearch, onRetry = onSearch)
                is SearchStatus.Success -> ResultGrid(status, onOpenPhoto)
            }
        }
    }
}

/** 56dp pill: arama düğmesi (simge), metin alanı ve (doluysa) temizle düğmesi. */
@Composable
private fun SearchPill(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onQueryFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val hint = stringResource(R.string.search_hint)
    val focusRequester = remember { FocusRequester() }
    Row(
        modifier = modifier.heightIn(min = AiGaleriDimens.SearchBarHeight)
            .clip(RoundedCornerShape(AiGaleriDimens.SearchBarCorner))
            .background(colors.surfaceContainer)
            // Pill'in boş kısmına dokununca da odak gelir (tıklama semantiği eklemeden).
            .pointerInput(Unit) { detectTapGestures { focusRequester.requestFocus() } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onSearch,
            enabled = state.canSearch,
            modifier = Modifier.testTag(TAG_SEARCH_BUTTON),
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = colors.primary,
                disabledContentColor = colors.onSurfaceVariant.copy(alpha = AiGaleriDimens.DisabledContentAlpha),
            ),
        ) {
            Icon(AppIcons.Search, contentDescription = stringResource(R.string.search_button))
        }
        BasicTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f)
                .heightIn(min = AiGaleriDimens.MinTouchTarget)
                .focusRequester(focusRequester)
                .testTag(TAG_QUERY_FIELD)
                .onFocusChanged { if (it.isFocused) onQueryFocused() }
                .semantics { if (state.query.isEmpty()) contentDescription = hint },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (state.canSearch) onSearch() }),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = AiGaleriDimens.MinTouchTarget),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (state.query.isEmpty()) {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (state.query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.testTag(TAG_CLEAR_BUTTON)) {
                Icon(
                    AppIcons.Close,
                    contentDescription = stringResource(R.string.search_clear),
                    tint = colors.onSurfaceVariant,
                )
            }
        } else {
            Box(Modifier.size(AiGaleriDimens.SpaceSm))
        }
    }
}

/** Alanın altında satır içi doğrulama: geçersiz istem nedeni veya karakter sınırı. */
@Composable
private fun InlineValidation(state: SearchUiState) {
    val status = state.status
    val message = when {
        status is SearchStatus.InvalidQuery -> stringResource(invalidQueryMessage(status.reason))
        state.query.length >= MAX_QUERY_LENGTH -> stringResource(R.string.search_max_length, MAX_QUERY_LENGTH)
        else -> null
    } ?: return
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = AiGaleriDimens.Margin + AiGaleriDimens.SpaceMd, vertical = AiGaleriDimens.SpaceXs)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceXs),
    ) {
        Icon(
            AppIcons.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(AiGaleriDimens.IconChevron),
        )
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * Boşta durumu: tek satır yatay kayan küçük öneri çipleri (DESIGN.md "Filter & Context Chips": 32dp, 8dp köşe,
 * Outline-Variant çerçeve). Dokunma alanı 48dp; çipe dokununca istem yazılır ve aranır.
 */
@Composable
private fun SuggestionChips(onPick: (String) -> Unit) {
    val rowDescription = stringResource(R.string.search_suggestions_title)
    val actionLabel = stringResource(R.string.search_suggestion_action)
    val shape = RoundedCornerShape(AiGaleriDimens.ChipCorner)
    LazyRow(
        modifier = Modifier.fillMaxWidth().testTag(TAG_SUGGESTION_CHIPS).semantics { contentDescription = rowDescription },
        contentPadding = PaddingValues(horizontal = AiGaleriDimens.Margin),
        horizontalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(SearchSuggestions.ALL) { suggestion ->
            Box(
                modifier = Modifier.heightIn(min = AiGaleriDimens.MinTouchTarget)
                    .clickable(role = Role.Button, onClickLabel = actionLabel) { onPick(suggestion) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.heightIn(min = AiGaleriDimens.ChipHeight)
                        .clip(shape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                        .padding(horizontal = AiGaleriDimens.ChipPaddingHorizontal),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        suggestion,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** "Aranıyor..." satırı, ince belirsiz ilerleme çizgisi ve soluk iskelet grid. */
@Composable
private fun LoadingState() {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.search_loading),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = AiGaleriDimens.Margin, vertical = AiGaleriDimens.SpaceSm)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().heightIn(max = AiGaleriDimens.ProgressLineThin),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(AiGaleriDimens.GridColumns),
            modifier = Modifier.fillMaxSize().padding(top = AiGaleriDimens.SpaceSm).alpha(AiGaleriDimens.SkeletonAlpha),
            userScrollEnabled = false,
            horizontalArrangement = Arrangement.spacedBy(AiGaleriDimens.Gutter),
            verticalArrangement = Arrangement.spacedBy(AiGaleriDimens.Gutter),
        ) {
            items(SKELETON_CELL_COUNT) {
                Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainerHigh))
            }
        }
    }
}

/** "En yakın eşleşmeler / N fotoğraf" satırı ve kenardan kenara 3 sütunlu 1:1 grid. */
@Composable
private fun ResultGrid(status: SearchStatus.Success, onOpenPhoto: (Long) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = AiGaleriDimens.Margin, vertical = AiGaleriDimens.SpaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceSm),
        ) {
            Text(
                stringResource(R.string.search_results_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Text(
                stringResource(R.string.search_result_count, formatCount(status.results.size)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(AiGaleriDimens.GridColumns),
            modifier = Modifier.fillMaxSize().testTag(TAG_RESULT_GRID),
            contentPadding = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues(),
            horizontalArrangement = Arrangement.spacedBy(AiGaleriDimens.Gutter),
            verticalArrangement = Arrangement.spacedBy(AiGaleriDimens.Gutter),
        ) {
            items(status.results, key = { it.photoId }) { result ->
                PhotoImage(
                    uri = Uri.parse(result.uri),
                    contentDescription = stringResource(R.string.photo_content_description),
                    modifier = Modifier.aspectRatio(1f)
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.photo_open_label)) { onOpenPhoto(result.photoId) },
                )
            }
        }
    }
}

/** Sonuç yok: kısa açıklama ve indeks durumuna bağlantı (indeks boşken tipik). */
@Composable
private fun EmptyState(onOpenIndexStatus: () -> Unit) {
    CenteredColumn {
        IconCircle(AppIcons.Search, size = AiGaleriDimens.IconCircleMedium, glyph = AiGaleriDimens.IconCircleMediumGlyph)
        Text(
            stringResource(R.string.search_empty),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            stringResource(R.string.search_empty_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(
            onClick = onOpenIndexStatus,
            modifier = Modifier.heightIn(min = AiGaleriDimens.MinTouchTarget).testTag(TAG_EMPTY_INDEX_LINK),
        ) {
            Text(stringResource(R.string.search_empty_index_link), style = MaterialTheme.typography.labelLarge)
            Icon(
                AppIcons.ArrowForward,
                contentDescription = null,
                modifier = Modifier.padding(start = AiGaleriDimens.SpaceXs).size(AiGaleriDimens.IconChevron),
            )
        }
    }
}

/** Genel hata: hata kapsayıcısında başlık, kısa açıklama ve "Tekrar dene" (aynı istemle yeniden arar). */
@Composable
private fun ErrorState(canRetry: Boolean, onRetry: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AiGaleriDimens.Margin),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(colors.errorContainer)
                .padding(AiGaleriDimens.SpaceMd)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceSm),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceSm)) {
                Icon(AppIcons.ErrorOutline, contentDescription = null, tint = colors.error)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.search_error),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onErrorContainer,
                    )
                    Text(
                        stringResource(R.string.search_error_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onErrorContainer,
                        modifier = Modifier.padding(top = AiGaleriDimens.SpaceXs),
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = onRetry,
                    enabled = canRetry,
                    shape = RoundedCornerShape(AiGaleriDimens.ButtonCorner),
                    modifier = Modifier.heightIn(min = AiGaleriDimens.MinTouchTarget).testTag(TAG_ERROR_RETRY),
                ) { Text(stringResource(R.string.search_error_retry), style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

@Composable
internal fun CenteredColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = AiGaleriDimens.Margin, vertical = AiGaleriDimens.SpaceLg),
        verticalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceSm, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = AiGaleriDimens.ContentMaxWidth),
            verticalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceSm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

/** Yuvarlak, tonlu simge kapsayıcısı (boş durum). */
@Composable
internal fun IconCircle(icon: ImageVector, size: Dp, glyph: Dp) {
    Box(
        modifier = Modifier.size(size).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(glyph))
    }
}

/** Geçersiz istem nedeninin kullanıcı mesajı (istem metni içermez). */
internal fun invalidQueryMessage(reason: InvalidQueryException.Reason): Int = when (reason) {
    InvalidQueryException.Reason.EMPTY -> R.string.search_invalid_empty
    InvalidQueryException.Reason.TOO_LONG -> R.string.search_invalid_too_long
}
