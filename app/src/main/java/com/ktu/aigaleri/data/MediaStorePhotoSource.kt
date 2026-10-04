package com.ktu.aigaleri.data

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [MediaPhotoSource]'un MediaStore gerçeklemesi. Sorgular [dispatcher] (varsayılan IO) üzerinde çalışır.
 * Filtre/argüman kullanılmaz, dolayısıyla sorguya kullanıcı girdisi karışmaz. Fotoğraf yolu ve
 * içeriği loglanmaz.
 */
class MediaStorePhotoSource(
    private val contentResolver: ContentResolver,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MediaPhotoSource {

    private val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    override suspend fun count(): Int = withContext(dispatcher) {
        contentResolver.query(collection, arrayOf(MediaStore.Images.Media._ID), null, null, null)
            ?.use { it.count } ?: 0
    }

    override suspend fun loadPhotos(): List<MediaPhoto> = withContext(dispatcher) {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN)
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media._ID} DESC"
        val result = ArrayList<MediaPhoto>()
        contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                // DATE_TAKEN bilinmiyorsa null veya 0 gelebilir.
                val taken = if (cursor.isNull(dateCol)) null else cursor.getLong(dateCol).takeIf { it > 0 }
                result += MediaPhoto(id, ContentUris.withAppendedId(collection, id).toString(), taken)
            }
        }
        result
    }
}
