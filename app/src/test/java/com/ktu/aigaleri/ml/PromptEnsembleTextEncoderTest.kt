package com.ktu.aigaleri.ml

import com.ktu.aigaleri.domain.EmbeddingSpec
import com.ktu.aigaleri.domain.InvalidQueryException
import com.ktu.aigaleri.domain.TextEncoder
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-033: şablon topluluğu katmanı, sahte [TextEncoder] ile (ORT yok). Python referansıyla uyum:
 * [PromptEnsembleGoldenTest]. Gerçek ORT üzerinde süre/kalite cihazda DOĞRULANMADI.
 */
class PromptEnsembleTextEncoderTest {
    private val dim = 8

    /** Gerçek kodlayıcı gibi önce doğrular; ham çağrıları kaydeder. */
    private open inner class FakeBase(
        val vectorFor: (String) -> FloatArray = ::hashVector,
    ) : TextEncoder {
        override val embeddingSpec = EmbeddingSpec(dim, "fake-v1")
        val calls = mutableListOf<String>()
        var beforeCall: suspend (index: Int) -> Unit = {}
        var afterCall: suspend (index: Int) -> Unit = {}

        override suspend fun encode(query: String): FloatArray {
            val normalized = QueryPreprocessor.normalize(query)
            val index = calls.size
            calls.add(query)
            beforeCall(index)
            val v = vectorFor(normalized)
            afterCall(index)
            return v
        }
    }

    /** Deterministik, sıfırdan farklı, farklı metne farklı vektör (birim değil). */
    private fun hashVector(text: String): FloatArray =
        FloatArray(dim) { ((text.hashCode() * 31 + it * 17) % 11 - 5).toFloat() + 0.5f }

    private fun expectedEnsemble(vs: List<FloatArray>): FloatArray {
        val mean = DoubleArray(dim) { i -> vs.sumOf { it[i].toDouble() } / vs.size }
        val norm = sqrt(mean.sumOf { it * it })
        return FloatArray(dim) { (mean[it] / norm).toFloat() }
    }

    private fun norm(v: FloatArray) = sqrt(v.sumOf { it.toDouble() * it })

    @Test
    fun shortQuery_encodesTemplatedVariantsInOrder() = runTest {
        val base = FakeBase()
        PromptEnsembleTextEncoder(base).encode("kedi")
        assertEquals(listOf("bir kedi", "bir kedi fotoğrafı"), base.calls)
    }

    @Test
    fun result_isNormalizedMeanOfVariantVectors() = runTest {
        val vectors = mapOf(
            "bir kedi" to floatArrayOf(3f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            "bir kedi fotoğrafı" to floatArrayOf(0f, 4f, 0f, 0f, 0f, 0f, 0f, 0f),
        )
        val base = FakeBase { vectors.getValue(it) }
        val out = PromptEnsembleTextEncoder(base).encode("kedi")
        // ortalama (1,5; 2) -> normalize (0,6; 0,8)
        assertArrayEquals(floatArrayOf(0.6f, 0.8f, 0f, 0f, 0f, 0f, 0f, 0f), out, 1e-6f)
        assertEquals(1.0, norm(out), 1e-6)
    }

    @Test
    fun result_matchesIndependentFormula_forHashVectors_andIsDeterministic() = runTest {
        val enc = PromptEnsembleTextEncoder(FakeBase())
        val a = enc.encode("su bardağı")
        val b = enc.encode("su bardağı")
        assertArrayEquals(a, b, 0f)
        assertArrayEquals(expectedEnsemble(listOf(hashVector("bir su bardağı"), hashVector("bir su bardağı fotoğrafı"))), a, 1e-6f)
        assertEquals(1.0, norm(a), 1e-6)
    }

    @Test
    fun embeddingSpec_passesThroughFromBase() {
        val base = FakeBase()
        assertSame(base.embeddingSpec, PromptEnsembleTextEncoder(base).embeddingSpec)
    }

    @Test
    fun wordLimit_longerQueryUsesBareEncodingOnly() = runTest {
        val base = FakeBase()
        val q = "dağda yürüyüş yapan insanlar" // 4 sözcük
        val out = PromptEnsembleTextEncoder(base).encode(q)
        assertEquals(listOf(q), base.calls) // tek çıkarım, ham sorgu aynen
        assertArrayEquals(hashVector(q), out, 0f) // ensemble yok: çıplak vektör olduğu gibi
    }

    @Test
    fun wordLimit_threeWordsStillTemplated() = runTest {
        val base = FakeBase()
        PromptEnsembleTextEncoder(base).encode("sahilde gülen çocuk")
        assertEquals(listOf("bir sahilde gülen çocuk", "bir sahilde gülen çocuk fotoğrafı"), base.calls)
    }

    @Test
    fun charLimit_boundaryAt64() = runTest {
        val at64 = listOf("a".repeat(20), "a".repeat(21), "a".repeat(21)).joinToString(" ") // 64 karakter, 3 sözcük
        assertEquals(64, at64.length)
        val b1 = FakeBase()
        PromptEnsembleTextEncoder(b1).encode(at64)
        assertEquals(2, b1.calls.size)

        val at65 = at64 + "a" // 65 karakter
        val b2 = FakeBase()
        PromptEnsembleTextEncoder(b2).encode(at65)
        assertEquals(listOf(at65), b2.calls)
    }

    @Test
    fun maxValidQuery_256chars_isBareAndDoesNotTripBaseLengthLimit() = runTest {
        val q = "a".repeat(QueryPreprocessor.MAX_QUERY_LENGTH) // geçerli en uzun sorgu; şablon eklense 256'yı aşardı
        val base = FakeBase()
        PromptEnsembleTextEncoder(base).encode(q)
        assertEquals(listOf(q), base.calls)
    }

    @Test
    fun turkishCasing_isNormalizedBeforeTemplating() = runTest {
        val base = FakeBase()
        val enc = PromptEnsembleTextEncoder(base)
        enc.encode("KIRMIZI ARABA")
        enc.encode("İSTANBUL")
        enc.encode("ISPARTA")
        enc.encode("  su   bardağı  ")
        assertEquals(
            listOf(
                "bir kırmızı araba", "bir kırmızı araba fotoğrafı",
                "bir istanbul", "bir istanbul fotoğrafı",
                "bir ısparta", "bir ısparta fotoğrafı",
                "bir su bardağı", "bir su bardağı fotoğrafı",
            ),
            base.calls,
        )
    }

    @Test
    fun placeholderInQuery_isNotReexpanded() = runTest {
        val base = FakeBase()
        PromptEnsembleTextEncoder(base).encode("{q}")
        assertEquals(listOf("bir {q}", "bir {q} fotoğrafı"), base.calls)
    }

    @Test
    fun invalidQueries_areRejectedBeforeAnyBaseCall() = runTest {
        val cases = mapOf(
            "" to InvalidQueryException.Reason.EMPTY,
            "   \t\n" to InvalidQueryException.Reason.EMPTY,
            "\u0000​‮" to InvalidQueryException.Reason.EMPTY,
            "a".repeat(QueryPreprocessor.MAX_QUERY_LENGTH + 1) to InvalidQueryException.Reason.TOO_LONG,
            "a".repeat(QueryPreprocessor.MAX_RAW_LENGTH + 1) to InvalidQueryException.Reason.TOO_LONG,
        )
        for ((q, reason) in cases) {
            val base = FakeBase()
            val e = assertThrows(InvalidQueryException::class.java) { runBlocking { PromptEnsembleTextEncoder(base).encode(q) } }
            assertEquals(reason, e.reason)
            assertTrue("base çağrılmamalı", base.calls.isEmpty())
            assertTrue("mesaj istemi içermemeli", !e.message!!.contains("aaa"))
        }
    }

    @Test
    fun invalidQueryFromBase_propagatesSameInstance_onBareAndTemplatedPaths() = runTest {
        val boom = InvalidQueryException(InvalidQueryException.Reason.TOO_LONG)
        for (q in listOf("kedi", "dağda yürüyüş yapan insanlar")) {
            val base = object : FakeBase() {}
            base.beforeCall = { throw boom }
            val e = assertThrows(InvalidQueryException::class.java) { runBlocking { PromptEnsembleTextEncoder(base).encode(q) } }
            assertSame(boom, e)
        }
    }

    @Test
    fun modelError_onSecondVariant_propagatesUnchanged() = runTest {
        val boom = ModelException.Inference("run", IllegalStateException("x"))
        val base = FakeBase()
        base.beforeCall = { if (it == 1) throw boom }
        val e = assertThrows(ModelException.Inference::class.java) { runBlocking { PromptEnsembleTextEncoder(base).encode("kedi") } }
        assertSame(boom, e)
        assertEquals(2, base.calls.size)
    }

    @Test
    fun cancellation_betweenVariants_stopsBeforeNextCall_andReturnsNothing() = runTest {
        val base = FakeBase()
        base.afterCall = { if (it == 0) currentCoroutineContext().job.cancel() }
        var result: FloatArray? = null
        val job = launch { result = PromptEnsembleTextEncoder(base).encode("kedi") }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, base.calls.size)
        assertNull(result)
    }

    @Test
    fun cancellation_afterLastVariant_isStillPropagated() = runTest {
        val base = FakeBase()
        base.afterCall = { if (it == 1) currentCoroutineContext().job.cancel() }
        var result: FloatArray? = null
        val job = launch { result = PromptEnsembleTextEncoder(base).encode("kedi") }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(2, base.calls.size)
        assertNull(result)
    }

    @Test
    fun cancellationException_fromBase_propagates() = runTest {
        val base = FakeBase()
        base.beforeCall = { if (it == 1) throw CancellationException("iptal") }
        assertThrows(CancellationException::class.java) { runBlocking { PromptEnsembleTextEncoder(base).encode("kedi") } }
    }

    @Test
    fun wrongDimension_isIllegalState() = runTest {
        val base = FakeBase { FloatArray(3) { 1f } }
        assertThrows(IllegalStateException::class.java) { runBlocking { PromptEnsembleTextEncoder(base).encode("kedi") } }
    }

    @Test
    fun zeroOrNonFiniteMean_isIllegalState() = runTest {
        val zero = FakeBase { FloatArray(dim) }
        assertThrows(IllegalStateException::class.java) { runBlocking { PromptEnsembleTextEncoder(zero).encode("kedi") } }
        val nan = FakeBase { FloatArray(dim) { Float.NaN } }
        assertThrows(IllegalStateException::class.java) { runBlocking { PromptEnsembleTextEncoder(nan).encode("kedi") } }
    }

    @Test
    fun constructor_rejectsBadConfiguration() {
        val base = FakeBase()
        assertThrows(IllegalArgumentException::class.java) { PromptEnsembleTextEncoder(base, templates = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { PromptEnsembleTextEncoder(base, templates = listOf("bir sorgu")) }
        assertThrows(IllegalArgumentException::class.java) { PromptEnsembleTextEncoder(base, maxWords = 0) }
        assertThrows(IllegalArgumentException::class.java) { PromptEnsembleTextEncoder(base, maxChars = 0) }
        // 256 karakterlik sorguya şablon eklemek 128 token kırpmasına ve 256 karakter sınırına ulaşabilir: reddedilir.
        assertThrows(IllegalArgumentException::class.java) { PromptEnsembleTextEncoder(base, maxChars = 256) }
        PromptEnsembleTextEncoder(base, templates = listOf("{q}"), maxWords = 1, maxChars = 100) // geçerli
    }

    /**
     * Şablon eklendikten sonra WordPiece 128 token kırpması: en uzun şablonlu varyantta (64 karakter + şablon) hiçbir
     * token düşmez, son ek ("fotoğrafı") sağlam kalır. Her karakter ayrı token olan SENTETİK sözlükle en kötü durum.
     */
    @Test
    fun templatedVariant_neverHitsTokenTruncation_worstCaseOneTokenPerChar() {
        val chars = "abcdefghijklmnopqrstuvwxyzöğıçşü"
        val vocab = LinkedHashMap<String, Int>()
        for (t in listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]")) vocab[t] = vocab.size
        for (c in chars) vocab[c.toString()] = vocab.size
        for (c in chars) vocab["##$c"] = vocab.size
        val tok = WordPieceTokenizer(vocab)
        val inv = vocab.entries.associate { it.value to it.key }

        val enc = PromptEnsembleTextEncoder(FakeBase())
        val q = listOf("a".repeat(20), "a".repeat(21), "a".repeat(21)).joinToString(" ")
        val variants = enc.variantsFor(QueryPreprocessor.normalize(q))!!
        assertEquals(2, variants.size)
        for (v in variants) {
            val ids = tok.encode(v)
            assertTrue("kırpılmadan sığmalı: ${ids.size}", ids.size <= WordPieceTokenizer.DEFAULT_MAX_LENGTH)
            assertEquals(v.length - v.count { it == ' ' } + 2, ids.size) // [CLS] + tüm karakterler + [SEP]
        }
        val last = tok.encode(variants.last())
        assertEquals("##ı", inv[last[last.size - 2]]) // "fotoğrafı" sonu düşmedi
        assertEquals("[SEP]", inv[last.last()])
        // Varsayılan yapılandırmanın en kötü uzunluk sınırı: 64 + şablon fazlası (14) + 2 <= 128
        assertTrue(PromptEnsembleTextEncoder.DEFAULT_MAX_CHARS + 14 + 2 <= WordPieceTokenizer.DEFAULT_MAX_LENGTH)
    }

    // ---- Tek oturum ve eşzamanlılık: her varyant slot kilidini ayrı alır; sonuçlar yarıştan etkilenmez.

    private class FakeSession : AutoCloseable {
        override fun close() {}
    }

    private inner class SlotBase(val slot: SingleSessionSlot) : TextEncoder {
        override val embeddingSpec = EmbeddingSpec(dim, "fake-v1")
        @Volatile var opens = 0
        override suspend fun encode(query: String): FloatArray {
            val q = QueryPreprocessor.normalize(query)
            return slot.withSession("text", open = { opens++; FakeSession() }) { hashVector(q) }
        }
    }

    @Test
    fun sequentialVariants_throughSingleSessionSlot_openSessionOnce() = runTest {
        val base = SlotBase(SingleSessionSlot())
        val out = PromptEnsembleTextEncoder(base).encode("bardak")
        assertEquals(1, base.opens)
        assertArrayEquals(expectedEnsemble(listOf(hashVector("bir bardak"), hashVector("bir bardak fotoğrafı"))), out, 1e-6f)
    }

    @Test
    fun concurrentSearches_shareOneSlot_andGetCorrectResults() = runBlocking {
        val base = SlotBase(SingleSessionSlot())
        val enc = PromptEnsembleTextEncoder(base)
        val queries = listOf("kedi", "köpek", "su bardağı", "araba", "dağda yürüyüş yapan insanlar", "deniz", "plaj", "tabak")
        val results = withContext(Dispatchers.Default) { queries.map { q -> async { enc.encode(q) } }.awaitAll() }
        queries.forEachIndexed { i, q ->
            val n = QueryPreprocessor.normalize(q)
            val expected = enc.variantsFor(n)?.let { vs -> expectedEnsemble(vs.map(::hashVector)) } ?: hashVector(n)
            if (enc.variantsFor(n) == null) assertArrayEquals(expected, results[i], 0f)
            else assertArrayEquals(expected, results[i], 1e-6f)
        }
        assertEquals(1, base.opens)
    }

    @Test
    fun otherSessionEvictingBetweenVariants_stillGivesCorrectResult_atReopenCost() = runBlocking {
        val slot = SingleSessionSlot()
        val base = SlotBase(slot)
        val enc = PromptEnsembleTextEncoder(base)
        base.encode("ısınma") // metin oturumu açık
        val evicting = launch(Dispatchers.Default) {
            repeat(50) {
                slot.withSession("vision", open = { FakeSession() }) { }
                yield()
            }
        }
        val out = withContext(Dispatchers.Default) { enc.encode("bardak") }
        evicting.join()
        assertArrayEquals(expectedEnsemble(listOf(hashVector("bir bardak"), hashVector("bir bardak fotoğrafı"))), out, 1e-6f)
        assertTrue("açılış sayısı en az 1", base.opens >= 1)
        assertTrue(abs(norm(out) - 1.0) < 1e-6)
    }
}
