package com.ktu.aigaleri.data

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VectorCodecDecodeIntoTest {
    @Test
    fun decodeInto_equalsDecode_forRandomAndSpecialValues() {
        val rnd = Random(5)
        val special = floatArrayOf(0f, -0f, 1f, -1f, Float.MIN_VALUE, Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN)
        val vectors = listOf(FloatArray(512) { rnd.nextGaussian().toFloat() }, special, FloatArray(0), floatArrayOf(3.25f))
        for (v in vectors) {
            val blob = VectorCodec.encode(v)
            val out = FloatArray(v.size)
            VectorCodec.decodeInto(blob, out)
            val expected = VectorCodec.decode(blob)
            for (i in v.indices) assertEquals(expected[i].toRawBits(), out[i].toRawBits())
            for (i in v.indices) assertEquals(v[i].toRawBits(), out[i].toRawBits())
        }
    }

    @Test
    fun decodeInto_reusesBufferAndOverwritesAll() {
        val out = FloatArray(4) { 99f }
        VectorCodec.decodeInto(VectorCodec.encode(floatArrayOf(1f, 2f, 3f, 4f)), out)
        VectorCodec.decodeInto(VectorCodec.encode(floatArrayOf(-1f, -2f, -3f, -4f)), out)
        assertTrue(out.contentEquals(floatArrayOf(-1f, -2f, -3f, -4f)))
    }

    @Test
    fun decodeInto_sizeMismatch_isIllegalArgument() {
        for ((blobSize, outSize) in listOf(8 to 3, 12 to 2, 5 to 1, 0 to 1, 4 to 0)) {
            try {
                VectorCodec.decodeInto(ByteArray(blobSize), FloatArray(outSize))
                fail("$blobSize/$outSize")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
