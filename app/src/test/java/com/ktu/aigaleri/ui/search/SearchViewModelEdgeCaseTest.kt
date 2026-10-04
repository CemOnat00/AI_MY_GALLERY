package com.ktu.aigaleri.ui.search

import com.ktu.aigaleri.domain.DEFAULT_SEARCH_LIMIT
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

/** Her çağrı kendi kapısıyla bekler; iptal edilen çağrılar [cancelled] listesine düşer. */
private class GatedRepository : SearchRepository {
    val queries = mutableListOf<String>()
    val limits = mutableListOf<Int>()
    val gates = mutableListOf<CompletableDeferred<List<SearchResult>>>()
    val cancelled = mutableListOf<String>()

    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        queries += query
        limits += limit
        val gate = CompletableDeferred<List<SearchResult>>()
        gates += gate
        try {
            return gate.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled += query
            throw e
        }
    }
}

private class ImmediateRepository(var results: List<SearchResult> = emptyList()) : SearchRepository {
    val queries = mutableListOf<String>()
    val limits = mutableListOf<Int>()
    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        queries += query
        limits += limit
        return results
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelEdgeCaseTest {
    private fun result(id: Long, score: Float) = SearchResult(id, "content://media/$id", score)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun query_exactly200_isKeptIntact() {
        val vm = SearchViewModel(ImmediateRepository())
        val text = "x".repeat(200)
        vm.onQueryChange(text)
        assertEquals(text, vm.state.value.query)
    }

    @Test
    fun query_cutInsideSurrogatePair_dropsWholeEmoji() {
        val vm = SearchViewModel(ImmediateRepository())
        vm.onQueryChange("a" + "\uD83D\uDE00".repeat(100)) // 201 UTF-16 birimi; 200. sınır çiftin ortasında
        val q = vm.state.value.query
        assertEquals("a" + "\uD83D\uDE00".repeat(99), q)
        assertTrue(q.length <= MAX_QUERY_LENGTH)
        assertFalse(q.last().isHighSurrogate())
    }

    @Test
    fun query_emojiEndingExactlyAtLimit_isKept() {
        val vm = SearchViewModel(ImmediateRepository())
        val text = "\uD83D\uDE00".repeat(100) + "x"
        vm.onQueryChange(text)
        assertEquals("\uD83D\uDE00".repeat(100), vm.state.value.query)
    }

    @Test
    fun query_201_isCutToFirst200() {
        val vm = SearchViewModel(ImmediateRepository())
        val text = "a".repeat(200) + "b"
        vm.onQueryChange(text)
        assertEquals("a".repeat(200), vm.state.value.query)
    }

    @Test
    fun longQuery_reachesRepositoryWithAtMost200Chars() {
        val repo = ImmediateRepository()
        val vm = SearchViewModel(repo)
        vm.onQueryChange("ş".repeat(5_000))
        vm.search()
        assertEquals(listOf("ş".repeat(200)), repo.queries)
    }

    @Test
    fun query_with200Spaces_isBlank_andNeverSearches() {
        val repo = ImmediateRepository()
        val vm = SearchViewModel(repo)
        vm.onQueryChange(" ".repeat(300))
        assertFalse(vm.state.value.canSearch)
        vm.search()
        assertTrue(repo.queries.isEmpty())
        assertEquals(SearchStatus.Idle, vm.state.value.status)
    }

    @Test
    fun emptyQuery_afterSuccess_goesBackToIdle() {
        val repo = ImmediateRepository(listOf(result(1, 0.9f)))
        val vm = SearchViewModel(repo)
        vm.onQueryChange("kedi")
        vm.search()
        assertTrue(vm.state.value.status is SearchStatus.Success)
        vm.onQueryChange("")
        vm.search()
        assertEquals(SearchStatus.Idle, vm.state.value.status)
        assertEquals(1, repo.queries.size)
    }

    @Test
    fun tabsAndNewlines_areTrimmed_innerWhitespaceKept() {
        val repo = ImmediateRepository()
        val vm = SearchViewModel(repo)
        vm.onQueryChange("\n\t kırmızı  araba \r\n")
        vm.search()
        assertEquals(listOf("kırmızı  araba"), repo.queries)
    }

    @Test
    fun turkishCharacters_arePassedUnchanged_noCaseFolding() {
        val repo = ImmediateRepository()
        val vm = SearchViewModel(repo)
        val text = "İstanbul ığdır ÇĞIÖŞÜ çğıöşü Iı İi"
        vm.onQueryChange(text)
        assertEquals(text, vm.state.value.query)
        vm.search()
        assertEquals(listOf(text), repo.queries)
    }

    @Test
    fun turkishQuery_of200Chars_isNotTruncated_and201Is() {
        val vm = SearchViewModel(ImmediateRepository())
        vm.onQueryChange("İ".repeat(200))
        assertEquals(200, vm.state.value.query.length)
        vm.onQueryChange("İ".repeat(201))
        assertEquals("İ".repeat(200), vm.state.value.query)
    }

    @Test
    fun search_usesDefaultLimit() {
        val repo = ImmediateRepository()
        val vm = SearchViewModel(repo)
        vm.onQueryChange("kedi")
        vm.search()
        assertEquals(listOf(DEFAULT_SEARCH_LIMIT), repo.limits)
    }

    @Test
    fun results_areNotReordered_evenIfNotScoreSorted() {
        // ViewModel sıralamayı repository'ye bırakır; gelen sırayı aynen korur (eşit skor dahil).
        val list = listOf(result(3, 0.5f), result(1, 0.5f), result(2, 0.9f), result(9, 0.1f))
        val vm = SearchViewModel(ImmediateRepository(list))
        vm.onQueryChange("kedi")
        vm.search()
        assertEquals(list, (vm.state.value.status as SearchStatus.Success).results)
    }

    @Test
    fun hugeResultList_isPassedThroughWhole() {
        val list = List(20_000) { result(it + 1L, 1f - it / 20_000f) }
        val vm = SearchViewModel(ImmediateRepository(list))
        vm.onQueryChange("kedi")
        vm.search()
        val got = (vm.state.value.status as SearchStatus.Success).results
        assertEquals(20_000, got.size)
        assertEquals(list.first(), got.first())
        assertEquals(list.last(), got.last())
    }

    @Test
    fun rapidSearches_onlyLastResultSurvives_earlierAreCancelled() {
        val repo = GatedRepository()
        val vm = SearchViewModel(repo)
        for (q in listOf("bir", "iki", "üç", "dört")) {
            vm.onQueryChange(q)
            vm.search()
        }
        assertEquals(listOf("bir", "iki", "üç", "dört"), repo.queries)
        assertEquals(listOf("bir", "iki", "üç"), repo.cancelled)
        assertEquals(SearchStatus.Loading, vm.state.value.status)
        // Eski kapılar açılsa bile sonuç ezilmez.
        repo.gates[0].complete(listOf(result(1, 1f)))
        assertEquals(SearchStatus.Loading, vm.state.value.status)
        repo.gates[3].complete(listOf(result(4, 0.4f)))
        assertEquals(SearchStatus.Success(listOf(result(4, 0.4f))), vm.state.value.status)
    }

    @Test
    fun blankSearch_whileLoading_cancelsInFlight_andLateResultIsDropped() {
        val repo = GatedRepository()
        val vm = SearchViewModel(repo)
        vm.onQueryChange("kedi")
        vm.search()
        assertEquals(SearchStatus.Loading, vm.state.value.status)
        vm.onQueryChange("   ")
        vm.search()
        assertEquals(SearchStatus.Idle, vm.state.value.status)
        assertEquals(listOf("kedi"), repo.cancelled)
        repo.gates[0].complete(listOf(result(1, 1f)))
        assertEquals(SearchStatus.Idle, vm.state.value.status)
    }

    @Test
    fun errorOfOldSearch_doesNotOverrideNewerSearch() {
        val repo = GatedRepository()
        val vm = SearchViewModel(repo)
        vm.onQueryChange("bir")
        vm.search()
        vm.onQueryChange("iki")
        vm.search()
        repo.gates[0].completeExceptionally(IllegalStateException("eski"))
        assertEquals(SearchStatus.Loading, vm.state.value.status)
        repo.gates[1].complete(emptyList())
        assertEquals(SearchStatus.Empty, vm.state.value.status)
    }

    @Test
    fun errorThenSuccess_recoversOnNextSearch() {
        val repo = GatedRepository()
        val vm = SearchViewModel(repo)
        vm.onQueryChange("kedi")
        vm.search()
        repo.gates[0].completeExceptionally(RuntimeException("x"))
        assertEquals(SearchStatus.Error, vm.state.value.status)
        vm.search()
        repo.gates[1].complete(listOf(result(7, 0.7f)))
        assertEquals(SearchStatus.Success(listOf(result(7, 0.7f))), vm.state.value.status)
    }

    @Test
    fun editingQuery_afterResults_keepsResultsUntilNextSearch() {
        val list = listOf(result(1, 0.9f))
        val vm = SearchViewModel(ImmediateRepository(list))
        vm.onQueryChange("kedi")
        vm.search()
        vm.onQueryChange("kedi siyah")
        assertEquals(SearchStatus.Success(list), vm.state.value.status)
        assertTrue(vm.state.value.canSearch)
    }

    @Test
    fun emptyGallery_emptyResult_isEmptyNotError() {
        val vm = SearchViewModel(ImmediateRepository(emptyList()))
        vm.onQueryChange("herhangi")
        vm.search()
        assertEquals(SearchStatus.Empty, vm.state.value.status)
    }
}
