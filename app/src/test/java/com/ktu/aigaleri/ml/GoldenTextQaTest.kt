package com.ktu.aigaleri.ml

import com.ktu.aigaleri.domain.InvalidQueryException
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * T-026 (QA): altın dosya bütünlüğü, Türkçe kapsama, ek (altın olmayan) cümlelerde token METNİ round-trip özelliği
 * ve tokenizer kenar durumları. Sözlük (assets/models, git dışı) gerektirenler yoksa atlanır; round-trip testleri
 * budanmış sözlükte de geçmelidir (T-027 kapısına ek koruma).
 */
class GoldenTextQaTest {
    private val tr: Locale = Locale.forLanguageTag("tr-TR")
    private val vocab = TokenVocab.fromAssetsOrNull()

    private fun raw(name: String): String =
        GoldenTextQaTest::class.java.getResourceAsStream("/ml/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun tokenizer() = TokenVocab.vocabFile().inputStream().use { WordPieceTokenizer.fromVocabStream(it) }

    private fun tokenTexts(v: TokenVocab, tok: WordPieceTokenizer, text: String): List<String> =
        v.tokensOf(tok.encode(QueryPreprocessor.normalize(text)))

    // ------------------------------------------------------------------ altın dosya bütünlüğü (modelsiz)

    @Test
    fun rawFiles_have76Lines_endWithNewline_noBlankNoCrNoBom() {
        for (name in listOf("golden_queries.tsv", "golden_text_vectors.tsv")) {
            val text = raw(name)
            assertTrue("$name sonu \\n değil", text.endsWith("\n"))
            assertFalse("$name CR içeriyor", text.contains('\r'))
            assertFalse("$name BOM", text.startsWith("﻿"))
            val lines = text.removeSuffix("\n").split('\n')
            assertEquals(name, GoldenText.QUERY_COUNT, lines.size)
            assertTrue("$name boş satır", lines.none { it.isEmpty() })
        }
    }

    @Test
    fun queryOrder_isIdenticalInBothFiles_andKeysAreUnique() {
        val q = raw("golden_queries.tsv").removeSuffix("\n").split('\n').map { it.substringBefore('\t') }
        val v = raw("golden_text_vectors.tsv").removeSuffix("\n").split('\n').map { it.substringBefore('\t') }
        assertEquals(q, v)
        assertEquals("yinelenen anahtar", q.size, q.toSet().size)
    }

    @Test
    fun everyVectorLine_hasTwoColumns_512FiniteFloats_andUnitNorm() {
        for (line in raw("golden_text_vectors.tsv").removeSuffix("\n").split('\n')) {
            val cols = line.split('\t')
            assertEquals("sütun sayısı", 2, cols.size)
            val parts = cols[1].split(' ')
            assertEquals(cols[0], GoldenText.VECTOR_DIM, parts.size)
            var sq = 0.0
            for (p in parts) {
                val f = p.toFloat()
                assertTrue("sonlu değil: $p", f.isFinite())
                assertTrue("makul aralık dışı: $p", abs(f) < 1.0f)
                sq += f.toDouble() * f
            }
            assertTrue("sıfır vektör", sq > 0.0)
            assertEquals("L2 normu ${cols[0]}", 1.0, sqrt(sq), 1e-6)
        }
    }

    @Test
    fun queriesFile_hasFourColumns_withCategoryColumn_andExpectedCategoryCounts() {
        val rows = raw("golden_queries.tsv").removeSuffix("\n").split('\n').map { it.split('\t') }
        assertTrue(rows.all { it.size == 4 && it.all { c -> c.isNotEmpty() } })
        val counts = rows.groupingBy { it[1] }.eachCount()
        val expectedBase = mapOf(
            "nesne" to 8, "sahne" to 6, "renk" to 5, "eylem" to 6, "kisi_sayisi" to 5, "mekan" to 7, "ek_var" to 5,
            "ek_yok" to 3, "sayi" to 4, "aksan" to 5, "emoji" to 2, "ing" to 4,
        )
        assertEquals(expectedBase.values.sum(), 60)
        assertEquals(expectedBase + mapOf("turkce_ozel" to 11, "unicode" to 5), counts)
        // kategori başlıkları dosyanın başka bir sütununda sızmaz (sorgu sütunu kategori adı olamaz)
        assertTrue(rows.none { it[0] in counts.keys })
    }

    @Test
    fun tokenIds_areInternallyConsistent_withTokenTexts() {
        for (g in GoldenText.queries()) {
            assertEquals(g.query, g.tokens.size, g.originalIds.size)
            assertEquals(101, g.originalIds.first())
            assertEquals(102, g.originalIds.last())
            assertTrue(g.query, g.originalIds.all { it in 0 until GoldenText.ORIGINAL_VOCAB_SIZE })
            // [UNK] id'si (100) <=> [UNK] metni; [CLS]/[SEP]/[PAD]/[MASK] gövdede yok
            for ((id, t) in g.originalIds.zip(g.tokens)) assertEquals("${g.query}: $t", id == 100, t == "[UNK]")
            val body = g.tokens.subList(1, g.tokens.size - 1)
            assertTrue(body.none { it in setOf("[CLS]", "[SEP]", "[PAD]", "[MASK]") })
            assertTrue("gövde ## ile başlayamaz: ${g.query}", !body.first().startsWith("##"))
            assertTrue(g.tokens.none { it.isEmpty() || it.any(Char::isWhitespace) })
        }
    }

    @Test
    fun goldenTokenTexts_allExistInVocab_andIdsMatchWhenOriginal() {
        val v = vocab
        assumeTrue("text_vocab.txt yok", v != null)
        val set = v!!.lines.toHashSet()
        for (g in GoldenText.queries()) for (t in g.tokens) assertTrue("sözlükte yok: $t (${g.query})", t in set)
        if (v.size == GoldenText.ORIGINAL_VOCAB_SIZE) {
            for (g in GoldenText.queries()) for ((id, t) in g.originalIds.zip(g.tokens)) assertEquals(t, v.lines[id])
        }
    }

    @Test
    fun toolsInputFile_matchesOutputQueriesAndCategories_inOrder() {
        val input = File(File(System.getProperty("user.dir")!!).parentFile!!, "tools/golden_text_queries.tsv")
        assertTrue(input.isFile)
        val inRows = input.readText().removeSuffix("\n").split('\n').map { it.split('\t') }
        assertTrue(inRows.all { it.size == 2 })
        val out = raw("golden_queries.tsv").removeSuffix("\n").split('\n').map { it.split('\t') }
        // girdi: kategori, sorgu; çıktı: sorgu, kategori (ayni kaçışlı metin)
        assertEquals(inRows.map { it[1] to it[0] }, out.map { it[0] to it[1] })
    }

    @Test
    fun equalNormalizedQueries_haveEqualTokensAndVectors_distinctOnesDiffer() {
        val queries = GoldenText.queries()
        val vectors = GoldenText.vectors()
        val byNorm = queries.groupBy { QueryPreprocessor.normalize(it.query) }
        val dup = byNorm.values.filter { it.size > 1 }
        assertTrue("normalize sonrası eşleşen çift beklenir (IŞIK/ışık)", dup.isNotEmpty())
        for (grp in dup) for (g in grp) {
            assertEquals(grp[0].tokens, g.tokens)
            val a = vectors.getValue(grp[0].query)
            val b = vectors.getValue(g.query)
            assertTrue("aynı normalize metin farklı vektör: ${g.query}", a.indices.all { a[it] == b[it] })
        }
        // Farklı token dizisi => farklı vektör (altın dosyası kopyala-yapıştır hatasını yakalar)
        val reps = byNorm.values.map { it[0] }
        for (i in reps.indices) for (j in i + 1 until reps.size) {
            if (reps[i].tokens == reps[j].tokens) continue
            val a = vectors.getValue(reps[i].query)
            val b = vectors.getValue(reps[j].query)
            assertTrue("aynı vektör: ${reps[i].query} / ${reps[j].query}", a.indices.any { a[it] != b[it] })
        }
    }

    // ------------------------------------------------------------------ Türkçe kapsama

    @Test
    fun goldenQueries_coverEveryTurkishLetterAndSuffixedWordsAndAccentless() {
        val queries = GoldenText.queries()
        val all = queries.joinToString("") { it.query }
        for (c in "âîûÂÎÛÇĞİÖŞÜçğıöşüI") assertTrue("altın sorgularda yok: $c", c in all)
        val cats = queries.groupBy { it.category }
        assertTrue(cats.getValue("ek_var").all { q -> q.query.length > 8 })
        assertTrue(cats.getValue("aksan").any { it.query.all { c -> c.code < 0x250 } && "çğıöşü" .none { c -> c in it.query } })
        assertTrue(queries.any { it.query == it.query.uppercase(tr) && it.query.any(Char::isLetter) })
        assertTrue(queries.any { it.query == it.query.lowercase(tr) && it.query.any(Char::isLetter) })
        assertTrue(queries.any { '\'' in it.query })
    }

    @Test
    fun dotlessAndDottedI_followTurkishRules_inNormalize() {
        assertEquals("ı", QueryPreprocessor.normalize("I"))
        assertEquals("i", QueryPreprocessor.normalize("İ"))
        assertEquals("ısık", QueryPreprocessor.normalize("ISIK"))
        assertEquals("istanbul", QueryPreprocessor.normalize("İSTANBUL"))
        assertEquals("ışık", QueryPreprocessor.normalize("IŞIK"))
        assertEquals("iı", QueryPreprocessor.normalize("iI"))
        // I + birleştirici nokta (U+0307): Java tr kuralı "i" verir (çift nokta oluşmaz)
        assertEquals("istanbul", QueryPreprocessor.normalize("İstanbul"))
        // Türkçe harfler büyükten küçüğe: Ç Ğ Ö Ş Ü Â Î Û
        assertEquals("çğöşüâîû", QueryPreprocessor.normalize("ÇĞÖŞÜÂÎÛ"))
        assertEquals("çğıöşü", QueryPreprocessor.normalize("çğıöşü"))
    }

    @Test
    fun turkishLetterTokens_neverUnk_andCaseVariantsMapToSameTokens() {
        val v = vocab
        assumeTrue("text_vocab.txt yok", v != null)
        val tok = tokenizer()
        val words = listOf(
            "çiçek", "ğ", "ığ", "ıssız", "öğle", "şeker", "üzüm", "kâr", "hâlâ", "îman", "rûh", "mahkûm", "dükkân",
            "ağaç", "yağmur", "çağrı", "göğüs", "küçük", "güneş", "ışık", "ılık", "isim", "inek",
        )
        for (w in words) {
            val t = tokenTexts(v!!, tok, w)
            assertFalse("UNK: $w -> $t", "[UNK]" in t)
            assertEquals(t, tokenTexts(v, tok, w.uppercase(tr)))
            assertEquals(t, tokenTexts(v, tok, w.replaceFirstChar { it.titlecase(tr) }))
        }
        // I/ı ve İ/i ayrı sözcüklerdir: ışık != isik, ılık != ilik
        assertNotEquals(tokenTexts(v!!, tok, "ışık"), tokenTexts(v, tok, "isik"))
        assertNotEquals(tokenTexts(v, tok, "ılık"), tokenTexts(v, tok, "ilik"))
        // aksansız yazımlar da UNK değildir ve aksanlıdan farklıdır
        for ((a, b) in listOf("çocuk" to "cocuk", "göğüs" to "gogus", "şeker" to "seker", "üzüm" to "uzum", "ağaç" to "agac")) {
            assertFalse("[UNK]" in tokenTexts(v, tok, b))
            assertNotEquals("$a/$b", tokenTexts(v, tok, a), tokenTexts(v, tok, b))
        }
    }

    @Test
    fun nfdInput_isCharacterizedAsUnk_whileNfcIsNot() {
        val v = vocab
        assumeTrue("text_vocab.txt yok", v != null)
        val tok = tokenizer()
        // Altın taban: NFD (c + U+0327) sözcüğü [UNK]; NFC karşılığı değil. Budama bunu kötüleştirmemeli.
        assertFalse("[UNK]" in tokenTexts(v!!, tok, "çocuk"))
        assertEquals(1, tokenTexts(v, tok, "kâğıt").count { it == "[UNK]" })
        // UNK ayrışık harfli yalnızca o sözcüğü yutar; çevre sözcükler korunur
        val t = tokenTexts(v, tok, "kedi kâğıt köpek")
        assertEquals(listOf("[CLS]", "ke", "##di", "[UNK]", "k", "##öp", "##ek", "[SEP]"), t)
    }

    // ------------------------------------------------------------------ round-trip özelliği

    private fun isPunct(cp: Int): Boolean {
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return when (Character.getType(cp).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION,
            -> true
            else -> false
        }
    }

    /** Bağımsız beklenen: normalize edilmiş metin, her noktalama tek sözcük olacak biçimde boşlukla ayrılmış. */
    private fun expectedBasicTokens(normalized: String): String {
        val sb = StringBuilder()
        normalized.codePoints().forEach { cp ->
            if (isPunct(cp)) sb.append(' ').appendCodePoint(cp).append(' ') else sb.appendCodePoint(cp)
        }
        return sb.toString().trim().split(Regex("\\s+")).joinToString(" ")
    }

    private fun decode(tokens: List<String>): String {
        val sb = StringBuilder()
        for (t in tokens.subList(1, tokens.size - 1)) {
            if (t.startsWith("##")) sb.append(t.substring(2)) else sb.append(' ').append(t)
        }
        return sb.toString().trim()
    }

    private fun sentences(): List<Pair<String, String>> =
        raw("roundtrip_sentences.tsv").removeSuffix("\n").split('\n').map { it.substringBefore('\t') to it.substringAfter('\t') }

    @Test
    fun sentenceSet_isLargeCategorizedAndDisjointFromGoldenSet() {
        val s = sentences()
        assertTrue("en az 150 cümle: ${s.size}", s.size >= 150)
        assertTrue(s.map { it.first }.toSet().size >= 8)
        assertEquals("yinelenen cümle", s.size, s.map { it.second }.toSet().size)
        val golden = GoldenText.queries().map { it.query }.toSet()
        assertTrue("altın sorgu tekrarı", s.none { it.second in golden })
        // iki dilin ağırlığı: Türkçe ve İngilizce kategoriler var, Türkçe özel harfler yeterince geçiyor
        assertTrue(s.count { it.first.startsWith("tr_") } >= 100)
        assertTrue(s.count { it.first.startsWith("en_") } >= 30)
        assertTrue(s.count { sentence -> sentence.second.any { it in "âîûÂÎÛ" } } >= 12)
    }

    @Test
    fun roundTrip_tokenTextsJoinedEqualNormalizedInput_forAllExtraSentences() {
        val v = vocab
        assumeTrue("text_vocab.txt yok", v != null)
        val tok = tokenizer()
        val failures = ArrayList<String>()
        var unkSentences = 0
        for ((cat, s) in sentences()) {
            val normalized = QueryPreprocessor.normalize(s)
            val tokens = v!!.tokensOf(tok.encode(normalized))
            if (tokens.first() != "[CLS]" || tokens.last() != "[SEP]" || tokens.size > WordPieceTokenizer.DEFAULT_MAX_LENGTH) {
                failures.add("[$cat] çerçeve/uzunluk: $s")
                continue
            }
            if ("[UNK]" in tokens) {
                unkSentences++
                continue
            }
            if (decode(tokens) != expectedBasicTokens(normalized)) failures.add("[$cat] '$s' -> '${decode(tokens)}'")
        }
        assertTrue("round-trip bozuk (${failures.size}):\n" + failures.joinToString("\n"), failures.isEmpty())
        // Latin harf/rakam/noktalama içeren bu cümlelerde [UNK] beklenmez (K-14 Katman A: tüm Latin korunur)
        assertEquals("[UNK] içeren cümle sayısı", 0, unkSentences)
    }

    @Test
    fun roundTrip_isIdempotent_onDecodedText_andStableOnReencode() {
        val v = vocab
        assumeTrue("text_vocab.txt yok", v != null)
        val tok = tokenizer()
        for ((cat, s) in sentences()) {
            val tokens = v!!.tokensOf(tok.encode(QueryPreprocessor.normalize(s)))
            // decode edilmiş metin yeniden normalize + encode edilince aynı token dizisi (kararlılık)
            val again = v.tokensOf(tok.encode(QueryPreprocessor.normalize(decode(tokens))))
            assertEquals("[$cat] $s", tokens, again)
        }
    }

    // ------------------------------------------------------------------ kenar durumlar

    @Test
    fun emptyAndBlankQueries_areRejected_beforeTokenizer() {
        for (blank in listOf("", " ", "   \t\n", "  ", "‍​", "\u0000�", "\u0007")) {
            try {
                QueryPreprocessor.normalize(blank)
                fail("kabul edildi: ${blank.map { it.code }}")
            } catch (e: InvalidQueryException) {
                assertEquals(InvalidQueryException.Reason.EMPTY, e.reason)
            }
        }
        // Tokenizer'a doğrudan boş metin: yalnızca [CLS] [SEP]
        val v = vocab
        if (v != null) assertEquals(listOf("[CLS]", "[SEP]"), v.tokensOf(tokenizer().encode("")))
    }

    @Test
    fun overlongQueries_areRejected_andLimitIs256AfterNormalization() {
        fun reason(s: String) = try {
            QueryPreprocessor.normalize(s)
            null
        } catch (e: InvalidQueryException) {
            e.reason
        }
        assertEquals(null, reason("ç".repeat(256)))
        assertEquals(InvalidQueryException.Reason.TOO_LONG, reason("ç".repeat(257)))
        assertEquals(InvalidQueryException.Reason.TOO_LONG, reason("Ğ".repeat(QueryPreprocessor.MAX_RAW_LENGTH + 1)))
        // Boşlukla şişmiş ama temizlenince kısa: kabul (ham sınır içinde)
        assertEquals("kedi", QueryPreprocessor.normalize(" ".repeat(1000) + "kedi" + " ".repeat(1000)))
        // Ham sınırı aşan yalnızca boşluk da reddedilir (TOO_LONG öncelikli, işlenmez)
        assertEquals(InvalidQueryException.Reason.TOO_LONG, reason(" ".repeat(QueryPreprocessor.MAX_RAW_LENGTH + 1)))
    }

    @Test
    fun maxLengthQuery_isTruncatedTo128Tokens_endingWithSep() {
        val v = vocab
        assumeTrue("text_vocab.txt yok", v != null)
        val tok = tokenizer()
        val q = "ç ".repeat(128).trim()
        val t = v!!.tokensOf(tok.encode(QueryPreprocessor.normalize(q)))
        assertEquals(WordPieceTokenizer.DEFAULT_MAX_LENGTH, t.size)
        assertEquals("[CLS]", t.first())
        assertEquals("[SEP]", t.last())
        assertTrue("[UNK]" !in t)
        // 256 tek karakterli sözcük (a b c ...) 128'de kesilir
        val many = (1..128).joinToString(" ") { "a" }
        val ids = tok.encode(many)
        assertEquals(128, ids.size)
    }

    @Test
    fun wordLengthBoundary_100CharsSplits_101CharsIsSingleUnk() {
        val v = vocab
        assumeTrue("text_vocab.txt yok", v != null)
        val tok = tokenizer()
        val ok = v!!.tokensOf(tok.encode("a".repeat(100)))
        assertTrue("100 karakter UNK olmamalı: $ok", "[UNK]" !in ok)
        assertEquals(listOf("[CLS]", "[UNK]", "[SEP]"), v.tokensOf(tok.encode("a".repeat(101))))
        // UNK, ardışık sözcüklerin gövdesini bozmaz
        assertEquals(listOf("[CLS]", "[UNK]", "ke", "##di", "[SEP]"), v.tokensOf(tok.encode("ç".repeat(101) + " kedi")))
    }
}
