package com.ktu.aigaleri.ml

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class EmbeddingMathTest {
    private val eps = 1e-6f

    @Test
    fun meanPool_ignoresMaskedPositions() {
        val hidden = floatArrayOf(1f, 2f, 3f, 3f, 4f, 5f, 100f, 100f, 100f)
        val out = EmbeddingMath.meanPool(hidden, longArrayOf(1, 1, 0), seqLen = 3, hiddenSize = 3)
        assertArrayEquals(floatArrayOf(2f, 3f, 4f), out, eps)
    }

    @Test
    fun meanPool_allOnesIsPlainMean() {
        val hidden = floatArrayOf(1f, 0f, 3f, 4f)
        assertArrayEquals(floatArrayOf(2f, 2f), EmbeddingMath.meanPool(hidden, longArrayOf(1, 1), 2, 2), eps)
    }

    @Test
    fun meanPool_allMasked_givesZeros() {
        val out = EmbeddingMath.meanPool(floatArrayOf(5f, 6f), longArrayOf(0), 1, 2)
        assertArrayEquals(floatArrayOf(0f, 0f), out, 0f)
    }

    @Test
    fun meanPool_rejectsShapeMismatch() {
        try {
            EmbeddingMath.meanPool(floatArrayOf(1f, 2f, 3f), longArrayOf(1, 1), 2, 2)
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun dense_isRowMajorOutByInWithoutBias() {
        // W = [[1,0,1],[0,2,0]], x = [1,2,3] -> [4,4]
        val w = floatArrayOf(1f, 0f, 1f, 0f, 2f, 0f)
        assertArrayEquals(floatArrayOf(4f, 4f), EmbeddingMath.denseNoBias(w, floatArrayOf(1f, 2f, 3f), 2, 3), eps)
    }

    @Test
    fun dense_zeroInputGivesZero_noBias() {
        val w = floatArrayOf(1f, 2f, 3f, 4f)
        assertArrayEquals(floatArrayOf(0f, 0f), EmbeddingMath.denseNoBias(w, floatArrayOf(0f, 0f), 2, 2), 0f)
    }

    @Test
    fun dense_rejectsWrongSizes() {
        try {
            EmbeddingMath.denseNoBias(floatArrayOf(1f, 2f), floatArrayOf(1f, 2f), 2, 2)
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun l2Normalize_givesUnitVector() {
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), EmbeddingMath.l2Normalize(floatArrayOf(3f, 4f)), eps)
        val v = EmbeddingMath.l2Normalize(floatArrayOf(1f, -2f, 3f, -4f, 5f))
        assertEquals(1.0, v.sumOf { it.toDouble() * it }, 1e-6)
    }

    @Test
    fun l2Normalize_doesNotMutateInput() {
        val input = floatArrayOf(3f, 4f)
        EmbeddingMath.l2Normalize(input)
        assertArrayEquals(floatArrayOf(3f, 4f), input, 0f)
    }

    @Test
    fun l2Normalize_zeroOrNonFinite_isRejected() {
        for (bad in listOf(floatArrayOf(0f, 0f), floatArrayOf(Float.NaN, 1f), floatArrayOf(Float.POSITIVE_INFINITY, 1f))) {
            try {
                EmbeddingMath.l2Normalize(bad)
                fail()
            } catch (_: IllegalStateException) {
            }
        }
    }

    @Test
    fun readFloat32LittleEndian_decodesBytes() {
        val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        bb.putFloat(1.0f).putFloat(-2.5f)
        assertArrayEquals(floatArrayOf(1f, -2.5f), EmbeddingMath.readFloat32LittleEndian(bb.array(), 2), 0f)
        // Açık bayt: 1.0f little-endian = 00 00 80 3F
        assertArrayEquals(
            floatArrayOf(1f),
            EmbeddingMath.readFloat32LittleEndian(byteArrayOf(0, 0, 0x80.toByte(), 0x3F), 1),
            0f,
        )
    }

    @Test
    fun readFloat32LittleEndian_rejectsWrongLength() {
        try {
            EmbeddingMath.readFloat32LittleEndian(ByteArray(7), 2)
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }
}
