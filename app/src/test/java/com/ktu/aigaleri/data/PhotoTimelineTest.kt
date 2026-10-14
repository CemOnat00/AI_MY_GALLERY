package com.ktu.aigaleri.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PhotoTimelineTest {
    private fun PhotoTimeline.ids() = List(size) { idAt(it) }

    @Test
    fun unsortedInput_isOrderedNewestFirst_unknownLast_tiesByIdDescending() {
        val t = PhotoTimeline.ofUnsorted(longArrayOf(1, 2, 3, 4, 5, 6), longArrayOf(100, -1, 300, 200, 100, 0))
        // 300, 200, 100(id5), 100(id1), 0, -1
        assertEquals(listOf(3L, 4L, 5L, 1L, 6L, 2L), t.ids())
        assertEquals(300L, t.dateAt(0))
        assertEquals(0L, t.dateAt(4))
        assertNull(t.dateAt(5))
    }

    @Test
    fun negativeDates_areUnknown() {
        val t = PhotoTimeline.ofUnsorted(longArrayOf(1, 2), longArrayOf(-5, 7))
        assertEquals(listOf(2L, 1L), t.ids())
        assertNull(t.dateAt(1))
    }

    @Test
    fun alreadySorted_isKeptWithoutCopyingOrder() {
        val ids = longArrayOf(9, 8, 7)
        val t = PhotoTimeline.ofUnsorted(ids, longArrayOf(90, 80, 70))
        assertEquals(listOf(9L, 8L, 7L), t.ids())
    }

    @Test
    fun emptyAndSingle() {
        assertEquals(0, PhotoTimeline.ofUnsorted(LongArray(0), LongArray(0)).size)
        assertEquals(listOf(4L), PhotoTimeline.ofUnsorted(longArrayOf(4), longArrayOf(1)).ids())
    }

    @Test
    fun randomLargeInput_matchesReferenceSort() {
        val rnd = Random(42)
        val n = 20_001
        val ids = LongArray(n) { it + 1L }.also { a -> for (i in a.size - 1 downTo 1) { val j = rnd.nextInt(i + 1); val t = a[i]; a[i] = a[j]; a[j] = t } }
        val dates = LongArray(n) { if (rnd.nextInt(10) == 0) -1L else rnd.nextInt(500).toLong() }
        val reference = ids.indices
            .sortedWith(compareByDescending<Int> { dates[it] }.thenByDescending { ids[it] })
            .map { ids[it] }
        val t = PhotoTimeline.ofUnsorted(ids.copyOf(), dates.copyOf())
        assertEquals(reference, t.ids())
        for (i in 1 until n) assertTrue((t.dateAt(i - 1) ?: -1) >= (t.dateAt(i) ?: -1))
    }

    @Test
    fun fromPhotos_orders_andEqualsByContent() {
        val photos = listOf(MediaPhoto(1, "u1", null), MediaPhoto(2, "u2", 20), MediaPhoto(3, "u3", 20))
        val t = PhotoTimeline.fromPhotos(photos)
        assertEquals(listOf(3L, 2L, 1L), t.ids())
        assertEquals(t, PhotoTimeline.fromPhotos(photos.reversed()))
        assertEquals(t.hashCode(), PhotoTimeline.fromPhotos(photos.reversed()).hashCode())
    }

    @Test
    fun mismatchedArrays_areRejected() {
        assertTrue(runCatching { PhotoTimeline(longArrayOf(1, 2), longArrayOf(1)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { PhotoTimeline.ofUnsorted(longArrayOf(1, 2), longArrayOf(1)) }.exceptionOrNull() is IllegalArgumentException)
    }
}
