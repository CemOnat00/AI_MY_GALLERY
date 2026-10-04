package com.ktu.aigaleri.data

/**
 * Galerideki fotoğraf listesinin kaynağı. Gerçek uygulama [MediaStorePhotoSource] kullanır; testlerde
 * sahte kaynak verilir.
 *
 * Hatalar: izin yoksa [SecurityException]; kaynak okunamıyorsa (ör. MediaStore sağlayıcısı null
 * cursor döndürürse) [IllegalStateException]. Boş galeri hata değildir, 0/boş liste döner.
 */
interface MediaPhotoSource {
    /** Galerideki fotoğraf sayısı (ucuz sorgu). */
    suspend fun count(): Int

    /**
     * Tüm fotoğraflar, [MediaPhoto.dateTaken] (çekim, yoksa eklenme zamanı) yeniden eskiye;
     * tarihi bilinmeyenler sonda.
     *
     * Bellek sınırı: liste tamamen belleğe alınır; 50-100 bin fotoğrafta onlarca MB tutabilir.
     * Sayfalama/parça okuma kararı indeksleme görevine (T-006) aittir.
     */
    suspend fun loadPhotos(): List<MediaPhoto>
}
