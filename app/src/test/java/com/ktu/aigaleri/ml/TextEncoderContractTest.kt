package com.ktu.aigaleri.ml

import com.ktu.aigaleri.domain.EmbeddingSpec
import com.ktu.aigaleri.domain.InvalidQueryException
import com.ktu.aigaleri.domain.TextEncoder
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T-007 QA: TextEncoder sözleşmesi. (1) Üretim parçalarından (QueryPreprocessor + WordPieceTokenizer +
 * TextEmbeddingPipeline) kurulan sahte-runner'lı kodlayıcı; (2) gerçek OnnxTextEncoder'ın ORT'ye ulaşmadan
 * görülebilen davranışları (hata dönüşümü, iptal, serbest bırakma). ORT'nin kendisi JVM'de çalışmaz.
 */
class TextEncoderContractTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val h = TextEmbeddingPipeline.HIDDEN_SIZE
    private val o = TextEmbeddingPipeline.OUTPUT_DIM
    private val tokens = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "ışık", "kedi", "istanbul", "ısparta")
    private val tokenizer = WordPieceTokenizer(tokens.withIndex().associate { it.value to it.index })
    private val identity = FloatArray(o * h).also { for (i in 0 until o) it[i * h + i] = 1f }

    /** Sözleşmeyi uygulayan, ORT'siz referans kodlayıcı. Çalıştırıcı çağrı sayısı sayılır. */
    private class RefEncoder(
        tokenizer: WordPieceTokenizer,
        dense: FloatArray,
        private val h: Int,
        val calls: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(),
        val seen: MutableList<LongArray> = java.util.Collections.synchronizedList(mutableListOf()),
    ) : TextEncoder {
        override val embeddingSpec = ModelManifest.EMBEDDING_SPEC
        private val pipeline = TextEmbeddingPipeline(tokenizer, dense, HiddenStateRunner { ids, _ ->
            calls.incrementAndGet()
            seen.add(ids)
            // Kimliğe bağlı deterministik gizli durum: farklı sorgu -> farklı vektör
            FloatArray(ids.size * h) { idx -> ((ids[idx / h] * 31 + idx % h) % 17 - 8).toFloat() + 0.5f }
        })

        override suspend fun encode(query: String): FloatArray {
            val q = QueryPreprocessor.normalize(query)
            return withContext(Dispatchers.Default) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                pipeline.embed(q)
            }
        }
    }

    private fun ref() = RefEncoder(tokenizer, identity, h)

    private fun norm(v: FloatArray) = Math.sqrt(v.sumOf { it.toDouble() * it })

    @Test
    fun spec_contract() {
        val s: EmbeddingSpec = ref().embeddingSpec
        assertEquals(512, s.dimension)
        assertTrue(s.modelVersion.isNotBlank())
        assertEquals(ModelManifest.MODEL_VERSION, s.modelVersion)
    }

    @Test
    fun encode_returns512Dim_unitNorm_finite_forVariedQueries() = runTest {
        val enc = ref()
        val queries = listOf(
            "ışık", "KEDİ", "İstanbul'da gün batımı", "a", "x".repeat(QueryPreprocessor.MAX_QUERY_LENGTH),
            "🐱", "قطة", "猫", "kedi, ışık! (test) — ok", "çğıöşü ÇĞIÖŞÜ", "a ".repeat(120),
        )
        for (q in queries) {
            val v = enc.encode(q)
            assertEquals(q, 512, v.size)
            assertEquals(q, 1.0, norm(v), 1e-5)
            assertTrue(q, v.all { it.isFinite() })
        }
    }

    @Test
    fun encode_isDeterministic_andCaseInsensitiveForTurkish() = runTest {
        val enc = ref()
        assertArrayEquals(enc.encode("ışık altında kedi"), enc.encode("ışık altında kedi"), 0f)
        assertArrayEquals(enc.encode("ışık kedi"), enc.encode("IŞIK KEDİ"), 0f)
        assertArrayEquals(enc.encode("kedi   ışık"), enc.encode(" \t kedi\nışık  "), 0f)
        // Farklı sorgu farklı vektör
        assertTrue(!enc.encode("ışık").contentEquals(enc.encode("kedi")))
    }

    @Test
    fun invalidQuery_neverReachesTheModel() = runTest {
        val enc = ref()
        val bad = listOf("", "  \t\n", "\u0000", "​", "x".repeat(QueryPreprocessor.MAX_QUERY_LENGTH + 1), " ".repeat(5000))
        for (q in bad) {
            try {
                enc.encode(q)
                fail("reddedilmeliydi")
            } catch (e: InvalidQueryException) {
                assertTrue(!e.message!!.contains("xxx"))
            }
        }
        assertEquals(0, enc.calls.get())
    }

    @Test
    fun hugeButValidQuery_isTruncatedBy128Tokens() = runTest {
        val enc = ref()
        enc.encode("ışık ".repeat(51)) // 255 karakter, 51 sözcük
        assertEquals(53, enc.seen.last().size)
        val long = tokens.drop(4).joinToString(" ").repeat(10).take(QueryPreprocessor.MAX_QUERY_LENGTH)
        enc.encode(long)
        assertTrue(enc.seen.last().size <= 128)
    }

    @Test
    fun cancelledCaller_getsCancellationException_notAVector() = runBlocking {
        val enc = ref()
        val job = launch(start = CoroutineStart.LAZY) { enc.encode("kedi") }
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, enc.calls.get())
    }

    @Test
    fun concurrentEncodes_areIndependentAndConsistent() = runBlocking {
        val enc = ref()
        val expected = enc.encode("ışık kedi")
        val results = (1..32).map { async(Dispatchers.Default) { enc.encode("IŞIK KEDİ") } }.awaitAll()
        for (r in results) assertArrayEquals(expected, r, 0f)
    }

    // ---- Gerçek OnnxTextEncoder: ORT'ye ulaşmadan gözlemlenebilen davranışlar ----

    private fun onnx(open: (String) -> InputStream): OnnxTextEncoder =
        OnnxTextEncoder(ModelStore(tmp.root, AssetOpener { open(it) }), SingleSessionSlot(), Dispatchers.IO)

    @Test
    fun onnx_corruptModelAsset_surfacesAsIntegrityFailure_notRawException() = runBlocking {
        val enc = onnx { _ -> java.io.ByteArrayInputStream(ByteArray(1234)) }
        try {
            enc.encode("gizli istem")
            fail()
        } catch (e: ModelException.IntegrityFailure) {
            assertTrue(!e.message!!.contains("gizli"))
        }
        assertEquals(emptyList<String>(), File(tmp.root, "models").list()!!.toList())
    }

    @Test
    fun onnx_ioErrorInAssets_surfacesAsModelExceptionIo() = runBlocking {
        val enc = onnx { _ ->
            object : InputStream() {
                override fun read(): Int = throw java.io.IOException("disk")
                override fun read(b: ByteArray, off: Int, len: Int): Int = throw java.io.IOException("disk")
            }
        }
        try {
            enc.encode("kedi")
            fail()
        } catch (_: ModelException.Io) {
        }
    }

    @Test
    fun onnx_cancelDuringModelCopy_cancels_andLeavesNoPartialFile() = runBlocking {
        val inRead = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val enc = onnx { _ ->
            object : InputStream() {
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    inRead.countDown()
                    proceed.await(10, TimeUnit.SECONDS)
                    val n = minOf(len, 1000)
                    java.util.Arrays.fill(b, off, off + n, 7)
                    return n
                }
            }
        }
        val job = launch(Dispatchers.Default) { enc.encode("kedi") }
        assertTrue(inRead.await(10, TimeUnit.SECONDS))
        job.cancel()
        proceed.countDown()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(emptyList<String>(), File(tmp.root, "models").list()!!.toList())
    }

    @Test
    fun onnx_release_onNeverOpenedEncoder_isHarmless_andRepeatable() = runBlocking {
        val enc = onnx { _ -> error("assets'e dokunulmamalı") }
        enc.release()
        enc.release()
    }

    @Test
    fun onnx_failedOpen_doesNotPoisonLaterCalls() = runBlocking {
        var fail = true
        val enc = onnx { _ -> if (fail) throw java.io.FileNotFoundException("yok") else throw java.io.IOException("başka") }
        try { enc.encode("kedi"); fail() } catch (_: ModelException.Missing) {}
        fail = false
        try { enc.encode("kedi"); fail() } catch (_: ModelException.Io) {}
        // Geçersiz sorgu her zaman önce reddedilir.
        try { enc.encode(""); fail() } catch (_: InvalidQueryException) {}
    }
}
