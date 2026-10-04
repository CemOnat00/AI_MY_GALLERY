package com.ktu.aigaleri.ui.image

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ktu.aigaleri.R

/** Yükleme sonucu: null = yükleniyor, Failed = okunamadı. */
private sealed interface Loaded {
    data class Ok(val bitmap: ImageBitmap) : Loaded
    data object Failed : Loaded
}

/**
 * [uri]'yi [ImageLoader] ile (IO thread) yükleyip gösterir. Kompozisyondan çıkınca (grid'de kaydırma)
 * yükleme iptal olur. [full] true ise büyük görünüm çözünürlüğü, değilse [thumbnailPx] küçük resmi.
 */
@Composable
fun AsyncPhoto(
    loader: ImageLoader,
    uri: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    full: Boolean = false,
    thumbnailPx: Int = 256,
) {
    val loaded by produceState<Loaded?>(initialValue = null, uri, full, thumbnailPx) {
        val bitmap = if (full) loader.loadFull(uri) else loader.loadThumbnail(uri, thumbnailPx)
        value = bitmap?.let { Loaded.Ok(it.asImageBitmap()) } ?: Loaded.Failed
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (val l = loaded) {
            null -> CircularProgressIndicator(modifier = Modifier.padding(4.dp))
            is Loaded.Ok -> Image(
                bitmap = l.bitmap,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.matchParentSize(),
            )
            Loaded.Failed -> Text(
                stringResource(R.string.image_load_failed),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
