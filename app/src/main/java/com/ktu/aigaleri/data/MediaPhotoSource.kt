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
     * İndeksleyici (T-006) bunu KULLANMAZ; [loadIds] ve [loadByIds] ile parça parça okur.
     */
    suspend fun loadPhotos(): List<MediaPhoto>

    /**
     * Galerideki tüm fotoğraf kimlikleri (sıra belirsiz). Yalnızca kimlik sütunu okunur; 100 bin fotoğraf
     * için ~800 KB (LongArray). Varsayılan gerçekleme [loadPhotos]'a düşer, gerçek kaynak geçersiz kılar.
     */
    suspend fun loadIds(): LongArray = loadPhotos().let { list -> LongArray(list.size) { list[it].id } }

    /**
     * Verilen kimliklerin fotoğrafları (sıra belirsiz). Galeride artık olmayan kimlikler sonuçta bulunmaz
     * (hata değil). Çağıran parçaları küçük tutmalıdır (indeksleyici <= 50); gerçek kaynak SQLite değişken
     * sınırı için kendi içinde de parçalar. Varsayılan gerçekleme [loadPhotos]'a düşer.
     */
    suspend fun loadByIds(ids: List<Long>): List<MediaPhoto> {
        val wanted = ids.toHashSet()
        return loadPhotos().filter { it.id in wanted }
    }
}
