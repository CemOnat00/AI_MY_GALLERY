package com.ktu.aigaleri.ml

import com.ktu.aigaleri.domain.EmbeddingSpec
import com.ktu.aigaleri.domain.TextEncoder
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Sorgu şablon topluluğu (prompt ensemble, T-033): çıplak kodlayıcının ([base]) ÜSTÜNDE ayrı bir katman.
 * Çok dilli damıtılmış metin kodlayıcı "bardak", "fincan", "tabak" gibi nadir Türkçe TEK sözcüklerde neredeyse içeriksiz
 * vektör üretir; kısa sorgu bir şablona yerleştirilince ("bir bardak", "bir bardak fotoğrafı") vektör belirgin biçimde
 * iyileşir. Şablonlu varyantların vektörleri ortalanıp L2 normalize edilir. Ölçüm, seçilen küme ve sınırlar:
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
 * - Uzunluk sınırları: kurucu `maxChars + şablon fazlası + 2 <= 128` koşulunu zorlar; bir token en az bir karakter olduğundan
 *   şablonlu varyantlar [WordPieceTokenizer]'ın 128 token kırpmasına asla ulaşmaz (şablon son eki kırpılıp sessizce
 *   düşmez) ve varyant [QueryPreprocessor.MAX_QUERY_LENGTH]'i (256) aşamaz (varyantta TOO_LONG oluşmaz). Uzun sorgu çıplak yola düşer.
 * - Varyantlar ARDIŞIKTIR (tek ONNX oturumu [SingleSessionSlot] kilidiyle zaten seri): her [base] çağrısı kilidi ayrı alır.
 *   İptal her varyantın ÖNCESİNDE ve son varyanttan SONRA denetlenir (`CancellationException` yayılır, kısmi sonuç dönmez).
 *   [base] hataları ([ModelException] vb.) aynen yayılır.
 * - Maliyet: şablon uygulanan sorguda `templates.size` (varsayılan 2) ORT çıkarımı, uygulanmayanda 1. Cihazda ölçülmedi.
 *
 * İstem, varyant ve vektör log'a yazılmaz.
 */
class PromptEnsembleTextEncoder(
    private val base: TextEncoder,
    private val templates: List<String> = DEFAULT_TEMPLATES,
    private val maxWords: Int = DEFAULT_MAX_WORDS,
    private val maxChars: Int = DEFAULT_MAX_CHARS,
) : TextEncoder {
    init {
        require(templates.isNotEmpty()) { "en az bir şablon gerekli" }
        require(templates.all { it.contains(PLACEHOLDER) }) { "her şablon $PLACEHOLDER içermeli" }
        require(maxWords >= 1) { "maxWords >= 1 olmalı" }
        require(maxChars in 1..QueryPreprocessor.MAX_QUERY_LENGTH) { "maxChars 1..${QueryPreprocessor.MAX_QUERY_LENGTH} olmalı" }
        val worst = templates.maxOf { it.length - PLACEHOLDER.length } + maxChars + 2
        require(worst <= WordPieceTokenizer.DEFAULT_MAX_LENGTH) {
            "şablonlu varyant token kırpmasına ulaşabilir: $worst > ${WordPieceTokenizer.DEFAULT_MAX_LENGTH}"
        }
    }

    override val embeddingSpec: EmbeddingSpec get() = base.embeddingSpec

    /** @throws com.ktu.aigaleri.domain.InvalidQueryException çıplak sorgu geçersizse (şablonlardan önce); model hataları [base]'ten. */
    override suspend fun encode(query: String): FloatArray {
        val normalized = QueryPreprocessor.normalize(query)
        val variants = variantsFor(normalized) ?: return base.encode(query)

        val dim = embeddingSpec.dimension
        val acc = DoubleArray(dim)
        for (variant in variants) {
            coroutineContext.ensureActive()
            val v = base.encode(variant)
            check(v.size == dim) { "kodlayıcı beklenmeyen boyutta vektör döndürdü" }
            for (i in 0 until dim) acc[i] += v[i].toDouble()
        }
        coroutineContext.ensureActive()
        val n = variants.size.toDouble()
        return EmbeddingMath.l2Normalize(FloatArray(dim) { (acc[it] / n).toFloat() })
    }

    /**
     * Normalleştirilmiş sorgu için şablonlu varyant metinleri; şablon uygulanmayacaksa null (çıplak yol).
     * `tools/query_ensemble.py` `variants_of` ile AYNI kural olmalıdır (altın dosya bunu sınar).
     */
    internal fun variantsFor(normalized: String): List<String>? {
        val words = normalized.count { it == ' ' } + 1
        if (words > maxWords || normalized.length > maxChars) return null
        return templates.map { it.replace(PLACEHOLDER, normalized) }
    }

    companion object {
        const val PLACEHOLDER = "{q}"

        /** Ölçümle seçilen küme (docs/query-ensemble.md). Sıra altın dosyayla aynıdır. */
        val DEFAULT_TEMPLATES: List<String> = listOf("bir {q}", "bir {q} fotoğrafı")

        /** Şablon en çok bu kadar sözcüklü sorguya uygulanır. */
        const val DEFAULT_MAX_WORDS = 3

        /** Şablon en çok bu kadar karakterli (UTF-16) normalleştirilmiş sorguya uygulanır. */
        const val DEFAULT_MAX_CHARS = 64
    }
}
