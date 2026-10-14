package com.ktu.aigaleri.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ktu.aigaleri.data.MediaPhotoSource
import com.ktu.aigaleri.data.PhotoTimeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** İlk sayfadaki galeri grid'inin durumu. */
sealed interface GalleryUiState {
    /** İlk okuma sürüyor (iskelet). */
    data object Loading : GalleryUiState

    data class Ready(val photos: PhotoTimeline) : GalleryUiState

    /** Galeri boş (hata değil). */
    data object Empty : GalleryUiState

    /**
     * Galeri izni yok veya geri alındı. Üst katman izin ekranını gösterir ([com.ktu.aigaleri.ui.MainViewModel]); bu durum
     * yalnızca o geçişe kadar iskeletin sonsuz kalmaması içindir. Eski liste tutulmaz.
     */
    data object PermissionRequired : GalleryUiState

    /** Hiç liste yüklenemedi; ayrıntı (yol/URI) tutulmaz ve loglanmaz. */
    data object Error : GalleryUiState
}

/**
 * Cihazdaki TÜM fotoğrafları [MediaPhotoSource]'tan (MediaStore; Room indeksinden DEĞİL, indekslemenin bitmesini
 * beklemez) okur. Okuma, eşleme ve sıralama ana thread dışında yapılır ([source] kendi IO dispatcher'ında, sonuç
 * [dispatcher] üzerinde). Fotoğraf yolu, URI ve tarih loglanmaz.
 *
 * Yenileme ([refresh]): (1) süren bir okuma varken gelen çağrılar birleşir (okuma iptal edilip baştan başlamaz; bitince
 * tek bir ucuz kontrol daha yapılır); (2) liste yüklüyse önce ucuz [MediaPhotoSource.changeToken] bakılır, değişmediyse
 * tam okuma atlanır; (3) yenileme sırasında iskelet görünmez; (4) yenileme hatasında eldeki durum korunur (sessiz;
 * sonraki yenilemede düzelir), yalnızca hiç liste yokken [GalleryUiState.Error] olur.
 */
class GalleryViewModel(
    private val source: MediaPhotoSource,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow<GalleryUiState>(GalleryUiState.Loading)
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var rerunRequested = false

    /** Son başarılı tam okumadaki imza; liste yüklü değilse null. */
    private var loadedToken: Long? = null

    /**
     * Ekran ön plana gelince veya izin sonucu bilinince sistemdeki gerçek izin durumuyla çağrılır. İzin yoksa okuma
     * yapılmaz, sürmekteyse iptal edilir ve durum [GalleryUiState.PermissionRequired] olur (eski liste atılır).
     */
    fun refresh(granted: Boolean) {
        if (granted) {
            requestLoad()
        } else {
            loadJob?.cancel()
            rerunRequested = false
            loadedToken = null
            _state.value = GalleryUiState.PermissionRequired
        }
    }

    /** "Tekrar dene": hata durumundan iskelete dönüp yeniden okur. */
    fun retry() {
        if (_state.value is GalleryUiState.Error) _state.value = GalleryUiState.Loading
        requestLoad()
    }

    private fun requestLoad() {
        if (loadJob?.isActive == true) {
            rerunRequested = true
            return
        }
        if (_state.value is GalleryUiState.PermissionRequired) _state.value = GalleryUiState.Loading
        loadJob = viewModelScope.launch {
            do {
                rerunRequested = false
                loadOnce()
            } while (rerunRequested)
        }
    }

    private suspend fun loadOnce() {
        try {
            val token = source.changeToken()
            val current = _state.value
            if (token == loadedToken && (current is GalleryUiState.Ready || current is GalleryUiState.Empty)) return
            val timeline = withContext(dispatcher) { source.loadTimeline() }
            _state.value = if (timeline.size == 0) GalleryUiState.Empty else GalleryUiState.Ready(timeline)
            loadedToken = token
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            // İzin okuma sırasında geri alınmış: eski liste atılır, izin ekranına geçişi MainViewModel yapar. Ayrıntı loglanmaz.
            loadedToken = null
            rerunRequested = false
            _state.value = GalleryUiState.PermissionRequired
        } catch (e: Exception) {
            // Genel hata: ayrıntı loglanmaz. Ready/Empty durumu korunur; yalnızca hiç yüklenmemişse Error.
            val current = _state.value
            if (current !is GalleryUiState.Ready && current !is GalleryUiState.Empty) _state.value = GalleryUiState.Error
        }
    }
}
