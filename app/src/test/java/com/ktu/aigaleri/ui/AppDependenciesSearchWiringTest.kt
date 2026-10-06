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
        assertTrue(deps.contains("OnnxTextEncoder(modelStore, sessionSlot)"))
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
        assertTrue(Regex("""RoomSearchRepository\(\s*encoder = textEncoder""").containsMatchIn(deps))
        assertTrue(deps.contains("suspend fun warmUpSearch() = textEncoder.warmUp()"))
        val activity = File(mainSrc, "ui/MainActivity.kt").readText()
        assertTrue(activity.contains("SearchViewModel(deps.searchRepository, deps::warmUpSearch)"))
    }

    @Test
    fun noLoggingOfQueryOrVectorsInSearchCode() {
        val files = listOf("data/RoomSearchRepository.kt", "data/TopKCollector.kt", "ui/search/SearchViewModel.kt")
        for (f in files) {
            val t = File(mainSrc, f).readText()
            assertTrue("$f log çağrısı içeriyor", !Regex("""\bLog\.|println\(|Timber|Logger""").containsMatchIn(t))
        }
    }
}
