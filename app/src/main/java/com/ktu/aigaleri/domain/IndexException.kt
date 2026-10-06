package com.ktu.aigaleri.domain

/**
 * [PhotoIndexer.index] akışını sonlandıran, tek fotoğrafla ilgisiz hatalar. Tek fotoğraf
 * hataları bu tiple bildirilmez; fotoğraf atlanır ve [IndexProgress.failed] artar.
 * Mesajlar fotoğraf yolu, içerik veya istem içermez.
 */
sealed class IndexException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Galeri okuma izni yok (READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE). */
    class PermissionMissing : IndexException("Galeri okuma izni yok")

    /**
     * Beklenmeyen, fotoğraftan bağımsız hata (ör. veritabanı, model yükleme). [cause] yalnızca hata ayıklama için
     * taşınır: cause'un mesajı ve stack trace'i log'a, analitiğe veya kullanıcıya YAZILMAZ (yol, içerik veya istem
     * sızdırabilir). `CancellationException` bu tiple SARILMAZ; iptal olduğu gibi yayılır.
     */
    class Unexpected(cause: Throwable) : IndexException("Beklenmeyen indeksleme hatası", cause)
}
