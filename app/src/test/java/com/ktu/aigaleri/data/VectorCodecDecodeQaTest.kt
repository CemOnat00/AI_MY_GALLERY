package com.ktu.aigaleri.data

import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * T-025 QA: [VectorCodec.decodeInto] (toplu LITTLE_ENDIAN FloatBuffer) bayt bayt Long kaydırmalı BAĞIMSIZ referansla
 * bit-bit aynı mı: tüm üs alanları, özel bit desenleri (NaN yükleri, -0.0, alt-normal, sonsuz), rastgele milyonlarca bit,
 * tampon yeniden kullanımı, yanlış uzunluk, eşzamanlı kullanım, encode/decode gidiş-dönüş.
 */
class VectorCodecDecodeQaTest {
    private fun refDecode(blob: ByteArray, out: FloatArray) {
        for (i in out.indices) {
            var bits = 0L
            for (k in 0 until 4) bits = bits or ((blob[i * 4 + k].toLong() and 0xFF) shl (8 * k))
            out[i] = Float.fromBits(bits.toInt())
        }
    }

    private fun bytesOf(bits: IntArray): ByteArray {
        val b = ByteArray(bits.size * 4)
        for (i in bits.indices) for (k in 0 until 4) b[i * 4 + k] = (bits[i] ushr (8 * k)).toByte()
        return b
    }

    private fun assertBitIdentical(bits: IntArray, label: String) {
        val blob = bytesOf(bits)
        val copy = blob.copyOf()
        val out = FloatArray(bits.size) { 1.5f }
        VectorCodec.decodeInto(blob, out)
        val ref = FloatArray(bits.size)
        refDecode(blob, ref)
        for (i in bits.indices) {
            assertEquals("$label i=$i bits=${Integer.toHexString(bits[i])}", ref[i].toRawBits(), out[i].toRawBits())
            assertEquals("$label i=$i beklenen ham bit", bits[i], out[i].toRawBits())
        }
        assertArrayEquals("$label: girdi BLOB değişmemeli", copy, blob)
    }

    @Test
    fun everyExponentSignAndMantissaPattern_bitIdentical() {
        val mantissas = intArrayOf(0, 1, 2, 0x400000, 0x7FFFFF, 0x2AAAAA, 0x555555, 0x000FFF, 0x200000, 0x600000)
        val list = ArrayList<Int>()
        for (sign in 0..1) for (exp in 0..255) for (m in mantissas) list += (sign shl 31) or (exp shl 23) or m
        assertBitIdentical(list.toIntArray(), "üs taraması") // NaN yükleri (üs 255, m != 0) dahil
    }

    @Test
    fun specialPatterns_nanPayloads_negativeZero_subnormals_infinities() {
        val special = intArrayOf(
            0x7FC00000, 0x7FC00001, 0x7F800001, 0x7FA00000, 0x7FBFFFFF, 0x7FFFFFFF, 0xFFC00000.toInt(), 0xFF800001.toInt(),
            0xFFBFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0x7F800000, 0xFF800000.toInt(), 0x00000000, 0x80000000.toInt(), 0x00000001,
            0x80000001.toInt(), 0x007FFFFF, 0x807FFFFF.toInt(), 0x00800000, 0x80800000.toInt(), 0x7F7FFFFF, 0xFF7FFFFF.toInt(),
            0x3F800000, 0xBF800000.toInt(), 0x01010101, 0xFEFEFEFE.toInt(), 0x80808080.toInt(), 0x7F7F7F7F, 0x12345678, 0x87654321.toInt(),
        )
        assertBitIdentical(special, "özel")
        for (b in special) assertBitIdentical(intArrayOf(b), "tek ${Integer.toHexString(b)}")
        // her bayt konumu 0x80+ (işaret genişletme hatası yakalanır): 0xFF, 0x80, 0x7F, 0x00 kombinasyonları
        val bytes = intArrayOf(0x00, 0x01, 0x7F, 0x80, 0xFF)
        val all = ArrayList<Int>()
        for (a in bytes) for (b in bytes) for (c in bytes) for (d in bytes) all += a or (b shl 8) or (c shl 16) or (d shl 24)
        assertBitIdentical(all.toIntArray(), "bayt kombinasyonları")
    }

    @Test
    fun randomBitPatterns_manyVectors_variousDims() {
        val rnd = Random(0xDEC0DE25L)
        for (dim in listOf(1, 2, 3, 4, 5, 7, 8, 31, 32, 33, 128, 512, 513)) {
            repeat(if (dim >= 128) 400 else 2000) {
                assertBitIdentical(IntArray(dim) { rnd.nextInt() }, "rastgele dim=$dim")
            }
        }
        // gerçekçi: normalize float32 değerleri
        repeat(500) {
            val v = randomUnit(rnd, 512)
            assertBitIdentical(IntArray(512) { v[it].toRawBits() }, "birim")
        }
    }

    @Test
    fun dimZero_isNoOp_andLengthMismatch_isIllegalArgument_leavingOutUntouched() {
        VectorCodec.decodeInto(ByteArray(0), FloatArray(0)) // hata yok
        val out = FloatArray(4) { 7f }
        for (n in listOf(0, 1, 3, 4, 12, 15, 17, 19, 20, 32, 64)) {
            try {
                VectorCodec.decodeInto(ByteArray(n), out)
                fail("n=$n")
            } catch (_: IllegalArgumentException) {
            }
            assertTrue("n=$n out değişti", out.all { it == 7f })
        }
        try {
            VectorCodec.decodeInto(ByteArray(4), FloatArray(0))
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun reusedScratch_acrossDifferentContents_hasNoResidue_andMatchesDecode() {
        val rnd = Random(9)
        val out = FloatArray(64)
        repeat(500) {
            val bits = IntArray(64) { if (rnd.nextInt(4) == 0) 0x7FC00000 else rnd.nextInt() }
            val blob = bytesOf(bits)
            VectorCodec.decodeInto(blob, out)
            val fresh = VectorCodec.decode(blob)
            for (i in 0 until 64) {
                assertEquals(bits[i], out[i].toRawBits())
                assertEquals(fresh[i].toRawBits(), out[i].toRawBits())
            }
        }
    }

    @Test
    fun encodeThenDecodeInto_roundTrip_preservesBitsIncludingNaNPayloads() {
        val rnd = Random(31)
        val bits = IntArray(2048) { rnd.nextInt() }
        bits[0] = 0x7F800001; bits[1] = 0xFFFFFFFF.toInt(); bits[2] = 0x80000000.toInt(); bits[3] = 0x00000001
        val v = FloatArray(bits.size) { Float.fromBits(bits[it]) }
        val out = FloatArray(bits.size)
        VectorCodec.decodeInto(VectorCodec.encode(v), out)
        for (i in bits.indices) assertEquals("i=$i ${Integer.toHexString(bits[i])}", bits[i], out[i].toRawBits())
    }

    @Test
    fun concurrentDecodeOfSharedBlob_intoSeparateBuffers_isDeterministic() {
        val rnd = Random(77)
        val bits = IntArray(512) { rnd.nextInt() }
        val blob = bytesOf(bits)
        val error = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)
        val threads = (0 until 6).map {
            Thread {
                try {
                    val out = FloatArray(512)
                    start.await()
                    repeat(3000) {
                        VectorCodec.decodeInto(blob, out)
                        for (i in 0 until 512) if (out[i].toRawBits() != bits[i]) throw AssertionError("i=$i")
                    }
                } catch (t: Throwable) {
                    error.compareAndSet(null, t)
                }
            }.also { it.start() }
        }
        start.countDown()
        threads.forEach { it.join() }
        error.get()?.let { throw it }
    }
}
