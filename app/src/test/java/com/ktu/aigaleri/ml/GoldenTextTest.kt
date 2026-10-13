package com.ktu.aigaleri.ml

import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * T-026: altın metin referansı. Asıl kapı token METNİ eşitliğidir (id'ye bağlı değil; sözlük budama T-027 id'leri
 * değiştirir). Üretim yolu birebir: [QueryPreprocessor.normalize] -> [WordPieceTokenizer.encode] -> id'ler sözlükten
 * METNE çevrilir. Gerçek sözlük (assets/models, git dışı) yoksa gerektiren testler Assume ile atlanır.
 * Altın dosyalar `tools/golden_text.py` ile ORİJİNAL model dosyalarından üretilir (SHA-256 doğrulamalı).
 */
class GoldenTextTest {
    private val golden = GoldenText.queries()
    private val vocab = TokenVocab.fromAssetsOrNull()

    /** Üretimle aynı yükleyici (fromVocabStream); sözlük dosyası TokenVocab ile aynıdır. */
    private fun tokenizer(@Suppress("UNUSED_PARAMETER") v: TokenVocab) =
        TokenVocab.vocabFile().inputStream().use { WordPieceTokenizer.fromVocabStream(it) }

    // ---- Modelsiz: altın dosyaların kendi tutarlılığı (her zaman çalışır) ----

    @Test
    fun goldenFiles_haveExpectedShape() {
        assertEquals(GoldenText.QUERY_COUNT, golden.size)
        assertEquals(golden.size, golden.map { it.query }.toSet().size)
        assertEquals(12, golden.map { it.category }.toSet().size)
        for (g in golden) {
            assertEquals(g.query, g.tokens.size, g.originalIds.size)
            assertEquals("[CLS]", g.tokens.first())
            assertEquals("[SEP]", g.tokens.last())
            assertTrue(g.query, g.tokens.size in 3..WordPieceTokenizer.DEFAULT_MAX_LENGTH)
        }
        val vectors = GoldenText.vectors()
        assertEquals(golden.map { it.query }.toSet(), vectors.keys)
        for ((q, v) in vectors) {
            assertEquals(q, GoldenText.VECTOR_DIM, v.size)
            val norm = sqrt(v.sumOf { it.toDouble() * it })
            assertEquals("norm $q", 1.0, norm, 1e-6)
        }
    }

    @Test
    fun goldenUnkBaseline_isPinned() {
        val unk = golden.sumOf { g -> g.tokens.count { it == WordPieceTokenizer.UNK } }
        val body = golden.sumOf { it.tokens.size - 2 }
        assertEquals(GoldenText.BASELINE_UNK_COUNT, unk)
        assertEquals(GoldenText.BASELINE_BODY_TOKENS, body)
        // [UNK] yalnızca iki emoji sorgusunda.
        assertEquals(setOf("🐱 kedi", "plaj 🏖️ tatil"), golden.filter { WordPieceTokenizer.UNK in it.tokens }.map { it.query }.toSet())
    }

    @Test
    fun tokenVocab_mapsIdsToTextAndRejectsOutOfRange() {
        val v = TokenVocab(listOf("[PAD]", "[UNK]", "kedi"))
        assertEquals(listOf("kedi", "[UNK]"), v.tokensOf(intArrayOf(2, 1)))
        assertEquals(2, v.idOf("kedi"))
        try {
            v.tokensOf(intArrayOf(3))
            org.junit.Assert.fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    // ---- Gerçek sözlükle (yoksa atlanır) ----

    @Test
    fun tokenTexts_equalGolden_forAll60Queries() {
        val v = vocab
        assumeTrue("text_vocab.txt yok (tools/fetch_models.sh)", v != null)
        val tok = tokenizer(v!!)
        for (g in golden) {
            val ids = tok.encode(QueryPreprocessor.normalize(g.query))
            assertEquals("token METNİ ayrıştı: ${g.query} [${g.category}]", g.tokens, v.tokensOf(ids))
        }
    }

    @Test
    fun unkCountAndRate_doNotExceedGoldenBaseline() {
        val v = vocab
        assumeTrue("text_vocab.txt yok (tools/fetch_models.sh)", v != null)
        val tok = tokenizer(v!!)
        val unkId = v.idOf(WordPieceTokenizer.UNK)
        var unk = 0
        var body = 0
        for (g in golden) {
            val ids = tok.encode(QueryPreprocessor.normalize(g.query))
            unk += ids.count { it == unkId }
            body += ids.size - 2
        }
        assertTrue("[UNK] sayısı arttı: $unk > ${GoldenText.BASELINE_UNK_COUNT}", unk <= GoldenText.BASELINE_UNK_COUNT)
        // Oran: unk/body <= baseline (çapraz çarpım, kayan nokta yok). Token sayısı da değişmemeli (METİN eşitliği zaten ister).
        assertTrue(
            "[UNK] oranı arttı: $unk/$body",
            unk.toLong() * GoldenText.BASELINE_BODY_TOKENS <= GoldenText.BASELINE_UNK_COUNT.toLong() * body,
        )
        assertEquals(GoldenText.BASELINE_BODY_TOKENS, body)
    }

    @Test
    fun originalVocab_goldenIdsMatchExactly_andIdsMapToGoldenText() {
        val v = vocab
        assumeTrue("text_vocab.txt yok (tools/fetch_models.sh)", v != null)
        // Yalnızca BUDANMAMIŞ sözlükte anlamlı: Python altın id'leri Kotlin ile birebir aynı olmalı. Budanmış sözlükte atlanır.
        assumeTrue("sözlük budanmış (id'ler değişti); id karşılaştırması atlandı", v!!.size == GoldenText.ORIGINAL_VOCAB_SIZE)
        val tok = tokenizer(v)
        for (g in golden) {
            assertEquals("id -> metin eşlemesi: ${g.query}", g.tokens, v.tokensOf(g.originalIds))
            assertArrayEquals(g.query, g.originalIds.toIntArray(), tok.encode(QueryPreprocessor.normalize(g.query)))
        }
    }
}
