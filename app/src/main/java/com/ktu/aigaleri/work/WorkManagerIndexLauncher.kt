package com.ktu.aigaleri.work

import androidx.work.WorkManager
import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.ui.IndexLauncher
import com.ktu.aigaleri.ui.IndexRunState
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

    /** İş durumundan ince durum (bekleyen/çalışan ayrımı); [isRunning] ve ısınma kararı bundan türer. */
    override val runState: Flow<IndexRunState> =
        workManager.getWorkInfosForUniqueWorkFlow(IndexWork.UNIQUE_NAME)
            .map { infos -> IndexWork.runState(infos.map { it.state }) }
            .distinctUntilChanged()

    /** Kuyrukta bekleyen veya çalışan iş var ([runState] != IDLE); UI "yeniden indeksle" kilidi bunu kullanır. */
    override val isRunning: Flow<Boolean> = runState.map { it != IndexRunState.IDLE }.distinctUntilChanged()

    override fun launch(mode: IndexMode) {
        workManager.enqueueUniqueWork(IndexWork.UNIQUE_NAME, IndexWork.policyFor(mode), IndexWork.request(mode))
    }
}
