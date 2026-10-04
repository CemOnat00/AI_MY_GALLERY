package com.ktu.aigaleri.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VectorCodecTest {
    @Test
    fun roundTrip_preservesValues_forAnyDimension() {
        for (dim in listOf(0, 1, 7, 512, 768)) {
            val v = FloatArray(dim) { (it - dim / 2) * 0.37f }
            val blob = VectorCodec.encode(v)
            assertEquals(dim * 4, blob.size)
            assertArrayEquals(v, VectorCodec.decode(blob), 0f)
        }
    }

    @Test
    fun encode_isLittleEndian() {
        assertArrayEquals(byteArrayOf(0, 0, -128, 63), VectorCodec.encode(floatArrayOf(1f)))
    }

    @Test
    fun decode_rejectsMisalignedBlob() {
        assertThrows(IllegalArgumentException::class.java) { VectorCodec.decode(ByteArray(5)) }
    }

    @Test
    fun embedding_equalsComparesBlobContent() {
        val a = PhotoEmbedding(1, byteArrayOf(1, 2), "m1")
        assertEquals(a, PhotoEmbedding(1, byteArrayOf(1, 2), "m1"))
        assertEquals(a.hashCode(), PhotoEmbedding(1, byteArrayOf(1, 2), "m1").hashCode())
        assertNotEquals(a, PhotoEmbedding(1, byteArrayOf(1, 3), "m1"))
    }
}
