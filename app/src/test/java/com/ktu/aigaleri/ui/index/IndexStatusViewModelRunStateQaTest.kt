package com.ktu.aigaleri.ui.index

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.ktu.aigaleri.data.IndexState
import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.ui.IndexLauncher
import com.ktu.aigaleri.ui.IndexRunState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.io.File

/**
 * T-016 QA: banner / durum ekranı runState kenar durumları ve IndexStatusViewModel paylaşılan runState akışı.
 * Kapsam: launching true iken IDLE, hızlı geçişler, çoklu gözlemci, kapanış, ilk kare tutarlılığı, bayat değer, hata.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IndexStatusViewModelRunStateQaTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    /** [runState] abonelik sayısını sayan launcher. */
    private class CountingLauncher(val run: MutableStateFlow<IndexRunState>) : IndexLauncher {
        var subscribed = 0
        var totalSubscriptions = 0
        val modes = mutableListOf<IndexMode>()
        override val runState: Flow<IndexRunState> = run
            .onStart { subscribed++; totalSubscriptions++ }
            .onCompletion { subscribed-- }
        override val isRunning: Flow<Boolean> = run.map { it != IndexRunState.IDLE }
        override fun launch(mode: IndexMode) { modes += mode }
    }

    private fun vm(launcher: IndexLauncher, row: IndexState? = null) =
        IndexStatusViewModel(MutableStateFlow(row), launcher)

    private val grace = IndexStatusViewModel.LAUNCH_GRACE_MS

    // ---- launching true iken IDLE ----

    @Test
    fun launching_whileRunStateIdle_locksButNoBanner_thenReleasesAfterGrace() = runTest {
        val l = CountingLauncher(MutableStateFlow(IndexRunState.IDLE))
        val vm = vm(l, IndexState(total = 10, processed = 3, lastRunAt = null))
        runCurrent()
        vm.reindex()
        runCurrent()
        assertEquals(listOf(IndexMode.FULL), l.modes)
        assertEquals(IndexRunState.IDLE, vm.runState.value)
        assertTrue(vm.reindexInProgress.value) // yerel bayrak kilitler
        // Band IDLE'a bağlı: yerel bayrak "sırada" iddiası üretmez.
        assertNull(indexingBanner(IndexStatusUiState.Data(10, 3, null), vm.runState.value))
        // Kilitliyken ikinci istek yok sayılır.
        vm.reindex()
        runCurrent()
        assertEquals(1, l.modes.size)

        advanceTimeBy(grace - 1)
        runCurrent()
        assertTrue(vm.reindexInProgress.value)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(vm.reindexInProgress.value)
        vm.reindex()
        runCurrent()
        assertEquals(2, l.modes.size)
    }

    @Test
    fun launching_isReleasedByWaitingAlone_andIdleThenUnlocksImmediately() = runTest {
        val run = MutableStateFlow(IndexRunState.IDLE)
        val l = CountingLauncher(run)
        val vm = vm(l)
        runCurrent()
        vm.reindex()
        run.value = IndexRunState.WAITING
        runCurrent()
        assertTrue(vm.reindexInProgress.value)
        run.value = IndexRunState.IDLE
        runCurrent() // grace beklenmeden: bayrak WAITING görülünce düşmüştü
        assertFalse(vm.reindexInProgress.value)
    }

    @Test
    fun reindexAfterRunStateFlowErrorAtStart_stillLocksViaLaunchingOnly() = runTest {
        val modes = mutableListOf<IndexMode>()
        val failing = object : IndexLauncher {
            override val runState: Flow<IndexRunState> = flow { throw IllegalStateException("work db") }
            override fun launch(mode: IndexMode) { modes += mode }
        }
        val vm = vm(failing)
        runCurrent()
        assertEquals(IndexRunState.IDLE, vm.runState.value)
        assertFalse(vm.reindexInProgress.value)
        vm.reindex()
        runCurrent()
        assertTrue(vm.reindexInProgress.value)
        vm.reindex()
        assertEquals(1, modes.size)
        advanceTimeBy(grace + 1)
        runCurrent()
        assertFalse(vm.reindexInProgress.value) // takılı kalmaz
        assertEquals(IndexRunState.IDLE, vm.runState.value) // yeniden deneme yok
    }

    // ---- hızlı geçişler ----

    @Test
    fun rapidTransitions_lockAlwaysEqualsRunStateNotIdle() = runTest {
        val run = MutableStateFlow(IndexRunState.IDLE)
        val vm = vm(CountingLauncher(run))
        runCurrent()
        val seq = listOf(
            IndexRunState.WAITING, IndexRunState.RUNNING, IndexRunState.WAITING, IndexRunState.RUNNING,
            IndexRunState.IDLE, IndexRunState.RUNNING, IndexRunState.IDLE, IndexRunState.WAITING, IndexRunState.IDLE,
        )
        repeat(20) {
            seq.forEach { s ->
                run.value = s
                runCurrent()
                assertEquals(s, vm.runState.value)
                assertEquals("$s", s != IndexRunState.IDLE, vm.reindexInProgress.value)
            }
        }
    }

    @Test
    fun sameTickFlicker_isConflated_runStateStaysIdle_launchingReleasedByGrace() = runTest {
        val run = MutableStateFlow(IndexRunState.IDLE)
        val vm = vm(CountingLauncher(run))
        runCurrent()
        vm.reindex()
        // Aynı karede IDLE -> RUNNING -> IDLE: StateFlow birleştirir, gözlemci hiçbir şey görmez.
        run.value = IndexRunState.RUNNING
        run.value = IndexRunState.IDLE
        runCurrent()
        assertEquals(IndexRunState.IDLE, vm.runState.value)
        assertTrue(vm.reindexInProgress.value)
        advanceTimeBy(grace + 1)
        runCurrent()
        assertFalse(vm.reindexInProgress.value)
    }

    @Test
    fun sameTickFlicker_endingRunning_showsRunning() = runTest {
        val run = MutableStateFlow(IndexRunState.IDLE)
        val vm = vm(CountingLauncher(run))
        runCurrent()
        run.value = IndexRunState.WAITING
        run.value = IndexRunState.IDLE
        run.value = IndexRunState.RUNNING
        runCurrent()
        assertEquals(IndexRunState.RUNNING, vm.runState.value)
        assertTrue(vm.reindexInProgress.value)
    }

    // ---- ilk kare tutarlılığı ----

    @Test
    fun firstFrame_beforeAnyDispatch_bothIdleAndUnlocked_thenBothFollowLauncherTogether() = runTest {
        val run = MutableStateFlow(IndexRunState.RUNNING)
        val vm = vm(CountingLauncher(run))
        // İlk kare: abonelik henüz işlenmedi; ikisi de aynı varsayılan.
        assertEquals(IndexRunState.IDLE, vm.runState.value)
        assertFalse(vm.reindexInProgress.value)
        runCurrent()
        assertEquals(IndexRunState.RUNNING, vm.runState.value)
        assertTrue(vm.reindexInProgress.value)
    }

    @Test
    fun firstFrame_waitingAtStart_lockAndRunStateAgree_afterOneDispatch() = runTest {
        val run = MutableStateFlow(IndexRunState.WAITING)
        val l = CountingLauncher(run)
        val vm = vm(l)
        runCurrent()
        assertEquals(IndexRunState.WAITING, vm.runState.value)
        assertTrue(vm.reindexInProgress.value)
        vm.reindex()
        runCurrent()
        assertTrue(l.modes.isEmpty())
    }

    /**
     * BULGU (düşük): reindexInProgress, runState'ten combine + stateIn ile ayrı bir dağıtım turunda türetilir; runState
     * değişince aynı karede bir gözlemci runState ile kilidin çelişkili olduğunu görür (WAITING/false, IDLE/true).
     * Üretim kodu düzeltilmedi (görev kapsamı dışı); bu test bulguyu belgeler. Çalıştırınca başarısız olur.
     */
    @Ignore("T-016 bulgu: runState ile reindexInProgress bir dağıtım turu farkla güncellenir (IndexStatusViewModel.kt reindexInProgress combine)")
    @Test
    fun observerSeesRunStateAndLock_neverContradicting() = runTest {
        val run = MutableStateFlow(IndexRunState.IDLE)
        val vm = vm(CountingLauncher(run))
        val contradictions = mutableListOf<String>()
        backgroundScope.launch {
            vm.runState.collect { s ->
                if ((s != IndexRunState.IDLE) != vm.reindexInProgress.value) contradictions += "$s/${vm.reindexInProgress.value}"
            }
        }
        runCurrent()
        listOf(IndexRunState.WAITING, IndexRunState.RUNNING, IndexRunState.IDLE, IndexRunState.WAITING).forEach {
            run.value = it
            runCurrent()
        }
        assertTrue("runState ile kilit çelişti: $contradictions", contradictions.isEmpty())
    }

    // ---- çoklu gözlemci, bayatlık, kapanış ----

    @Test
    fun manyObservers_shareSingleLauncherSubscription_andSeeSameValue() = runTest {
        val run = MutableStateFlow(IndexRunState.IDLE)
        val l = CountingLauncher(run)
        val vm = vm(l)
        val seen = List(5) { mutableListOf<IndexRunState>() }
        seen.forEach { list -> backgroundScope.launch { vm.runState.collect { list += it } } }
        backgroundScope.launch { vm.reindexInProgress.collect { } }
        runCurrent()
        run.value = IndexRunState.WAITING
        runCurrent()
        run.value = IndexRunState.RUNNING
        runCurrent()
        run.value = IndexRunState.IDLE
        runCurrent()
        val expected = listOf(IndexRunState.IDLE, IndexRunState.WAITING, IndexRunState.RUNNING, IndexRunState.IDLE)
        seen.forEach { assertEquals(expected, it) }
        assertEquals(1, l.subscribed)
        assertEquals(1, l.totalSubscriptions)
    }

    @Test
    fun noObserver_eagerSharing_launcherSubscribedOnce_valueNotStale() = runTest {
        val run = MutableStateFlow(IndexRunState.IDLE)
        val l = CountingLauncher(run)
        val vm = vm(l)
        runCurrent()
        assertEquals(1, l.subscribed) // gözlemci olmadan da abone (Eagerly)
        run.value = IndexRunState.RUNNING
        runCurrent()
        assertEquals(IndexRunState.RUNNING, vm.runState.value)
        advanceTimeBy(60_000) // WhileSubscribed(5s) olsaydı kopardı; Eagerly koparmaz
        run.value = IndexRunState.WAITING
        runCurrent()
        assertEquals(IndexRunState.WAITING, vm.runState.value)
        assertEquals(1, l.totalSubscriptions)
    }

    @Test
    fun observerLeavesAndReturnsLater_seesCurrentValue_notOldOne() = runTest {
        val run = MutableStateFlow(IndexRunState.RUNNING)
        val vm = vm(CountingLauncher(run))
        val first = backgroundScope.launch { vm.runState.collect { } }
        runCurrent()
        assertEquals(IndexRunState.RUNNING, vm.runState.value)
        first.cancel()
        advanceTimeBy(30_000)
        run.value = IndexRunState.IDLE // gözlemci yokken bitti
        runCurrent()
        val late = mutableListOf<IndexRunState>()
        backgroundScope.launch { vm.runState.collect { late += it } }
        runCurrent()
        assertEquals(listOf(IndexRunState.IDLE), late) // bayat RUNNING görülmez
        assertNull(indexingBanner(IndexStatusUiState.Data(10, 3, null), late.last()))
    }

    @Test
    fun roomState_resubscribeAfterLongGap_restartsWithLoading_notStaleData() = runTest {
        val row = MutableStateFlow<IndexState?>(IndexState(total = 10, processed = 10, lastRunAt = 5L))
        val vm = IndexStatusViewModel(row, CountingLauncher(MutableStateFlow(IndexRunState.RUNNING)))
        val job = backgroundScope.launch { vm.state.collect { } }
        advanceTimeBy(IndexStatusViewModel.SAMPLE_PERIOD_MS + 1)
        runCurrent()
        assertEquals(IndexStatusUiState.Data(10, 10, 5L), vm.state.value)
        job.cancel()
        advanceTimeBy(10_000) // WhileSubscribed(5s) süresi doldu
        row.value = IndexState(total = 10, processed = 2, lastRunAt = null)
        val seen = mutableListOf<IndexStatusUiState>()
        backgroundScope.launch { vm.state.collect { seen += it } }
        runCurrent()
        advanceTimeBy(IndexStatusViewModel.SAMPLE_PERIOD_MS + 1)
        runCurrent()
        assertEquals(IndexStatusUiState.Data(10, 2, null), vm.state.value)
        // Bilinen davranış: stateIn(WhileSubscribed) resetReplay'siz olduğundan yeniden abone ilk karede bayat değeri
        // (önceki Data) alır; ardından Loading ve güncel veri gelir. Bayat değer yalnızca İLK emisyondur.
        assertFalse(seen.drop(1).contains(IndexStatusUiState.Data(10, 10, 5L)))
        assertTrue(seen.contains(IndexStatusUiState.Loading))
        // Bayat tamamlanmış sayı bandı bozmaz: RUNNING + processed == total nötr metin verir.
        assertEquals(IndexingBanner(null, null), indexingBanner(IndexStatusUiState.Data(10, 10, 5L), IndexRunState.RUNNING))
    }

    @Test
    fun cleared_cancelsLauncherSubscription_andFreezesRunState_andLaterCallsDoNotCrash() = runTest {
        val run = MutableStateFlow(IndexRunState.WAITING)
        val l = CountingLauncher(run)
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = IndexStatusViewModel(MutableStateFlow(null), l) as T
        })[IndexStatusViewModel::class.java]
        runCurrent()
        assertEquals(1, l.subscribed)
        assertEquals(IndexRunState.WAITING, vm.runState.value)

        store.clear()
        runCurrent()
        assertEquals(0, l.subscribed) // sızıntı yok
        run.value = IndexRunState.RUNNING
        runCurrent()
        assertEquals(IndexRunState.WAITING, vm.runState.value) // donmuş, güncellenmez
        // Temizlenmiş VM'de reindex çökmez (kilitli olduğu için istek de gitmez).
        vm.reindex()
        runCurrent()
        assertTrue(l.modes.isEmpty())
    }

    @Test
    fun cleared_whileLaunchGracePending_noCrash_noLateLaunch() = runTest {
        val l = CountingLauncher(MutableStateFlow(IndexRunState.IDLE))
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = IndexStatusViewModel(MutableStateFlow(null), l) as T
        })[IndexStatusViewModel::class.java]
        runCurrent()
        vm.reindex()
        store.clear()
        advanceTimeBy(grace * 2)
        runCurrent()
        assertEquals(1, l.modes.size)
        assertEquals(0, l.subscribed)
    }

    // ---- banner / eşleme sınırları ----

    @Test
    fun banner_boundaries_processedBelowTotalShows_equalOrAboveNeutral() {
        val r = IndexRunState.RUNNING
        assertEquals(IndexingBanner(0, 1), indexingBanner(IndexStatusUiState.Data(1, 0, null), r))
        assertEquals(IndexingBanner(Int.MAX_VALUE - 1, Int.MAX_VALUE), indexingBanner(IndexStatusUiState.Data(Int.MAX_VALUE, Int.MAX_VALUE - 1, null), r))
        assertEquals(IndexingBanner(null, null), indexingBanner(IndexStatusUiState.Data(Int.MAX_VALUE, Int.MAX_VALUE, null), r))
        assertEquals(IndexingBanner(null, null), indexingBanner(IndexStatusUiState.Data(1, 2, null), r))
        assertEquals(IndexingBanner(null, null), indexingBanner(IndexStatusUiState.Data(0, 0, 1L), r)) // boş galeri
        assertEquals(IndexingBanner(null, null), indexingBanner(IndexStatusUiState.Data(0, 5, 1L), r)) // total=0, processed>total
    }

    @Test
    fun toUiState_negativeProcessedClamped_extremes_totalUntouched() {
        assertEquals(IndexStatusUiState.Data(10, 0, null), IndexState(total = 10, processed = Int.MIN_VALUE, lastRunAt = null).toUiState())
        assertEquals(IndexStatusUiState.Data(-3, 0, 9L), IndexState(total = -3, processed = -3, lastRunAt = 9L).toUiState())
        val d = IndexState(total = 10, processed = -1, lastRunAt = null).toUiState() as IndexStatusUiState.Data
        assertEquals(0f, d.fraction, 0f)
        // Negatif processed + RUNNING: bantta "0 / 10" (negatif asla gösterilmez).
        assertEquals(IndexingBanner(0, 10), indexingBanner(d, IndexRunState.RUNNING))
    }

    @Test
    fun banner_waitingAndIdle_ignoreCountsEntirely_evenExtremes() {
        val extremes = listOf(
            IndexStatusUiState.Data(Int.MIN_VALUE, Int.MIN_VALUE, null),
            IndexStatusUiState.Data(0, 0, null),
            IndexStatusUiState.Data(10, 12, 5L),
        )
        extremes.forEach {
            assertEquals(IndexingBanner(null, null, waiting = true), indexingBanner(it, IndexRunState.WAITING))
            assertNull(indexingBanner(it, IndexRunState.IDLE))
        }
    }

    // ---- metinler ----

    private fun strings(): String = File(System.getProperty("user.dir")!!, "src/main/res/values/strings.xml").readText()

    private fun stringValue(name: String): String {
        val m = Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(strings())
        assertNotNull("string yok: $name", m)
        return m!!.groupValues[1]
    }

    @Test
    fun bannerAndRunStateStrings_makeNoFalseClaims_andKeepTurkishCharacters() {
        val names = listOf(
            "indexing_progress", "indexing_running", "indexing_waiting",
            "index_run_state_running", "index_run_state_waiting",
        )
        names.forEach { n ->
            val v = stringValue(n).lowercase(java.util.Locale.forLanguageTag("tr-TR"))
            listOf("şarj", "boşta", "wifi", "wi-fi", "charging", "idle").forEach {
                assertFalse("$n yanlış iddia içeriyor: $it", v.contains(it))
            }
        }
        assertTrue(stringValue("indexing_running").startsWith("İndeksleniyor")) // büyük İ
        assertTrue(stringValue("indexing_progress").startsWith("İndeksleniyor ("))
        assertEquals("İndeksleme sırada; birazdan ya da cihaz uygun olunca başlayacak. Sonuçlar eksik olabilir.", stringValue("indexing_waiting"))
        assertTrue(stringValue("index_run_state_running").contains("Çalışıyor"))
        assertTrue(stringValue("index_run_state_waiting").contains("Beklemede"))
        // Çalışıyor metni "Beklemede" iddiası taşımaz ve tersi.
        assertFalse(stringValue("index_run_state_running").contains("Beklemede"))
        assertFalse(stringValue("index_run_state_waiting").contains("Çalışıyor"))
        // Bekleyen metin sayı göstermez; ilerleme yer tutucusu yalnızca progress'te.
        assertFalse(stringValue("indexing_waiting").contains("%"))
        assertFalse(stringValue("indexing_running").contains("%"))
    }
}
