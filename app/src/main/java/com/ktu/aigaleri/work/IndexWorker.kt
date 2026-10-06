package com.ktu.aigaleri.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ktu.aigaleri.ui.AppDependencies

/**
 * [com.ktu.aigaleri.domain.PhotoIndexer.index] akışını toplayan WorkManager işi. Mantık [IndexWork.execute]'tadır
 * (JVM'de test edilir). `CoroutineWorker`, iş iptal/durdurulunca coroutine'i iptal eder; indeksleyici iptale
 * duyarlıdır ve yazılan kayıtlar korunur. Foreground servis/bildirim kullanılmaz (bkz. [IndexWork] sınırları).
 * Log yazılmaz.
 */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val indexer = AppDependencies.get(applicationContext).photoIndexer
        val mode = IndexWork.parseMode(inputData.getString(IndexWork.KEY_MODE))
        return when (IndexWork.execute(indexer, mode, runAttemptCount)) {
            IndexOutcome.SUCCESS -> Result.success()
            IndexOutcome.RETRY -> Result.retry()
            IndexOutcome.FAILURE -> Result.failure()
        }
    }
}
