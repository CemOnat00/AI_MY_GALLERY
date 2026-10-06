package com.ktu.aigaleri.ml

import java.io.BufferedReader
import java.io.InputStream

/**
 * Kütüphanesiz BERT WordPiece tokenizer (distilbert-base-multilingual-cased, T-007).
 * HF `tokenizers` BertNormalizer + BertPreTokenizer + WordPiece + TemplateProcessing davranışını izler:
 * - clean_text: NUL, U+FFFD ve kontrol/biçim karakterleri atılır, her boşluk ' ' olur;
 * - handle_chinese_chars: CJK karakterlerin çevresine boşluk eklenir;
 * - küçük harfe çevirme ve aksan atma YOK (cased sözlük; Türkçe küçük harf [QueryPreprocessor]'da yapılır);
 * - boşlukla ayır, sonra noktalama işaretlerini ayrı sözcük yap;
 * - WordPiece: en uzun eşleşme, devam parçaları "##"; 100 karakteri aşan veya parçalanamayan sözcük tek [UNK];
 * - çıktı: [CLS] ... [SEP]; en fazla [maxLength] token (özel token'lar dahil), fazlası kesilir.
 *
 * Saf Kotlin, Android bağımlılığı yok; iş parçacığı güvenlidir (değişmez durum).
 */
class WordPieceTokenizer(
    private val vocab: Map<String, Int>,
    val maxLength: Int = DEFAULT_MAX_LENGTH,
) {
    private val clsId = vocab[CLS] ?: throw IllegalArgumentException("sözlükte $CLS yok")
    private val sepId = vocab[SEP] ?: throw IllegalArgumentException("sözlükte $SEP yok")
    private val unkId = vocab[UNK] ?: throw IllegalArgumentException("sözlükte $UNK yok")

    init {
        require(maxLength >= 2) { "maxLength >= 2 olmalı" }
    }

    /** Metni [CLS] + parçalar + [SEP] kimliklerine çevirir; uzunluk <= [maxLength]. */
    fun encode(text: String): IntArray {
        val budget = maxLength - 2
        val body = ArrayList<Int>(minOf(budget, 32))
        val cleaned = cleanAndSpaceChinese(text)
        var i = 0
        val n = cleaned.length
        while (i < n && body.size < budget) {
            // Boşlukla ayrılmış sözcük
            while (i < n && cleaned[i] == ' ') i++
            if (i >= n) break
            val start = i
            while (i < n && cleaned[i] != ' ') i++
            splitPunctuation(cleaned.substring(start, i)) { piece ->
                if (body.size < budget) wordPiece(piece, body)
            }
        }
        val out = IntArray(minOf(body.size, budget) + 2)
        out[0] = clsId
        for (k in 0 until out.size - 2) out[k + 1] = body[k]
        out[out.size - 1] = sepId
        return out
    }

    private fun cleanAndSpaceChinese(text: String): String {
        val sb = StringBuilder(text.length + 8)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == 0 || cp == 0xFFFD) continue
            if (isBertWhitespace(cp)) {
                sb.append(' ')
                continue
            }
            if (isBertControl(cp)) continue
            if (isChineseChar(cp)) {
                sb.append(' ').appendCodePoint(cp).append(' ')
            } else {
                sb.appendCodePoint(cp)
            }
        }
        return sb.toString()
    }

    /** Sözcüğü noktalama işaretlerinde böler; her işaret ayrı parça olur. */
    private inline fun splitPunctuation(word: String, emit: (String) -> Unit) {
        var start = 0
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            val len = Character.charCount(cp)
            if (isBertPunctuation(cp)) {
                if (i > start) emit(word.substring(start, i))
                emit(word.substring(i, i + len))
                start = i + len
            }
            i += len
        }
        if (start < word.length) emit(word.substring(start))
    }

    private fun wordPiece(word: String, out: MutableList<Int>) {
        val cps = word.codePoints().toArray()
        if (cps.size > MAX_CHARS_PER_WORD) {
            out.add(unkId)
            return
        }
        val pieces = ArrayList<Int>(4)
        var start = 0
        while (start < cps.size) {
            var end = cps.size
            var found = -1
            while (end > start) {
                val sub = String(cps, start, end - start)
                val key = if (start > 0) "##$sub" else sub
                val id = vocab[key]
                if (id != null) {
                    found = id
                    break
                }
                end--
            }
            if (found < 0) {
                out.add(unkId)
                return
            }
            pieces.add(found)
            start = end
        }
        out.addAll(pieces)
    }

    companion object {
        const val CLS = "[CLS]"
        const val SEP = "[SEP]"
        const val UNK = "[UNK]"
        const val DEFAULT_MAX_LENGTH = 128
        private const val MAX_CHARS_PER_WORD = 100

        /** vocab.txt: satır numarası (0'dan) = kimlik. Akış kapatılır. */
        fun fromVocabStream(stream: InputStream, maxLength: Int = DEFAULT_MAX_LENGTH): WordPieceTokenizer {
            val vocab = HashMap<String, Int>(1 shl 18)
            stream.bufferedReader(Charsets.UTF_8).use { r: BufferedReader ->
                var id = 0
                while (true) {
                    val line = r.readLine() ?: break
                    vocab.putIfAbsent(line, id)
                    id++
                }
            }
            return WordPieceTokenizer(vocab, maxLength)
        }

        private fun isBertWhitespace(cp: Int): Boolean =
            cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code ||
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85

        private fun isBertControl(cp: Int): Boolean {
            if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
            return when (Character.getType(cp).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE -> true
                else -> false
            }
        }

        private fun isBertPunctuation(cp: Int): Boolean {
            if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
            return when (Character.getType(cp).toByte()) {
                Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION,
                Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
                -> true
                else -> false
            }
        }

        private fun isChineseChar(cp: Int): Boolean =
            cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF ||
                cp in 0x2A700..0x2B73F || cp in 0x2B740..0x2B81F || cp in 0x2B820..0x2CEAF ||
                cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F
    }
}
