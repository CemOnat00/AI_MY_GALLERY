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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeSearchRepository(
    var results: List<SearchResult> = emptyList(),
    var failure: Exception? = null,
    var gate: CompletableDeferred<Unit>? = null,
) : SearchRepository {
    val queries = mutableListOf<String>()
    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        queries += query
        gate?.await()
        failure?.let { throw it }
        return results
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val r1 = SearchResult(1L, "content://media/1", 0.9f)
    private val r2 = SearchResult(2L, "content://media/2", 0.5f)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun initialState_isIdle_andCannotSearch() {
        val vm = SearchViewModel(FakeSearchRepository())
        assertEquals(SearchStatus.Idle, vm.state.value.status)
        assertFalse(vm.state.value.canSearch)
    }

    @Test
    fun blankQuery_doesNotCallRepository() {
        val repo = FakeSearchRepository()
        val vm = SearchViewModel(repo)
        vm.onQueryChange("   ")
        vm.search()
        assertTrue(repo.queries.isEmpty())
        assertEquals(SearchStatus.Idle, vm.state.value.status)
    }

    @Test
    fun results_keepRepositoryOrder_andQueryIsTrimmed() {
        val repo = FakeSearchRepository(results = listOf(r1, r2))
        val vm = SearchViewModel(repo)
        vm.onQueryChange("  plajda çocuk ")
        vm.search()
        assertEquals(listOf("plajda çocuk"), repo.queries)
        assertEquals(SearchStatus.Success(listOf(r1, r2)), vm.state.value.status)
    }

    @Test
    fun noMatches_isEmptyStatus() {
        val vm = SearchViewModel(FakeSearchRepository(results = emptyList()))
        vm.onQueryChange("kedi")
        vm.search()
        assertEquals(SearchStatus.Empty, vm.state.value.status)
    }

    @Test
    fun repositoryFailure_isErrorStatus() {
        val vm = SearchViewModel(FakeSearchRepository(failure = IllegalStateException("boom")))
        vm.onQueryChange("kedi")
        vm.search()
        assertEquals(SearchStatus.Error, vm.state.value.status)
        // Hata sonrası yeniden aranabilir.
        assertTrue(vm.state.value.canSearch)
    }

    @Test
    fun whileSearching_isLoading_thenSuccess() {
        val gate = CompletableDeferred<Unit>()
        val vm = SearchViewModel(FakeSearchRepository(results = listOf(r1), gate = gate))
        vm.onQueryChange("kedi")
        vm.search()
        assertEquals(SearchStatus.Loading, vm.state.value.status)
        assertFalse(vm.state.value.canSearch)
        gate.complete(Unit)
        assertEquals(SearchStatus.Success(listOf(r1)), vm.state.value.status)
    }

    @Test
    fun query_isLimitedToMaxLength() {
        val vm = SearchViewModel(FakeSearchRepository())
        vm.onQueryChange("a".repeat(MAX_QUERY_LENGTH + 50))
        assertEquals(MAX_QUERY_LENGTH, vm.state.value.query.length)
        vm.onQueryChange("b".repeat(MAX_QUERY_LENGTH))
        assertEquals(MAX_QUERY_LENGTH, vm.state.value.query.length)
    }

    @Test
    fun newSearch_replacesPreviousInFlightSearch() {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeSearchRepository(results = listOf(r1), gate = gate)
        val vm = SearchViewModel(repo)
        vm.onQueryChange("birinci")
        vm.search()
        repo.gate = null
        repo.results = listOf(r2)
        vm.onQueryChange("ikinci")
        vm.search()
        assertEquals(SearchStatus.Success(listOf(r2)), vm.state.value.status)
        gate.complete(Unit)
        // Eski arama iptal edildi; sonuç ezilmez.
        assertEquals(SearchStatus.Success(listOf(r2)), vm.state.value.status)
    }
}
