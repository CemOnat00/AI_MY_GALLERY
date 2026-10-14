package com.ktu.aigaleri.data

/** Testler için sahte MediaStore kaynağı. */
class FakeMediaPhotoSource(
    var photos: List<MediaPhoto> = emptyList(),
    var failWith: Throwable? = null,
) : MediaPhotoSource {
    var countCalls = 0
        private set

    /** [loadPhotos]/[loadTimeline] çağrı sayısı (tam okuma). */
    var loadCalls = 0
        private set

    /** [changeToken] çağrı sayısı (ucuz kontrol). */
    var tokenCalls = 0
        private set

    /** Verilirse [loadPhotos] bitmeden bu geçit açılana kadar bekler (çakışan yenileme testleri). */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun count(): Int {
        countCalls++
        failWith?.let { throw it }
        return photos.size
    }

    override suspend fun loadPhotos(): List<MediaPhoto> {
        loadCalls++
        gate?.await()
        failWith?.let { throw it }
        return photos
    }

    /** Sayı + en yeni kimlik (gerçek kaynağın imzasına benzer); [count] çağrı sayacını artırmaz. */
    override suspend fun changeToken(): Long {
        tokenCalls++
        failWith?.let { throw it }
        return photos.size * 1_000_003L xor (photos.maxOfOrNull { it.id } ?: 0L)
    }
}
