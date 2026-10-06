package com.ktu.aigaleri.work

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import com.ktu.aigaleri.domain.EmbeddingSpec
import com.ktu.aigaleri.domain.IndexException
import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.domain.IndexPhase
import com.ktu.aigaleri.domain.IndexProgress
import com.ktu.aigaleri.domain.PhotoIndexer
import com.ktu.aigaleri.ml.ModelException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** T-006: worker/launcher'ın Android'siz mantığı (WorkManager sınıfları testte örneklenmez; androidTest'te denenir). */
class IndexWorkTest {
    private class FakeIndexer(private val body: suspend (IndexMode) -> Unit = {}) : PhotoIndexer {
        override val embeddingSpec = EmbeddingSpec(512, "v")
        val modes = mutableListOf<IndexMode>()
        var emitted = 0

        override fun index(mode: IndexMode): Flow<IndexProgress> = flow {
            modes += mode
            emit(IndexProgress(IndexPhase.SCANNING, 0, 0)); emitted++
            body(mode)
            emit(IndexProgress(IndexPhase.COMPLETED, 0, 0)); emitted++
        }
    }

    @Test
    fun uniqueWorkPolicy_incrementalKeeps_fullReplaces() {
        assertEquals(ExistingWorkPolicy.KEEP, IndexWork.policyFor(IndexMode.INCREMENTAL))
        assertEquals(ExistingWorkPolicy.REPLACE, IndexWork.policyFor(IndexMode.FULL))
    }

    @Test
    fun parseMode_roundTrips_andDefaultsToIncremental() {
        for (m in IndexMode.entries) assertEquals(m, IndexWork.parseMode(m.name))
        for (bad in listOf(null, "", "full", "X")) assertEquals(IndexMode.INCREMENTAL, IndexWork.parseMode(bad))
    }

    @Test
    fun isActive_trueForEnqueuedRunningBlocked_falseForFinalStates() {
        assertFalse(IndexWork.isActive(emptyList()))
        for (s in listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)) {
            assertTrue(s.name, IndexWork.isActive(listOf(s)))
        }
        for (s in listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)) {
            assertFalse(s.name, IndexWork.isActive(listOf(s)))
        }
        assertTrue(IndexWork.isActive(listOf(WorkInfo.State.CANCELLED, WorkInfo.State.RUNNING)))
    }

    @Test
    fun execute_collectsWholeFlow_andSucceeds() = runBlocking {
        val indexer = FakeIndexer()
        assertEquals(IndexOutcome.SUCCESS, IndexWork.execute(indexer, IndexMode.FULL, 0))
        assertEquals(listOf(IndexMode.FULL), indexer.modes)
        assertEquals(2, indexer.emitted)
    }

    @Test
    fun execute_keepsRequestedMode_evenAfterRestart() = runBlocking {
        // FULL kendi başına yeniden başlatılabilir (RoomPhotoIndexer): mod yeniden denemede değiştirilmez.
        for (attempt in 0..2) {
            val indexer = FakeIndexer()
            IndexWork.execute(indexer, IndexMode.FULL, attempt)
            assertEquals(listOf(IndexMode.FULL), indexer.modes)
        }
    }

    @Test
    fun execute_permissionMissing_isFailure_noRetry() = runBlocking {
        val indexer = FakeIndexer { throw IndexException.PermissionMissing() }
        assertEquals(IndexOutcome.FAILURE, IndexWork.execute(indexer, IndexMode.INCREMENTAL, 0))
    }

    @Test
    fun execute_unexpected_retriesUntilMaxAttempts_thenFails() = runBlocking {
        val indexer = FakeIndexer { throw IndexException.Unexpected(RuntimeException("disk")) }
        assertEquals(IndexOutcome.RETRY, IndexWork.execute(indexer, IndexMode.INCREMENTAL, 0))
        assertEquals(IndexOutcome.RETRY, IndexWork.execute(indexer, IndexMode.INCREMENTAL, IndexWork.MAX_ATTEMPTS - 2))
        assertEquals(IndexOutcome.FAILURE, IndexWork.execute(indexer, IndexMode.INCREMENTAL, IndexWork.MAX_ATTEMPTS - 1))
        assertEquals(IndexOutcome.FAILURE, IndexWork.execute(indexer, IndexMode.INCREMENTAL, 10))
    }

    @Test
    fun execute_missingOrCorruptModel_failsImmediately_sinceRetryCannotFixIt() = runBlocking {
        for (cause in listOf(ModelException.Missing("models/x"), ModelException.IntegrityFailure("x", "sha"))) {
            val indexer = FakeIndexer { throw IndexException.Unexpected(cause) }
            assertEquals(IndexOutcome.FAILURE, IndexWork.execute(indexer, IndexMode.INCREMENTAL, 0))
        }
        // Geçici olabilecek model hatası (ör. depolama) yeniden denenir.
        val transient = FakeIndexer { throw IndexException.Unexpected(ModelException.InsufficientStorage("x", 1)) }
        assertEquals(IndexOutcome.RETRY, IndexWork.execute(transient, IndexMode.INCREMENTAL, 0))
    }

    @Test
    fun execute_cancellation_propagates_notSwallowedAsResult() {
        val indexer = FakeIndexer { throw CancellationException("durduruldu") }
        try {
            runBlocking { IndexWork.execute(indexer, IndexMode.INCREMENTAL, 0) }
            fail("CancellationException beklenir")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun constants_areStable() {
        assertEquals("ai-galeri-index", IndexWork.UNIQUE_NAME)
        assertEquals(3, IndexWork.MAX_ATTEMPTS)
    }
}
