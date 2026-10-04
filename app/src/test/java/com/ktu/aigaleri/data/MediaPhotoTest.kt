package com.ktu.aigaleri.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaPhotoTest {
    private val base = "content://media/external/images/media"

    @Test
    fun toPhoto_mapsFieldsToEntity() {
        val photo = MediaPhoto(42L, "$base/42", 1_700_000_000_000L).toPhoto(indexedAt = 5L, indexVersion = 2)
        assertEquals(Photo(42L, "$base/42", 1_700_000_000_000L, 5L, 2), photo)
    }

    @Test
    fun toPhoto_keepsNullDateTaken() {
        assertNull(MediaPhoto(1L, "content://x/1", null).toPhoto(0L, 1).dateTaken)
    }

    @Test
    fun effectiveDate_prefersDateTaken() {
        assertEquals(1_000L, MediaPhotoMapping.effectiveDate(1_000L, 5L))
    }

    @Test
    fun effectiveDate_nullOrZeroTaken_fallsBackToDateAddedSecondsTimes1000() {
        assertEquals(5_000L, MediaPhotoMapping.effectiveDate(null, 5L))
        assertEquals(5_000L, MediaPhotoMapping.effectiveDate(0L, 5L))
    }

    @Test
    fun effectiveDate_unknownBoth_isNull() {
        assertNull(MediaPhotoMapping.effectiveDate(null, null))
        assertNull(MediaPhotoMapping.effectiveDate(0L, 0L))
        assertNull(MediaPhotoMapping.effectiveDate(null, -1L))
    }

    @Test
    fun uriFor_appendsId_withOrWithoutTrailingSlash() {
        assertEquals("$base/7", MediaPhotoMapping.uriFor(base, 7L))
        assertEquals("$base/7", MediaPhotoMapping.uriFor("$base/", 7L))
    }

    @Test
    fun toMediaPhoto_combinesIdUriAndDate() {
        assertEquals(
            MediaPhoto(9L, "$base/9", 3_000L),
            MediaPhotoMapping.toMediaPhoto(base, 9L, dateTakenMs = null, dateAddedSec = 3L),
        )
    }

    @Test
    fun sortNewestFirst_ordersByDateThenIdDescending_unknownLast() {
        val a = MediaPhoto(1L, "u1", 100L)
        val b = MediaPhoto(2L, "u2", 300L)
        val c = MediaPhoto(3L, "u3", null)
        val d = MediaPhoto(4L, "u4", 300L)
        assertEquals(listOf(d, b, a, c), MediaPhotoMapping.sortNewestFirst(listOf(a, b, c, d)))
    }
}
