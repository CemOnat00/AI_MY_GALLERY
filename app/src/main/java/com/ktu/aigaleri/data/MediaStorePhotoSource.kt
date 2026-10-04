package com.ktu.aigaleri.data

import android.content.ContentResolver
import android.database.Cursor
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * [MediaPhotoSource]'un MediaStore gerçeklemesi. Sorgular [dispatcher] (varsayılan IO) üzerinde çalışır.
 * Filtre/argüman kullanılmaz, dolayısıyla sorguya kullanıcı girdisi karışmaz. Fotoğraf yolu ve
 * içeriği loglanmaz. Cursor eşlemesi saf [MediaPhotoMapping] içindedir. Okuma döngüsü iptale duyarlıdır.
 *
 * Bellek: [loadPhotos] tüm listeyi belleğe alır (bkz. [MediaPhotoSource.loadPhotos]).
 */
class MediaStorePhotoSource(
    private val contentResolver: ContentResolver,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MediaPhotoSource {

    private val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    override suspend fun count(): Int = withContext(dispatcher) {
        openCursor(arrayOf(MediaStore.Images.Media._ID)).use { it.count }
    }

    override suspend fun loadPhotos(): List<MediaPhoto> = withContext(dispatcher) {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val base = collection.toString()
        val result = ArrayList<MediaPhoto>()
        openCursor(projection).use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                ensureActive()
                result += MediaPhotoMapping.toMediaPhoto(
                    collectionUri = base,
                    id = cursor.getLong(idCol),
                    dateTakenMs = cursor.longOrNull(takenCol),
                    dateAddedSec = cursor.longOrNull(addedCol),
                )
            }
        }
        // Etkin tarihe göre (DATE_TAKEN yedeği DATE_ADDED) sıralama SQL'de ifade edilemediğinden bellekte yapılır.
        MediaPhotoMapping.sortNewestFirst(result)
    }

    /** Sağlayıcı null cursor döndürürse (okunamıyor) sessizce boş saymak yerine hata verir. */
    private fun openCursor(projection: Array<String>): Cursor =
        contentResolver.query(collection, projection, null, null, null)
            ?: throw IllegalStateException("MediaStore sorgusu null cursor döndürdü")

    private fun Cursor.longOrNull(column: Int): Long? = if (isNull(column)) null else getLong(column)
}
