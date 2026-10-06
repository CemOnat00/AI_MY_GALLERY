package com.ktu.aigaleri.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.content.res.AssetManager
import com.ktu.aigaleri.domain.EmbeddingSpec
import com.ktu.aigaleri.domain.TextEncoder
import java.nio.LongBuffer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
 * Bellek/süre: ilk [encode] çağrısında (IO/Default dışı thread, ana thread değil) model dosyaları
 * doğrulanıp filesDir'e kopyalanır ve oturum açılır; sonraki çağrılar açık oturumu kullanır. Oturum
 * dosya yolundan açılır (135 MB Java yığınına girmez), CPU arena kapalı, 2 iş parçacığı. İş bitince
 * (ör. indekslemeye geçmeden) [release] oturumu ve sözlük/ağırlık belleğini bırakır. Tek oturum kuralı
 * [SingleSessionSlot] ile sağlanır. Hiçbir istem, token veya vektör log'a yazılmaz.
 */
class OnnxTextEncoder(
    private val store: ModelStore,
    private val slot: SingleSessionSlot = SingleSessionSlot.shared,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TextEncoder {
    override val embeddingSpec: EmbeddingSpec = ModelManifest.EMBEDDING_SPEC

    // Yalnızca slot kilidi altında okunur/yazılır (withSession bloğu ve release).
    private var tokenizer: WordPieceTokenizer? = null
    private var dense: FloatArray? = null
    private var pipeline: TextEmbeddingPipeline? = null
    private var pipelineSession: OrtSession? = null

    override suspend fun encode(query: String): FloatArray {
        // Doğrulama önce: geçersiz sorguda model dosyalarına/oturuma dokunulmaz.
        val normalized = QueryPreprocessor.normalize(query)
        return withContext(dispatcher) {
            slot.withSession(SESSION_KEY, open = { openSession() }) { session ->
                pipelineFor(session).embed(normalized)
            }
        }
    }

    /** Oturumu kapatır ve sözlük/Dense belleğini bırakır; sonraki [encode] yeniden açar. */
    suspend fun release() {
        slot.close(SESSION_KEY) {
            tokenizer = null
            dense = null
            pipeline = null
            pipelineSession = null
        }
    }

    private fun openSession(): OrtSession {
        val modelFile = store.ensure(ModelManifest.TEXT_ONNX)
        val env = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(INTRA_OP_THREADS)
            options.setMemoryPatternOptimization(false)
            options.setCPUArenaAllocator(false)
            return env.createSession(modelFile.absolutePath, options)
        }
    }

    /** Oturum başka bir model yüzünden kapatılıp yeniden açıldıysa runner yeni oturumla yeniden kurulur. */
    private fun pipelineFor(session: OrtSession): TextEmbeddingPipeline {
        val cached = pipeline
        if (cached != null && pipelineSession === session) return cached
        val tok = tokenizer ?: store.ensure(ModelManifest.TEXT_VOCAB).inputStream()
            .use { WordPieceTokenizer.fromVocabStream(it) }.also { tokenizer = it }
        val w = dense ?: EmbeddingMath.readFloat32LittleEndian(
            store.ensure(ModelManifest.TEXT_DENSE).readBytes(),
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

        /** Uygulama bağlamından kurar; AppDependencies bağlantısı T-008'dedir. */
        fun create(context: Context): OnnxTextEncoder {
            val app = context.applicationContext
            return OnnxTextEncoder(ModelStore(app.filesDir, AssetManagerOpener(app.assets)))
        }
    }
}

/** ORT oturumundan `last_hidden_state` okur (batch 1). Yalnızca modelin bildirdiği girdileri besler. */
internal class OrtHiddenStateRunner(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : HiddenStateRunner {
    override fun run(inputIds: LongArray, attentionMask: LongArray): FloatArray {
        val n = inputIds.size
        val shape = longArrayOf(1, n.toLong())
        val names = session.inputNames
        val tensors = ArrayList<OnnxTensor>(3)
        try {
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
                check(outShape.size == 3 && outShape[0] == 1L && outShape[1] == n.toLong() &&
                    outShape[2] == TextEmbeddingPipeline.HIDDEN_SIZE.toLong()) { "beklenmeyen model çıktı şekli" }
                val fb = out.floatBuffer
                return FloatArray(fb.remaining()).also { fb.get(it) }
            }
        } finally {
            tensors.forEach { it.close() }
        }
    }
}
