package com.ktu.aigaleri.data

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TopKCollectorTest {
    private fun collect(limit: Int, items: List<Pair<Long, Float>>): List<Pair<Long, Float>> {
        val c = TopKCollector(limit)
        for ((id, s) in items) c.offer(id, s, "content://media/$id", null)
        return c.drainSorted().map { it.photoId to it.score }
    }

    private fun reference(limit: Int, items: List<Pair<Long, Float>>) =
        items.sortedWith(compareByDescending<Pair<Long, Float>> { it.second }.thenBy { it.first }).take(limit)

    @Test
    fun matchesFullSort_forRandomSeededInputs_andAllLimits() {
        val rnd = Random(42)
        // az sayıda ayrık skor değeri -> çok sayıda eşitlik
        val items = (1L..500L).map { it to (rnd.nextInt(20) / 10f - 1f) }.shuffled(Random(7))
        for (limit in listOf(1, 2, 3, 10, 49, 50, 51, 499, 500, 501, 100_000)) {
            assertEquals("limit=$limit", reference(limit, items), collect(limit, items))
        }
    }

    @Test
    fun resultIsIndependentOfArrivalOrder() {
        val items = (1L..200L).map { it to ((it * 37) % 11) / 10f }
        val expected = reference(25, items)
        for (seed in 1L..5L) assertEquals(expected, collect(25, items.shuffled(Random(seed))))
        assertEquals(expected, collect(25, items.sortedBy { it.first }))
        assertEquals(expected, collect(25, items.sortedByDescending { it.first }))
    }

    @Test
    fun tieAtHeapBoundary_keepsSmallerPhotoId() {
        // limit 2: id 9 ve 3 aynı skorda ikinci sıraya yarışır; küçük id kalır
        assertEquals(listOf(1L to 0.9f, 3L to 0.5f), collect(2, listOf(1L to 0.9f, 9L to 0.5f, 3L to 0.5f)))
        assertEquals(listOf(1L to 0.9f, 3L to 0.5f), collect(2, listOf(1L to 0.9f, 3L to 0.5f, 9L to 0.5f)))
    }

    @Test
    fun negativeZeroEqualsZero_orderedByPhotoId() {
        val got = collect(3, listOf(3L to 0f, 1L to -0f, 2L to 0f))
        assertEquals(listOf(1L, 2L, 3L), got.map { it.first })
    }

    @Test
    fun sizeNeverExceedsLimit_andDrainEmpties() {
        val c = TopKCollector(3)
        for (i in 1L..100L) {
            c.offer(i, i.toFloat(), "u", null)
            assertTrue(c.size <= 3)
        }
        assertEquals(listOf(100L, 99L, 98L), c.drainSorted().map { it.photoId })
        assertEquals(0, c.size)
        assertEquals(emptyList<Any>(), c.drainSorted())
    }

    @Test
    fun emptyCollector_drainsEmpty_andInvalidLimitRejected() {
        assertEquals(emptyList<Any>(), TopKCollector(5).drainSorted())
        for (bad in listOf(0, -3)) {
            try {
                TopKCollector(bad)
                fail()
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun hugeLimit_doesNotPreallocateByLimit() {
        val c = TopKCollector(Int.MAX_VALUE)
        c.offer(1, 0.5f, "u", null)
        assertEquals(1, c.drainSorted().size)
    }
}
