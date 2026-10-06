package com.ktu.aigaleri.ml

import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** T-007 QA: EmbeddingMath ve TextEmbeddingPipeline sayısal sınır durumları. */
class EmbeddingMathEdgeCaseTest {
    private val h = TextEmbeddingPipeline.HIDDEN_SIZE
    private val o = TextEmbeddingPipeline.OUTPUT_DIM

    private fun failsWithState(block: () -> Unit) {
        try {
            block()
            fail("IllegalStateException bekleniyordu")
        } catch (_: IllegalStateException) {
        }
    }

    private fun failsWithArg(block: () -> Unit) {
        try {
            block()
            fail("IllegalArgumentException bekleniyordu")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun l2_zeroEmptyAndNonFinite_areRejected() {
        failsWithState { EmbeddingMath.l2Normalize(FloatArray(512)) }
        failsWithState { EmbeddingMath.l2Normalize(FloatArray(0)) }
        failsWithState { EmbeddingMath.l2Normalize(floatArrayOf(Float.NaN)) }
        failsWithState { EmbeddingMath.l2Normalize(floatArrayOf(1f, Float.NEGATIVE_INFINITY)) }
        failsWithState { EmbeddingMath.l2Normalize(floatArrayOf(-0f, 0f)) }
    }

    @Test
    fun l2_extremeMagnitudes_stayUnitLength() {
        for (scale in listOf(1e-40f, 1e-20f, 1f, 1e20f, 3e38f)) {
            val v = EmbeddingMath.l2Normalize(floatArrayOf(scale, -scale, 2 * scale.coerceAtMost(1.5e38f)))
            assertEquals("scale=$scale", 1.0, v.sumOf { it.toDouble() * it }, 1e-3)
            assertTrue(v.all { it.isFinite() })
        }
        assertArrayEquals(floatArrayOf(-1f), EmbeddingMath.l2Normalize(floatArrayOf(-5f)), 0f)
    }

    @Test
    fun meanPool_singleToken_isIdentity() {
        val hidden = FloatArray(h) { it * 0.5f - 100f }
        assertArrayEquals(hidden, EmbeddingMath.meanPool(hidden, longArrayOf(1), 1, h), 1e-6f)
    }

    @Test
    fun meanPool_fullyMasked_isZeros_andMaskedNaNDoesNotLeak() {
        val hidden = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, 1f, 2f)
        assertArrayEquals(floatArrayOf(0f, 0f), EmbeddingMath.meanPool(hidden, longArrayOf(0, 0), 2, 2), 0f)
        // Yalnız maskeli satırda NaN/Inf: sonuç sonlu kalır.
        assertArrayEquals(floatArrayOf(1f, 2f), EmbeddingMath.meanPool(hidden, longArrayOf(0, 1), 2, 2), 0f)
    }

    @Test
    fun meanPool_nonBinaryAndInterleavedMask() {
        val hidden = floatArrayOf(2f, 4f, 100f, 6f)
        // maske 2 -> 1 gibi sayılır (yalnız 0 dışlar); ortadaki 0 satırı atlanır.
        assertArrayEquals(floatArrayOf(36f), EmbeddingMath.meanPool(hidden, longArrayOf(2, 0, 1, 1), 4, 1), 1e-5f)
    }

    @Test
    fun meanPool_unmaskedNaNOrInf_propagates_andL2Rejects() {
        val nan = EmbeddingMath.meanPool(floatArrayOf(Float.NaN, 1f), longArrayOf(1), 1, 2)
        assertTrue(nan[0].isNaN())
        failsWithState { EmbeddingMath.l2Normalize(nan) }
        val inf = EmbeddingMath.meanPool(floatArrayOf(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1f, 1f), longArrayOf(1, 1), 2, 2)
        failsWithState { EmbeddingMath.l2Normalize(inf) }
    }

    @Test
    fun meanPool_invalidShapes() {
        failsWithArg { EmbeddingMath.meanPool(FloatArray(0), LongArray(0), 0, 4) }
        failsWithArg { EmbeddingMath.meanPool(FloatArray(0), LongArray(1), 1, 0) }
        failsWithArg { EmbeddingMath.meanPool(FloatArray(4), LongArray(3), 2, 2) }
        failsWithArg { EmbeddingMath.denseNoBias(FloatArray(4), FloatArray(3), 2, 2) }
        failsWithArg { EmbeddingMath.denseNoBias(FloatArray(5), FloatArray(2), 2, 2) }
    }

    @Test
    fun meanPool_and_dense_matchNaiveReferenceAtRealSizes() {
        val rnd = Random(7)
        val seq = 128
        val hidden = FloatArray(seq * h) { rnd.nextGaussian().toFloat() }
        val mask = LongArray(seq) { if (it < 40) 1L else 0L }
        val pooled = EmbeddingMath.meanPool(hidden, mask, seq, h)
        for (j in 0 until h) {
            var s = 0.0
            for (t in 0 until 40) s += hidden[t * h + j]
            assertEquals(s / 40, pooled[j].toDouble(), 1e-5)
        }
        val w = FloatArray(o * h) { rnd.nextGaussian().toFloat() * 0.05f }
        val out = EmbeddingMath.denseNoBias(w, pooled, o, h)
        assertEquals(o, out.size)
        for (i in listOf(0, 1, 255, 511)) {
            var s = 0.0
            for (j in 0 until h) s += w[i * h + j].toDouble() * pooled[j]
            assertEquals(s, out[i].toDouble(), 1e-4)
        }
        assertEquals(1.0, EmbeddingMath.l2Normalize(out).sumOf { it.toDouble() * it }, 1e-5)
    }

    @Test
    fun readFloat32_zeroCountAndSpecialValues() {
        assertArrayEquals(FloatArray(0), EmbeddingMath.readFloat32LittleEndian(ByteArray(0), 0), 0f)
        val nan = EmbeddingMath.readFloat32LittleEndian(byteArrayOf(0, 0, 0xC0.toByte(), 0x7F), 1)
        assertTrue(nan[0].isNaN())
        failsWithArg { EmbeddingMath.readFloat32LittleEndian(ByteArray(8), 1) }
    }

    // --- TextEmbeddingPipeline: sahte runner ile sözleşme ---

    private val tokens = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "kedi")
    private val tokenizer = WordPieceTokenizer(tokens.withIndex().associate { it.value to it.index })
    private val identity = FloatArray(o * h).also { for (i in 0 until o) it[i * h + i] = 1f }

    @Test
    fun pipeline_hugeQuery_feedsAtMost128Tokens_withAllOnesMask() {
        var n = -1
        var maskOk = false
        val runner = HiddenStateRunner { ids, mask ->
            n = ids.size
            maskOk = mask.size == ids.size && mask.all { it == 1L }
            FloatArray(ids.size * h) { 1f }
        }
        val out = TextEmbeddingPipeline(tokenizer, identity, runner).embed("kedi ".repeat(1000))
        assertEquals(128, n)
        assertTrue(maskOk)
        assertEquals(o, out.size)
        assertEquals(1.0, out.sumOf { it.toDouble() * it }, 1e-5)
    }

    @Test
    fun pipeline_emptyNormalizedQuery_stillWorksOnSpecialTokensOnly() {
        // Doğrulama QueryPreprocessor'da; ardışık düzen kendisi boş metinde çökmemeli ([CLS][SEP]).
        var ids2: LongArray? = null
        val runner = HiddenStateRunner { ids, _ -> ids2 = ids; FloatArray(ids.size * h) { 1f } }
        val out = TextEmbeddingPipeline(tokenizer, identity, runner).embed("")
        assertArrayEquals(longArrayOf(2, 3), ids2)
        assertEquals(o, out.size)
    }

    @Test
    fun pipeline_degenerateModelOutputs_areRejectedNotReturned() {
        val zero = HiddenStateRunner { ids, _ -> FloatArray(ids.size * h) }
        failsWithState { TextEmbeddingPipeline(tokenizer, identity, zero).embed("kedi") }
        val nan = HiddenStateRunner { ids, _ -> FloatArray(ids.size * h) { Float.NaN } }
        failsWithState { TextEmbeddingPipeline(tokenizer, identity, nan).embed("kedi") }
        val inf = HiddenStateRunner { ids, _ -> FloatArray(ids.size * h) { Float.POSITIVE_INFINITY } }
        failsWithState { TextEmbeddingPipeline(tokenizer, identity, inf).embed("kedi") }
        val allOutsideIdentity = HiddenStateRunner { ids, _ -> FloatArray(ids.size * h) { if (it % h >= o) 5f else 0f } }
        failsWithState { TextEmbeddingPipeline(tokenizer, identity, allOutsideIdentity).embed("kedi") }
    }

    @Test
    fun pipeline_runnerException_propagatesUnchanged() {
        class Boom : RuntimeException("runner")
        try {
            TextEmbeddingPipeline(tokenizer, identity, HiddenStateRunner { _, _ -> throw Boom() }).embed("kedi")
            fail()
        } catch (_: Boom) {
        }
    }

    @Test
    fun pipeline_isDeterministic_andDoesNotMutateDenseWeights() {
        val dense = FloatArray(o * h) { ((it * 17) % 13 - 6) / 7f }
        val copy = dense.copyOf()
        val runner = HiddenStateRunner { ids, _ -> FloatArray(ids.size * h) { (it % 5 - 2).toFloat() + 0.25f } }
        val p = TextEmbeddingPipeline(tokenizer, dense, runner)
        assertArrayEquals(p.embed("kedi"), p.embed("kedi"), 0f)
        assertArrayEquals(copy, dense, 0f)
    }
}
