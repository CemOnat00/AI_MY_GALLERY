package com.ktu.aigaleri.work

import androidx.work.WorkManager
import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.ui.IndexLauncher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Gerçek [IndexLauncher]: indeksleme işini WorkManager benzersiz kuyruğuna ekler ([IndexWork]: INCREMENTAL KEEP,
 * FULL REPLACE). [launch] hemen döner. [isRunning], kuyruktaki işin durumundan türetilir (sabit bekleme süresi yok).
 * Ağ kısıtı yoktur. [WorkManager] enjekte edilir (androidTest'te test başlatıcısıyla denenir).
 */
class WorkManagerIndexLauncher(private val workManager: WorkManager) : IndexLauncher {
    override val isAvailable: Boolean = true

    override val isRunning: Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(IndexWork.UNIQUE_NAME)
            .map { infos -> IndexWork.isActive(infos.map { it.state }) }
            .distinctUntilChanged()

    override fun launch(mode: IndexMode) {
        workManager.enqueueUniqueWork(IndexWork.UNIQUE_NAME, IndexWork.policyFor(mode), IndexWork.request(mode))
    }
}
