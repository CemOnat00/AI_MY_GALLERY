package com.ktu.aigaleri.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ktu.aigaleri.domain.InvalidQueryException
import com.ktu.aigaleri.domain.SearchRepository
import com.ktu.aigaleri.domain.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI'daki basit istem uzunluk sınırı; asıl doğrulama T-007'de (architecture.md Bölüm 8). */
const val MAX_QUERY_LENGTH = 200

/** [text]'i [MAX_QUERY_LENGTH] UTF-16 birimine kısaltır; surrogate çiftini bölmez. */
internal fun truncateQuery(text: String): String {
    if (text.length <= MAX_QUERY_LENGTH) return text
    var end = MAX_QUERY_LENGTH
    if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
    return text.substring(0, end)
}

/** Arama sonucu durumu. */
sealed interface SearchStatus {
    /** Henüz arama yapılmadı (veya istem boş). */
    data object Idle : SearchStatus
    data object Loading : SearchStatus

    /** Sonuçlar skor sırasında ([SearchRepository] sözleşmesi). */
    data class Success(val results: List<SearchResult>) : SearchStatus

    /** Arama tamamlandı, eşleşme yok. */
    data object Empty : SearchStatus

    /** Arama başarısız oldu; ayrıntı (istem/yol) tutulmaz ve loglanmaz. */
    data object Error : SearchStatus

    /**
     * İstem doğrulamayı geçemedi ([InvalidQueryException]); [Error]'dan ayrıdır (kullanıcı girdisi düzeltilebilir).
     * Yalnızca neden sınıfı tutulur: istem ve ham istisna saklanmaz, gösterilmez, loglanmaz.
     */
    data class InvalidQuery(val reason: InvalidQueryException.Reason) : SearchStatus
}

data class SearchUiState(
    val query: String = "",
    val status: SearchStatus = SearchStatus.Idle,
) {
    val canSearch: Boolean get() = query.isNotBlank() && status !is SearchStatus.Loading
}

/**
 * Arama ekranı mantığı; arama [repository] üzerinden (indeks okuma) yapılır, ana thread'de bloklamaz.
 *
 * @param warmUpAction metin modeli oturumunu önceden açan en iyi çaba işlemi ([warmUp]); varsayılan işlem yok.
 */
class SearchViewModel(
    private val repository: SearchRepository,
    private val warmUpAction: suspend () -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var searchJob: Job? = null
    private var warmedUp = false

    /**
     * Arama ekranı açılırken çağrılır; ViewModel başına en fazla BİR kez çalışır (tekrar çağrı etkisizdir).
     * Arka planda ısınma işlemini yürütür; hata ve ayrıntı yutulur (ısınma başarısızsa ilk sorgu normal yoldan
     * modeli açar ve hatayı kendisi bildirir). İptal (ViewModel temizlenmesi) yayılır. Log yazılmaz.
     */
    fun warmUp() {
        if (warmedUp) return
        warmedUp = true
        viewModelScope.launch {
            try {
                warmUpAction()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // En iyi çaba: ayrıntı loglanmaz.
            }
        }
    }

    /**
     * İstem [MAX_QUERY_LENGTH] UTF-16 birimiyle kısıtlanır (fazlası kesilir). Kesme noktası bir
     * surrogate çiftinin ortasına denk gelirse çiftin yüksek yarısı da atılır (yarım emoji kalmaz);
     * yani sonuç en fazla [MAX_QUERY_LENGTH] birim, bazen bir eksik olur.
     */
    fun onQueryChange(text: String) = _state.update { it.copy(query = truncateQuery(text)) }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) {
            searchJob?.cancel()
            _state.update { it.copy(status = SearchStatus.Idle) }
            return
        }
        searchJob?.cancel()
        _state.update { it.copy(status = SearchStatus.Loading) }
        searchJob = viewModelScope.launch {
            val status = try {
                val results = repository.search(query)
                if (results.isEmpty()) SearchStatus.Empty else SearchStatus.Success(results)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidQueryException) {
                SearchStatus.InvalidQuery(e.reason)
            } catch (e: Exception) {
                // Genel hata: istem ve ayrıntı loglanmaz.
                SearchStatus.Error
            }
            _state.update { it.copy(status = status) }
        }
    }
}
