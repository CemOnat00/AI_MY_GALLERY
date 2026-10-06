package com.ktu.aigaleri.ui

import android.content.Context
import androidx.work.WorkManager
import com.ktu.aigaleri.data.AiGaleriDatabase
import com.ktu.aigaleri.data.IndexState
import com.ktu.aigaleri.data.MediaPhotoSource
import com.ktu.aigaleri.data.MediaStorePhotoSource
import com.ktu.aigaleri.data.RoomPhotoIndexer
import com.ktu.aigaleri.data.RoomSearchRepository
import com.ktu.aigaleri.data.RoomTransactionRunner
import com.ktu.aigaleri.domain.PhotoIndexer
import com.ktu.aigaleri.domain.SearchRepository
import com.ktu.aigaleri.ml.AssetManagerOpener
import com.ktu.aigaleri.ml.ModelStore
import com.ktu.aigaleri.ml.OnnxImageEncoder
import com.ktu.aigaleri.ml.OnnxTextEncoder
import com.ktu.aigaleri.ml.SingleSessionSlot
import com.ktu.aigaleri.work.WorkManagerIndexLauncher
import kotlinx.coroutines.flow.Flow

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

    /** Arama metin kodlayıcısı (tek örnek; [modelStore] ve [sessionSlot] paylaşılır). */
    val textEncoder: OnnxTextEncoder by lazy { OnnxTextEncoder(modelStore, sessionSlot) }

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
        RoomSearchRepository(encoder = textEncoder, dao = database.photoEmbeddingDao())
    }

    /** Arama ekranı açılırken çağrılır: metin oturumunu önceden açar (ilk sorgu gecikmesi). En iyi çabadır. */
    suspend fun warmUpSearch() = textEncoder.warmUp()

    val indexLauncher: IndexLauncher by lazy { WorkManagerIndexLauncher(WorkManager.getInstance(appContext)) }

    companion object {
        @Volatile private var instance: AppDependencies? = null

        fun get(context: Context): AppDependencies =
            instance ?: synchronized(this) {
                instance ?: AppDependencies(context).also { instance = it }
            }
    }
}
