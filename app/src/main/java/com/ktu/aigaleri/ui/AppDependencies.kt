package com.ktu.aigaleri.ui

import android.content.Context
import com.ktu.aigaleri.data.AiGaleriDatabase
import com.ktu.aigaleri.data.IndexState
import com.ktu.aigaleri.data.MediaPhotoSource
import com.ktu.aigaleri.data.MediaStorePhotoSource
import com.ktu.aigaleri.domain.SearchRepository
import com.ktu.aigaleri.ui.image.ImageLoader
import com.ktu.aigaleri.ui.stub.StubIndexLauncher
import com.ktu.aigaleri.ui.stub.StubSearchRepository
import kotlinx.coroutines.flow.Flow

/**
 * Basit elle bağımlılık sağlama (DI kütüphanesi yok); tüm bağlantılar TEK YERDE burada.
 *
 * GEÇİCİ: [searchRepository] ve [indexLauncher] stub'dır. T-008'de gerçek `SearchRepository`,
 * T-006'da gerçek WorkManager tabanlı [IndexLauncher] burada değiştirilecek; ekran kodu değişmez.
 */
class AppDependencies private constructor(context: Context) {
    private val appContext = context.applicationContext

    val photoSource: MediaPhotoSource by lazy { MediaStorePhotoSource(appContext.contentResolver) }

    /** Room veritabanı; ilk sorguda (ana thread dışında) açılır. */
    private val database: AiGaleriDatabase by lazy { AiGaleriDatabase.create(appContext) }

    val indexState: Flow<IndexState?> get() = database.indexStateDao().observe()

    val searchRepository: SearchRepository = StubSearchRepository()
    val indexLauncher: IndexLauncher = StubIndexLauncher()

    val imageLoader: ImageLoader by lazy { ImageLoader(appContext.contentResolver) }

    companion object {
        @Volatile private var instance: AppDependencies? = null

        fun get(context: Context): AppDependencies =
            instance ?: synchronized(this) {
                instance ?: AppDependencies(context).also { instance = it }
            }
    }
}
