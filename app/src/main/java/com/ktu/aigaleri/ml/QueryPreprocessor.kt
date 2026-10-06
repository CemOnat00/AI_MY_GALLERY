package com.ktu.aigaleri.ml

import com.ktu.aigaleri.domain.InvalidQueryException
import java.util.Locale

/**
 * Arama istemi doğrulama ve normalleştirme (architecture.md Bölüm 8). Saf Kotlin, Android bağımlılığı yok.
 *
 * Sıra: (1) ham uzunluk koruması, (2) kontrol/biçim/vekil/özel kullanım karakterlerini ve U+FFFD'yi at,
 * her türlü boşluğu tek boşluğa indir ve kırp, (3) Türkçe yerel ayarla (`tr`) küçük harfe çevir
 * (I -> ı, İ -> i; metin modeli büyük/küçük harfe duyarlı sözlük kullanır, bkz. model-research.md bölüm 3),
 * (4) boşsa ve uzunluk sınırını aşıyorsa reddet.
 */
object QueryPreprocessor {
    /** Temizlikten önce kabul edilen en uzun ham girdi (çok büyük girdiyi işlemeden reddetmek için). */
    const val MAX_RAW_LENGTH = 2048

    /** Normalleştirilmiş sorgunun en fazla uzunluğu (UTF-16 birimi). Model ayrıca 128 token'da keser. */
    const val MAX_QUERY_LENGTH = 256

    private val TURKISH: Locale = Locale.forLanguageTag("tr-TR")

    /** @throws InvalidQueryException boş veya çok uzun girdide. */
    fun normalize(raw: String): String {
        if (raw.length > MAX_RAW_LENGTH) throw InvalidQueryException(InvalidQueryException.Reason.TOO_LONG)

        val sb = StringBuilder(raw.length)
        var pendingSpace = false
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            when {
                isSpace(cp) -> pendingSpace = true
                isDropped(cp) -> Unit
                else -> {
                    if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                    pendingSpace = false
                    sb.appendCodePoint(cp)
                }
            }
        }
        val result = sb.toString().lowercase(TURKISH)
        if (result.isEmpty()) throw InvalidQueryException(InvalidQueryException.Reason.EMPTY)
        if (result.length > MAX_QUERY_LENGTH) throw InvalidQueryException(InvalidQueryException.Reason.TOO_LONG)
        return result
    }

    private fun isSpace(cp: Int): Boolean =
        Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85

    private fun isDropped(cp: Int): Boolean {
        if (cp == 0 || cp == 0xFFFD) return true
        return when (Character.getType(cp).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE -> true
            else -> false
        }
    }
}
