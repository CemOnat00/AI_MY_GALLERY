package com.ktu.aigaleri.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.ktu.aigaleri.domain.SearchRepository
import com.ktu.aigaleri.domain.SearchResult
import com.ktu.aigaleri.ui.gallery.GalleryViewModel
import com.ktu.aigaleri.ui.index.IndexStatusContent
import com.ktu.aigaleri.ui.index.IndexStatusUiState
import com.ktu.aigaleri.ui.index.IndexStatusViewModel
import com.ktu.aigaleri.ui.index.TAG_LICENSES_ROW
import com.ktu.aigaleri.ui.license.LicensesScreen
import com.ktu.aigaleri.ui.license.TAG_LICENSES_BACK
import com.ktu.aigaleri.ui.license.TAG_LICENSES_LIST
import com.ktu.aigaleri.ui.search.SearchViewModel
import com.ktu.aigaleri.ui.stub.StubIndexLauncher
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * T-037 (K-12): Lisanslar satırı, ekranı ve geri dönüşü. Emülatör yok: bu görevde yalnızca DERLENİR, ÇALIŞTIRILMADI
 * (qa çalıştırır). İçerik gerçek assets/licenses/ dosyalarından okunur.
 */
class LicensesScreenTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var navController: NavHostController

    private val repository = object : SearchRepository {
        override suspend fun search(query: String, limit: Int) = emptyList<SearchResult>()
    }

    private fun route() = navController.currentBackStackEntry?.destination?.route

    @Test
    fun indexStatus_showsLicensesRow_atLeast48dp_andClickInvokesCallback() {
        var opened = false
        rule.setContent {
            IndexStatusContent(
                state = IndexStatusUiState.NeverRun,
                reindexAvailable = true,
                reindexInProgress = false,
                onReindex = {},
                onRetry = {},
                onBack = {},
                onOpenLicenses = { opened = true },
            )
        }
        rule.onNodeWithTag(TAG_LICENSES_ROW).performScrollTo().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Lisanslar").assertExists()
        rule.onNodeWithTag(TAG_LICENSES_ROW).performClick()
        assertTrue(opened)
    }

    @Test
    fun licensesScreen_loadsRealAssets_showsComponentsNoticeAndFullApacheText() {
        rule.setContent { LicensesScreen(onBack = {}) }
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithTag(TAG_LICENSES_LIST).fetchSemanticsNodes().isNotEmpty()
        }
        val list = rule.onNodeWithTag(TAG_LICENSES_LIST)
        list.performScrollToNode(hasText("Değişiklik tarihi: 13 Ekim 2026", substring = true))
        list.performScrollToNode(hasText("Telif hakkı (c) Microsoft Corporation", substring = true))
        list.performScrollToNode(hasText("Dönüşümün kendi lisans beyanı yoktur", substring = true))
        list.performScrollToNode(hasText("END OF TERMS AND CONDITIONS", substring = true))
        list.performScrollToNode(hasText("Copyright (c) 2021 OpenAI", substring = true))
    }

    @Test
    fun licensesScreen_backButton_invokesOnBack() {
        var backed = false
        rule.setContent { LicensesScreen(onBack = { backed = true }) }
        rule.onNodeWithContentDescription("Geri").performClick()
        assertTrue(backed)
    }

    @Test
    fun navigation_indexStatus_toLicenses_andBack() {
        rule.setContent {
            navController = rememberNavController()
            AppNavHost(
                navController = navController,
                state = MainUiState(permission = PermissionState.Granted),
                searchViewModel = SearchViewModel(repository),
                indexStatusViewModel = IndexStatusViewModel(flowOf(null), StubIndexLauncher()),
                galleryViewModel = GalleryViewModel(emptyGallerySource),
                onRequestPermission = {},
                onOpenSettings = {},
            )
        }
        rule.runOnUiThread { navController.navigate(Routes.INDEX_STATUS) }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_LICENSES_ROW).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(Routes.LICENSES, route())
        rule.onNodeWithTag(TAG_LICENSES_BACK).performClick()
        rule.waitForIdle()
        assertEquals(Routes.INDEX_STATUS, route())
    }
}
