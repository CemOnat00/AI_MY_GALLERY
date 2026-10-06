package com.ktu.aigaleri.ui.search

import com.ktu.aigaleri.domain.InvalidQueryException
import com.ktu.aigaleri.domain.SearchRepository
import com.ktu.aigaleri.domain.SearchResult
import java.io.File
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

/** T-008 QA: eski aramanın sonucu yenisini ezmez (InvalidQuery dahil), InvalidQuery sonrası akış, Türkçe mesajlar. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelStaleAndMessagesTest {
    private class ScriptedRepo : SearchRepository {
        val gates = mutableListOf<CompletableDeferred<List<SearchResult>>>()
        val queries = mutableListOf<String>()
        override suspend fun search(query: String, limit: Int): List<SearchResult> {
            queries += query
            return CompletableDeferred<List<SearchResult>>().also { gates += it }.await()
        }
    }

    private fun res(id: Long) = listOf(SearchResult(id, "content://media/$id", 0.5f))

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun staleSearch_failingWithInvalidQuery_doesNotOverrideNewResult() {
        val repo = ScriptedRepo()
        val vm = SearchViewModel(repo)
        vm.onQueryChange("eski"); vm.search()
        vm.onQueryChange("yeni"); vm.search()
        repo.gates[0].completeExceptionally(InvalidQueryException(InvalidQueryException.Reason.TOO_LONG)) // iptal edilmişti
        assertEquals(SearchStatus.Loading, vm.state.value.status)
        repo.gates[1].complete(res(7))
        assertEquals(SearchStatus.Success(res(7)), vm.state.value.status)
    }

    @Test
    fun staleSearch_completingLate_doesNotOverrideNewResult() {
        val repo = ScriptedRepo()
        val vm = SearchViewModel(repo)
        vm.onQueryChange("a"); vm.search()
        vm.onQueryChange("b"); vm.search()
        vm.onQueryChange("c"); vm.search()
        repo.gates[2].complete(res(3))
        repo.gates[0].complete(res(1))
        repo.gates[1].complete(res(2))
        assertEquals(SearchStatus.Success(res(3)), vm.state.value.status)
        assertEquals(listOf("a", "b", "c"), repo.queries)
    }

    @Test
    fun invalidQuery_thenBlank_goesIdle_thenValidSucceeds() {
        val repo = ScriptedRepo()
        val vm = SearchViewModel(repo)
        vm.onQueryChange("x"); vm.search()
        repo.gates[0].completeExceptionally(InvalidQueryException(InvalidQueryException.Reason.EMPTY))
        assertEquals(SearchStatus.InvalidQuery(InvalidQueryException.Reason.EMPTY), vm.state.value.status)
        vm.onQueryChange("   "); vm.search()
        assertEquals(SearchStatus.Idle, vm.state.value.status)
        vm.onQueryChange("İstanbul"); vm.search()
        repo.gates[1].complete(res(9))
        assertTrue(vm.state.value.status is SearchStatus.Success)
        assertEquals("İstanbul", repo.queries[1])
    }

    @Test
    fun invalidQuery_whileLoading_isReplacedByLoading_notStuck() {
        val repo = ScriptedRepo()
        val vm = SearchViewModel(repo)
        vm.onQueryChange("x"); vm.search()
        assertFalse(vm.state.value.canSearch) // yükleniyor
        repo.gates[0].completeExceptionally(InvalidQueryException(InvalidQueryException.Reason.TOO_LONG))
        assertTrue(vm.state.value.canSearch)
    }

    @Test
    fun invalidQueryMessages_areTurkish_distinct_andHaveNoPlaceholders() {
        val strings = File(System.getProperty("user.dir")!!, "src/main/res/values/strings.xml").readText()
        fun text(name: String) =
            Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(strings)!!.groupValues[1]
        val empty = text("search_invalid_empty")
        val tooLong = text("search_invalid_too_long")
        val generic = text("search_error")
        assertTrue(empty.isNotBlank() && tooLong.isNotBlank())
        assertEquals(3, setOf(empty, tooLong, generic).size)
        for (m in listOf(empty, tooLong)) {
            assertFalse(m.contains("%")) // istem metni basılmaz
            assertTrue(m.any { it in "çğıöşüÇĞİÖŞÜ" } || m.contains("İstem"))
        }
        assertTrue(empty.contains("boş"))
        assertTrue(tooLong.contains("uzun"))
    }
}
