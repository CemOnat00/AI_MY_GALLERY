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
 * Bellek: [loadPhotos] tüm listeyi belleğe alır (bkz. [MediaPhotoSource.loadPhotos]); [loadIds] yalnızca
 * kimlik sütununu, [loadByIds] en fazla [ID_CHUNK] kimlik için okur. Kimlikler `?` argümanı olarak
 * bağlanır (SQL'e metin olarak katılmaz).
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

    override suspend fun loadIds(): LongArray = withContext(dispatcher) {
        openCursor(arrayOf(MediaStore.Images.Media._ID)).use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val ids = LongArray(cursor.count)
            var n = 0
            while (cursor.moveToNext() && n < ids.size) {
                ensureActive()
                ids[n++] = cursor.getLong(idCol)
            }
            if (n == ids.size) ids else ids.copyOf(n)
        }
    }

    override suspend fun loadByIds(ids: List<Long>): List<MediaPhoto> = withContext(dispatcher) {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val base = collection.toString()
        val result = ArrayList<MediaPhoto>(ids.size)
        for (chunk in ids.chunked(ID_CHUNK)) {
            ensureActive()
            val selection = "${MediaStore.Images.Media._ID} IN (${chunk.joinToString(",") { "?" }})"
            val args = Array(chunk.size) { chunk[it].toString() }
            val cursor = contentResolver.query(collection, projection, selection, args, null)
                ?: throw IllegalStateException("MediaStore sorgusu null cursor döndürdü")
            cursor.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val takenCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val addedCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (it.moveToNext()) {
                    result += MediaPhotoMapping.toMediaPhoto(
                        collectionUri = base,
                        id = it.getLong(idCol),
                        dateTakenMs = it.longOrNull(takenCol),
                        dateAddedSec = it.longOrNull(addedCol),
                    )
                }
            }
        }
        result
    }

    /** Sağlayıcı null cursor döndürürse (okunamıyor) sessizce boş saymak yerine hata verir. */
    private fun openCursor(projection: Array<String>): Cursor =
        contentResolver.query(collection, projection, null, null, null)
            ?: throw IllegalStateException("MediaStore sorgusu null cursor döndürdü")

    private fun Cursor.longOrNull(column: Int): Long? = if (isNull(column)) null else getLong(column)

    private companion object {
        /** SQLite değişken sınırının (eski sürümlerde 999) altında güvenli parça boyutu. */
        const val ID_CHUNK = 500
    }
}
