package com.ktu.aigaleri.data

/**
 * Galerideki fotoğraf listesinin kaynağı. Gerçek uygulama [MediaStorePhotoSource] kullanır; testlerde
 * sahte kaynak verilir. İzin yoksa uygulamalar [SecurityException] fırlatabilir.
 */
interface MediaPhotoSource {
    /** Galerideki fotoğraf sayısı (ucuz sorgu). */
    suspend fun count(): Int

    /** Tüm fotoğraflar, çekim zamanına göre yeniden eskiye. */
    suspend fun loadPhotos(): List<MediaPhoto>
}
