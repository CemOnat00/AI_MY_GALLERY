package com.ktu.aigaleri.ml

import com.ktu.aigaleri.domain.EmbeddingSpec
import com.ktu.aigaleri.domain.TextEncoder
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Sorgu şablon topluluğu (prompt ensemble, T-033): çıplak kodlayıcının ([base]) ÜSTÜNDE ayrı bir katman.
 * Çok dilli damıtılmış metin kodlayıcı "bardak", "fincan", "tabak" gibi nadir Türkçe TEK sözcüklerde neredeyse içeriksiz
 * vektör üretir; kısa sorgu bir şablona yerleştirilince ("bir bardak", "bir bardak fotoğrafı") ölçümde vekil sıralama
 * kalitesi artar. Şablonlu varyantların vektörleri ortalanıp L2 normalize edilir. Ölçüm, seçilen küme ve sınırlar:
 * docs/query-ensemble.md; Python referansı: tools/query_ensemble.py (aynı kural; altın dosya `query_ensemble_golden.tsv`).
 *
 * Sözleşme [TextEncoder] ile aynıdır ([embeddingSpec] değişmez, 512 boyut, L2 normalize, [com.ktu.aigaleri.domain.InvalidQueryException]).
 * Görüntü vektörleri ve `MODEL_VERSION` değişmez; yeniden indeksleme gerekmez. Yalnızca ARAMA yolunda kullanılır; ısınma
 * ([OnnxTextEncoder.warmUp]) ve altın testler çıplak kodlayıcıyı kullanmaya devam eder.
 *
 * Davranış:
 * - Doğrulama ŞABLON EKLEMEDEN ÖNCE ve çıplak sorguya yapılır ([QueryPreprocessor.normalize]); geçersiz sorguda [base]
 *   hiç çağrılmaz ve [com.ktu.aigaleri.domain.InvalidQueryException] aynen yayılır.
 * - Şablon yalnızca normalleştirilmiş sorgu en çok [maxWords] sözcük VE en çok [maxChars] karakterse uygulanır
 *   (ölçüm: kısa sorguda kazanç büyük, 4+ sözcükte ihmal edilebilir ve çıkarım sayısı boşuna iki katına çıkar). Aksi halde
 *   YALNIZCA çıplak sorgu kodlanır (`base.encode(query)`, tek çıkarım; ensemble yok).
 * - Çift şablon önleme: sorgu "bir" veya sayı sözcüğüyle (bir, iki, üç, dört, beş, altı, yedi, sekiz, dokuz, on) ya da
 *   rakamla başlıyorsa şablonun ÖN EKİ ("bir ") eklenmez (çift "bir" olmaz, sayı anlamı bozulmaz); sorgu
 *   fotoğraf/fotoğrafı/foto/resim/resmi ile bitiyorsa şablonun SON EKİ (" fotoğrafı") eklenmez. Ön/son eki atılmış varyant
 *   çıplak sorguyla aynıysa ya da başka bir varyantın kopyasıysa elenir; hiç varyant kalmazsa çıplak yol.
 * - Uzunluk sınırları: kurucu `maxChars + şablon fazlası + 2 <= 128` koşulunu zorlar; bir token en az bir karakter olduğundan
 *   şablonlu varyantlar [WordPieceTokenizer]'ın 128 token kırpmasına asla ulaşmaz (şablon son eki kırpılıp sessizce
 *   düşmez) ve varyant [QueryPreprocessor.MAX_QUERY_LENGTH]'i (256) aşamaz (varyantta TOO_LONG oluşmaz). Uzun sorgu çıplak yola düşer.
 * - Varyantlar ARDIŞIKTIR. [base] bir [BatchTextEncoder] ise (üretimde [OnnxTextEncoder]) hepsi TEK `encodeAll` çağrısıyla,
 *   yani tek [SingleSessionSlot] kilidi altında kodlanır: indeksleme sürerken varyantlar arasına görüntü oturumu girip
 *   metin oturumunu kapatamaz (arama başına tek oturum açılışı). Değilse her varyant `base.encode` ile (kilit ayrı alınır).
 *   İptal her varyantın ÖNCESİNDE ve son varyanttan SONRA denetlenir (`CancellationException` yayılır, kısmi sonuç dönmez).
 *   [base] hataları ([ModelException] vb.) aynen yayılır.
 * - Maliyet: şablon uygulanan sorguda en çok `templates.size` (varsayılan 2) ORT çıkarımı, uygulanmayanda 1. Cihazda ölçülmedi.
 *
 * İstem, varyant ve vektör log'a yazılmaz.
 */
class PromptEnsembleTextEncoder(
    private val base: TextEncoder,
    private val templates: List<String> = DEFAULT_TEMPLATES,
    private val maxWords: Int = DEFAULT_MAX_WORDS,
    private val maxChars: Int = DEFAULT_MAX_CHARS,
) : TextEncoder {
    private val prefixes: List<String>
    private val suffixes: List<String>

    init {
        require(templates.isNotEmpty()) { "en az bir şablon gerekli" }
        require(templates.all { it.split(PLACEHOLDER).size == 2 }) { "her şablon $PLACEHOLDER'i tam bir kez içermeli" }
        require(maxWords >= 1) { "maxWords >= 1 olmalı" }
        require(maxChars in 1..QueryPreprocessor.MAX_QUERY_LENGTH) { "maxChars 1..${QueryPreprocessor.MAX_QUERY_LENGTH} olmalı" }
        val worst = templates.maxOf { it.length - PLACEHOLDER.length } + maxChars + 2
        require(worst <= WordPieceTokenizer.DEFAULT_MAX_LENGTH) {
            "şablonlu varyant token kırpmasına ulaşabilir: $worst > ${WordPieceTokenizer.DEFAULT_MAX_LENGTH}"
        }
        prefixes = templates.map { it.substringBefore(PLACEHOLDER) }
        suffixes = templates.map { it.substringAfter(PLACEHOLDER) }
    }

    override val embeddingSpec: EmbeddingSpec get() = base.embeddingSpec

    /** @throws com.ktu.aigaleri.domain.InvalidQueryException çıplak sorgu geçersizse (şablonlardan önce); model hataları [base]'ten. */
    override suspend fun encode(query: String): FloatArray {
        val normalized = QueryPreprocessor.normalize(query)
        val variants = variantsFor(normalized) ?: return base.encode(query)

        val dim = embeddingSpec.dimension
        val vectors: List<FloatArray> = if (base is BatchTextEncoder) {
            coroutineContext.ensureActive()
            base.encodeAll(variants).also { check(it.size == variants.size) { "toplu kodlayıcı beklenmeyen sayıda vektör döndürdü" } }
        } else {
            variants.map { variant ->
                coroutineContext.ensureActive()
                base.encode(variant)
            }
        }
        val acc = DoubleArray(dim)
        for (v in vectors) {
            check(v.size == dim) { "kodlayıcı beklenmeyen boyutta vektör döndürdü" }
            for (i in 0 until dim) acc[i] += v[i].toDouble()
        }
        coroutineContext.ensureActive()
        val n = vectors.size.toDouble()
        return EmbeddingMath.l2Normalize(FloatArray(dim) { (acc[it] / n).toFloat() })
    }

    /**
     * Normalleştirilmiş sorgu için şablonlu varyant metinleri (en az bir); şablon uygulanmayacaksa null (çıplak yol).
     * `tools/query_ensemble.py` `variants_of` ile AYNI kural olmalıdır (altın dosya bunu sınar).
     */
    internal fun variantsFor(normalized: String): List<String>? {
        val words = normalized.count { it == ' ' } + 1
        if (words > maxWords || normalized.length > maxChars) return null
        val dropPrefix = startsWithCountOrDigit(normalized)
        val dropSuffix = endsWithPhotoWord(normalized)
        val variants = templates.indices.map { i ->
            (if (dropPrefix) "" else prefixes[i]) + normalized + (if (dropSuffix) "" else suffixes[i])
        }.filter { it != normalized }.distinct()
        return variants.ifEmpty { null }
    }

    private fun startsWithCountOrDigit(normalized: String): Boolean =
        normalized.first().isDigit() || normalized.substringBefore(' ') in COUNT_WORDS

    private fun endsWithPhotoWord(normalized: String): Boolean = normalized.substringAfterLast(' ') in PHOTO_WORDS

    companion object {
        const val PLACEHOLDER = "{q}"

        /** Ölçümle seçilen küme (docs/query-ensemble.md). Sıra altın dosyayla aynıdır. */
        val DEFAULT_TEMPLATES: List<String> = listOf("bir {q}", "bir {q} fotoğrafı")

        /** Şablon en çok bu kadar sözcüklü sorguya uygulanır. */
        const val DEFAULT_MAX_WORDS = 3

        /** Şablon en çok bu kadar karakterli (UTF-16) normalleştirilmiş sorguya uygulanır. */
        const val DEFAULT_MAX_CHARS = 64

        /** Bu sözcüklerle başlayan sorguya "bir " ön eki eklenmez (tools/query_ensemble.py `COUNT_WORDS` ile aynı). */
        private val COUNT_WORDS = setOf("bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz", "on")

        /** Bu sözcüklerle biten sorguya " fotoğrafı" son eki eklenmez (tools/query_ensemble.py `PHOTO_WORDS` ile aynı). */
        private val PHOTO_WORDS = setOf("fotoğraf", "fotoğrafı", "foto", "resim", "resmi")
    }
}
