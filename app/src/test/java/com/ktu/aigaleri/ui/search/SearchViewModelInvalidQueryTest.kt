package com.ktu.aigaleri.ui.search

import com.ktu.aigaleri.R
import com.ktu.aigaleri.domain.InvalidQueryException
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class ThrowingRepo(var failure: Exception? = null, var results: List<SearchResult> = emptyList()) : SearchRepository {
    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        failure?.let { throw it }
        return results
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelInvalidQueryTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun invalidQuery_isDistinctFromGenericError_andCarriesOnlyReason() {
        for (reason in InvalidQueryException.Reason.values()) {
            val vm = SearchViewModel(ThrowingRepo(InvalidQueryException(reason)))
            vm.onQueryChange("gizli istem")
            vm.search()
            val status = vm.state.value.status
            assertEquals(SearchStatus.InvalidQuery(reason), status)
            assertNotEquals(SearchStatus.Error, status)
            assertTrue(!status.toString().contains("gizli"))
        }
    }

    @Test
    fun otherFailures_stayGenericError_andRecoverAfterInvalidQuery() {
        val repo = ThrowingRepo(IllegalStateException("boom"))
        val vm = SearchViewModel(repo)
        vm.onQueryChange("kedi")
        vm.search()
        assertEquals(SearchStatus.Error, vm.state.value.status)
        repo.failure = InvalidQueryException(InvalidQueryException.Reason.TOO_LONG)
        vm.search()
        assertEquals(SearchStatus.InvalidQuery(InvalidQueryException.Reason.TOO_LONG), vm.state.value.status)
        assertTrue(vm.state.value.canSearch) // yeniden denenebilir
        repo.failure = null
        repo.results = listOf(SearchResult(1, "content://media/1", 0.5f))
        vm.search()
        assertTrue(vm.state.value.status is SearchStatus.Success)
    }

    @Test
    fun messages_areDistinct_andPerReason() {
        val ids = InvalidQueryException.Reason.values().map { invalidQueryMessage(it) }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(R.string.search_invalid_empty, invalidQueryMessage(InvalidQueryException.Reason.EMPTY))
        assertEquals(R.string.search_invalid_too_long, invalidQueryMessage(InvalidQueryException.Reason.TOO_LONG))
        assertNotEquals(R.string.search_error, invalidQueryMessage(InvalidQueryException.Reason.EMPTY))
    }

    @Test
    fun warmUp_whileInFlight_isDeduplicated_thenCanRunAgain() {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val vm = SearchViewModel(ThrowingRepo()) { calls++; gate.await(); true }
        vm.warmUp()
        vm.warmUp()
        vm.warmUp()
        assertEquals(1, calls) // sürerken tekrar çağrı etkisiz
        gate.complete(Unit)
        vm.warmUp() // bitti: yeniden çağrı action'a gider (ısınmışsa action ucuz döner)
        assertEquals(2, calls)
    }

    @Test
    fun warmUp_deferredResult_isRetriedOnNextFocus() {
        val answers = ArrayDeque(listOf(false, false, true))
        var calls = 0
        val vm = SearchViewModel(ThrowingRepo()) { calls++; answers.removeFirst() }
        vm.warmUp(); vm.warmUp(); vm.warmUp()
        assertEquals(3, calls)
    }

    @Test
    fun warmUp_afterFailure_isNotRetried() {
        var calls = 0
        val vm = SearchViewModel(ThrowingRepo()) { calls++; throw IllegalStateException("model yok") }
        vm.warmUp(); vm.warmUp()
        assertEquals(1, calls)
    }

    @Test
    fun warmUp_failureIsSwallowed_andSearchStillWorks() {
        val vm = SearchViewModel(ThrowingRepo(results = listOf(SearchResult(1, "content://media/1", 0.5f)))) {
            throw IllegalStateException("model yok")
            @Suppress("UNREACHABLE_CODE") true
        }
        vm.warmUp() // fırlatmaz
        assertEquals(SearchStatus.Idle, vm.state.value.status)
        vm.onQueryChange("kedi")
        vm.search()
        assertTrue(vm.state.value.status is SearchStatus.Success)
    }

    @Test
    fun warmUp_doesNotBlockSearch_whileWarmUpIsInFlight() {
        val gate = CompletableDeferred<Unit>()
        val vm = SearchViewModel(ThrowingRepo(results = listOf(SearchResult(1, "content://media/1", 0.5f)))) { gate.await(); true }
        vm.warmUp()
        vm.onQueryChange("kedi")
        vm.search()
        assertTrue(vm.state.value.status is SearchStatus.Success)
        gate.complete(Unit)
    }

    @Test
    fun defaultConstructor_hasNoopWarmUp() {
        SearchViewModel(ThrowingRepo()).warmUp() // varsayılan: işlem yok, hata yok
    }
}
