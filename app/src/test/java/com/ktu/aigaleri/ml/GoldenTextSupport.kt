package com.ktu.aigaleri.ml

import java.io.File

/** Altın sorgu satırı (golden_queries.tsv): ham sorgu, kategori, ORİJİNAL modelin token METİNLERİ ve id'leri. */
data class GoldenQuery(
    val query: String,
    val category: String,
    val tokens: List<String>,
    val originalIds: List<Int>,
)

/**
 * T-026 test yardımcısı. Sözlük budama sonrası id'ler değişir; bu yüzden karşılaştırma token METNİ üzerindendir.
 * [TokenVocab], tokenizer'ın kullandığı sözlükten (satır numarası = id) id <-> metin eşlemesini yapar.
 */
class TokenVocab(val lines: List<String>) {
    val size: Int get() = lines.size

    /** id -> token metni; aralık dışı id AssertionError değil IllegalArgumentException verir (test hatası net olsun). */
    fun tokensOf(ids: IntArray): List<String> = ids.map {
        require(it in lines.indices) { "id $it sözlük aralığı dışında (0..${lines.size - 1})" }
        lines[it]
    }

    fun tokensOf(ids: List<Int>): List<String> = tokensOf(ids.toIntArray())

    fun idOf(token: String): Int = lines.indexOf(token).also { require(it >= 0) { "sözlükte yok: $token" } }

    companion object {
        /** `src/main/assets/models/text_vocab.txt` (git dışı); yoksa null (testler Assume ile atlanır). */
        fun vocabFile() = File("src/main/assets/models/${ModelManifest.TEXT_VOCAB.fileName}")

        fun fromAssetsOrNull(): TokenVocab? {
            val f = vocabFile()
            if (!f.isFile) return null
            // WordPieceTokenizer.fromVocabStream ile aynı satır kuralı: '\n' ile ayır, sondaki boş satır sayılmaz.
            val text = f.readBytes().toString(Charsets.UTF_8)
            val lines = text.split('\n').let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
            return TokenVocab(lines)
        }
    }
}

object GoldenText {
    const val QUERY_COUNT = 60
    const val VECTOR_DIM = 512

    /** ORİJİNAL (budanmamış) sözlüğün satır sayısı; id'lerin birebir karşılaştırılabildiği tek durum. */
    const val ORIGINAL_VOCAB_SIZE = 119_547

    /**
     * ALTIN [UNK] TABANI (T-026, orijinal model + sözlük; tools/golden_text.py çıktısı): 329 içerik token'ından
     * 2'si [UNK] (iki emoji sorgusu: "🐱 kedi", "plaj 🏖️ tatil"). Budama sonrası ne sayı ne oran artmamalı.
     */
    const val BASELINE_UNK_COUNT = 2
    const val BASELINE_BODY_TOKENS = 329

    private fun resource(name: String): String =
        GoldenText::class.java.getResourceAsStream("/ml/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    fun queries(): List<GoldenQuery> = resource("golden_queries.tsv").split('\n').filter { it.isNotEmpty() }.map { line ->
        val p = line.split('\t')
        require(p.size == 4) { "golden_queries.tsv: 4 sütun bekleniyor, ${p.size} var" }
        GoldenQuery(p[0], p[1], p[2].split(' '), p[3].split(' ').map { it.toInt() })
    }

    /** golden_text_vectors.tsv: sorgu -> 512 boyutlu L2 normalize vektör (ORİJİNAL model, 1 iş parçacığı). */
    fun vectors(): Map<String, FloatArray> = resource("golden_text_vectors.tsv").split('\n').filter { it.isNotEmpty() }
        .associate { line ->
            val tab = line.indexOf('\t')
            line.substring(0, tab) to line.substring(tab + 1).split(' ').map { it.toFloat() }.toFloatArray()
        }
}
