package com.ktu.aigaleri.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ktu.aigaleri.data.MediaPhoto
import com.ktu.aigaleri.data.MediaPhotoMapping
import com.ktu.aigaleri.data.MediaPhotoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Galeri grid'i için hafif fotoğraf listesi: yalnızca kimlik ve tarih, iki düz [LongArray] (fotoğraf başına 16 bayt;
 * 50 bin fotoğraf ~800 KB). URI ve küçük resim hücre çizilirken üretilir. Sıra en yeniden eskiye.
 */
class GalleryPhotos(private val ids: LongArray, private val dates: LongArray) {
    init {
        require(ids.size == dates.size) { "ids ve dates aynı boyutta olmalı" }
    }

    val size: Int get() = ids.size

    fun idAt(index: Int): Long = ids[index]

    /** Çekim tarihi (epoch ms; çekim yoksa eklenme zamanı); bilinmiyorsa null. */
    fun dateAt(index: Int): Long? = dates[index].takeIf { it >= 0 }

    override fun equals(other: Any?): Boolean =
        other is GalleryPhotos && ids.contentEquals(other.ids) && dates.contentEquals(other.dates)

    override fun hashCode(): Int = 31 * ids.contentHashCode() + dates.contentHashCode()

    internal companion object {
        const val UNKNOWN_DATE = -1L
    }
}

/** İlk sayfadaki galeri grid'inin durumu. */
sealed interface GalleryUiState {
    /** İlk okuma sürüyor (iskelet). */
    data object Loading : GalleryUiState

    data class Ready(val photos: GalleryPhotos) : GalleryUiState

    /** Galeri boş (hata değil). */
    data object Empty : GalleryUiState

    /** Okunamadı; ayrıntı (yol/URI) tutulmaz ve loglanmaz. */
    data object Error : GalleryUiState
}

/**
 * Cihazdaki TÜM fotoğrafları [MediaPhotoSource]'tan (MediaStore; Room indeksinden DEĞİL, indekslemenin bitmesini
 * beklemez) okur. Okuma ve sıralama ana thread dışında yapılır ([source] kendi IO dispatcher'ında, eşleme/sıralama
 * [dispatcher] üzerinde). Fotoğraf yolu, URI ve tarih loglanmaz.
 *
 * Yenileme: [refresh] her ön plana gelişte çağrılır. Zaten bir liste gösteriliyorsa yenileme sırasında iskelet
 * görünmez; yenileme başarısız olursa eldeki liste korunur (hata yalnızca hiç liste yokken gösterilir).
 */
class GalleryViewModel(
    private val source: MediaPhotoSource,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow<GalleryUiState>(GalleryUiState.Loading)
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()
    private var loadJob: Job? = null

    /**
     * Ekran ön plana gelince veya izin sonucu bilinince sistemdeki gerçek izin durumuyla çağrılır. İzin yoksa okuma
     * yapılmaz, sürmekteyse iptal edilir ve durum başa ([GalleryUiState.Loading]) döner.
     */
    fun refresh(granted: Boolean) {
        if (granted) {
            load()
        } else {
            loadJob?.cancel()
            _state.value = GalleryUiState.Loading
        }
    }

    /** "Tekrar dene": hata durumundan iskelete dönüp yeniden okur. */
    fun retry() {
        if (_state.value is GalleryUiState.Error) _state.value = GalleryUiState.Loading
        load()
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val photos = source.loadPhotos()
                _state.value = withContext(dispatcher) { toState(photos) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                // İzin okuma sırasında geri alınmış; izin ekranına geçişi MainViewModel yapar. Ayrıntı loglanmaz.
                _state.value = GalleryUiState.Loading
            } catch (e: Exception) {
                // Genel hata: ayrıntı loglanmaz. Eldeki liste varsa korunur.
                if (_state.value !is GalleryUiState.Ready) _state.value = GalleryUiState.Error
            }
        }
    }

    private fun toState(photos: List<MediaPhoto>): GalleryUiState {
        if (photos.isEmpty()) return GalleryUiState.Empty
        // Kaynak sözleşmesi zaten yeniden eskiye sıralıdır; sıralı girdide timsort doğrusaldır. Tarihsizler sonda.
        val sorted = MediaPhotoMapping.sortNewestFirst(photos)
        val ids = LongArray(sorted.size) { sorted[it].id }
        val dates = LongArray(sorted.size) { sorted[it].dateTaken ?: GalleryPhotos.UNKNOWN_DATE }
        return GalleryUiState.Ready(GalleryPhotos(ids, dates))
    }
}
