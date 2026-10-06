package com.ktu.aigaleri.ml

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SingleSessionSlotTest {
    private class Fake(val name: String) : AutoCloseable {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    @Test
    fun sameKey_opensOnce() = runTest {
        val slot = SingleSessionSlot()
        var opens = 0
        val a = slot.withSession("a", { opens++; Fake("a") }) { it }
        val b = slot.withSession("a", { opens++; Fake("a") }) { it }
        assertSame(a, b)
        assertEquals(1, opens)
        assertTrue(!a.closed)
    }

    @Test
    fun differentKey_closesPreviousBeforeOpening() = runTest {
        val slot = SingleSessionSlot()
        val a = slot.withSession("text", { Fake("text") }) { it }
        var textClosedWhenOpening: Boolean? = null
        val b = slot.withSession("vision", { textClosedWhenOpening = a.closed; Fake("vision") }) { it }
        assertEquals(true, textClosedWhenOpening)
        assertTrue(a.closed)
        assertTrue(!b.closed)
    }

    @Test
    fun close_onlyAffectsMatchingKey_andRunsCallbackAlways() = runTest {
        val slot = SingleSessionSlot()
        val a = slot.withSession("a", { Fake("a") }) { it }
        var callbacks = 0
        slot.close("other") { callbacks++ }
        assertTrue(!a.closed)
        slot.close("a") { callbacks++ }
        assertTrue(a.closed)
        assertEquals(2, callbacks)
        // Kapatıldıktan sonra yeniden açılır.
        val a2 = slot.withSession("a", { Fake("a") }) { it }
        assertTrue(a2 !== a && !a2.closed)
    }

    @Test
    fun onClosed_runsOnEvictionCloseAndCloseAll() = runTest {
        val slot = SingleSessionSlot()
        var released = 0
        slot.withSession("text", { Fake("t") }, onClosed = { released++ }) { }
        slot.withSession("vision", { Fake("v") }, onClosed = { released += 10 }) { } // text evict edildi
        assertEquals(1, released)
        slot.closeAll()
        assertEquals(11, released)
    }

    @Test
    fun concurrentSessions_neverOverlapAndNeverCloseInUseSession() {
        val slot = SingleSessionSlot()
        val active = java.util.concurrent.atomic.AtomicInteger()
        val maxActive = java.util.concurrent.atomic.AtomicInteger()
        val closedWhileInUse = java.util.concurrent.atomic.AtomicInteger()
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Default) {
            (0 until 40).map { i ->
                async {
                    val key = if (i % 2 == 0) "text" else "vision"
                    slot.withSession(key, { Fake(key) }) { s ->
                        maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                        Thread.sleep(2)
                        if (s.closed) closedWhileInUse.incrementAndGet()
                        active.decrementAndGet()
                    }
                }
            }.awaitAll()
        }
        assertEquals(1, maxActive.get())
        assertEquals(0, closedWhileInUse.get())
    }

    @Test
    fun twoConcurrentCallsSameKey_shareOneSession() {
        val slot = SingleSessionSlot()
        val opens = java.util.concurrent.atomic.AtomicInteger()
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Default) {
            listOf(async { slot.withSession("a", { opens.incrementAndGet(); Fake("a") }) { Thread.sleep(20) } },
                async { slot.withSession("a", { opens.incrementAndGet(); Fake("a") }) { Thread.sleep(20) } }).awaitAll()
        }
        assertEquals(1, opens.get())
    }

    @Test
    fun closeAll_closesCurrent() = runTest {
        val slot = SingleSessionSlot()
        val a = slot.withSession("a", { Fake("a") }) { it }
        slot.closeAll()
        assertTrue(a.closed)
    }

    @Test
    fun openFailure_leavesSlotEmptyAndUsable() = runTest {
        val slot = SingleSessionSlot()
        val a = slot.withSession("a", { Fake("a") }) { it }
        try {
            slot.withSession<Fake, Unit>("b", { error("açılamadı") }) { }
            fail()
        } catch (_: IllegalStateException) {
        }
        assertTrue(a.closed) // önceki oturum kapatıldı, yarım durum kalmadı
        val b = slot.withSession("b", { Fake("b") }) { it }
        assertTrue(!b.closed)
    }

    @Test
    fun blockFailure_keepsSessionOpen() = runTest {
        val slot = SingleSessionSlot()
        var created: Fake? = null
        try {
            slot.withSession<Fake, Unit>("a", { Fake("a").also { created = it } }) { error("çıkarım hatası") }
            fail()
        } catch (_: IllegalStateException) {
        }
        assertTrue(!created!!.closed)
        val again = slot.withSession("a", { Fake("x") }) { it }
        assertSame(created, again)
    }
}
