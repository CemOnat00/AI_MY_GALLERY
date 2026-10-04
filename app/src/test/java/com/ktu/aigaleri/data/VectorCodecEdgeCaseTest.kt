package com.ktu.aigaleri.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/** T-002 QA: VectorCodec sınır durumları. */
class VectorCodecEdgeCaseTest {
    @Test
    fun emptyVector_encodesToEmptyBlob_andBack() {
        assertEquals(0, VectorCodec.encode(FloatArray(0)).size)
        assertEquals(0, VectorCodec.decode(ByteArray(0)).size)
    }

    @Test
    fun singleElement_roundTrips() {
        val out = VectorCodec.decode(VectorCodec.encode(floatArrayOf(-3.25f)))
        assertArrayEquals(floatArrayOf(-3.25f), out, 0f)
    }

    @Test
    fun nanAndInfinity_surviveRoundTripBitExact() {
        val v = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0f, Float.MIN_VALUE, Float.MAX_VALUE)
        val out = VectorCodec.decode(VectorCodec.encode(v))
        assertEquals(v.size, out.size)
        for (i in v.indices) assertEquals("index $i", v[i].toRawBits(), out[i].toRawBits())
        assertTrue(out[0].isNaN())
    }

    @Test
    fun decode_rejectsEveryMisalignedLength() {
        for (n in listOf(1, 2, 3, 5, 6, 7, 4093)) {
            assertThrows("size=$n", IllegalArgumentException::class.java) { VectorCodec.decode(ByteArray(n)) }
        }
    }

    @Test
    fun dimensionIsInferredFromBlobLength_notFixed() {
        for (n in listOf(1, 2, 3, 100, 512, 768, 1024)) {
            assertEquals(n, VectorCodec.decode(ByteArray(n * 4)).size)
        }
    }

    @Test
    fun veryLargeVector_roundTrips() {
        val v = FloatArray(1_000_000) { it * 0.001f }
        val blob = VectorCodec.encode(v)
        assertEquals(4_000_000, blob.size)
        assertArrayEquals(v, VectorCodec.decode(blob), 0f)
    }

    @Test
    fun encode_producesIndependentBlob() {
        val v = floatArrayOf(1f, 2f)
        val blob = VectorCodec.encode(v)
        v[0] = 99f
        assertEquals(1f, VectorCodec.decode(blob)[0], 0f)
    }

    @Test
    fun decode_doesNotMutateInput() {
        val blob = VectorCodec.encode(floatArrayOf(1f, 2f, 3f))
        val copy = blob.copyOf()
        VectorCodec.decode(blob)
        assertArrayEquals(copy, blob)
    }

    @Test
    fun decode_ofBigEndianBytes_isNotTheSameValue() {
        // 1.0f big-endian = 3F 80 00 00; little-endian okuyucu bunu 1.0 olarak görmemeli.
        val out = VectorCodec.decode(byteArrayOf(0x3F, 0x80.toByte(), 0, 0))
        assertTrue(out[0] != 1f)
    }
}
