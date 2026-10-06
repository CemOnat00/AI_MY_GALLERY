package com.ktu.aigaleri.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.Context
import com.ktu.aigaleri.domain.EmbeddingSpec
import com.ktu.aigaleri.domain.ImageEncoder
import java.nio.FloatBuffer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/** Model çıktısı: ham (normalize edilmemiş) görüntü vektörü. Testte sahte, uygulamada ONNX Runtime. */
fun interface ImageEmbeddingRunner {
    /** [pixelValues]: `[3][224][224]` CHW float. Dönen: ham `image_embeds` (512). */
    fun run(pixelValues: FloatArray): FloatArray
}

/**
 * Model çıktısını doğrular ve L2 normalize eder (metin vektörüyle aynı [ModelManifest.EMBEDDING_SPEC]:
 * 512 boyut, norm 1, kosinüs = nokta çarpımı).
 */
class ImageEmbeddingPipeline(private val runner: ImageEmbeddingRunner) {
    fun embed(pixelValues: FloatArray): FloatArray {
        require(pixelValues.size == ImagePreprocessor.TENSOR_SIZE) { "girdi tensörü 3x224x224 olmalı" }
        val raw = runner.run(pixelValues)
        if (raw.size != ModelManifest.EMBEDDING_DIMENSION) {
            throw ModelException.Inference("output-shape", IllegalStateException("beklenmeyen vektör boyutu"))
        }
        return try {
            EmbeddingMath.l2Normalize(raw)
        } catch (e: IllegalStateException) {
            throw ModelException.Inference("output-values", e)
        }
    }
}

/**
 * ONNX Runtime ile OpenAI CLIP ViT-B/32 görüntü kodlayıcı (T-006). Model: Xenova/clip-vit-base-patch32
 * `onnx/vision_model_quantized.onnx` (int8; openai/clip-vit-base-patch32 dönüşümü, MIT lisansı; kaynak ve
 * SHA-256: docs/model-research.md, docs/model-setup.md). Girdi `pixel_values` [1,3,224,224], çıktı
 * `image_embeds` [1,512] (masaüstünde doğrulandı).
 *
 * Çalıştırma durumu: ön işleme Python/Pillow referansıyla, boru hattı sahte runner'la birim testlidir;
 * ORT Android üzerinde çalıştırma, süre ve bellek DOĞRULANMADI (cihaz yok). `ImageEncoderDeviceTest` yazıldı
 * ama ÇALIŞTIRILMADI.
 *
 * Tek oturum kuralı: oturum [SingleSessionSlot.shared] içinde "vision" anahtarıyla açılır; metin oturumu açılırsa
 * (arama) bu kapatılır ve bir sonraki [encode] yeniden açar (açma maliyeti: dosya doğrulama zaten yapıldı, yalnızca
 * oturum yükleme). Çözme ve ön işleme slot kilidi DIŞINDA yapılır (metin sorgusunu gereksiz bekletmez); kilit yalnızca
 * model çıkarımı sırasında tutulur. Ağır iş [dispatcher]'da (IO), iptal her adım arasında denetlenir.
 * [release] indeksleme bitince oturumu kapatır. ORT hataları [ModelException.Inference] olur: "open"/"native-load"
 * aşamaları model düzeyinde (tüm fotoğrafları etkiler, bkz. [ModelFailures]), "run"/"output-*" tek çıkarıma özgüdür.
 *
 * TEKİL KULLANIM: uygulamada tek örnek olmalıdır (AppDependencies). Log'a fotoğraf yolu, içerik veya vektör yazılmaz.
 */
class OnnxImageEncoder(
    private val store: ModelStore,
    private val loader: PixelLoader,
    private val slot: SingleSessionSlot = SingleSessionSlot.shared,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ImageEncoder {
    override val embeddingSpec: EmbeddingSpec = ModelManifest.EMBEDDING_SPEC

    // Yalnızca slot kilidi altında okunur/yazılır.
    private var pipeline: ImageEmbeddingPipeline? = null
    private var pipelineSession: OrtSession? = null

    override suspend fun encode(uri: String): FloatArray = withContext(dispatcher) {
        val job = currentCoroutineContext().job
        val cancelCheck = { job.ensureActive() }
        // Çözme + ön işleme: yalnızca küçük piksel dizisi ve 588 KB tensör; Bitmap yükleyicide serbest bırakılır.
        val input = ImagePreprocessor.toModelInput(loader.load(uri))
        cancelCheck()
        slot.withSession(SESSION_KEY, open = { openSession(cancelCheck) }, onClosed = ::dropReferences) { session ->
            pipelineFor(session).embed(input)
        }
    }

    override suspend fun release() {
        slot.close(SESSION_KEY, afterClose = ::dropReferences)
    }

    private fun dropReferences() {
        pipeline = null
        pipelineSession = null
    }

    private fun openSession(cancelCheck: () -> Unit): OrtSession {
        val modelFile = store.ensure(ModelManifest.VISION_ONNX, cancelCheck)
        cancelCheck()
        try {
            val env = OrtEnvironment.getEnvironment()
            try {
                env.setTelemetry(false)
            } catch (_: OrtException) {
            }
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(INTRA_OP_THREADS)
                options.setMemoryPatternOptimization(false)
                options.setCPUArenaAllocator(false)
                return env.createSession(modelFile.absolutePath, options)
            }
        } catch (e: OrtException) {
            throw ModelException.Inference("open", e)
        } catch (e: LinkageError) { // UnsatisfiedLinkError: yerel kütüphane yüklenemedi
            throw ModelException.Inference("native-load", e)
        }
    }

    private fun pipelineFor(session: OrtSession): ImageEmbeddingPipeline {
        val cached = pipeline
        if (cached != null && pipelineSession === session) return cached
        return ImageEmbeddingPipeline(OrtImageRunner(OrtEnvironment.getEnvironment(), session)).also {
            pipeline = it
            pipelineSession = session
        }
    }

    companion object {
        private const val SESSION_KEY = "vision"
        private const val INTRA_OP_THREADS = 2

        /** Uygulama bağlamından kurar (AppDependencies kendi [ModelStore]'unu verir). */
        fun create(
            context: Context,
            store: ModelStore,
            slot: SingleSessionSlot = SingleSessionSlot.shared,
        ): OnnxImageEncoder =
            OnnxImageEncoder(store, ContentResolverImageLoader(context.applicationContext.contentResolver), slot)
    }
}

/** ORT oturumundan `image_embeds` okur (batch 1). [OrtException] [ModelException.Inference] olur. */
internal class OrtImageRunner(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : ImageEmbeddingRunner {
    override fun run(pixelValues: FloatArray): FloatArray {
        val shape = longArrayOf(1, 3, ImagePreprocessor.SIZE.toLong(), ImagePreprocessor.SIZE.toLong())
        try {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(pixelValues), shape).use { input ->
                session.run(mapOf(INPUT_NAME to input)).use { result ->
                    val out = (result.get(OUTPUT_NAME).orElse(null) ?: result[0]) as OnnxTensor
                    val outShape = out.info.shape
                    if (!(outShape.size == 2 && outShape[0] == 1L && outShape[1] == ModelManifest.EMBEDDING_DIMENSION.toLong())) {
                        throw ModelException.Inference("output-shape", IllegalStateException("beklenmeyen model çıktı şekli"))
                    }
                    val fb = out.floatBuffer
                    return FloatArray(fb.remaining()).also { fb.get(it) }
                }
            }
        } catch (e: OrtException) {
            throw ModelException.Inference("run", e)
        }
    }

    private companion object {
        const val INPUT_NAME = "pixel_values"
        const val OUTPUT_NAME = "image_embeds"
    }
}
