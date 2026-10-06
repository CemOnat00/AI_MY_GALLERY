package com.ktu.aigaleri.ml

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** T-007 QA: SingleSessionSlot hata/iptal yolları. */
class SingleSessionSlotEdgeCaseTest {
    private class Fake(val failOnClose: Boolean = false) : AutoCloseable {
        var closes = 0
        override fun close() {
            closes++
            if (failOnClose) throw IllegalStateException("kapanış hatası")
        }
    }

    @Test
    fun closeThrows_onEviction_callbackStillRuns_andSlotRecovers() = runTest {
        val slot = SingleSessionSlot()
        var released = 0
        val a = Fake(failOnClose = true)
        slot.withSession("a", { a }, onClosed = { released++ }) { }
        try {
            slot.withSession("b", { Fake() }) { }
            fail()
        } catch (_: IllegalStateException) {
        }
        assertEquals(1, a.closes)
        assertEquals(1, released) // kaynaklar kapanış hatasında bile bırakıldı
        val b = Fake()
        slot.withSession("b", { b }) { }
        assertEquals(0, b.closes)
        slot.closeAll()
        assertEquals(1, b.closes)
    }

    @Test
    fun closeAll_onEmptySlot_isNoop_andRepeatable() = runTest {
        val slot = SingleSessionSlot()
        slot.closeAll()
        slot.closeAll()
        slot.close("x")
    }

    @Test
    fun doubleClose_closesSessionOnlyOnce() = runTest {
        val slot = SingleSessionSlot()
        val a = Fake()
        slot.withSession("a", { a }) { }
        slot.close("a")
        slot.close("a")
        slot.closeAll()
        assertEquals(1, a.closes)
    }

    @Test
    fun cancelledWaiter_doesNotOpenSession_andDoesNotBlockOthers() = runBlocking {
        val slot = SingleSessionSlot()
        var opened = 0
        val gate = java.util.concurrent.CountDownLatch(1)
        val holder = launch(kotlinx.coroutines.Dispatchers.Default) {
            slot.withSession("a", { Fake() }) { gate.await() }
        }
        while (!holder.isActive) yield()
        Thread.sleep(50)
        val waiter = launch(kotlinx.coroutines.Dispatchers.Default, start = CoroutineStart.DEFAULT) {
            slot.withSession("b", { opened++; Fake() }) { }
        }
        Thread.sleep(50)
        waiter.cancel()
        gate.countDown()
        holder.join()
        waiter.join()
        assertTrue(waiter.isCancelled)
        assertEquals(0, opened)
        slot.withSession("a", { Fake() }) { } // yuva hâlâ kullanılabilir
    }

    @Test
    fun manyKeySwitches_neverLeakOpenSessions() = runTest {
        val slot = SingleSessionSlot()
        val all = ArrayList<Fake>()
        repeat(100) { i ->
            slot.withSession(if (i % 2 == 0) "text" else "vision", { Fake().also { all.add(it) } }) { }
        }
        assertEquals(100, all.size)
        assertEquals(99, all.count { it.closes == 1 })
        assertEquals(1, all.count { it.closes == 0 })
        slot.closeAll()
        assertTrue(all.all { it.closes == 1 })
    }
}
