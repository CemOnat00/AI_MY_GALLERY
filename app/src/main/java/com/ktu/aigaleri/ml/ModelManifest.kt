package com.ktu.aigaleri.ml

import com.ktu.aigaleri.domain.EmbeddingSpec

/**
 * Assets'teki model dosyası (assets/models/, git'te yok; tools/fetch_models.sh ile indirilir ve budanır).
 * Boyut ve SHA-256, docs/model-setup.md ile aynıdır (T-027'den sonra metin ONNX ve sözlük BUDANMIŞ sürümdür).
 */
data class ModelFile(
    val assetPath: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    init {
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) { "sha256 küçük harf hex olmalı" }
        require(sizeBytes > 0) { "sizeBytes > 0 olmalı" }
    }
}

/** Aday A model dosyaları ve indeks sürüm etiketi (karar: Cem, 2026-10-06). */
object ModelManifest {
    /**
     * Metin ve görüntü kodlayıcı birlikte bu etiketi taşır; T-006 indeksleyicisi aynı spec'i kullanmalıdır.
     * Niceleme/model değişirse etiket değişir (eski kayıtlar aramaya girmez).
     */
    const val MODEL_VERSION = "clip-b32-multilingual-v1:text-qint8-arm64,vision-int8"
    const val EMBEDDING_DIMENSION = 512
    val EMBEDDING_SPEC = EmbeddingSpec(EMBEDDING_DIMENSION, MODEL_VERSION)

    /**
     * sentence-transformers/clip-ViT-B-32-multilingual-v1 onnx/model_qint8_arm64.onnx (Apache-2.0), DEĞİŞTİRİLMİŞ
     * (T-027, K-14 Katman A): `embeddings.word_embeddings.weight_quantized` [119547, 768] -> [69642, 768] satır dilimi
     * (tools/prune_text_model.py); graf ve diğer ağırlıklar aynı. Orijinal: 135336307 bayt, SHA-256 3bed77e8...a8a7.
     * MODEL_VERSION değişmez: metin vektörleri orijinalle bit özdeştir, görüntü indeksi geçerli kalır.
     */
    val TEXT_ONNX = ModelFile(
        assetPath = "models/text_model_qint8_arm64.onnx",
        fileName = "text_model_qint8_arm64.onnx",
        sizeBytes = 97_009_267L,
        sha256 = "4e73a91f8463faaece382c033a25b035fe7adc54260910acb5d8e4a77872301c",
    )

    /** 2_Dense/model.safetensors içindeki linear.weight, ham float32 LE [512][768] (fetch_models.sh üretir). */
    val TEXT_DENSE = ModelFile(
        assetPath = "models/text_dense_768x512_f32.bin",
        fileName = "text_dense_768x512_f32.bin",
        sizeBytes = 1_572_864L,
        sha256 = "e5f6548cbcef6c62631b3946d89042ab4c8451762c7bb0a3be11eb6c480b686d",
    )

    /**
     * distilbert-base-multilingual-cased WordPiece sözlüğü, BUDANMIŞ (T-027, Katman A): 119547 satırdan 69642'si, sıra
     * korunarak (id = satır numarası). Özel token id'leri artık 100/101/102 DEĞİL ([UNK]=1, [CLS]=2, [SEP]=3); id sabiti
     * varsayılmaz, tokenizer [UNK]/[CLS]/[SEP]'i dizeyle bulur.
     * Orijinal: 995526 bayt, SHA-256 fe0fda7c...310c.
     */
    val TEXT_VOCAB = ModelFile(
        assetPath = "models/text_vocab.txt",
        fileName = "text_vocab.txt",
        sizeBytes = 520_398L,
        sha256 = "e3375077b9b203e67d8ed9ca271e68dc2e463af61a0c976041de6ee3e7b19cc8",
    )

    /** OpenAI CLIP ViT-B/32 görüntü kodlayıcı int8 (OpenAI CLIP MIT'den dönüşüm; Xenova lisans beyanı yok). T-006 kullanır; bu görevde yüklenmez. */
    val VISION_ONNX = ModelFile(
        assetPath = "models/vision_model_quantized.onnx",
        fileName = "vision_model_quantized.onnx",
        sizeBytes = 89_117_001L,
        sha256 = "583fd1110a514667812fee7d684952aaf82a99b959760c8d7dca7e0ab9839299",
    )
}
