package com.ktu.aigaleri.ml

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ImageEmbeddingPipelineTest {
    private val input = FloatArray(ImagePreprocessor.TENSOR_SIZE)

    private fun norm(v: FloatArray) = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()

    @Test
    fun output_isL2Normalized_512() {
        val raw = FloatArray(512) { (it % 7 - 3).toFloat() }
        val out = ImageEmbeddingPipeline { raw }.embed(input)
        assertEquals(ModelManifest.EMBEDDING_SPEC.dimension, out.size)
        assertEquals(1f, norm(out), 1e-5f)
        // Yön korunur.
        assertEquals(raw[5] / norm(raw), out[5], 1e-6f)
    }

    @Test
    fun runnerReceivesTheTensor_andCosineEqualsDotProduct() {
        var seen: FloatArray? = null
        val a = ImageEmbeddingPipeline { seen = it; FloatArray(512) { i -> if (i == 0) 3f else 0f } }.embed(input)
        assertTrue(seen === input)
        val b = ImageEmbeddingPipeline { FloatArray(512) { i -> if (i == 0) 5f else 0f } }.embed(input)
        assertEquals(1f, a.indices.sumOf { (a[it] * b[it]).toDouble() }.toFloat(), 1e-6f)
    }

    @Test
    fun wrongOutputSize_isInferenceError() {
        for (n in listOf(0, 511, 513, 768)) {
            try {
                ImageEmbeddingPipeline { FloatArray(n) { 1f } }.embed(input)
                fail("n=$n")
            } catch (e: ModelException.Inference) {
                assertEquals("output-shape", e.stage)
                assertFalse(ModelFailures.isModelLevel(e))
            }
        }
    }

    @Test
    fun zeroOrNonFiniteVector_isInferenceError() {
        for (bad in listOf(FloatArray(512), FloatArray(512) { Float.NaN }, FloatArray(512) { Float.POSITIVE_INFINITY })) {
            try {
                ImageEmbeddingPipeline { bad }.embed(input)
                fail()
            } catch (e: ModelException.Inference) {
                assertEquals("output-values", e.stage)
            }
        }
    }

    @Test
    fun wrongInputSize_isProgrammingError() {
        try {
            ImageEmbeddingPipeline { FloatArray(512) { 1f } }.embed(FloatArray(10))
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun modelFailures_classification() {
        assertTrue(ModelFailures.isModelLevel(ModelException.Missing("models/x")))
        assertTrue(ModelFailures.isModelLevel(ModelException.IntegrityFailure("x", "d")))
        assertTrue(ModelFailures.isModelLevel(ModelException.InsufficientStorage("x", 1)))
        assertTrue(ModelFailures.isModelLevel(ModelException.Io("x", RuntimeException())))
        assertTrue(ModelFailures.isModelLevel(ModelException.Inference("open", RuntimeException())))
        assertTrue(ModelFailures.isModelLevel(ModelException.Inference("native-load", RuntimeException())))
        assertFalse(ModelFailures.isModelLevel(ModelException.Inference("run", RuntimeException())))
        assertFalse(ModelFailures.isModelLevel(ModelException.Inference("output-shape", RuntimeException())))
        assertFalse(ModelFailures.isModelLevel(ImageDecodeException("x")))
        assertFalse(ModelFailures.isModelLevel(RuntimeException()))
    }
}
