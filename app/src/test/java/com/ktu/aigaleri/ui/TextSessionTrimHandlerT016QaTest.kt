@file:Suppress("DEPRECATION") // TRIM_MEMORY_* sabitleri Java'da deprecated; test eski API düzeylerindeki eşik davranışını doğrular (T-016)

package com.ktu.aigaleri.ui

import android.content.ComponentCallbacks2
import com.ktu.aigaleri.ml.SingleSessionSlot
import com.ktu.aigaleri.ui.search.SearchWarmUp
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-016 QA: trim işleyicisi + ısınma + slot bütünleşmesi; deprecated bastırma kapsamı; AppDependencies bağları. */
@OptIn(ExperimentalCoroutinesApi::class)
class TextSessionTrimHandlerT016QaTest {
    private val mainSrc = File(System.getProperty("user.dir")!!, "src/main/java/com/ktu/aigaleri")

    private fun wired(warmUp: SearchWarmUp, release: suspend () -> Unit) = TextSessionTrimHandler(
        scope = CoroutineScope(UnconfinedTestDispatcher()),
        release = release,
        afterRelease = warmUp::onSessionReleased,
        beforeRelease = warmUp::onSessionReleased,
    )

    /** Isınma sürerken trim: her bırakma düzeyinde warmed true KALMAZ. */
    @Test
    fun trimDuringWarmUp_atEveryReleaseLevel_doesNotLeaveWarmedTrue() = runBlocking {
        val levels = listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        )
        for (level in levels) {
            val warmCalls = AtomicInteger()
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            var first = true
            val p = SearchWarmUp({ true }, { false }, {
                warmCalls.incrementAndGet()
                if (first) {
                    first = false
                    started.complete(Unit)
                    gate.await()
                }
            })
            val h = wired(p) { }
            val run = async(Dispatchers.Default) { p.run() }
            started.await()
            h.onTrimMemory(level)
            gate.complete(Unit)
            assertFalse("level=$level", run.await())
            assertTrue(p.run())
            assertEquals("level=$level", 2, warmCalls.get())
        }
    }

    /** Eşik altı trim (MODERATE) ısınmayı bozmaz. */
    @Test
    fun trimBelowThreshold_doesNotResetWarmFlag() = runBlocking {
        val warmCalls = AtomicInteger()
        val p = SearchWarmUp({ true }, { false }, { warmCalls.incrementAndGet() })
        val h = wired(p) { }
        assertTrue(p.run())
        h.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        assertTrue(p.run())
        assertEquals(1, warmCalls.get())
    }

    /** release istisna atarsa: bayrak sıfır kalır (beforeRelease ve afterRelease çalıştı), sonraki run yeniden ısıtır. */
    @Test
    fun releaseThrows_whileWarmUpInFlight_flagNotTrue() = runBlocking {
        val warmCalls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var first = true
        val p = SearchWarmUp({ true }, { false }, {
            warmCalls.incrementAndGet()
            if (first) {
                first = false
                started.complete(Unit)
                gate.await()
            }
        })
        val h = wired(p) { throw IllegalStateException("closeLocked") }
        val run = async(Dispatchers.Default) { p.run() }
        started.await()
        h.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        gate.complete(Unit)
        assertFalse(run.await())
        assertTrue(p.run())
        assertEquals(2, warmCalls.get())
    }

    /**
     * Gerçek slot ile: release sırasında slot.close -> onClosed (bayrak sıfırlama) -> afterRelease; üç bildirim de idempotent,
     * sonraki run tam bir kez yeniden ısıtır.
     */
    @Test
    fun trimWithRealSlot_multipleNotifications_resultInSingleRewarm() = runBlocking {
        val slot = SingleSessionSlot()
        val warmCalls = AtomicInteger()
        lateinit var p: SearchWarmUp
        p = SearchWarmUp({ true }, { false }, {
            warmCalls.incrementAndGet()
            slot.withSession("text", open = { AutoCloseable { } }, onClosed = { p.onSessionReleased() }) { }
        })
        val h = wired(p) { slot.close("text") }
        assertTrue(p.run())
        h.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertTrue(p.run())
        assertEquals(2, warmCalls.get())
        assertTrue(p.run())
        assertEquals(2, warmCalls.get())
        slot.closeAll()
    }

    /** Slot'ta metin oturumu hiç açılmamışken release: onClosed çağrılmaz ama before/afterRelease bayrağı yine sıfırlar. */
    @Test
    fun trimWhenNoTextSessionOpen_stillResetsWarmFlagViaBeforeAndAfter() = runBlocking {
        val slot = SingleSessionSlot()
        val warmCalls = AtomicInteger()
        val p = SearchWarmUp({ true }, { false }, { warmCalls.incrementAndGet() })
        assertTrue(p.run()) // bayrak true ama slot boş (stale senaryosu)
        val h = wired(p) { slot.close("text") }
        h.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        assertTrue(p.run())
        assertEquals(2, warmCalls.get())
    }

    /** beforeRelease hatası yutulur ve afterRelease yine çalışır (karakterizasyon: release bu durumda atlanır). */
    @Test
    fun beforeReleaseThrows_isSwallowed_afterReleaseStillRuns() {
        val afters = AtomicInteger()
        val h = TextSessionTrimHandler(
            CoroutineScope(UnconfinedTestDispatcher()),
            release = { },
            afterRelease = { afters.incrementAndGet() },
            beforeRelease = { throw IllegalStateException("before") },
        )
        h.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE) // fırlatmaz
        assertEquals(1, afters.get())
    }

    /** Art arda ve eşzamanlı trimler: before/after sayaçları release sayısıyla birebir. */
    @Test
    fun manyTrims_callbacksCountMatches() {
        val b = AtomicInteger(); val r = AtomicInteger(); val a = AtomicInteger()
        val h = TextSessionTrimHandler(
            CoroutineScope(UnconfinedTestDispatcher()),
            release = { r.incrementAndGet() },
            afterRelease = { a.incrementAndGet() },
            beforeRelease = { b.incrementAndGet() },
        )
        repeat(500) { h.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE) }
        assertEquals(500, b.get()); assertEquals(500, r.get()); assertEquals(500, a.get())
    }

    /** TRIM_MEMORY_RUNNING_LOW sabiti: eski davranış (minSdk 26) ile aynı değer 10; eşik tam bu değerde. */
    @Test
    fun runningLowConstant_isTen_andThresholdUnchanged() {
        assertEquals(10, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertFalse(TextSessionTrimHandler.shouldRelease(9))
        assertTrue(TextSessionTrimHandler.shouldRelease(10))
    }

    /** @Suppress("DEPRECATION") yalnızca shouldRelease üzerinde: dosya/sınıf düzeyinde geniş bastırma yok, gerekçe yorumu var. */
    @Test
    fun deprecationSuppress_isNarrow_andJustified() {
        val src = File(mainSrc, "ui/TextSessionTrimHandler.kt").readText()
        val lines = src.lines()
        val idx = lines.indices.filter { lines[it].contains("@Suppress(\"DEPRECATION\")") }
        assertEquals("tam bir DEPRECATION bastırması beklenir", 1, idx.size)
        assertTrue(lines[idx[0] + 1].contains("fun shouldRelease("))
        assertFalse(src.contains("@file:Suppress"))
        assertFalse(Regex("""@Suppress\([^)]*\)\s*\n\s*(class|object|interface)""").containsMatchIn(src))
        // Gerekçe: bastırmanın hemen üstündeki KDoc'ta
        assertTrue(lines.subList(maxOf(0, idx[0] - 6), idx[0]).any { it.contains("kullanımdan kaldırıldı") })
    }

    /** Ana kodda TRIM_MEMORY_RUNNING_LOW başka bir yerde kullanılmıyor (bastırma tek ifadede yeterli). */
    @Test
    fun deprecatedTrimConstant_usedOnlyInHandler() {
        val users = mainSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("TRIM_MEMORY_RUNNING_LOW") }.map { it.name }.toList()
        assertEquals(listOf("TextSessionTrimHandler.kt"), users.distinct())
    }

    /** AppDependencies: üç bırakma bildirimi ve RUNNING-yalnız ısınma kararı bağlı. */
    @Test
    fun appDependencies_wiresAllReleaseNotifications_andRunningOnlyDeferral() {
        val deps = File(mainSrc, "ui/AppDependencies.kt").readText()
        assertTrue(deps.contains("onSessionClosed = {"))
        assertTrue(Regex("""beforeRelease = \{[^}]*searchWarmUp\.onSessionReleased\(\)""").containsMatchIn(deps))
        assertTrue(Regex("""afterRelease = \{[^}]*searchWarmUp\.onSessionReleased\(\)""").containsMatchIn(deps))
        assertTrue(deps.contains("isIndexing = { indexLauncher.isIndexingNow() }"))
        assertFalse("isRunning ile ısınma ertelenmemeli", Regex("""isIndexing = \{[^}]*isRunning""").containsMatchIn(deps))
    }

    /** onSessionClosed geri çağrısı yalnızca bayrak sıfırlar: slot'a yeniden girmez (slot kilidi altında çağrılır, Mutex yeniden girişsiz). */
    @Test
    fun onSessionClosedCallbackInAppDependencies_doesNotReenterSlot() {
        val deps = File(mainSrc, "ui/AppDependencies.kt").readText()
        val block = deps.substringAfter("onSessionClosed = {").substringBefore("})")
        assertFalse(block.contains("withSession"))
        assertFalse(block.contains("warmUp()"))
        assertFalse(block.contains("encode("))
        assertFalse(block.contains("runBlocking"))
        assertTrue(block.contains("onSessionReleased"))
    }
}
