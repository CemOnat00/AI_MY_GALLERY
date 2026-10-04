package com.ktu.aigaleri.ui.index

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ktu.aigaleri.data.IndexState
import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.ui.IndexLauncher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn

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
 * İndeks durumunu Room Flow'undan okur (conflate + sample ile; her fotoğrafta güncelleme yağmurunu
 * UI'a yansıtmaz) ve "yeniden indeksle" isteğini [launcher]'a iletir.
 */
@OptIn(FlowPreview::class)
class IndexStatusViewModel(
    indexState: Flow<IndexState?>,
    private val launcher: IndexLauncher,
) : ViewModel() {
    val state: StateFlow<IndexStatusUiState> = indexState
        .conflate()
        .sample(SAMPLE_PERIOD_MS)
        .map { it.toUiState() }
        .catch { emit(IndexStatusUiState.Error) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IndexStatusUiState.Loading)

    /** Tüm galeriyi yeniden indeksleme isteği ([IndexMode.FULL]); iş arka planda yürür. */
    fun reindex() = launcher.launch(IndexMode.FULL)

    companion object {
        const val SAMPLE_PERIOD_MS = 250L
    }
}
