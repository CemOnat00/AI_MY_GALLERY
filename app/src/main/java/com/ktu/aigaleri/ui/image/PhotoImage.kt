package com.ktu.aigaleri.ui.image

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/**
 * MediaStore content URI'sini Coil ile gösterir. Coil yalnızca ağsız artefaktlarla eklenmiştir
 * (coil-compose/coil-core); http(s) yükleme yeteneği yoktur, content:// URI'leri ve EXIF yönü Coil'de
 * çözülür. Decode boyutu yerleşim boyutuna göre seçilir (grid'de küçük, tam ekranda büyük), bellek
 * önbelleği Coil varsayılanıdır. Yükleme/hata sırasında spinner yerine statik renkli yer tutucu
 * gösterilir (kaydırırken titreme olmaz).
 */
@Composable
fun PhotoImage(
    uri: Uri,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholderColor: Color = Color(0xFFE0E0E0),
) {
    val context = LocalContext.current
    val request = remember(uri, context) { ImageRequest.Builder(context).data(uri).build() }
    val placeholder = remember(placeholderColor) { ColorPainter(placeholderColor) }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        placeholder = placeholder,
        error = placeholder,
        contentScale = contentScale,
    )
}
