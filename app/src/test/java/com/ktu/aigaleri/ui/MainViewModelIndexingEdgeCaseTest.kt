package com.ktu.aigaleri.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.ktu.aigaleri.data.FakeMediaPhotoSource
import com.ktu.aigaleri.data.IndexState
import com.ktu.aigaleri.data.MediaPhoto
import com.ktu.aigaleri.data.MediaPhotoSource
import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.ui.index.IndexStatusUiState
import com.ktu.aigaleri.ui.index.IndexStatusViewModel
import com.ktu.aigaleri.ui.index.IndexingBanner
import com.ktu.aigaleri.ui.index.indexingBanner
import com.ktu.aigaleri.ui.index.toUiState
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-011 QA: artimli indekslemeyi baslatma (izin gecis tablosu, hizli ardisik onResume, launch istisnasi),
 * arama ekrani banner'i (sinir degerleri, akis hatasi) ve ViewModel yasam dongusu.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelIndexingEdgeCaseTest {
    private class Launcher(
        override val isAvailable: Boolean = true,
        var failWith: Throwable? = null,
        override val isRunning: Flow<Boolean> = MutableStateFlow(false),
    ) : IndexLauncher {
        val modes = mutableListOf<IndexMode>()

        override fun launch(mode: IndexMode) {
            modes += mode
            failWith?.let { throw it }
        }
    }

    private val photos = listOf(MediaPhoto(1L, "content://x/1", null), MediaPhoto(2L, "content://x/2", 5L))

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(launcher: IndexLauncher, source: MediaPhotoSource = FakeMediaPhotoSource(photos)) =
        MainViewModel(source, launcher)

    // ---- (1)/(2) izin gecis tablosu ----

    private sealed interface Event {
        fun applyTo(vm: MainViewModel)
        data class Resume(val granted: Boolean) : Event {
            override fun applyTo(vm: MainViewModel) = vm.onResume(granted)
        }
        data class Result(val granted: Boolean, val rationale: Boolean) : Event {
            override fun applyTo(vm: MainViewModel) = vm.onPermissionResult(granted, rationale)
        }
    }

    private val events = listOf(
        Event.Resume(true), Event.Resume(false),
        Event.Result(true, false), Event.Result(false, true), Event.Result(false, false),
    )

    /** Baslangic durumuna ulasmak icin olay dizisi (her biri taze ViewModel uzerinde). */
    private val prefixes = mapOf(
        PermissionState.NotRequested to emptyList<Event>(),
        PermissionState.Granted to listOf<Event>(Event.Result(true, false)),
        PermissionState.Denied to listOf<Event>(Event.Result(false, true)),
        PermissionState.PermanentlyDenied to listOf<Event>(Event.Result(false, false)),
    )

    /** (baslangic, olay) -> beklenen yeni durum; elle yazilmis tablo (uretim mantigini kopyalamaz). */
    private fun expected(from: PermissionState, e: Event): PermissionState = when (e) {
        Event.Resume(true), Event.Result(true, false) -> PermissionState.Granted
        Event.Result(false, true) -> PermissionState.Denied
        Event.Result(false, false) -> PermissionState.PermanentlyDenied
        Event.Resume(false) -> when (from) {
            PermissionState.Granted -> PermissionState.NotRequested
            else -> from
        }
        else -> error("tabloda yok: $e")
    }

    @Test
    fun transitionTable_everyStateAndEvent_enqueuesExactlyWhenResultIsGranted() {
        for ((from, prefix) in prefixes) {
            for (e in events) {
                val launcher = Launcher()
                val vm = vm(launcher)
                prefix.forEach { it.applyTo(vm) }
                assertEquals("onkosul $from", from, vm.state.value.permission)
                val before = launcher.modes.size
                e.applyTo(vm)
                val next = expected(from, e)
                assertEquals("$from + $e", next, vm.state.value.permission)
                val delta = launcher.modes.size - before
                assertEquals("$from + $e enqueue sayisi", if (next == PermissionState.Granted) 1 else 0, delta)
                assertTrue(launcher.modes.all { it == IndexMode.INCREMENTAL })
                // Granted disinda sayi/hata temiz.
                if (next != PermissionState.Granted) {
                    assertNull("$from + $e", vm.state.value.photoCount)
                    assertEquals(false, vm.state.value.loadError)
                }
            }
        }
    }

    @Test
    fun permissionFlapping_grantedDeniedGranted_enqueuesOncePerGrantedTransition() {
        val launcher = Launcher()
        val vm = vm(launcher)
        repeat(5) {
            vm.onResume(granted = true)
            vm.onResume(granted = false)
        }
        assertEquals(5, launcher.modes.size)
        assertEquals(PermissionState.NotRequested, vm.state.value.permission)
    }

    @Test
    fun rapidConsecutiveResumes_eachEnqueuesIncremental_stateAndCountStayConsistent() {
        val launcher = Launcher()
        val source = FakeMediaPhotoSource(photos)
        val vm = vm(launcher, source)
        repeat(1_000) { vm.onResume(granted = true) }
        assertEquals(1_000, launcher.modes.size)
        assertTrue(launcher.modes.all { it == IndexMode.INCREMENTAL })
        assertEquals(MainUiState(PermissionState.Granted, 2), vm.state.value)
    }

    @Test
    fun rapidResumes_withSlowCount_onlyLatestCountIsApplied() {
        val gates = mutableListOf<CompletableDeferred<Int>>()
        val source = object : MediaPhotoSource {
            override suspend fun count(): Int = CompletableDeferred<Int>().also { gates += it }.await()
            override suspend fun loadPhotos(): List<MediaPhoto> = emptyList()
        }
        val launcher = Launcher()
        val vm = vm(launcher, source)
        repeat(3) { vm.onResume(granted = true) }
        assertEquals(3, gates.size)
        gates[2].complete(30)
        gates[1].complete(20) // iptal edilmis; etkisiz
        gates[0].complete(10)
        assertEquals(30, vm.state.value.photoCount)
        assertEquals(3, launcher.modes.size)
    }

    @Test
    fun emptyAndHugeGallery_stillEnqueue() {
        val l1 = Launcher()
        vm(l1, FakeMediaPhotoSource(emptyList())).onResume(true)
        assertEquals(listOf(IndexMode.INCREMENTAL), l1.modes)
        val huge = List(100_000) { MediaPhoto(it + 1L, "content://x/${it + 1}", null) }
        val l2 = Launcher()
        val vm2 = vm(l2, FakeMediaPhotoSource(huge))
        vm2.onResume(true)
        assertEquals(listOf(IndexMode.INCREMENTAL), l2.modes)
        assertEquals(100_000, vm2.state.value.photoCount)
    }

    // ---- (4) launch istisnasi ----

    @Test
    fun launchException_stillLoadsCount_andKeepsGranted() {
        val launcher = Launcher(failWith = RuntimeException())
        val vm = vm(launcher)
        vm.onPermissionResult(granted = true, shouldShowRationale = false)
        assertEquals(MainUiState(PermissionState.Granted, 2, loadError = false), vm.state.value)
    }

    @Test
    fun launchSecurityException_isSwallowed_notTreatedAsPermissionLoss() {
        val launcher = Launcher(failWith = SecurityException())
        val vm = vm(launcher)
        vm.onResume(granted = true)
        assertEquals(PermissionState.Granted, vm.state.value.permission)
        assertEquals(1, launcher.modes.size)
    }

    @Test
    fun launchFailsThenSucceeds_thenFailsAgain_everyResumeRetries_noBackgroundRetry() {
        val launcher = Launcher(failWith = IllegalStateException())
        val vm = vm(launcher)
        vm.onResume(true)
        assertEquals(1, launcher.modes.size) // otomatik tekrar yok
        launcher.failWith = null
        vm.onResume(true)
        assertEquals(2, launcher.modes.size)
        launcher.failWith = IOException()
        vm.onResume(true)
        vm.onPermissionResult(true, false)
        assertEquals(4, launcher.modes.size)
        assertEquals(PermissionState.Granted, vm.state.value.permission)
        assertEquals(2, vm.state.value.photoCount)
    }

    @Test
    fun launcherFailsRepeatedly_manyResumes_noCrash() {
        val launcher = Launcher(failWith = RuntimeException())
        val vm = vm(launcher)
        repeat(200) { vm.onResume(true) }
        assertEquals(200, launcher.modes.size)
        assertEquals(PermissionState.Granted, vm.state.value.permission)
    }

    @Test
    fun launchAndCountBothFail_securityFromCountResetsPermission_noCrash() {
        val launcher = Launcher(failWith = RuntimeException())
        val vm = vm(launcher, FakeMediaPhotoSource(photos, failWith = SecurityException()))
        vm.onResume(true)
        assertEquals(MainUiState(PermissionState.NotRequested, null), vm.state.value)
        // Izin yeniden verilir ve her sey duzelirse yeniden baslar.
        launcher.failWith = null
        vm.onResume(true)
        assertEquals(2, launcher.modes.size)
    }

    @Test
    fun unavailableLauncher_neverCalled_acrossAllPermissionEvents_butCountStillLoads() {
        val launcher = Launcher(isAvailable = false)
        val vm = vm(launcher)
        events.forEach { it.applyTo(vm) }
        vm.onResume(true)
        assertTrue(launcher.modes.isEmpty())
        assertEquals(2, vm.state.value.photoCount)
    }

    // ---- ViewModel yasam dongusu ----

    @Test
    fun clearedViewModel_pendingCountIsCancelled_andLateResultDoesNotUpdateState() {
        val gate = CompletableDeferred<Int>()
        val source = object : MediaPhotoSource {
            override suspend fun count(): Int = gate.await()
            override suspend fun loadPhotos(): List<MediaPhoto> = emptyList()
        }
        val store = ViewModelStore()
        val launcher = Launcher()
        val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(source, launcher) as T
        })[MainViewModel::class.java]
        vm.onResume(true)
        store.clear()
        gate.complete(7)
        assertNull(vm.state.value.photoCount)
        // Temizlenmis ViewModel'de cagri cokmez.
        vm.onResume(true)
        assertNull(vm.state.value.photoCount)
    }

    // ---- (3) banner ----

    private val neutral = IndexingBanner(null, null)

    @Test
    fun banner_notRunning_isHiddenForEveryStatus() {
        listOf(
            IndexStatusUiState.Loading, IndexStatusUiState.NeverRun, IndexStatusUiState.Error,
            IndexStatusUiState.Data(0, 0, null), IndexStatusUiState.Data(10, 3, null),
            IndexStatusUiState.Data(10, 10, 1L), IndexStatusUiState.Data(10, 12, 1L),
        ).forEach { assertNull("$it", indexingBanner(it, running = false)) }
    }

    @Test
    fun banner_running_boundaryValues() {
        assertEquals(IndexingBanner(0, 1), indexingBanner(IndexStatusUiState.Data(1, 0, null), true))
        assertEquals(IndexingBanner(9, 10), indexingBanner(IndexStatusUiState.Data(10, 9, null), true))
        assertEquals(IndexingBanner(0, 150_000), indexingBanner(IndexStatusUiState.Data(150_000, 0, null), true))
        assertEquals(
            IndexingBanner(Int.MAX_VALUE - 1, Int.MAX_VALUE),
            indexingBanner(IndexStatusUiState.Data(Int.MAX_VALUE, Int.MAX_VALUE - 1, null), true),
        )
        assertEquals(neutral, indexingBanner(IndexStatusUiState.Data(1, 1, null), true))
        assertEquals(neutral, indexingBanner(IndexStatusUiState.Data(Int.MAX_VALUE, Int.MAX_VALUE, null), true))
        assertEquals(neutral, indexingBanner(IndexStatusUiState.Data(5, 6, null), true))
    }

    @Test
    fun banner_running_negativeTotalOrZeroTotal_isNeutral() {
        assertEquals(neutral, indexingBanner(IndexStatusUiState.Data(0, 0, null), true))
        assertEquals(neutral, indexingBanner(IndexStatusUiState.Data(0, 3, null), true))
        assertEquals(neutral, indexingBanner(IndexStatusUiState.Data(-1, -5, null), true))
        assertEquals(neutral, indexingBanner(IndexStatusUiState.Data(-1, 0, 1L), true))
        assertEquals(neutral, indexingBanner(IndexStatusUiState.Data(Int.MIN_VALUE, Int.MIN_VALUE, null), true))
    }

    @Test
    fun banner_running_noDataStates_areNeutral() {
        assertEquals(neutral, indexingBanner(IndexStatusUiState.Loading, true))
        assertEquals(neutral, indexingBanner(IndexStatusUiState.NeverRun, true))
        assertEquals(neutral, indexingBanner(IndexStatusUiState.Error, true))
    }

    @Test
    fun banner_dataFromRoomRow_nullRowIsNeverRun_andShowsNeutralWhenRunning() {
        val none: IndexState? = null
        assertEquals(neutral, indexingBanner(none.toUiState(), true))
    }

    @Test
    fun banner_neutralAndProgressStrings_matchAcceptanceText() {
        val strings = File(System.getProperty("user.dir")!!, "src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("sürüyor veya sırada; sonuçlar eksik olabilir"))
        assertTrue(strings.contains("(%1\$d / %2\$d)"))
    }

    @Test
    fun banner_roomStreamFails_whileRunning_showsNeutral_notCrash() = runTest(UnconfinedTestDispatcher()) {
        val failing: Flow<IndexState?> = flow { throw IOException() }
        val running = MutableStateFlow(true)
        val status = IndexStatusViewModel(failing, Launcher(isRunning = running))
        backgroundScope.launch { status.state.collect {} }
        backgroundScope.launch { status.reindexInProgress.collect {} }
        assertEquals(IndexStatusUiState.Error, status.state.value)
        assertEquals(neutral, indexingBanner(status.state.value, status.reindexInProgress.value))
        running.value = false
        assertNull(indexingBanner(status.state.value, status.reindexInProgress.value))
    }

    @Test
    fun banner_roomStreamFailsAfterData_thenError_whileRunning_showsNeutral() = runTest(UnconfinedTestDispatcher()) {
        val src: Flow<IndexState?> = flow {
            emit(IndexState(total = 10, processed = 4, lastRunAt = null))
            throw IOException()
        }
        val status = IndexStatusViewModel(src, Launcher(isRunning = MutableStateFlow(true)))
        backgroundScope.launch { status.state.collect {} }
        assertEquals(IndexStatusUiState.Error, status.state.value)
        assertEquals(neutral, indexingBanner(status.state.value, true))
    }

    @Test
    fun banner_launcherRunningFlowFails_treatedAsNotRunning_bannerHidden() = runTest(UnconfinedTestDispatcher()) {
        val broken: Flow<Boolean> = flow { throw IllegalStateException() }
        val status = IndexStatusViewModel(MutableStateFlow(IndexState(total = 10, processed = 3, lastRunAt = null)), Launcher(isRunning = broken))
        backgroundScope.launch { status.state.collect {} }
        backgroundScope.launch { status.reindexInProgress.collect {} }
        advanceTimeBy(IndexStatusViewModel.SAMPLE_PERIOD_MS * 2); runCurrent()
        assertEquals(IndexStatusUiState.Data(10, 3, null), status.state.value)
        assertNull(indexingBanner(status.state.value, status.reindexInProgress.value))
    }

    @Test
    fun banner_runningWithRoomProgress_showsProgress_viaRealViewModel() = runTest(UnconfinedTestDispatcher()) {
        val row = MutableStateFlow<IndexState?>(IndexState(total = 10, processed = 3, lastRunAt = null))
        val status = IndexStatusViewModel(row, Launcher(isRunning = MutableStateFlow(true)))
        backgroundScope.launch { status.state.collect {} }
        backgroundScope.launch { status.reindexInProgress.collect {} }
        advanceTimeBy(IndexStatusViewModel.SAMPLE_PERIOD_MS * 2); runCurrent()
        assertEquals(IndexingBanner(3, 10), indexingBanner(status.state.value, status.reindexInProgress.value))
    }
}
