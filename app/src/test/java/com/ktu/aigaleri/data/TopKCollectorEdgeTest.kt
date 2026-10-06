package com.ktu.aigaleri.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-008 QA: TopKCollector sınır durumları (k=1, k>N, eşit skorlar, +-0, sonsuz/NaN girdi, çok büyük k). */
class TopKCollectorEdgeTest {
    private fun collect(limit: Int, items: List<Pair<Long, Float>>): List<Pair<Long, Float>> {
        val c = TopKCollector(limit)
        for ((id, s) in items) c.offer(id, s, "content://media/$id", null)
        return c.drainSorted().map { it.photoId to it.score }
    }

    @Test
    fun kEqualsOne_keepsBestOnly_tieGoesToSmallestId() {
        assertEquals(listOf(4L to 0.9f), collect(1, listOf(1L to 0.1f, 4L to 0.9f, 2L to 0.5f)))
        assertEquals(listOf(2L to 0.9f), collect(1, listOf(7L to 0.9f, 2L to 0.9f, 5L to 0.9f)))
    }

    @Test
    fun kGreaterThanN_returnsAllSorted() {
        val got = collect(1_000, listOf(3L to 0.2f, 1L to 0.8f, 2L to 0.2f))
        assertEquals(listOf(1L to 0.8f, 2L to 0.2f, 3L to 0.2f), got)
    }

    @Test
    fun allEqualScores_keepsSmallestIds_inAscendingOrder_regardlessOfArrival() {
        val ids = (1L..300L).toList()
        for (arrival in listOf(ids, ids.reversed(), ids.shuffled(java.util.Random(9)))) {
            val got = collect(10, arrival.map { it to 0.25f })
            assertEquals((1L..10L).toList(), got.map { it.first })
        }
    }

    @Test
    fun negativeAndPositiveZero_areEqual_andReportedAsPositiveZero() {
        val got = collect(2, listOf(5L to -0f, 2L to 0f, 9L to -0f))
        assertEquals(listOf(2L, 5L), got.map { it.first })
        for ((_, s) in got) assertEquals(0, java.lang.Float.floatToRawIntBits(s)) // -0.0 normalize edilir
    }

    @Test
    fun infiniteScores_orderCorrectly() {
        val got = collect(
            3,
            listOf(1L to Float.NEGATIVE_INFINITY, 2L to Float.POSITIVE_INFINITY, 3L to 0.5f, 4L to Float.POSITIVE_INFINITY),
        )
        assertEquals(listOf(2L, 4L, 3L), got.map { it.first })
    }

    @Test
    fun nanInput_doesNotThrow_andNeverExceedsLimit() {
        // Sözleşme: çağıran NaN'ı süzer (RoomSearchRepository süzer). Yine de toplayıcı çökmemeli.
        val c = TopKCollector(2)
        for (i in 1L..50L) c.offer(i, if (i % 3 == 0L) Float.NaN else i / 100f, "u", null)
        assertTrue(c.size <= 2)
        assertTrue(c.drainSorted().size <= 2)
    }

    @Test
    fun largeK_manyOffers_isSortedAndComplete() {
        val n = 20_000
        val c = TopKCollector(Int.MAX_VALUE)
        for (i in 1..n) c.offer(i.toLong(), ((i * 7919L) % 1000L) / 1000f, "u", null)
        val out = c.drainSorted()
        assertEquals(n, out.size)
        for (i in 1 until out.size) {
            val a = out[i - 1]
            val b = out[i]
            assertTrue(a.score > b.score || (a.score == b.score && a.photoId < b.photoId))
        }
    }

    @Test
    fun extremeIds_negativeAndLongMax_tieBreakStillAscending() {
        val got = collect(3, listOf(Long.MAX_VALUE to 0.5f, -5L to 0.5f, 0L to 0.5f, Long.MIN_VALUE + 1 to 0.5f))
        assertEquals(listOf(Long.MIN_VALUE + 1, -5L, 0L), got.map { it.first })
    }
}
