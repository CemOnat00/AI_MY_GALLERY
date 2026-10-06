package com.ktu.aigaleri.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ktu.aigaleri.data.MediaPhotoSource
import com.ktu.aigaleri.domain.IndexMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Ana ekran durumu.
 *
 * @property photoCount galerideki fotoğraf sayısı; izin yokken, henüz okunmadıysa veya okunamadıysa null.
 * @property loadError izin verilmiş olduğu halde fotoğraf sayısı okunamadı (genel hata).
 */
data class MainUiState(
    val permission: PermissionState = PermissionState.NotRequested,
    val photoCount: Int? = null,
    val loadError: Boolean = false,
)

/** Başlatıcı verilmediğinde (yalnızca testler) kullanılan, hiçbir şey yapmayan başlatıcı. */
private object UnavailableIndexLauncher : IndexLauncher {
    override val isAvailable: Boolean = false

    override fun launch(mode: IndexMode) = Unit
}

/**
 * İzin durumunu tutar; izin verilince fotoğraf sayısını [source] üzerinden (ana thread dışında) okur ve
 * artımlı indekslemeyi [launcher] ile başlatır (izin Granted'a geçince bir kez; izin kaybolup geri gelmedikçe
 * tekrarlanmaz. Çift iş zaten WorkManager unique work KEEP ile de engellenir).
 */
class MainViewModel(
    private val source: MediaPhotoSource,
    private val launcher: IndexLauncher = UnavailableIndexLauncher,
) : ViewModel() {
    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var incrementalRequested = false

    /**
     * Ekran ön plana geldiğinde sistemdeki gerçek izin durumuyla çağrılır. İzin verilmişse sayı
     * bilinçli olarak her çağrıda yeniden okunur (ayarlardan dönüş, uygulama dışında eklenen/silinen
     * fotoğraflar); sorgu ucuzdur ve ana thread dışında çalışır.
     */
    fun onResume(granted: Boolean) = apply(GalleryPermission.afterResume(_state.value.permission, granted))

    /** Sistem izin diyaloğu sonuçlanınca çağrılır. */
    fun onPermissionResult(granted: Boolean, shouldShowRationale: Boolean) =
        apply(GalleryPermission.afterRequest(granted, shouldShowRationale))

    private fun apply(permission: PermissionState) {
        _state.update { it.copy(
                permission = permission,
                photoCount = if (permission == PermissionState.Granted) it.photoCount else null,
                loadError = if (permission == PermissionState.Granted) it.loadError else false,
            ) }
        if (permission == PermissionState.Granted) {
            loadCount()
            startIncrementalIndexing()
        } else {
            loadJob?.cancel()
            incrementalRequested = false
        }
    }

    private fun startIncrementalIndexing() {
        if (incrementalRequested || !launcher.isAvailable) return
        incrementalRequested = true
        try {
            launcher.launch(IndexMode.INCREMENTAL)
        } catch (e: CancellationException) {
            incrementalRequested = false
            throw e
        } catch (e: Exception) {
            // Başlatılamadı: çökme yok, ayrıntı loglanmaz; sonraki onResume'da yeniden denenir.
            incrementalRequested = false
        }
    }

    private fun loadCount() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val count = source.count()
                _state.update { it.copy(photoCount = count, loadError = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                // İzin okuma sırasında geri alınmış; ayrıntı (yol/içerik) loglanmaz.
                _state.value = MainUiState(PermissionState.NotRequested, null)
                incrementalRequested = false
            } catch (e: Exception) {
                // Genel hata: ayrıntı loglanmaz, ekran "okunamadı" gösterir.
                _state.update { it.copy(photoCount = null, loadError = true) }
            }
        }
    }
}
