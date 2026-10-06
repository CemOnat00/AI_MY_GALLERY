package com.ktu.aigaleri.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.ktu.aigaleri.domain.SearchRepository
import com.ktu.aigaleri.domain.SearchResult
import com.ktu.aigaleri.ui.index.IndexStatusViewModel
import com.ktu.aigaleri.ui.index.TAG_INDEXING_BANNER
import com.ktu.aigaleri.ui.search.SearchViewModel
import com.ktu.aigaleri.ui.search.TAG_QUERY_FIELD
import com.ktu.aigaleri.ui.stub.StubIndexLauncher
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Navigasyon, geri davranışı ve izin kapısı testleri. Emülatör yok: bu görevde yalnızca DERLENİR,
 * ÇALIŞTIRILMADI.
 */
class NavigationTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var navController: NavHostController

    private val repository = object : SearchRepository {
        override suspend fun search(query: String, limit: Int) = emptyList<SearchResult>()
    }

    private val runningLauncher = object : IndexLauncher {
        override val isRunning = flowOf(true)
        override fun launch(mode: com.ktu.aigaleri.domain.IndexMode) = Unit
    }

    private fun show(permission: PermissionState, launcher: IndexLauncher = StubIndexLauncher()) {
        rule.setContent {
            navController = rememberNavController()
            AppNavHost(
                navController = navController,
                state = MainUiState(permission = permission),
                searchViewModel = SearchViewModel(repository),
                indexStatusViewModel = IndexStatusViewModel(flowOf(null), launcher),
                onRequestPermission = {},
                onOpenSettings = {},
            )
        }
    }

    private fun route() = navController.currentBackStackEntry?.destination?.route

    @Test
    fun withoutPermission_searchShowsExplanation_notSearchField() {
        show(PermissionState.Denied)
        rule.onNodeWithText("Galeri izni verilmedi", substring = true).assertExists()
        rule.onNodeWithTag(TAG_QUERY_FIELD).assertDoesNotExist()
    }

    @Test
    fun withoutPermission_indexScreenShowsExplanation() {
        show(PermissionState.NotRequested)
        rule.runOnUiThread { navController.navigate(Routes.INDEX_STATUS) }
        rule.waitForIdle()
        rule.onNodeWithText("galeri erişimine izin vermen gerekiyor", substring = true).assertExists()
    }

    @Test
    fun viewer_backReturnsToSearch() {
        show(PermissionState.Granted)
        rule.runOnUiThread { navController.navigate(Routes.viewer(1L)) }
        rule.waitForIdle()
        assertEquals(Routes.VIEWER, route())
        rule.onNodeWithText("Geri").performClick()
        rule.waitForIdle()
        assertEquals(Routes.SEARCH, route())
    }

    @Test
    fun backButtonTwice_onViewer_doesNotEmptyTheStack() {
        show(PermissionState.Granted)
        rule.runOnUiThread { navController.navigate(Routes.viewer(1L)) }
        rule.waitForIdle()
        rule.runOnUiThread {
            navController.safeBack()
            navController.safeBack()
        }
        rule.waitForIdle()
        assertEquals(Routes.SEARCH, route())
        assertNull(navController.previousBackStackEntry)
    }

    @Test
    fun searchRoot_safeBackDoesNothing() {
        show(PermissionState.Granted)
        rule.runOnUiThread { navController.safeBack() }
        rule.waitForIdle()
        assertNotNull(navController.currentBackStackEntry)
        assertEquals(Routes.SEARCH, route())
    }

    @Test
    fun indexingBanner_shownWhileRunning_andClickOpensIndexStatus() {
        show(PermissionState.Granted, runningLauncher)
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithTag(TAG_INDEXING_BANNER).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag(TAG_INDEXING_BANNER).performClick()
        rule.waitForIdle()
        assertEquals(Routes.INDEX_STATUS, route())
    }

    @Test
    fun indexingBanner_hiddenWhenNotRunning() {
        show(PermissionState.Granted)
        rule.onNodeWithTag(TAG_INDEXING_BANNER).assertDoesNotExist()
    }

    @Test
    fun indexStatus_openFromSearch_thenBack() {
        show(PermissionState.Granted)
        rule.onNodeWithText("İndeks durumu").performClick()
        rule.waitForIdle()
        assertEquals(Routes.INDEX_STATUS, route())
        rule.onNodeWithText("Geri").performClick()
        rule.waitForIdle()
        assertEquals(Routes.SEARCH, route())
    }
}
