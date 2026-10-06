package com.ktu.aigaleri.work

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import com.ktu.aigaleri.domain.IndexMode
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-006 (QA): WorkRequest/kısıt yapılandırması (JVM; WorkManager örneği gerekmez, yalnızca istek nesneleri kurulur). */
class IndexWorkRequestTest {
    @Test
    fun constraints_batteryNotLow_only_noNetworkNoChargingNoIdle() {
        val c = IndexWork.constraints()
        assertTrue(c.requiresBatteryNotLow())
        assertEquals(NetworkType.NOT_REQUIRED, c.requiredNetworkType) // ağ kullanılmaz
        assertFalse(c.requiresCharging())
        assertFalse(c.requiresDeviceIdle())
        assertFalse(c.requiresStorageNotLow())
    }

    @Test
    fun request_carriesModeAndPolicy_forEveryMode() {
        for (mode in IndexMode.entries) {
            val r = IndexWork.request(mode)
            assertEquals(mode.name, r.workSpec.input.getString(IndexWork.KEY_MODE))
            assertEquals(mode, IndexWork.parseMode(r.workSpec.input.getString(IndexWork.KEY_MODE)))
            assertEquals(IndexWorker::class.java.name, r.workSpec.workerClassName)
            assertTrue(r.workSpec.constraints.requiresBatteryNotLow())
            assertEquals(BackoffPolicy.EXPONENTIAL, r.workSpec.backoffPolicy)
            assertEquals(TimeUnit.SECONDS.toMillis(30), r.workSpec.backoffDelayDuration)
            assertFalse(r.workSpec.isPeriodic)
        }
    }

    @Test
    fun eachRequest_hasUniqueId_soReplaceNeverReusesIt() {
        assertNotEquals(IndexWork.request(IndexMode.FULL).id, IndexWork.request(IndexMode.FULL).id)
    }
}
