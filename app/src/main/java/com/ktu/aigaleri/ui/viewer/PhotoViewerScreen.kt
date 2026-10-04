package com.ktu.aigaleri.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.ktu.aigaleri.ui.image.AsyncPhoto
import com.ktu.aigaleri.ui.image.ImageLoader

/** Tek fotoğraf tam ekran. [uri] null ise (ör. süreç yeniden başladı, sonuçlar kayboldu) açıklama gösterir. */
@Composable
fun PhotoViewerScreen(uri: String?, imageLoader: ImageLoader, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (uri == null) {
            Text(
                stringResource(R.string.viewer_not_found),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        } else {
            AsyncPhoto(
                loader = imageLoader,
                uri = uri,
                contentDescription = stringResource(R.string.photo_content_description),
                contentScale = ContentScale.Fit,
                full = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Button(onClick = onBack) { Text(stringResource(R.string.back_button)) }
        }
    }
}
