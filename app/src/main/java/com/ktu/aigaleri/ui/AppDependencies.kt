package com.ktu.aigaleri.ui

import android.content.Context
import androidx.work.WorkManager
import com.ktu.aigaleri.data.AiGaleriDatabase
import com.ktu.aigaleri.data.IndexState
import com.ktu.aigaleri.data.MediaPhotoSource
import com.ktu.aigaleri.data.MediaStorePhotoSource
import com.ktu.aigaleri.data.RoomPhotoIndexer
import com.ktu.aigaleri.data.RoomTransactionRunner
import com.ktu.aigaleri.domain.PhotoIndexer
import com.ktu.aigaleri.domain.SearchRepository
import com.ktu.aigaleri.ml.AssetManagerOpener
import com.ktu.aigaleri.ml.ModelStore
import com.ktu.aigaleri.ml.OnnxImageEncoder
import com.ktu.aigaleri.ui.stub.StubSearchRepository
import com.ktu.aigaleri.work.WorkManagerIndexLauncher
import kotlinx.coroutines.flow.Flow

/**
 * Basit elle bağımlılık sağlama (DI kütüphanesi yok); tüm bağlantılar TEK YERDE burada.
 *
 * GEÇİCİ: [searchRepository] stub'dır; T-008'de gerçek `SearchRepository` ile değiştirilecek, ekran kodu değişmez.
 * T-006: [indexLauncher] gerçek WorkManager tabanlıdır ve [photoIndexer] indeksleme hattını kurar.
 */
class AppDependencies private constructor(context: Context) {
    private val appContext = context.applicationContext

    val photoSource: MediaPhotoSource by lazy { MediaStorePhotoSource(appContext.contentResolver) }

    /** Room veritabanı; ilk sorguda (ana thread dışında) açılır. */
    private val database: AiGaleriDatabase by lazy { AiGaleriDatabase.create(appContext) }

    val indexState: Flow<IndexState?> get() = database.indexStateDao().observe()

    /**
     * Model dosyası yöneticisi; uygulamada TEK örnek olmalıdır (`ModelStore` kilidi örnek başınadır). T-008 metin
     * kodlayıcısı da bunu kullanmalıdır.
     */
    val modelStore: ModelStore by lazy { ModelStore(appContext.filesDir, AssetManagerOpener(appContext.assets)) }

    /** İndeksleme hattı (görüntü kodlayıcı + MediaStore + Room); yalnızca WorkManager işinde toplanır. */
    val photoIndexer: PhotoIndexer by lazy {
        RoomPhotoIndexer(
            source = photoSource,
            photoDao = database.photoDao(),
            embeddingDao = database.photoEmbeddingDao(),
            stateDao = database.indexStateDao(),
            transactions = RoomTransactionRunner(database),
            encoder = OnnxImageEncoder.create(appContext, modelStore),
        )
    }

    val searchRepository: SearchRepository = StubSearchRepository()
    val indexLauncher: IndexLauncher by lazy { WorkManagerIndexLauncher(WorkManager.getInstance(appContext)) }

    companion object {
        @Volatile private var instance: AppDependencies? = null

        fun get(context: Context): AppDependencies =
            instance ?: synchronized(this) {
                instance ?: AppDependencies(context).also { instance = it }
            }
    }
}
