package com.ktu.aigaleri.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T-002 QA: entity eşitlik/varsayılan davranışları (architecture.md Bölüm 5). */
class EntitiesTest {
    @Test
    fun embedding_equalsAndHashCode_coverAllFields() {
        val a = PhotoEmbedding(1, byteArrayOf(1, 2), "m1")
        assertEquals(a, a)
        assertNotEquals(a, PhotoEmbedding(2, byteArrayOf(1, 2), "m1"))
        assertNotEquals(a, PhotoEmbedding(1, byteArrayOf(1, 2), "m2"))
        assertNotEquals(a, PhotoEmbedding(1, byteArrayOf(1, 2, 0), "m1"))
        assertFalse(a.equals(null))
        assertFalse(a.equals("x"))
    }

    @Test
    fun embedding_emptyVectorsAreEqual_andHashStable() {
        val a = PhotoEmbedding(1, ByteArray(0), "m")
        val b = PhotoEmbedding(1, ByteArray(0), "m")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun embedding_turkishModelVersionComparedExactly() {
        val a = PhotoEmbedding(1, byteArrayOf(1), "çğıöşü-İ")
        assertEquals(a, PhotoEmbedding(1, byteArrayOf(1), "çğıöşü-İ"))
        assertNotEquals(a, PhotoEmbedding(1, byteArrayOf(1), "çğıöşü-I"))
        assertNotEquals(a, PhotoEmbedding(1, byteArrayOf(1), "çğıöşü-i"))
    }

    @Test
    fun indexedVector_equalsComparesBlobContent_andNullDate() {
        val a = IndexedVector(1, "u", null, byteArrayOf(1, 2))
        val b = IndexedVector(1, "u", null, byteArrayOf(1, 2))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, IndexedVector(1, "u", 0L, byteArrayOf(1, 2)))
        assertNotEquals(a, IndexedVector(1, "u", null, byteArrayOf(1, 3)))
        assertNotEquals(a, IndexedVector(2, "u", null, byteArrayOf(1, 2)))
        assertNotEquals(a, IndexedVector(1, "v", null, byteArrayOf(1, 2)))
        assertFalse(a.equals(null))
    }

    @Test
    fun indexState_singletonIdIsOne_andIsDefault() {
        assertEquals(1, IndexState.SINGLETON_ID)
        val s = IndexState(total = 0, processed = 0, lastRunAt = null)
        assertEquals(IndexState.SINGLETON_ID, s.id)
        assertNull(s.lastRunAt)
    }

    @Test
    fun photo_nullableDateTaken_andDataEquality() {
        val p = Photo(Long.MAX_VALUE, "content://x/ğ ü ş", null, 0L, 1)
        assertEquals(p, p.copy())
        assertNull(p.dateTaken)
        assertNotEquals(p, p.copy(mediaStoreId = 1))
    }
}
