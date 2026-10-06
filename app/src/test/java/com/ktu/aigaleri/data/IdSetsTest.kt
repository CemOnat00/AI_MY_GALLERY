package com.ktu.aigaleri.data

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class IdSetsTest {
    @Test
    fun difference_basics() {
        assertArrayEquals(longArrayOf(1, 5), IdSets.difference(longArrayOf(1, 3, 5, 7), longArrayOf(3, 7, 9)))
        assertArrayEquals(longArrayOf(), IdSets.difference(longArrayOf(), longArrayOf(1)))
        assertArrayEquals(longArrayOf(2, 4), IdSets.difference(longArrayOf(2, 4), longArrayOf()))
        assertArrayEquals(longArrayOf(), IdSets.difference(longArrayOf(1, 2), longArrayOf(1, 2)))
        assertArrayEquals(longArrayOf(Long.MAX_VALUE), IdSets.difference(longArrayOf(0, Long.MAX_VALUE), longArrayOf(0)))
    }

    @Test
    fun mergeDescending_interleaves() {
        assertArrayEquals(longArrayOf(9, 7, 5, 3, 2, 1), IdSets.mergeDescending(longArrayOf(1, 5, 9), longArrayOf(2, 3, 7)))
        assertArrayEquals(longArrayOf(3, 2, 1), IdSets.mergeDescending(longArrayOf(1, 2, 3), longArrayOf()))
        assertArrayEquals(longArrayOf(3, 2, 1), IdSets.mergeDescending(longArrayOf(), longArrayOf(1, 2, 3)))
        assertArrayEquals(longArrayOf(), IdSets.mergeDescending(longArrayOf(), longArrayOf()))
    }
}
