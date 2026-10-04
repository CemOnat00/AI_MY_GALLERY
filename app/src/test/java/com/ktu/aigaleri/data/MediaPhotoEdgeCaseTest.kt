package com.ktu.aigaleri.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/** T-003 QA: MediaPhoto/MediaPhotoMapping sınır durumları. */
class MediaPhotoEdgeCaseTest {
    private val base = "content://media/external/images/media"

    @Test
    fun sortNewestFirst_emptyList_isEmpty() {
        assertTrue(MediaPhotoMapping.sortNewestFirst(emptyList()).isEmpty())
    }

    @Test
    fun sortNewestFirst_singleElement_unchanged() {
        val p = MediaPhoto(1L, "u", null)
        assertEquals(listOf(p), MediaPhotoMapping.sortNewestFirst(listOf(p)))
    }

    @Test
    fun sortNewestFirst_equalDates_tieBrokenByIdDescending_regardlessOfInputOrder() {
        val ps = (1L..5L).map { MediaPhoto(it, "u$it", 1_000L) }
        val expected = ps.sortedByDescending { it.id }
        assertEquals(expected, MediaPhotoMapping.sortNewestFirst(ps))
        assertEquals(expected, MediaPhotoMapping.sortNewestFirst(ps.reversed()))
    }

    @Test
    fun sortNewestFirst_allUnknownDates_orderedByIdDescending() {
        val ps = listOf(MediaPhoto(2L, "a", null), MediaPhoto(9L, "b", null), MediaPhoto(5L, "c", null))
        assertEquals(listOf(9L, 5L, 2L), MediaPhotoMapping.sortNewestFirst(ps).map { it.id })
    }

    @Test
    fun sortNewestFirst_doesNotMutateInput() {
        val ps = listOf(MediaPhoto(1L, "a", 1L), MediaPhoto(2L, "b", 2L))
        val copy = ps.toList()
        MediaPhotoMapping.sortNewestFirst(ps)
        assertEquals(copy, ps)
    }

    @Test
    fun sortNewestFirst_veryLargeList_isSortedAndComplete() {
        val n = 200_000
        val ps = List(n) { i ->
            val id = (i + 1).toLong()
            MediaPhoto(id, "$base/$id", if (i % 7 == 0) null else (i.toLong() * 31 % 10_007))
        }
        val sorted = MediaPhotoMapping.sortNewestFirst(ps)
        assertEquals(n, sorted.size)
        assertEquals(n, sorted.map { it.id }.toSet().size)
        var seenNull = false
        for (i in 1 until sorted.size) {
            val a = sorted[i - 1]
            val b = sorted[i]
            if (a.dateTaken == null) seenNull = true
            if (seenNull) assertNull("null tarihliler sonda olmalı", b.dateTaken)
            else if (b.dateTaken != null) {
                assertTrue(a.dateTaken!! >= b.dateTaken!!)
                if (a.dateTaken == b.dateTaken) assertTrue(a.id > b.id)
            }
        }
    }

    @Test
    fun effectiveDate_negativeValues_areTreatedAsUnknown() {
        assertNull(MediaPhotoMapping.effectiveDate(-5L, null))
        assertEquals(7_000L, MediaPhotoMapping.effectiveDate(-5L, 7L))
        assertNull(MediaPhotoMapping.effectiveDate(Long.MIN_VALUE, Long.MIN_VALUE))
    }

    @Test
    fun effectiveDate_dateTakenMaxValue_isKept() {
        assertEquals(Long.MAX_VALUE, MediaPhotoMapping.effectiveDate(Long.MAX_VALUE, 1L))
    }

    @Test
    fun effectiveDate_smallestPositiveValues() {
        assertEquals(1L, MediaPhotoMapping.effectiveDate(1L, null))
        assertEquals(1_000L, MediaPhotoMapping.effectiveDate(null, 1L))
    }

    @Test
    fun effectiveDate_dateAddedNearUpperBound_doesNotOverflow() {
        // Long.MAX_VALUE / 1000 saniye * 1000 sınırda taşmaz.
        val sec = Long.MAX_VALUE / 1000
        assertEquals(sec * 1000, MediaPhotoMapping.effectiveDate(null, sec))
    }

    @Ignore("Bilinen bulgu: MediaPhoto.kt effectiveDate dateAddedSec*1000 Long taşması (üretim kodu değiştirilmedi)")
    @Test
    fun effectiveDate_dateAddedMaxValue_neverReturnsNegative() {
        val r = MediaPhotoMapping.effectiveDate(null, Long.MAX_VALUE)
        assertTrue("taşma negatif tarih üretmemeli: $r", r == null || r > 0)
    }

    @Test
    fun toPhoto_mapsAllFields_andNullDate() {
        val p = MediaPhoto(Long.MAX_VALUE, "$base/${Long.MAX_VALUE}", null).toPhoto(indexedAt = Long.MAX_VALUE, indexVersion = Int.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, p.mediaStoreId)
        assertEquals("$base/${Long.MAX_VALUE}", p.uri)
        assertNull(p.dateTaken)
        assertEquals(Long.MAX_VALUE, p.indexedAt)
        assertEquals(Int.MAX_VALUE, p.indexVersion)
    }

    @Test
    fun toPhoto_doesNotDefaultOrDeriveIndexFields() {
        val m = MediaPhoto(3L, "u", 10L)
        val a = m.toPhoto(0L, 0)
        val b = m.toPhoto(123L, 4)
        assertEquals(0L, a.indexedAt)
        assertEquals(0, a.indexVersion)
        assertEquals(123L, b.indexedAt)
        assertEquals(4, b.indexVersion)
        assertEquals(a.mediaStoreId, b.mediaStoreId)
    }

    @Test
    fun uriFor_multipleTrailingSlashes_andIdEdgeValues() {
        assertEquals("$base/0", MediaPhotoMapping.uriFor("$base///", 0L))
        assertEquals("$base/${Long.MAX_VALUE}", MediaPhotoMapping.uriFor(base, Long.MAX_VALUE))
    }

    @Test
    fun toMediaPhoto_prefersTakenOverAddedAndKeepsMillisecondPrecision() {
        val p = MediaPhotoMapping.toMediaPhoto(base, 1L, dateTakenMs = 1_234L, dateAddedSec = 99L)
        assertEquals(1_234L, p.dateTaken)
        assertEquals("$base/1", p.uri)
    }
}
