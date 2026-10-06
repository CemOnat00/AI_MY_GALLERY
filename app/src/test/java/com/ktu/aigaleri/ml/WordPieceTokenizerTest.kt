package com.ktu.aigaleri.ml

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class WordPieceTokenizerTest {
    private val tokens = listOf(
        "[PAD]", "[UNK]", "[CLS]", "[SEP]", // 0..3
        "ışık", "altında", "kedi", "ke", "##di", // 4..8
        "gül", "##en", "ş", "##ş", "ğ", "##ğ", "i", // 9..15
        "'", ",", "!", "çocuk", "istanbul", "da", // 16..21
        "猫", "a", "##a", // 22..24
    )
    private val vocab = tokens.withIndex().associate { it.value to it.index }
    private val tok = WordPieceTokenizer(vocab)

    private fun ids(vararg v: Int) = v

    private fun enc(s: String, t: WordPieceTokenizer = tok) = t.encode(s)

    @Test
    fun simpleSentence_addsClsAndSep() {
        assertArrayEquals(ids(2, 4, 5, 6, 3), enc("ışık altında kedi"))
    }

    @Test
    fun emptyAndWhitespaceOnly_giveOnlySpecialTokens() {
        assertArrayEquals(ids(2, 3), enc(""))
        assertArrayEquals(ids(2, 3), enc("  \t\n "))
    }

    @Test
    fun tabsAndNewlines_actAsSeparators() {
        assertArrayEquals(ids(2, 4, 5, 6, 3), enc("ışık\taltında\nkedi"))
    }

    @Test
    fun wordPiece_greedyLongestMatchAndContinuationPrefix() {
        assertArrayEquals(ids(2, 7, 8, 3), enc("kedi", WordPieceTokenizer(vocab - "kedi")))
        assertArrayEquals(ids(2, 13, 12, 3), enc("ğş"))
        assertArrayEquals(ids(2, 11, 14, 3), enc("şğ"))
        assertArrayEquals(ids(2, 9, 10, 3), enc("gülen"))
    }

    @Test
    fun unknownWord_becomesSingleUnk_notPartial() {
        assertArrayEquals(ids(2, 1, 3), enc("xyz"))
        // "ke" ve "##ş" var ama "##x" yok: sözcüğün tamamı tek [UNK] olur.
        assertArrayEquals(ids(2, 1, 3), enc("keşx"))
        assertArrayEquals(ids(2, 6, 1, 6, 3), enc("kedi xyz kedi"))
    }

    @Test
    fun turkishCharacters_notInVocabAreUnk_withoutAccentStripping() {
        // Aksan atma yok: "İ" sözlükte yok, küçük harfe çevirme tokenizer'ın işi değil.
        assertArrayEquals(ids(2, 1, 3), enc("İ"))
        assertArrayEquals(ids(2, 15, 3), enc("i"))
    }

    @Test
    fun punctuation_isSplitIntoOwnTokens() {
        assertArrayEquals(ids(2, 20, 16, 21, 17, 6, 18, 3), enc("istanbul'da, kedi!"))
    }

    @Test
    fun chineseCharacters_areSurroundedBySpaces() {
        assertArrayEquals(ids(2, 22, 6, 3), enc("猫kedi"))
        assertArrayEquals(ids(2, 6, 22, 6, 3), enc("kedi猫kedi"))
    }

    @Test
    fun controlAndFormatCharacters_areRemoved() {
        assertArrayEquals(ids(2, 6, 3), enc("ke​di"))
        assertArrayEquals(ids(2, 6, 3), enc("ke\u0000di"))
        assertArrayEquals(ids(2, 6, 3), enc("ke�di"))
    }

    @Test
    fun nonBreakingSpace_isSeparator() {
        assertArrayEquals(ids(2, 6, 6, 3), enc("kedi kedi"))
    }

    @Test
    fun supplementaryCharacter_isUnk() {
        assertArrayEquals(ids(2, 1, 3), enc("🐱"))
    }

    @Test
    fun veryLongInput_isTruncatedTo128WithSep() {
        val out = enc("kedi ".repeat(500))
        assertEquals(128, out.size)
        assertEquals(2, out.first())
        assertEquals(3, out.last())
        assertTrue(out.slice(1..126).all { it == 6 })
    }

    @Test
    fun truncationBoundary_126WordsFit_127AreCut() {
        assertEquals(128, enc("kedi ".repeat(126)).size)
        val cut = enc("kedi ".repeat(127))
        assertEquals(128, cut.size)
        assertEquals(3, cut.last())
    }

    @Test
    fun truncation_cutsInsideWordPieces() {
        val t = WordPieceTokenizer(vocab - "kedi", maxLength = 4)
        // ke ##di ke ##di -> yalnızca ilk 2 parça + özel token'lar
        assertArrayEquals(ids(2, 7, 8, 3), enc("kedi kedi", t))
    }

    @Test
    fun customMaxLength_isHonored() {
        assertArrayEquals(ids(2, 6, 6, 3), enc("kedi kedi kedi", WordPieceTokenizer(vocab, maxLength = 4)))
    }

    @Test
    fun wordLongerThan100Chars_isUnk_exactly100IsSplit() {
        assertArrayEquals(ids(2, 1, 3), enc("a".repeat(101)))
        val hundred = enc("a".repeat(100))
        assertEquals(102, hundred.size)
        assertEquals(23, hundred[1])
        assertEquals(24, hundred[2])
    }

    @Test
    fun specialTokenTextInInput_isNotTreatedAsSpecial() {
        // "[CLS]" -> "[", "CLS", "]" parçalarına bölünür; hiçbiri sözlükte olmadığından [UNK]; kimlik 2 üretilmez.
        val out = enc("[CLS]")
        assertArrayEquals(ids(2, 1, 1, 1, 3), out)
    }

    @Test
    fun missingSpecialToken_isRejected() {
        var failed = false
        try {
            WordPieceTokenizer(vocab - "[SEP]")
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun fromVocabStream_lineNumberIsId() {
        val text = tokens.joinToString("\n") + "\n"
        val t = WordPieceTokenizer.fromVocabStream(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        assertArrayEquals(ids(2, 4, 5, 6, 3), t.encode("ışık altında kedi"))
        assertArrayEquals(ids(2, 20, 16, 21, 3), t.encode("istanbul'da"))
    }

    @Test
    fun withPreprocessor_turkishCapitalsReachTheVocab() {
        val q = QueryPreprocessor.normalize("IŞIK ALTINDA KEDİ")
        assertEquals("ışık altında kedi", q)
        assertArrayEquals(ids(2, 4, 5, 6, 3), enc(q))
        // Önişleme olmadan büyük harfli metin sözlükte bulunmaz (cased sözlük).
        assertArrayEquals(ids(2, 1, 1, 1, 3), enc("IŞIK ALTINDA KEDİ"))
    }

    // --- Gerçek sözlük ve Python HF tokenizers referansı (model dosyası yoksa atlanır) ---

    @Test
    fun realVocab_matchesPythonTokenizersReference() {
        val vocabFile = File("src/main/assets/models/text_vocab.txt")
        assumeTrue("text_vocab.txt yok (tools/fetch_models.sh)", vocabFile.isFile)
        val real = vocabFile.inputStream().use { WordPieceTokenizer.fromVocabStream(it) }
        val lines = javaClass.getResourceAsStream("/ml/tokenizer_reference.tsv")!!
            .readBytes().toString(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
        assertTrue(lines.size >= 15)
        for (line in lines) {
            val tab = line.lastIndexOf('\t')
            val text = line.substring(0, tab)
            val expected = line.substring(tab + 1).trim().split(' ').map { it.toInt() }.toIntArray()
            assertArrayEquals("referans uyuşmadı (satır uzunluğu ${text.length})", expected, real.encode(text))
        }
    }
}
