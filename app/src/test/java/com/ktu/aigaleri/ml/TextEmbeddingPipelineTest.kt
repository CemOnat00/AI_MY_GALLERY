package com.ktu.aigaleri.ml

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class TextEmbeddingPipelineTest {
    private val tokens = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "kedi", "köpek")
    private val tokenizer = WordPieceTokenizer(tokens.withIndex().associate { it.value to it.index })
    private val h = TextEmbeddingPipeline.HIDDEN_SIZE
    private val o = TextEmbeddingPipeline.OUTPUT_DIM

    /** İlk 512 girdiyi aynen taşıyan Dense: W[i][i] = 1. */
    private val identityDense = FloatArray(o * h).also { for (i in 0 until o) it[i * h + i] = 1f }

    @Test
    fun pipeline_runsTokenizerPoolDenseAndNormalize() {
        var seenIds: LongArray? = null
        var seenMask: LongArray? = null
        val runner = HiddenStateRunner { ids, mask ->
            seenIds = ids
            seenMask = mask
            FloatArray(ids.size * h) { idx -> if (idx % h == 0) 3f else if (idx % h == 1) 4f else 0f }
        }
        val out = TextEmbeddingPipeline(tokenizer, identityDense, runner).embed("kedi köpek")
        assertArrayEquals(longArrayOf(2, 4, 5, 3), seenIds)
        assertArrayEquals(longArrayOf(1, 1, 1, 1), seenMask)
        assertEquals(o, out.size)
        assertEquals(0.6f, out[0], 1e-6f)
        assertEquals(0.8f, out[1], 1e-6f)
        assertEquals(0f, out[2], 0f)
    }

    @Test
    fun pipeline_resultIsUnitLength() {
        val runner = HiddenStateRunner { ids, _ -> FloatArray(ids.size * h) { (it % 7 - 3).toFloat() + 0.5f } }
        val dense = FloatArray(o * h) { ((it * 31) % 11 - 5) / 10f }
        val out = TextEmbeddingPipeline(tokenizer, dense, runner).embed("kedi")
        assertEquals(1.0, out.sumOf { it.toDouble() * it }, 1e-5)
    }

    @Test
    fun pipeline_rejectsWrongModelOutputSize() {
        val runner = HiddenStateRunner { _, _ -> FloatArray(10) }
        try {
            TextEmbeddingPipeline(tokenizer, identityDense, runner).embed("kedi")
            org.junit.Assert.fail()
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun pipeline_rejectsWrongDenseSize() {
        try {
            TextEmbeddingPipeline(tokenizer, FloatArray(5), HiddenStateRunner { _, _ -> FloatArray(0) })
            org.junit.Assert.fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    // --- Gerçek Dense ağırlığı + Python (onnxruntime + numpy) referansı; dosya yoksa atlanır ---

    @Test
    fun realDense_matchesPythonReferenceFromPooledVectors() {
        val denseFile = File("src/main/assets/models/text_dense_768x512_f32.bin")
        assumeTrue("text_dense_768x512_f32.bin yok (tools/fetch_models.sh)", denseFile.isFile)
        val w = EmbeddingMath.readFloat32LittleEndian(denseFile.readBytes(), o * h)
        val lines = javaClass.getResourceAsStream("/ml/embedding_reference.tsv")!!
            .readBytes().toString(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
        assertTrue(lines.size >= 3)
        for (line in lines) {
            val parts = line.split('\t')
            val pooled = parts[1].trim().split(' ').map { it.toFloat() }.toFloatArray()
            val expected = parts[2].trim().split(' ').map { it.toFloat() }.toFloatArray()
            assertEquals(h, pooled.size)
            assertEquals(o, expected.size)
            val got = EmbeddingMath.l2Normalize(EmbeddingMath.denseNoBias(w, pooled, o, h))
            assertArrayEquals(expected, got, 1e-5f)
        }
    }
}
