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

    // ---- Çift şablon önleme ("bir" öneki, sayı, "fotoğraf/resim" soneki)

    private suspend fun variantsOf(q: String): List<String> {
        val base = FakeBase()
        PromptEnsembleTextEncoder(base).encode(q)
        return base.calls
    }

    @Test
    fun articleOrCountPrefix_dropsBirPrefix() = runTest {
        assertEquals(listOf("bir kedi fotoğrafı"), variantsOf("bir kedi")) // "bir bir kedi" olmaz
        assertEquals(listOf("iki kişi fotoğrafı"), variantsOf("iki kişi"))
        assertEquals(listOf("üç çocuk fotoğrafı"), variantsOf("ÜÇ ÇOCUK"))
        assertEquals(listOf("5 araba fotoğrafı"), variantsOf("5 araba"))
        assertEquals(listOf("10 kişi fotoğrafı"), variantsOf("10 kişi"))
        assertEquals(listOf("bir bardak çay fotoğrafı"), variantsOf("bir bardak çay"))
        for (w in listOf("bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz", "on")) {
            assertEquals("$w kedi", listOf("$w kedi fotoğrafı"), variantsOf("$w kedi"))
        }
    }

    @Test
    fun photoSuffix_dropsPhotoSuffix() = runTest {
        assertEquals(listOf("bir kedi fotoğrafı"), variantsOf("kedi fotoğrafı")) // "bir {q}" kalır; " fotoğrafı" eklenmez
        assertEquals(listOf("bir deniz resmi"), variantsOf("deniz resmi"))
        for (w in listOf("fotoğraf", "fotoğrafı", "foto", "resim", "resmi")) {
            assertEquals("kedi $w", listOf("bir kedi $w"), variantsOf("kedi $w"))
        }
    }

    @Test
    fun bothPrefixAndSuffixBlocked_fallsBackToBareQueryOnce() = runTest {
        val base = FakeBase()
        val q = "bir kedi fotoğrafı"
        val out = PromptEnsembleTextEncoder(base).encode(q)
        assertEquals(listOf(q), base.calls) // ham sorgu, tek çıkarım
        assertArrayEquals(hashVector(q), out, 0f)
    }

    @Test
    fun regularWordsAreNotMistakenForCountWords() = runTest {
        // "on" ve "bir" yalnızca TAM sözcük olarak sayılır; "bira", "onur", "birinci" şablonlanır.
        assertEquals(listOf("bir bira", "bir bira fotoğrafı"), variantsOf("bira"))
        assertEquals(listOf("bir onur", "bir onur fotoğrafı"), variantsOf("onur"))
        assertEquals(listOf("bir birinci gün", "bir birinci gün fotoğrafı"), variantsOf("birinci gün"))
        // sayı sözcüğü başta değilse ("kedi bir") ön ek eklenir
        assertEquals(listOf("bir kedi bir", "bir kedi bir fotoğrafı"), variantsOf("kedi bir"))
    }

    // ---- BatchTextEncoder yolu: tüm varyantlar tek encodeAll çağrısında

    private inner class FakeBatchBase : FakeBase(), BatchTextEncoder {
        val batches = mutableListOf<List<String>>()
        var beforeBatch: suspend () -> Unit = {}
        override suspend fun encodeAll(queries: List<String>): List<FloatArray> {
            beforeBatch()
            batches.add(queries)
            return queries.map { encode(it) }
        }
    }

    @Test
    fun batchBase_getsAllVariantsInOneEncodeAllCall() = runTest {
        val base = FakeBatchBase()
        val out = PromptEnsembleTextEncoder(base).encode("kedi")
        assertEquals(listOf(listOf("bir kedi", "bir kedi fotoğrafı")), base.batches)
        assertArrayEquals(expectedEnsemble(listOf(hashVector("bir kedi"), hashVector("bir kedi fotoğrafı"))), out, 1e-6f)
    }

    @Test
    fun batchBase_sameResultAsSequentialBase() = runTest {
        for (q in listOf("kedi", "su bardağı", "iki kişi", "kedi fotoğrafı", "sahilde gülen çocuk")) {
            val a = PromptEnsembleTextEncoder(FakeBase()).encode(q)
            val b = PromptEnsembleTextEncoder(FakeBatchBase()).encode(q)
            assertArrayEquals(q, a, b, 0f)
        }
    }

    @Test
    fun batchBase_bareQueryUsesPlainEncode_noBatchCall() = runTest {
        val base = FakeBatchBase()
        PromptEnsembleTextEncoder(base).encode("dağda yürüyüş yapan insanlar")
        assertTrue(base.batches.isEmpty())
        assertEquals(1, base.calls.size)
    }

    @Test
    fun batchBase_invalidQueryRejectedBeforeBatch() = runTest {
        val base = FakeBatchBase()
        assertThrows(InvalidQueryException::class.java) { runBlocking { PromptEnsembleTextEncoder(base).encode("  ") } }
        assertTrue(base.batches.isEmpty() && base.calls.isEmpty())
    }

    @Test
    fun batchBase_errorPropagatesUnchanged_andCancellationIsHonored() = runTest {
        val boom = ModelException.Inference("run", IllegalStateException("x"))
        val failing = FakeBatchBase()
        failing.beforeBatch = { throw boom }
        val e = assertThrows(ModelException.Inference::class.java) { runBlocking { PromptEnsembleTextEncoder(failing).encode("kedi") } }
        assertSame(boom, e)

        // İptal toplu çağrı sırasında olursa (taban dönse bile) sonuç dönmez.
        val cancelling = FakeBatchBase()
        cancelling.afterCall = { if (it == 1) currentCoroutineContext().job.cancel() }
        var result: FloatArray? = null
        val job = launch { result = PromptEnsembleTextEncoder(cancelling).encode("kedi") }
        job.join()
        assertTrue(job.isCancelled)
        assertNull(result)

        // Toplu çağrıdan ÖNCE iptal edilmişse taban hiç çağrılmaz.
        val never = FakeBatchBase()
        val job2 = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().job.cancel()
            PromptEnsembleTextEncoder(never).encode("kedi")
        }
        job2.join()
        assertTrue(job2.isCancelled)
        assertTrue(never.batches.isEmpty() && never.calls.isEmpty())
    }

    @Test
    fun batchBase_wrongCountOrDimension_isIllegalState() = runTest {
        val wrongCount = object : FakeBase(), BatchTextEncoder {
            override suspend fun encodeAll(queries: List<String>) = listOf(hashVector("x"))
        }
        assertThrows(IllegalStateException::class.java) { runBlocking { PromptEnsembleTextEncoder(wrongCount).encode("kedi") } }
        val wrongDim = object : FakeBase(), BatchTextEncoder {
            override suspend fun encodeAll(queries: List<String>) = queries.map { FloatArray(3) { 1f } }
        }
        assertThrows(IllegalStateException::class.java) { runBlocking { PromptEnsembleTextEncoder(wrongDim).encode("kedi") } }
    }

    // ---- Tek oturum ve eşzamanlılık: her varyant slot kilidini ayrı alır; sonuçlar yarıştan etkilenmez.

    private class FakeSession : AutoCloseable {
        override fun close() {}
    }

    private open inner class SlotBase(val slot: SingleSessionSlot) : TextEncoder {
        override val embeddingSpec = EmbeddingSpec(dim, "fake-v1")
        @Volatile var opens = 0
        /** Her tek `encode` çağrısından SONRA (kilit bırakıldıktan sonra) çalışır; index = bu tabanın kaç çağrı yaptığı. */
        var afterEncode: suspend (index: Int) -> Unit = {}
        private val encodeCount = java.util.concurrent.atomic.AtomicInteger()

        override suspend fun encode(query: String): FloatArray {
            val q = QueryPreprocessor.normalize(query)
            val v = slot.withSession("text", open = { opens++; FakeSession() }) { hashVector(q) }
            afterEncode(encodeCount.getAndIncrement())
            return v
        }
    }

    /** OnnxTextEncoder.encodeAll gibi: tüm sorguları TEK withSession altında kodlar. */
    private inner class SlotBatchBase(slot: SingleSessionSlot) : SlotBase(slot), BatchTextEncoder {
        var insideBatch: suspend (index: Int) -> Unit = {}
        override suspend fun encodeAll(queries: List<String>): List<FloatArray> {
            val normalized = queries.map { QueryPreprocessor.normalize(it) }
            return slot.withSession("text", open = { opens++; FakeSession() }) {
                normalized.mapIndexed { i, q -> hashVectorBlocking(q, i) }
            }
        }
        private fun hashVectorBlocking(q: String, i: Int): FloatArray {
            kotlinx.coroutines.runBlocking { insideBatch(i) }
            return hashVector(q)
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
            val vs = enc.variantsFor(n)
            if (vs == null) assertArrayEquals(hashVector(n), results[i], 0f)
            else assertArrayEquals(expectedEnsemble(vs.map(::hashVector)), results[i], 1e-6f)
        }
        assertEquals(1, base.opens)
    }

    @Test
    fun plainBase_eviction_betweenVariants_reopensSession_opensEquals2() = runBlocking {
        val slot = SingleSessionSlot()
        val base = SlotBase(slot)
        // Birinci varyanttan SONRA (kilit serbestken) görüntü oturumu açılır: metin oturumu kapanır.
        base.afterEncode = { if (it == 0) slot.withSession("vision", open = { FakeSession() }) { } }
        val out = PromptEnsembleTextEncoder(base).encode("bardak")
        assertEquals("tek-tek encode yolunda iki açılış", 2, base.opens)
        assertArrayEquals(expectedEnsemble(listOf(hashVector("bir bardak"), hashVector("bir bardak fotoğrafı"))), out, 1e-6f)
    }

    @Test
    fun batchBase_eviction_attemptedBetweenVariants_cannotReopen_opensEquals1() = runBlocking {
        val slot = SingleSessionSlot()
        val base = SlotBatchBase(slot)
        var evictor: kotlinx.coroutines.Job? = null
        // Birinci varyant çıkarılırken (kilit TUTULUYOR) başka bir iş görüntü oturumu açmaya çalışır: kilit bırakılana kadar giremez.
        base.insideBatch = { i ->
            if (i == 0) evictor = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { slot.withSession("vision", open = { FakeSession() }) { } }
        }
        val out = PromptEnsembleTextEncoder(base).encode("bardak")
        assertEquals("toplu yolda tek açılış", 1, base.opens)
        assertArrayEquals(expectedEnsemble(listOf(hashVector("bir bardak"), hashVector("bir bardak fotoğrafı"))), out, 1e-6f)
        evictor!!.join()
        // Eviction toplu çağrıdan SONRA gerçekleşti: bir sonraki arama oturumu yeniden açar.
        PromptEnsembleTextEncoder(base).encode("kedi")
        assertEquals(2, base.opens)
    }
}
