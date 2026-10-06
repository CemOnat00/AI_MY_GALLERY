package com.ktu.aigaleri.ui.search

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ktu.aigaleri.R
import com.ktu.aigaleri.domain.InvalidQueryException
import com.ktu.aigaleri.ui.image.PhotoImage
import com.ktu.aigaleri.ui.index.IndexingBanner
import com.ktu.aigaleri.ui.index.IndexingBannerContent

const val TAG_QUERY_FIELD = "query_field"
const val TAG_SEARCH_BUTTON = "search_button"
const val TAG_RESULT_GRID = "result_grid"

/** Durumsuz arama ekranı: istem alanı, arama butonu ve duruma göre sonuç grid'i/mesaj. */
@Composable
fun SearchContent(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenPhoto: (Long) -> Unit,
    onOpenIndexStatus: () -> Unit,
    indexing: IndexingBanner? = null,
    onQueryFocused: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().testTag(TAG_QUERY_FIELD)
                .onFocusChanged { if (it.isFocused) onQueryFocused() },
            label = { Text(stringResource(R.string.search_hint)) },
            supportingText = { Text("${state.query.length}/$MAX_QUERY_LENGTH") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (state.canSearch) onSearch() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSearch, enabled = state.canSearch, modifier = Modifier.testTag(TAG_SEARCH_BUTTON)) {
                Text(stringResource(R.string.search_button))
            }
            OutlinedButton(onClick = onOpenIndexStatus) { Text(stringResource(R.string.index_status_open)) }
        }
        if (indexing != null) IndexingBannerContent(indexing, onOpenIndexStatus)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (val status = state.status) {
                SearchStatus.Idle -> Message(R.string.search_idle)
                SearchStatus.Loading -> CircularProgressIndicator()
                SearchStatus.Empty -> Message(R.string.search_empty)
                SearchStatus.Error -> Message(R.string.search_error)
                is SearchStatus.InvalidQuery -> Message(invalidQueryMessage(status.reason))
                is SearchStatus.Success -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    modifier = Modifier.fillMaxSize().testTag(TAG_RESULT_GRID),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(status.results, key = { it.photoId }) { result ->
                        PhotoImage(
                            uri = Uri.parse(result.uri),
                            contentDescription = stringResource(R.string.photo_content_description),
                            modifier = Modifier.aspectRatio(1f).clickable { onOpenPhoto(result.photoId) },
                        )
                    }
                }
            }
        }
    }
}

/** Geçersiz istem nedeninin kullanıcı mesajı (istem metni içermez). */
internal fun invalidQueryMessage(reason: InvalidQueryException.Reason): Int = when (reason) {
    InvalidQueryException.Reason.EMPTY -> R.string.search_invalid_empty
    InvalidQueryException.Reason.TOO_LONG -> R.string.search_invalid_too_long
}

@Composable
private fun Message(resId: Int) {
    Text(stringResource(resId), style = MaterialTheme.typography.bodyLarge)
}
