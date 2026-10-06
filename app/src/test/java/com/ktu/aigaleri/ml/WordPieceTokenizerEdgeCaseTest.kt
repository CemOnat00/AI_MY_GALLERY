package com.ktu.aigaleri.ml

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** T-007 QA: WordPieceTokenizer sınır durumları (küçük sözlükle; gerçek sözlük testi WordPieceTokenizerTest'te). */
class WordPieceTokenizerEdgeCaseTest {
    private val tokens = listOf(
        "[PAD]", "[UNK]", "[CLS]", "[SEP]", // 0..3
        "ışık", "kedi", "ke", "##di", "ı", "i", "ısparta", "istanbul", // 4..11
        "猫", "a", "##a", ",", "!", "-", "x", // 12..18
    )
    private val vocab = tokens.withIndex().associate { it.value to it.index }
    private val tok = WordPieceTokenizer(vocab)
    private val unk = 1
    private val cls = 2
    private val sep = 3

    private fun enc(s: String, t: WordPieceTokenizer = tok) = t.encode(s)

    private fun ids(vararg v: Int) = v

    @Test
    fun onlyInvisibleOrControl_giveOnlySpecials() {
        assertArrayEquals(ids(cls, sep), enc("\u0000\u0001​﻿\uD800�"))
        assertArrayEquals(ids(cls, sep), enc(" 　 \r\n\t"))
    }

    @Test
    fun emojiAndSurrogates() {
        assertArrayEquals(ids(cls, unk, sep), enc("🐱"))
        assertArrayEquals(ids(cls, unk, sep), enc("🐱🐱🐱")) // bitişik: tek sözcük, tek [UNK]
        assertArrayEquals(ids(cls, unk, unk, sep), enc("🐱 🐱"))
        assertArrayEquals(ids(cls, unk, sep), enc("kedi🐱")) // sözcüğün tamamı [UNK]
        assertArrayEquals(ids(cls, 5, sep), enc("ke\uD83Ddi")) // yalnız yüksek vekil atılır
        assertArrayEquals(ids(cls, 5, sep), enc("ke\uDC31di")) // yalnız alçak vekil atılır
        assertArrayEquals(ids(cls, unk, sep), enc("👨‍👩‍👧")) // ZWJ atılır, tek sözcük
        assertArrayEquals(ids(cls, unk, sep), enc("🇹🇷"))
    }

    @Test
    fun turkishCasing_directToTokenizer_isNotFolded() {
        assertArrayEquals(ids(cls, 8, 9, sep), enc("ı i"))
        assertArrayEquals(ids(cls, unk, unk, sep), enc("I İ"))
        // Önişlemden geçince dört harf de sözlükteki karşılığına düşer.
        val q = QueryPreprocessor.normalize("I İ i ı")
        assertEquals("ı i i ı", q)
        assertArrayEquals(ids(cls, 8, 9, 9, 8, sep), enc(q))
        assertArrayEquals(ids(cls, 10, 11, sep), enc(QueryPreprocessor.normalize("ISPARTA İSTANBUL")))
        assertArrayEquals(ids(cls, 4, 5, sep), enc(QueryPreprocessor.normalize("IŞIK KEDİ")))
    }

    @Test
    fun ligaturesAndCompatibilityChars_areNotNormalized() {
        for (s in listOf("ﬁ", "ǆ", "ŉ", "ß", "ẞ", "ﬃ", "Ⅷ", "ａ", "é")) {
            val out = enc(s)
            assertEquals(cls, out.first())
            assertEquals(sep, out.last())
            assertTrue(out.size in 3..4)
        }
        assertArrayEquals(ids(cls, unk, sep), enc("ﬁ"))
        assertArrayEquals(ids(cls, unk, sep), enc("ａ")) // tam genişlik a: NFKC yok
    }

    @Test
    fun veryLongSingleWord_isSingleUnk() {
        for (n in listOf(101, 150, 10_000, 1_000_000)) {
            assertArrayEquals("n=$n", ids(cls, unk, sep), enc("a".repeat(n)))
        }
        assertArrayEquals(ids(cls, unk, sep), enc("x".repeat(100))) // 100 x: "##x" yok -> tek [UNK]
        // 100 karakter: parçalanır (a ##a ... ), 102 token
        assertEquals(102, enc("a".repeat(100)).size)
    }

    @Test
    fun exactly128Boundary_withUnks_pieces_andPunctuation() {
        val unks = enc("zz ".repeat(126))
        assertEquals(128, unks.size)
        assertTrue(unks.slice(1..126).all { it == unk })
        assertEquals(sep, unks.last())
        assertEquals(128, enc("zz ".repeat(127)).size)
        assertEquals(128, enc("zz ".repeat(5000)).size)
        assertEquals(128, enc("zz ".repeat(125) + "kedi").size) // 125 + 1 gövde + 2 özel
        // Yalnız noktalama: 200 virgül -> 126 token + 2
        val commas = enc(",".repeat(200))
        assertEquals(128, commas.size)
        assertTrue(commas.slice(1..126).all { it == 15 })
        // Tam 126 gövde: son token bir parça sözcüğün ilk parçası olabilir, [SEP] her zaman sonda.
        val partial = enc("kedi ".repeat(125), WordPieceTokenizer(vocab - "kedi")) // ke ##di x125 = 250 token
        assertEquals(128, partial.size)
        assertEquals(6, partial[1]); assertEquals(7, partial[2])
        assertEquals(sep, partial.last())
    }

    @Test
    fun maxLengthTwo_givesOnlySpecials_andBelowTwoRejected() {
        assertArrayEquals(ids(cls, sep), enc("kedi kedi", WordPieceTokenizer(vocab, maxLength = 2)))
        assertArrayEquals(ids(cls, 5, sep), enc("kedi kedi", WordPieceTokenizer(vocab, maxLength = 3)))
        for (bad in listOf(1, 0, -1)) {
            try {
                WordPieceTokenizer(vocab, maxLength = bad)
                fail("maxLength=$bad reddedilmeliydi")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun allUnk_noOtherIdsLeak() {
        assertArrayEquals(ids(cls, unk, unk, unk, sep), enc("qqq www eee"))
        // "##a": '#' noktalamadır -> "#", "#", "a"; "##a" devam parçası olarak sözcük başında eşleşmez.
        assertArrayEquals(ids(cls, unk, unk, 13, sep), enc("##a"))
    }

    @Test
    fun cjk_isSplitPerCharacter_otherScriptsAreNot() {
        assertArrayEquals(ids(cls, 12, 12, 12, sep), enc("猫猫猫"))
        assertArrayEquals(ids(cls, unk, 12, unk, sep), enc("犬猫狗"))
        assertArrayEquals(ids(cls, unk, sep), enc("ねこ")) // hiragana CJK sayılmaz: tek sözcük
        assertArrayEquals(ids(cls, unk, sep), enc("고양이")) // hangul
        assertArrayEquals(ids(cls, unk, 12, sep), enc("𠀀猫")) // CJK Ext-B (supplementary) ayrılır
        assertArrayEquals(ids(cls, 5, 12, 5, sep), enc("kedi猫kedi"))
    }

    @Test
    fun adjacentPunctuation() {
        assertArrayEquals(ids(cls, 5, 15, 5, 16, 16, 16, sep), enc("kedi,kedi!!!"))
        assertArrayEquals(ids(cls, 5, 17, 5, sep), enc("kedi-kedi"))
        assertArrayEquals(ids(cls, 5, unk, 5, sep), enc("kedi+kedi")) // '+' ASCII aralığında: noktalama
        assertArrayEquals(ids(cls, 5, unk, 5, sep), enc("kedi，kedi")) // tam genişlik virgül
        assertArrayEquals(ids(cls, 5, unk, 5, sep), enc("kedi—kedi")) // em dash
        assertArrayEquals(ids(cls, 5, unk, 5, sep), enc("kedi$" + "kedi"))
        assertArrayEquals(ids(cls, unk, unk, unk, unk, sep), enc("(\"')"))
        assertArrayEquals(ids(cls, 15, 15, 15, sep), enc(",,,"))
    }

    @Test
    fun specialTokenTextIsNeverSpecial() {
        for (s in listOf("[CLS]", "[SEP]", "[PAD]", "[UNK]", "[MASK]")) {
            val out = enc(s)
            assertTrue("$s: gövdede özel kimlik üretilmemeli", out.slice(1 until out.size - 1).none { it == cls || it == sep })
            assertEquals(cls, out.first())
            assertEquals(sep, out.last())
        }
    }

    @Test
    fun hugeInput_isTruncatedAndFast() {
        val big = "kedi, ışık! ".repeat(200_000)
        val t0 = System.nanoTime()
        val out = enc(big)
        assertEquals(128, out.size)
        assertTrue("çok yavaş", (System.nanoTime() - t0) / 1_000_000 < 3000)
    }

    @Test
    fun vocabStream_edgeCases() {
        // CRLF sözlük: satır sonu bayt'ı kimliği bozmamalı.
        val crlf = tokens.joinToString("\r\n") + "\r\n"
        val t = WordPieceTokenizer.fromVocabStream(ByteArrayInputStream(crlf.toByteArray(Charsets.UTF_8)))
        assertArrayEquals(ids(cls, 5, sep), t.encode("kedi"))
        // Yinelenen satır: ilk kimlik korunur.
        val dup = (tokens + listOf("kedi")).joinToString("\n")
        val t2 = WordPieceTokenizer.fromVocabStream(ByteArrayInputStream(dup.toByteArray(Charsets.UTF_8)))
        assertArrayEquals(ids(cls, 5, sep), t2.encode("kedi"))
        // Boş akış: özel token yok -> reddedilir.
        try {
            WordPieceTokenizer.fromVocabStream(ByteArrayInputStream(ByteArray(0)))
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }
}
