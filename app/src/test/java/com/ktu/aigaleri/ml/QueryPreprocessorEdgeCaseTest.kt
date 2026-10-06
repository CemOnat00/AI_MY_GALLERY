package com.ktu.aigaleri.ml

import com.ktu.aigaleri.domain.InvalidQueryException
import com.ktu.aigaleri.domain.InvalidQueryException.Reason
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** T-007 QA: QueryPreprocessor sınır durumları (uzunluk sınırları, kontrol/RTL/boşluk, Türkçe harfler, fuzz). */
class QueryPreprocessorEdgeCaseTest {
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

    private val max = QueryPreprocessor.MAX_QUERY_LENGTH
    private val raw = QueryPreprocessor.MAX_RAW_LENGTH

    @Test
    fun exactLimit_ok_limitPlusOne_rejected_includingTrailingWhitespaceAndEmoji() {
        assertEquals(max, n("a".repeat(max) + "   ").length)
        assertEquals(max, n("   " + "a".repeat(max)).length)
        assertEquals(Reason.TOO_LONG, reasonOf("a".repeat(max + 1)))
        // 128 emoji = 256 UTF-16 birimi: sınırda kabul, bir fazlası ret.
        assertEquals(max, n("🐱".repeat(max / 2)).length)
        assertEquals(Reason.TOO_LONG, reasonOf("🐱".repeat(max / 2 + 1)))
        // Boşluklar tek boşluğa inince sınır içine giren uzun ham girdi kabul edilir.
        assertEquals("a b", n("a" + " ".repeat(500) + "b"))
    }

    @Test
    fun spacesBetweenWordsCountAfterCollapse() {
        // 128 x "a " = 255 karakter (son boşluk kırpılır) -> kabul; 129 x -> 257 -> ret.
        assertEquals(255, n("a ".repeat(128)).length)
        assertEquals(Reason.TOO_LONG, reasonOf("a ".repeat(129)))
    }

    @Test
    fun rawGuard_exactBoundary() {
        assertEquals(Reason.EMPTY, reasonOf(" ".repeat(raw)))
        assertEquals(Reason.TOO_LONG, reasonOf(" ".repeat(raw + 1)))
        assertEquals(Reason.TOO_LONG, reasonOf("a".repeat(raw)))
        assertEquals(Reason.TOO_LONG, reasonOf("\u0000".repeat(raw + 1)))
    }

    @Test
    fun hugeInput_isRejectedFastWithoutScanning() {
        val huge = "x".repeat(20_000_000)
        val t0 = System.nanoTime()
        assertEquals(Reason.TOO_LONG, reasonOf(huge))
        assertTrue("çok yavaş", (System.nanoTime() - t0) / 1_000_000 < 1000)
    }

    @Test
    fun onlyControlOrInvisible_isEmpty() {
        val inputs = listOf(
            "\u0000", "\u0000".repeat(100), "\u0001\u0002\u0003\u007F", "​‌‍﻿⁠",
            "‮‬‎‏", "�", "\uD800", "\uDC00\uD800", "", "󰀀", // özel kullanım
            "\t\n\r\u000B\u000C\u0085    　 ",
        )
        for (s in inputs) assertEquals("giriş: ${s.map { it.code }}", Reason.EMPTY, reasonOf(s))
    }

    @Test
    fun nulInsideWord_isDroppedNotSeparator() {
        assertEquals("kedi", n("ke\u0000di"))
        assertEquals("ke di", n("ke \u0000 di"))
    }

    @Test
    fun rtlTextIsKept_directionMarksAreDropped() {
        assertEquals("قطة على الشاطئ", n("قطة  على\tالشاطئ"))
        assertEquals("חתול", n("‫חתול‬"))
        assertEquals("kedi חתול", n("kedi ‏ חתול‎"))
    }

    @Test
    fun tabsAndLineBreaks_becomeSingleSpace() {
        assertEquals("a b", n("a\r\nb"))
        assertEquals("a b c d", n("a b c\u0085d"))
        assertEquals("a b", n("\n\n a\t\t\tb \r\n"))
    }

    @Test
    fun turkishI_allFourVariants() {
        assertEquals("ı", n("I"))
        assertEquals("i", n("İ"))
        assertEquals("i", n("i"))
        assertEquals("ı", n("ı"))
        assertEquals("ıi", n("Iİ"))
        assertEquals("çğiöşü", n("ÇĞİÖŞÜ"))
        assertEquals("çğıöşü", n("çğıöşü"))
        assertEquals("ıspırtı ııi", n("ISPIRTI IIİ"))
    }

    @Test
    fun turkishCapitalLengthsDoNotGrow() {
        // Türkçe yerel ayarda İ -> i (1 birim); varsayılan yerel ayarda "i̇" (2 birim) olurdu ve sınırı aşardı.
        assertEquals(max, n("İ".repeat(max)).length)
        assertEquals("i".repeat(max), n("İ".repeat(max)))
    }

    @Test
    fun mixedCaseSentences() {
        assertEquals("ığdır'da şöyle bir çocuk", n("IĞDIR'DA ŞÖYLE BİR ÇOCUK"))
        assertEquals("iğne ile ığdır", n("İĞNE İLE IĞDIR"))
    }

    @Test
    fun combiningDotAbove_afterCapitalI_doesNotCrashAndStaysBounded() {
        val out = n("İSTANBUL")
        assertTrue(out.endsWith("stanbul"))
        assertTrue(out.length <= 9)
    }

    @Test
    fun supplementaryAtBoundaryAndMixedWithControl() {
        assertEquals("🐱🐱", n("🐱\u0000🐱"))
        assertEquals("🐱 🐱", n("🐱 ​ 🐱"))
    }

    @Test
    fun errorsAreInvalidQueryException_notOtherRuntimeExceptions_forAnyInput() {
        val rnd = Random(20261006)
        val pool = intArrayOf(
            0, 7, 9, 10, 13, 32, 0x85, 0xA0, 0x200B, 0x202E, 0xFFFD, 0xD800, 0xDC00, 0xE000, 0x1F431,
            'a'.code, 'I'.code, 'İ'.code, 'ı'.code, 'ç'.code, 'Ş'.code, 0x05D0, 0x0627, 0x3042, 0x732B, 0x10FFFF,
        )
        repeat(2000) {
            val len = rnd.nextInt(raw + 300)
            val sb = StringBuilder()
            while (sb.length < len) {
                val cp = if (rnd.nextInt(4) == 0) rnd.nextInt(0x110000) else pool[rnd.nextInt(pool.size)]
                if (cp in 0xD800..0xDFFF) sb.append(cp.toChar()) else sb.appendCodePoint(cp)
            }
            val s = sb.toString()
            try {
                val out = n(s)
                assertTrue(out.isNotEmpty() && out.length <= max)
                assertEquals(out.trim(), out)
                assertFalse("çift boşluk", out.contains("  "))
                assertTrue(out.none { it == '\u0000' || it == '�' || it.isISOControl() })
                assertEquals("idempotent değil", out, n(out))
            } catch (_: InvalidQueryException) {
            }
        }
    }
}
