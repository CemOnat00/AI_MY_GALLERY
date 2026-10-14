package com.ktu.aigaleri.ui.search

import com.ktu.aigaleri.domain.SearchRepository
import com.ktu.aigaleri.domain.SearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** T-036: boşta galeri grid'i ile arama arasındaki geçişler (çip -> arama, temizleyince boşta). */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelGalleryTest {
    private val queries = mutableListOf<String>()
    private var gate: CompletableDeferred<Unit>? = null
    private val repository = object : SearchRepository {
        override suspend fun search(query: String, limit: Int): List<SearchResult> {
            queries += query
            gate?.await()
            return listOf(SearchResult(1L, "content://media/1", 0.9f))
        }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun chipTap_fillsQueryAndSearchesThatPrompt() {
        val vm = SearchViewModel(repository)
        val chip = SearchSuggestions.ALL.first()
        // SearchContent çip davranışı: istemi yaz, ara
        vm.onQueryChange(chip)
        vm.search()
        assertEquals(listOf(chip), queries)
        assertEquals(chip, vm.state.value.query)
        assertTrue(vm.state.value.status is SearchStatus.Success)
    }

    @Test
    fun clearingQuery_afterResults_returnsToIdle() {
        val vm = SearchViewModel(repository)
        vm.onQueryChange("kedi")
        vm.search()
        assertTrue(vm.state.value.status is SearchStatus.Success)
        vm.onQueryChange("")
        assertEquals(SearchUiState(), vm.state.value)
    }

    @Test
    fun clearingQuery_whileSearching_cancelsAndReturnsToIdle() {
        gate = CompletableDeferred()
        val vm = SearchViewModel(repository)
        vm.onQueryChange("kedi")
        vm.search()
        assertEquals(SearchStatus.Loading, vm.state.value.status)
        vm.onQueryChange("")
        gate!!.complete(Unit)
        assertEquals(SearchUiState(), vm.state.value)
    }

    @Test
    fun clearingQuery_afterInvalidQuery_returnsToIdle() {
        val vm = SearchViewModel(repository)
        vm.onQueryChange("   ")
        vm.search()
        assertTrue(vm.state.value.status is SearchStatus.Idle)
        vm.onQueryChange("")
        assertEquals(SearchUiState(), vm.state.value)
    }

    @Test
    fun editingNonEmptyQuery_keepsResults() {
        val vm = SearchViewModel(repository)
        vm.onQueryChange("kedi")
        vm.search()
        vm.onQueryChange("kedi ")
        assertTrue(vm.state.value.status is SearchStatus.Success)
    }
}
