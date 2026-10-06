package com.ktu.aigaleri.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.Context
import android.content.res.AssetManager
import com.ktu.aigaleri.domain.EmbeddingSpec
import com.ktu.aigaleri.domain.TextEncoder
import java.nio.LongBuffer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/** [AssetManager] üzerinden akış (ACCESS_STREAMING). */
class AssetManagerOpener(private val assets: AssetManager) : AssetOpener {
    override fun open(assetPath: String) = assets.open(assetPath, AssetManager.ACCESS_STREAMING)
}

/**
 * ONNX Runtime ile çok dilli CLIP metin kodlayıcı (T-007). Model: sentence-transformers/
 * clip-ViT-B-32-multilingual-v1 `model_qint8_arm64.onnx` (Apache-2.0, bkz. docs/model-setup.md).
 * Arama sırasında çalışan tek ML bileşenidir; görüntü modeli yüklenmez.
 *
 * Çalıştırma durumu: birim testlerle (sahte runner) ve masaüstü Python referansıyla doğrulandı; ORT Android
 * üzerinde çalıştırma, süre ve bellek DOĞRULANMADI. Cihaz testi `OnnxTextEncoderDeviceTest` yazıldı ama
 * ÇALIŞTIRILMADI (cihaz/emülatör yok).
 *
 * Bellek/süre: ilk [encode] çağrısında model dosyaları doğrulanıp filesDir'e kopyalanır (SHA-256) ve oturum
 * açılır; bu iş [Dispatchers.IO]'da yürür (ana thread'de değil) ve kopya sırasında iptale duyarlıdır
 * (oturum oluşturma [OrtSession] içinde bölünemez). İlk sorgu gecikmesi ölçülmedi; arama ekranı açılırken
 * [warmUp] çağrılır (T-008). Sonraki çağrılar açık oturumu kullanır.
 * Oturum dosya yolundan açılır (135 MB Java yığınına girmez), CPU arena kapalı, 2 iş parçacığı. [release]
 * oturumu ve sözlük/ağırlık belleğini bırakır; oturum başka bir model yüzünden slot'tan çıkarılırsa
 * ([SingleSessionSlot]) bu referanslar geri çağrıyla bırakılır ve sonraki [encode] yeniden açar.
 *
 * TEKİL KULLANIM KURALI: uygulamada tek [OnnxTextEncoder] örneği olmalıdır (ör. AppDependencies'te
 * singleton). Aynı slot ve anahtarı paylaşan iki örnekte [release] diğerinin oturumunu da kapatır
 * (diğeri bir sonraki [encode]'da yeniden açar), ve geri çağrı yalnızca oturumu açan örneğin referanslarını bırakır.
 *
 * ORT telemetrisi: AAR'ın TelemetryInitializer provider'ı manifestten kaldırılır; ek olarak oturum açılırken
 * `OrtEnvironment.setTelemetry(false)` çağrılır. ORT hataları [ModelException.Inference] olarak yüzeye çıkar.
 * Hiçbir istem, token veya vektör log'a yazılmaz.
 */
class OnnxTextEncoder(
    private val store: ModelStore,
    private val slot: SingleSessionSlot = SingleSessionSlot.shared,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TextEncoder {
    override val embeddingSpec: EmbeddingSpec = ModelManifest.EMBEDDING_SPEC

    // Yalnızca slot kilidi altında okunur/yazılır (withSession bloğu, onClosed ve release).
    private var tokenizer: WordPieceTokenizer? = null
    private var dense: FloatArray? = null
    private var pipeline: TextEmbeddingPipeline? = null
    private var pipelineSession: OrtSession? = null

    /** @throws com.ktu.aigaleri.domain.InvalidQueryException geçersiz sorgu; @throws ModelException model/ORT hatası. */
    override suspend fun encode(query: String): FloatArray {
        // Doğrulama önce: geçersiz sorguda model dosyalarına/oturuma dokunulmaz.
        val normalized = QueryPreprocessor.normalize(query)
        return withContext(dispatcher) {
            val job = currentCoroutineContext().job
            val cancelCheck = { job.ensureActive() }
            slot.withSession(
                SESSION_KEY,
                open = { openSession(cancelCheck) },
                onClosed = ::dropReferences,
            ) { session ->
                pipelineFor(session, cancelCheck).embed(normalized)
            }
        }
    }

    /**
     * Isınma: model dosyalarını doğrular, oturumu açar ve sabit bir ifadeyle bir çıkarım yapar; böylece ilk gerçek
     * sorgu bu maliyeti ödemez. Kullanıcı istemi kullanılmaz (sabit [WARM_UP_QUERY]); sonuç atılır. Oturum zaten
     * açıksa yalnızca tek kısa çıkarımdır. Eşzamanlı gerçek sorgu slot kilidinde bekler ve açık oturumu kullanır
     * (çift açılış olmaz). İndeksleme sürerken görüntü oturumunu kapatır (bkz. [com.ktu.aigaleri.data.RoomSearchRepository]).
     * Hata ve iptal [encode] gibi yayılır; çağıran en iyi çabayla yutmalıdır.
     */
    suspend fun warmUp() {
        encode(WARM_UP_QUERY)
    }

    /** Oturumu kapatır ve sözlük/Dense belleğini bırakır; sonraki [encode] yeniden açar. */
    suspend fun release() {
        slot.close(SESSION_KEY, afterClose = ::dropReferences)
    }

    private fun dropReferences() {
        tokenizer = null
        dense = null
        pipeline = null
        pipelineSession = null
    }

    private fun openSession(cancelCheck: () -> Unit): OrtSession {
        val modelFile = store.ensure(ModelManifest.TEXT_ONNX, cancelCheck)
        cancelCheck()
        try {
            val env = OrtEnvironment.getEnvironment()
            disableTelemetry(env)
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

    /** Provider kaldırıldığı için telemetri zaten başlamaz; bu ek savunmadır, başarısızlığı kritik değildir. */
    private fun disableTelemetry(env: OrtEnvironment) {
        try {
            env.setTelemetry(false)
        } catch (_: OrtException) {
        }
    }

    /** Oturum başka bir model yüzünden kapatılıp yeniden açıldıysa runner yeni oturumla yeniden kurulur. */
    private fun pipelineFor(session: OrtSession, cancelCheck: () -> Unit): TextEmbeddingPipeline {
        val cached = pipeline
        if (cached != null && pipelineSession === session) return cached
        val tok = tokenizer ?: store.ensure(ModelManifest.TEXT_VOCAB, cancelCheck).inputStream()
            .use { WordPieceTokenizer.fromVocabStream(it) }.also { tokenizer = it }
        val w = dense ?: EmbeddingMath.readFloat32LittleEndian(
            store.ensure(ModelManifest.TEXT_DENSE, cancelCheck).readBytes(),
            TextEmbeddingPipeline.OUTPUT_DIM * TextEmbeddingPipeline.HIDDEN_SIZE,
        ).also { dense = it }
        val runner = OrtHiddenStateRunner(OrtEnvironment.getEnvironment(), session)
        return TextEmbeddingPipeline(tok, w, runner).also {
            pipeline = it
            pipelineSession = session
        }
    }

    companion object {
        private const val SESSION_KEY = "text"
        private const val INTRA_OP_THREADS = 2
        private const val WARM_UP_QUERY = "ısınma"

        /**
         * Kendi [ModelStore]'unu kurar; YALNIZCA cihaz testleri için. Uygulamada `AppDependencies` paylaşılan tek
         * `ModelStore` ve `SingleSessionSlot` ile doğrudan kurucuyu kullanır.
         */
        fun create(context: Context): OnnxTextEncoder {
            val app = context.applicationContext
            return OnnxTextEncoder(ModelStore(app.filesDir, AssetManagerOpener(app.assets)))
        }
    }
}

/**
 * ORT oturumundan `last_hidden_state` okur (batch 1). Yalnızca modelin bildirdiği girdileri besler.
 * [OrtException] ve beklenmeyen çıktı şekli [ModelException.Inference] olur (mesajda girdi yok).
 */
internal class OrtHiddenStateRunner(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : HiddenStateRunner {
    override fun run(inputIds: LongArray, attentionMask: LongArray): FloatArray {
        val n = inputIds.size
        val shape = longArrayOf(1, n.toLong())
        val tensors = ArrayList<OnnxTensor>(3)
        try {
            val names = session.inputNames
            val feeds = HashMap<String, OnnxTensor>()
            fun add(name: String, data: LongArray) {
                if (name in names) {
                    val t = OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)
                    tensors.add(t)
                    feeds[name] = t
                }
            }
            add("input_ids", inputIds)
            add("attention_mask", attentionMask)
            add("token_type_ids", LongArray(n))
            session.run(feeds).use { result ->
                val out = result[0] as OnnxTensor
                val outShape = out.info.shape
                if (!(outShape.size == 3 && outShape[0] == 1L && outShape[1] == n.toLong() &&
                        outShape[2] == TextEmbeddingPipeline.HIDDEN_SIZE.toLong())
                ) {
                    throw ModelException.Inference("output-shape", IllegalStateException("beklenmeyen model çıktı şekli"))
                }
                val fb = out.floatBuffer
                return FloatArray(fb.remaining()).also { fb.get(it) }
            }
        } catch (e: OrtException) {
            throw ModelException.Inference("run", e)
        } finally {
            tensors.forEach { it.close() }
        }
    }
}
