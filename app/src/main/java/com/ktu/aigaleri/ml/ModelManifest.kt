package com.ktu.aigaleri.ml

import com.ktu.aigaleri.domain.EmbeddingSpec

/**
 * Assets'teki model dosyası (assets/models/, git'te yok; tools/fetch_models.sh ile indirilir).
 * Boyut ve SHA-256, mobile/docs/model-research.md bölüm 6 ve docs/model-setup.md ile aynıdır.
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

    /** sentence-transformers/clip-ViT-B-32-multilingual-v1 onnx/model_qint8_arm64.onnx (Apache-2.0). */
    val TEXT_ONNX = ModelFile(
        assetPath = "models/text_model_qint8_arm64.onnx",
        fileName = "text_model_qint8_arm64.onnx",
        sizeBytes = 135_336_307L,
        sha256 = "3bed77e83926519660b5c0833cb3321cfde952331edec4f7d501c7d384b8a8a7",
    )

    /** 2_Dense/model.safetensors içindeki linear.weight, ham float32 LE [512][768] (fetch_models.sh üretir). */
    val TEXT_DENSE = ModelFile(
        assetPath = "models/text_dense_768x512_f32.bin",
        fileName = "text_dense_768x512_f32.bin",
        sizeBytes = 1_572_864L,
        sha256 = "e5f6548cbcef6c62631b3946d89042ab4c8451762c7bb0a3be11eb6c480b686d",
    )

    /** distilbert-base-multilingual-cased WordPiece sözlüğü (119547 satır). */
    val TEXT_VOCAB = ModelFile(
        assetPath = "models/text_vocab.txt",
        fileName = "text_vocab.txt",
        sizeBytes = 995_526L,
        sha256 = "fe0fda7c425b48c516fc8f160d594c8022a0808447475c1a7c6d6479763f310c",
    )

    /** OpenAI CLIP ViT-B/32 görüntü kodlayıcı int8 (Xenova, MIT). T-006 kullanır; bu görevde yüklenmez. */
    val VISION_ONNX = ModelFile(
        assetPath = "models/vision_model_quantized.onnx",
        fileName = "vision_model_quantized.onnx",
        sizeBytes = 89_117_001L,
        sha256 = "583fd1110a514667812fee7d684952aaf82a99b959760c8d7dca7e0ab9839299",
    )
}
