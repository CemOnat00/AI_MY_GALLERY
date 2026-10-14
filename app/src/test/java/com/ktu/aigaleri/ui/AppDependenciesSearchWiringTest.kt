package com.ktu.aigaleri.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-008 (3a): görüntü (indeksleme) ve metin (arama) kodlayıcıları AYNI ModelStore ve AYNI SingleSessionSlot
 * örneğini paylaşmalı; WorkManager işi bağımlılığı AppDependencies singleton'ından almalı. AppDependencies Context
 * gerektirdiğinden kaynak üzerinden doğrulanır (çalışma dizini mobile/app).
 */
class AppDependenciesSearchWiringTest {
    private val mainSrc = File(System.getProperty("user.dir")!!, "src/main/java/com/ktu/aigaleri")
    private val deps = File(mainSrc, "ui/AppDependencies.kt").readText()
    private fun mainFiles() = mainSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun singleModelStoreAndSlot_sharedByBothEncoders() {
        assertEquals(1, Regex("""\bModelStore\(""").findAll(deps).count())
        assertTrue(deps.contains("OnnxTextEncoder(modelStore, sessionSlot,"))
        assertTrue(deps.contains("OnnxImageEncoder.create(appContext, modelStore, sessionSlot)"))
        assertTrue(deps.contains("SingleSessionSlot.shared"))
        assertEquals(1, Regex("""SingleSessionSlot\.shared""").findAll(deps).count())
    }

    @Test
    fun noOtherMainCodeBuildsItsOwnModelStoreOrSlot() {
        // OnnxTextEncoder.create kendi ModelStore'unu kurar ama yalnızca cihaz testleri içindir (KDoc'ta belirtilir).
        val allowed = setOf("AppDependencies.kt", "OnnxTextEncoder.kt")
        val offenders = mainFiles().filter { it.name !in allowed && it.name != "SingleSessionSlot.kt" && it.name != "ModelStore.kt" }
            .filter { Regex("""\bModelStore\(|\bSingleSessionSlot\(""").containsMatchIn(it.readText()) }
            .map { it.name }
        assertTrue("kendi store/slot'unu kuran dosya: $offenders", offenders.isEmpty())
        val createUsers = mainFiles().filter { Regex("""OnnxTextEncoder\.create\(""").containsMatchIn(it.readText()) }.map { it.name }
        assertTrue("OnnxTextEncoder.create ana kodda kullanılmamalı: $createUsers", createUsers.isEmpty())
    }

    @Test
    fun workerTakesIndexerFromAppDependenciesSingleton() {
        val worker = File(mainSrc, "work/IndexWorker.kt").readText()
        assertTrue(worker.contains("AppDependencies.get(applicationContext).photoIndexer"))
    }

    @Test
    fun searchRepositoryIsRealAndUsesSharedTextEncoder() {
        // T-033: arama, paylaşılan çıplak kodlayıcının üstündeki şablon topluluğunu kullanır; ısınma çıplak kodlayıcıda kalır.
        assertTrue(Regex("""RoomSearchRepository\(\s*encoder = searchTextEncoder""").containsMatchIn(deps))
        assertTrue(deps.contains("val searchTextEncoder: TextEncoder by lazy { PromptEnsembleTextEncoder(textEncoder) }"))
        assertTrue(deps.contains("suspend fun warmUpSearch(): Boolean = searchWarmUp.run()"))
        assertTrue(deps.contains("warm = { textEncoder.warmUp() }"))
        assertTrue(deps.contains("indexLauncher.isIndexingNow()")) // eşleme tek yerde (IndexLauncher.isIndexingNow); davranış IndexLauncherRunStateTest'te
        assertTrue(deps.contains("registerComponentCallbacks("))
        assertTrue(deps.contains("TextSessionTrimHandler("))
        val activity = File(mainSrc, "ui/MainActivity.kt").readText()
        assertTrue(activity.contains("SearchViewModel(deps.searchRepository, deps::warmUpSearch)"))
    }

    @Test
    fun searchRepository_policyIsOff_untilReMeasuredOnPhone() {
        // T-013 PO kararı: eşik altyapısı kalır ama uygulamada KAPALI bağlanır (K-7 hedefi ölçümle sağlanamadı).
        val block = Regex("""RoomSearchRepository\((.*?)\n        \)""", RegexOption.DOT_MATCHES_ALL).find(deps)?.groupValues?.get(1)
        assertTrue("RoomSearchRepository bağlantısı bulunamadı", block != null)
        assertTrue(Regex("""policy = SearchPolicy\.OFF,""").containsMatchIn(block!!))
        assertTrue(!block.contains("SearchPolicy.DEFAULT"))
    }

    @Test
    fun warmUpIsTriggeredByQueryFieldFocus_notByScreenOpen() {
        val screen = File(mainSrc, "ui/MainScreen.kt").readText()
        assertTrue(screen.contains("onQueryFocused = searchViewModel::warmUp"))
        assertTrue(!screen.contains("LaunchedEffect"))
        assertTrue(File(mainSrc, "ui/search/SearchScreen.kt").readText().contains("onFocusChanged"))
    }

    @Test
    fun noLoggingOfQueryOrVectorsInSearchCode() {
        val files = listOf("data/RoomSearchRepository.kt", "data/TopKCollector.kt", "ui/search/SearchViewModel.kt", "ui/search/SearchWarmUp.kt", "ui/TextSessionTrimHandler.kt")
        for (f in files) {
            val t = File(mainSrc, f).readText()
            assertTrue("$f log çağrısı içeriyor", !Regex("""\bLog\.|println\(|Timber|Logger""").containsMatchIn(t))
        }
    }
}
