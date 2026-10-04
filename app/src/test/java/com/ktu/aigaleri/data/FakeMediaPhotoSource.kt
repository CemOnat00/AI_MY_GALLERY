package com.ktu.aigaleri.data

/** Testler için sahte MediaStore kaynağı. */
class FakeMediaPhotoSource(
    var photos: List<MediaPhoto> = emptyList(),
    var failWith: Throwable? = null,
) : MediaPhotoSource {
    var countCalls = 0
        private set

    override suspend fun count(): Int {
        countCalls++
        failWith?.let { throw it }
        return photos.size
    }

    override suspend fun loadPhotos(): List<MediaPhoto> {
        failWith?.let { throw it }
        return photos
    }
}
