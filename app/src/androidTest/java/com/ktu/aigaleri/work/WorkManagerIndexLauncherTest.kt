package com.ktu.aigaleri.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.ktu.aigaleri.domain.IndexMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gerçek WorkManager (test başlatıcısıyla, kısıtlar karşılanmadığı için iş ÇALIŞMAZ, yalnızca kuyruğa girer) üzerinde
 * benzersiz iş politikasını ve [WorkManagerIndexLauncher.isRunning]'i sınar. İşin gövdesi ([IndexWork.execute])
 * JVM'de test edilir.
 *
 * DURUM: YAZILDI, DERLENDİ, ÇALIŞTIRILMADI (cihaz/emülatör yok).
 */
@RunWith(AndroidJUnit4::class)
class WorkManagerIndexLauncherTest {
    private lateinit var workManager: WorkManager
    private lateinit var launcher: WorkManagerIndexLauncher

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        workManager = WorkManager.getInstance(context)
        launcher = WorkManagerIndexLauncher(workManager)
    }

    private fun infos() = workManager.getWorkInfosForUniqueWork(IndexWork.UNIQUE_NAME).get()

    @Test
    fun isAvailable_andInitiallyNotRunning() = runBlocking {
        assertTrue(launcher.isAvailable)
        assertFalse(launcher.isRunning.first())
    }

    @Test
    fun incrementalTwice_keepsSingleEnqueuedWork() = runBlocking {
        launcher.launch(IndexMode.INCREMENTAL)
        launcher.launch(IndexMode.INCREMENTAL)
        val list = infos()
        assertEquals(1, list.size)
        assertEquals(WorkInfo.State.ENQUEUED, list.single().state)
        assertTrue(launcher.isRunning.first())
    }

    @Test
    fun full_replacesPendingIncremental() {
        launcher.launch(IndexMode.INCREMENTAL)
        val firstId = infos().single().id
        launcher.launch(IndexMode.FULL)
        val states = infos().associate { it.id to it.state }
        assertEquals(WorkInfo.State.CANCELLED, states[firstId])
        assertEquals(1, states.values.count { it == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun incremental_doesNotReplacePendingFull() {
        launcher.launch(IndexMode.FULL)
        val fullId = infos().single().id
        launcher.launch(IndexMode.INCREMENTAL)
        val list = infos()
        assertEquals(1, list.size)
        assertEquals(fullId, list.single().id)
    }

    @Test
    fun request_hasBatteryNotLow_andNoNetworkConstraint() {
        val c = IndexWork.constraints()
        assertTrue(c.requiresBatteryNotLow())
        assertEquals(androidx.work.NetworkType.NOT_REQUIRED, c.requiredNetworkType)
        assertEquals(IndexMode.FULL.name, IndexWork.request(IndexMode.FULL).workSpec.input.getString(IndexWork.KEY_MODE))
    }
}
