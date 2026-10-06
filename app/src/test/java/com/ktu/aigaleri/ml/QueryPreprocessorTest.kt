package com.ktu.aigaleri.ml

import com.ktu.aigaleri.domain.InvalidQueryException
import com.ktu.aigaleri.domain.InvalidQueryException.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QueryPreprocessorTest {
    private fun n(s: String) = QueryPreprocessor.normalize(s)

    private fun reasonOf(s: String): Reason {
        try {
            n(s)
        } catch (e: InvalidQueryException) {
            return e.reason
        }
        fail("InvalidQueryException bekleniyordu")
        error("unreachable")
    }

    @Test
    fun turkishLowercase_dottedAndDotlessI() {
        assertEquals("plajda gülen çocuk", n("Plajda Gülen ÇOCUK"))
        assertEquals("ışık altında kedi", n("IŞIK ALTINDA KEDİ"))
        assertEquals("istanbul", n("İstanbul"))
        assertEquals("ısparta", n("ISPARTA"))
        assertEquals("şğüöç", n("ŞĞÜÖÇ"))
    }

    @Test
    fun whitespace_isCollapsedAndTrimmed() {
        assertEquals("a b c", n("  a \t b\n\n c  "))
        assertEquals("a b", n("a  b"))
        assertEquals("a b", n("a　b"))
        assertEquals("a b", n("a" + " ".repeat(1000) + "b"))
    }

    @Test
    fun controlAndFormatCharacters_areDropped() {
        assertEquals("abc", n("a\u0000b\u0007c"))
        assertEquals("ab", n("a​b"))
        assertEquals("ab", n("a�b"))
        assertEquals("ab", n("a\uD800b"))
        assertEquals("ab", n("ab"))
    }

    @Test
    fun validSupplementaryCharacters_areKept() {
        assertEquals("kedi 🐱", n("KEDİ 🐱"))
    }

    @Test
    fun emptyAfterCleaning_isRejected() {
        assertEquals(Reason.EMPTY, reasonOf(""))
        assertEquals(Reason.EMPTY, reasonOf("   "))
        assertEquals(Reason.EMPTY, reasonOf("\u0000​"))
        assertEquals(Reason.EMPTY, reasonOf("\n\t  "))
    }

    @Test
    fun lengthLimit_isEnforcedAfterNormalization() {
        assertEquals(QueryPreprocessor.MAX_QUERY_LENGTH, n("a".repeat(QueryPreprocessor.MAX_QUERY_LENGTH)).length)
        assertEquals(Reason.TOO_LONG, reasonOf("a".repeat(QueryPreprocessor.MAX_QUERY_LENGTH + 1)))
    }

    @Test
    fun rawLengthGuard_rejectsHugeInputEvenIfMostlyWhitespace() {
        assertEquals(Reason.TOO_LONG, reasonOf(" ".repeat(QueryPreprocessor.MAX_RAW_LENGTH + 1)))
        assertEquals("a", n("a" + " ".repeat(QueryPreprocessor.MAX_RAW_LENGTH - 1)))
    }

    @Test
    fun exceptionMessage_doesNotEchoQuery() {
        val secret = "gizli-istem-" + "x".repeat(300)
        try {
            n(secret)
            fail()
        } catch (e: InvalidQueryException) {
            assertFalse(e.message!!.contains("gizli"))
        }
    }

    @Test
    fun normalization_isIdempotent() {
        val inputs = listOf("IŞIK  altında\tKEDİ", "İSTANBUL'DA gün batımı", "a​ b")
        for (s in inputs) assertEquals(n(s), n(n(s)))
    }

    @Test
    fun sqlLikeAndSpecialTokenText_isJustText() {
        assertEquals("'; drop table photo; --", n("'; DROP TABLE photo; --"))
        // Büyük harfli özel token metni küçültülür, böylece tokenizer'da [CLS]/[SEP] olarak eşleşemez.
        assertTrue(n("[CLS] [SEP]") == "[cls] [sep]")
    }
}
