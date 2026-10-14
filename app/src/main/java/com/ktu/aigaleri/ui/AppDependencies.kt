package com.ktu.aigaleri.ui

import android.content.Context
import com.ktu.aigaleri.ui.search.SearchWarmUp
import androidx.work.WorkManager
import com.ktu.aigaleri.data.AiGaleriDatabase
import com.ktu.aigaleri.data.IndexState
import com.ktu.aigaleri.data.MediaPhotoSource
import com.ktu.aigaleri.data.MediaStorePhotoSource
import com.ktu.aigaleri.data.RoomPhotoIndexer
import com.ktu.aigaleri.data.RoomSearchRepository
import com.ktu.aigaleri.data.SearchPolicy
import com.ktu.aigaleri.data.RoomTransactionRunner
import com.ktu.aigaleri.domain.PhotoIndexer
import com.ktu.aigaleri.domain.SearchRepository
import com.ktu.aigaleri.domain.TextEncoder
import com.ktu.aigaleri.ml.AssetManagerOpener
import com.ktu.aigaleri.ml.ModelManifest
import com.ktu.aigaleri.ml.ModelStore
import com.ktu.aigaleri.ml.OnnxImageEncoder
import com.ktu.aigaleri.ml.OnnxTextEncoder
import com.ktu.aigaleri.ml.PromptEnsembleTextEncoder
import com.ktu.aigaleri.ml.SingleSessionSlot
import com.ktu.aigaleri.work.WorkManagerIndexLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Basit elle bağımlılık sağlama (DI kütüphanesi yok); tüm bağlantılar TEK YERDE burada.
 *
 * T-006: [indexLauncher] gerçek WorkManager tabanlıdır ve [photoIndexer] indeksleme hattını kurar.
 * T-008: [searchRepository] gerçek (Room + metin kodlayıcı). Görüntü kodlayıcı (indeksleme, WorkManager işi bu
 * singleton'dan [photoIndexer]'ı alır; iş aynı süreçte çalışır) ve metin kodlayıcı (arama) AYNI [modelStore] ve AYNI
 * [sessionSlot] örneğini paylaşır; bu, tek ONNX oturum kuralının ön koşuludur.
 */
class AppDependencies private constructor(context: Context) {
    private val appContext = context.applicationContext

    val photoSource: MediaPhotoSource by lazy { MediaStorePhotoSource(appContext.contentResolver) }

    /** Room veritabanı; ilk sorguda (ana thread dışında) açılır. */
    private val database: AiGaleriDatabase by lazy { AiGaleriDatabase.create(appContext) }

    val indexState: Flow<IndexState?> get() = database.indexStateDao().observe()

    /**
     * Model dosyası yöneticisi; uygulamada TEK örnek olmalıdır (`ModelStore` kilidi örnek başınadır). Görüntü
     * ([photoIndexer]) ve metin ([textEncoder]) kodlayıcıları bunu paylaşır.
     */
    val modelStore: ModelStore by lazy { ModelStore(appContext.filesDir, AssetManagerOpener(appContext.assets)) }

    /** Tek ONNX oturum kuralı yuvası; görüntü ve metin kodlayıcı aynı örneği kullanır. */
    val sessionSlot: SingleSessionSlot get() = SingleSessionSlot.shared

    private val textEncoderDelegate = lazy {
        // Oturum kapanınca (release veya görüntü oturumu açılıp metin oturumu çıkarılınca) ısınma bayrağı sıfırlanır.
        OnnxTextEncoder(modelStore, sessionSlot, onSessionClosed = {
            if (searchWarmUpDelegate.isInitialized()) searchWarmUp.onSessionReleased()
        })
    }

    /** Arama metin kodlayıcısı (tek örnek; [modelStore] ve [sessionSlot] paylaşılır). */
    val textEncoder: OnnxTextEncoder by textEncoderDelegate

    /**
     * Aramanın kullandığı kodlayıcı (T-033): [textEncoder]'ın ÜSTÜNDE sorgu şablon topluluğu (kısa Türkçe sorgularda
     * kalite yaması, docs/query-ensemble.md). Isınma ([warmUpSearch]) ve altın testler çıplak [textEncoder]'ı kullanır.
     */
    val searchTextEncoder: TextEncoder by lazy { PromptEnsembleTextEncoder(textEncoder) }

    /** İndeksleme hattı (görüntü kodlayıcı + MediaStore + Room); yalnızca WorkManager işinde toplanır. */
    val photoIndexer: PhotoIndexer by lazy {
        RoomPhotoIndexer(
            source = photoSource,
            photoDao = database.photoDao(),
            embeddingDao = database.photoEmbeddingDao(),
            stateDao = database.indexStateDao(),
            transactions = RoomTransactionRunner(database),
            encoder = OnnxImageEncoder.create(appContext, modelStore, sessionSlot),
        )
    }

    val searchRepository: SearchRepository by lazy {
        RoomSearchRepository(
            encoder = searchTextEncoder,
            dao = database.photoEmbeddingDao(),
            // T-013 PO kararı: politika altyapısı hazır ama KAPALI (K-7 hedefi emülatör ölçümüyle sağlanamadı; yanlış
            // "bulunamadı" riski ölçülmedi). Cem'in telefonunda yeniden ölçüm sonrası aday sabite (DEFAULT) çevrilir
            // (tek satır; docs/search-threshold.md).
            policy = SearchPolicy.OFF,
        )
    }

    /**
     * Isınma politikası: yalnızca aranabilir indeks varsa ve indeksleme GERÇEKTEN çalışmıyorsa (RUNNING değilse)
     * metin oturumunu önceden açar (bkz. [SearchWarmUp]). Kuyrukta bekleyen (WAITING; ör. pil kısıtı) iş ısınmayı
     * ertelemez: görüntü oturumu açık değildir, süresiz ertelenme olmaz.
     */
    private val searchWarmUpDelegate = lazy {
        SearchWarmUp(
            // Not: getPageForModel (T-025) JOIN'siz olduğundan photo satırı olmayan (yetim) embedding'i de sayar. Gerçekte
            // yetim yoktur (FK CASCADE + Room foreign_keys=ON); olsaydı yalnızca ısınma gereksiz yere yapılırdı.
            hasSearchableIndex = {
                database.photoEmbeddingDao().getPageForModel(ModelManifest.MODEL_VERSION, Long.MIN_VALUE, 1).isNotEmpty()
            },
            isIndexing = { indexLauncher.isIndexingNow() },
            warm = { textEncoder.warmUp() },
        )
    }
    private val searchWarmUp: SearchWarmUp by searchWarmUpDelegate

    /** İlk metin alanı odağında çağrılır. @return ısınmış mı (false: koşullar uygun değil, sonra yeniden denenir). En iyi çaba. */
    suspend fun warmUpSearch(): Boolean = searchWarmUp.run()

    val indexLauncher: IndexLauncher by lazy { WorkManagerIndexLauncher(WorkManager.getInstance(appContext)) }

    init {
        // Arka plana alınınca/bellek baskısında metin oturumunu bırak (süreç boyunca ~97 MB tutma).
        appContext.registerComponentCallbacks(
            TextSessionTrimHandler(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                release = { if (textEncoderDelegate.isInitialized()) textEncoder.release() },
                afterRelease = { if (searchWarmUpDelegate.isInitialized()) searchWarmUp.onSessionReleased() },
                beforeRelease = { if (searchWarmUpDelegate.isInitialized()) searchWarmUp.onSessionReleased() },
            ),
        )
    }

    companion object {
        @Volatile private var instance: AppDependencies? = null

        fun get(context: Context): AppDependencies =
            instance ?: synchronized(this) {
                instance ?: AppDependencies(context).also { instance = it }
            }
    }
}
