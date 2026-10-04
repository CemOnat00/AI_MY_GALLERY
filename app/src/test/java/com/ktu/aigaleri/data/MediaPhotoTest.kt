package com.ktu.aigaleri.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaPhotoTest {
    @Test
    fun toPhoto_mapsFieldsToEntity() {
        val photo = MediaPhoto(42L, "content://media/external/images/media/42", 1_700_000_000_000L)
            .toPhoto(indexedAt = 5L, indexVersion = 2)
        assertEquals(Photo(42L, "content://media/external/images/media/42", 1_700_000_000_000L, 5L, 2), photo)
    }

    @Test
    fun toPhoto_keepsNullDateTaken() {
        assertNull(MediaPhoto(1L, "content://x/1", null).toPhoto(0L, 1).dateTaken)
    }

    @Test
    fun fakeSource_returnsPhotosAndCount() = runTest {
        val list = listOf(MediaPhoto(1L, "content://x/1", null), MediaPhoto(2L, "content://x/2", 10L))
        val source: MediaPhotoSource = FakeMediaPhotoSource(list)
        assertEquals(2, source.count())
        assertEquals(list, source.loadPhotos())
    }

    @Test
    fun fakeSource_propagatesSecurityException() = runTest {
        val source = FakeMediaPhotoSource(failWith = SecurityException())
        assertThrows(SecurityException::class.java) { kotlinx.coroutines.runBlocking { source.count() } }
    }
}
