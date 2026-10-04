package com.ktu.aigaleri.ui.viewer

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ktu.aigaleri.R
import com.ktu.aigaleri.ui.image.PhotoImage

/**
 * Fotoğrafın MediaStore URI'si; veri katmanıyla ([com.ktu.aigaleri.data.MediaStorePhotoSource]) aynı
 * biçim: `EXTERNAL_CONTENT_URI/<id>`. Büyük görünüm arama sonuçlarından bağımsız, yalnızca kimlikle açılır.
 */
fun mediaStoreImageUri(photoId: Long): Uri =
    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoId)

/** Tek fotoğraf tam ekran. [photoId] null veya geçersizse (<= 0) açıklama gösterir. */
@Composable
fun PhotoViewerScreen(photoId: Long?, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (photoId == null || photoId <= 0) {
            Text(
                stringResource(R.string.viewer_not_found),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        } else {
            PhotoImage(
                uri = mediaStoreImageUri(photoId),
                contentDescription = stringResource(R.string.photo_content_description),
                contentScale = ContentScale.Fit,
                placeholderColor = Color.Black,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Button(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Text(stringResource(R.string.back_button))
        }
    }
}
