package com.ktu.aigaleri.ui.index

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ktu.aigaleri.data.IndexState
import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.ui.IndexLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** İndeks durumu ekranının durumu. */
sealed interface IndexStatusUiState {
    /** Room'dan ilk değer henüz gelmedi. */
    data object Loading : IndexStatusUiState

    /** İndeksleme hiç çalışmadı (IndexState satırı yok). */
    data object NeverRun : IndexStatusUiState

    /** Okunamadı; ayrıntı loglanmaz. */
    data object Error : IndexStatusUiState

    data class Data(val total: Int, val processed: Int, val lastRunAt: Long?) : IndexStatusUiState {
        /** 0f..1f; total 0 ise son çalışma varsa 1f (boş galeri tamamlandı), yoksa 0f. */
        val fraction: Float
            get() = if (total > 0) (processed.toFloat() / total).coerceIn(0f, 1f) else if (lastRunAt != null) 1f else 0f
    }
}

/** Room `IndexState` satırını ekran durumuna eşler. */
fun IndexState?.toUiState(): IndexStatusUiState =
    if (this == null) IndexStatusUiState.NeverRun else IndexStatusUiState.Data(total, processed, lastRunAt)

/**
 * İndeks durumunu Room Flow'undan okur (sample ile; her fotoğrafta güncelleme yağmurunu UI'a
 * yansıtmaz) ve "yeniden indeksle" isteğini [launcher]'a iletir.
 *
 * Çift dokunma koruması: [reindexInProgress], iş hattının gerçek durumundan ([IndexLauncher.isRunning], WorkManager
 * `WorkInfo`) türetilir; iş kuyrukta/çalışırken yeni istekler yok sayılır. İstek ile iş durumunun görünmesi arasındaki
 * kısa aralık için yerel bir "başlatılıyor" bayrağı vardır: iş durumu görünür görünmez ([IndexLauncher.isRunning] true)
 * ya da en geç [LAUNCH_GRACE_MS] sonra düşer (iş çok kısa sürüp hiç gözlenmediyse takılı kalmasın).
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class IndexStatusViewModel(
    private val indexState: Flow<IndexState?>,
    private val launcher: IndexLauncher,
) : ViewModel() {
    private val retryCount = MutableStateFlow(0)

    val state: StateFlow<IndexStatusUiState> = retryCount
        .flatMapLatest {
            indexState
                .sample(SAMPLE_PERIOD_MS)
                .map { it.toUiState() }
                .catch { emit(IndexStatusUiState.Error) }
                .onStart { emit(IndexStatusUiState.Loading) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IndexStatusUiState.Loading)

    /** İş hattı bağlı değilse (stub) UI butonu devre dışı bırakıp açıklar. */
    val reindexAvailable: Boolean get() = launcher.isAvailable

    /** İstek verildi ama iş durumu henüz gözlenmedi. */
    private val launching = MutableStateFlow(false)

    private val launcherRunning: Flow<Boolean> = launcher.isRunning.catch { emit(false) }

    val reindexInProgress: StateFlow<Boolean> = combine(launching, launcherRunning) { l, r -> l || r }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _reindexError = MutableStateFlow(false)

    /** Son yeniden indeksleme isteği başlatılamadıysa true; sonraki istekte sıfırlanır. */
    val reindexError: StateFlow<Boolean> = _reindexError.asStateFlow()

    /** Hata durumunda Room akışını yeniden başlatır. */
    fun retry() {
        retryCount.update { it + 1 }
    }

    /** Tüm galeriyi yeniden indeksleme isteği ([IndexMode.FULL]); iş arka planda yürür. */
    fun reindex() {
        if (!launcher.isAvailable || reindexInProgress.value || !launching.compareAndSet(false, true)) return
        _reindexError.value = false
        try {
            launcher.launch(IndexMode.FULL)
        } catch (e: CancellationException) {
            launching.value = false
            throw e
        } catch (e: Exception) {
            // Başlatılamadı: bayrak geri alınır (kilitli kalmaz), UI'a genel hata bildirilir; ayrıntı loglanmaz.
            launching.value = false
            _reindexError.value = true
            return
        }
        viewModelScope.launch {
            // İş durumu gözlenince (veya zaman aşımında) yerel bayrak düşer; kalıcı kilidi iş durumu belirler.
            withTimeoutOrNull(LAUNCH_GRACE_MS) { launcherRunning.firstOrNull { it } }
            launching.value = false
        }
    }

    companion object {
        const val SAMPLE_PERIOD_MS = 250L
        const val LAUNCH_GRACE_MS = 3_000L
    }
}
