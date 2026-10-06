package com.ktu.aigaleri.ui

import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.domain.SearchRepository
import com.ktu.aigaleri.ui.stub.StubIndexLauncher
import com.ktu.aigaleri.ui.stub.StubSearchRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** T-009 (5): stub'lar tek yerde ve beklenen sözleşmede. Çalışma dizini mobile/app. */
class AppDependenciesStubsTest {
    private val mainSrc = File(System.getProperty("user.dir")!!, "src/main/java/com/ktu/aigaleri")

    @Test
    fun stubSearchRepository_returnsEmptyList_forAnyQuery() = runBlocking {
        val repo: SearchRepository = StubSearchRepository()
        for (q in listOf("", "   ", "kedi", "İstanbul ığdır çğıöşü", "a".repeat(10_000))) {
            assertEquals(q, emptyList<Any>(), repo.search(q))
        }
        assertEquals(emptyList<Any>(), repo.search("kedi", 1))
        assertEquals(emptyList<Any>(), repo.search("kedi", Int.MAX_VALUE))
    }

    @Test
    fun stubSearchRepository_rejectsNonPositiveLimit() = runBlocking {
        val repo = StubSearchRepository()
        for (limit in listOf(0, -1, Int.MIN_VALUE)) {
            try {
                repo.search("kedi", limit)
                fail("limit=$limit için IllegalArgumentException beklenir")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun stubIndexLauncher_isUnavailable_andLaunchIsNoOp() {
        val launcher: IndexLauncher = StubIndexLauncher()
        assertFalse(launcher.isAvailable)
        IndexMode.values().forEach { launcher.launch(it) }
    }

    @Test
    fun indexLauncher_defaultIsAvailableTrue() {
        val launcher = object : IndexLauncher {
            override fun launch(mode: IndexMode) = Unit
        }
        assertTrue(launcher.isAvailable)
    }

    @Test
    fun stubSearchRepository_isInstantiatedOnlyInAppDependencies_andIndexLauncherIsReal() {
        val offenders = mainSrc.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "AppDependencies.kt" && !it.path.contains("/ui/stub/") }
            .filter { Regex("""Stub(SearchRepository|IndexLauncher)\s*\(""").containsMatchIn(it.readText()) }
            .map { it.name }
            .toList()
        assertTrue("stub örneği AppDependencies dışında: $offenders", offenders.isEmpty())
        val deps = File(mainSrc, "ui/AppDependencies.kt").readText()
        assertTrue(deps.contains("StubSearchRepository()"))
        // T-006: indeks başlatıcı gerçek (WorkManager); stub yalnızca UI testlerinde kalır.
        assertTrue(deps.contains("WorkManagerIndexLauncher("))
        assertFalse(deps.contains("StubIndexLauncher"))
    }

    @Test
    fun noNetworkOrAnalyticsReferences_inUiPackage() {
        val bad = File(mainSrc, "ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""java\.net\.|okhttp|HttpURLConnection|https?://""").containsMatchIn(it.readText()) }
            .map { it.name }
            .toList()
        assertTrue("ağ referansı: $bad", bad.isEmpty())
    }
}
