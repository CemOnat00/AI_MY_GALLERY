package com.ktu.aigaleri.ml

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** T-016 QA: OnnxTextEncoder.onSessionClosed, oturum hiç açılmadığında çağrılmaz ve slot kilitlenmez. */
class OnnxTextEncoderSessionClosedTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val missing = AssetOpener { throw java.io.FileNotFoundException(it) }

    @Test
    fun failedOpen_doesNotInvokeOnSessionClosed_andLeavesSlotUsable() = runBlocking {
        val closed = AtomicInteger()
        val slot = SingleSessionSlot()
        val enc = OnnxTextEncoder(ModelStore(File(tmp.root, "a").also { it.mkdirs() }, missing), slot, onSessionClosed = { closed.incrementAndGet() })
        repeat(3) {
            try {
                enc.encode("kedi")
                fail()
            } catch (_: ModelException) {
            }
        }
        assertEquals(0, closed.get())
        assertEquals("ok", withTimeout(5_000) { slot.withSession("image", open = { AutoCloseable { } }) { "ok" } })
    }

    @Test
    fun release_withoutOpenSession_doesNotInvokeOnSessionClosed_andIsRepeatable() = runBlocking {
        val closed = AtomicInteger()
        val slot = SingleSessionSlot()
        val enc = OnnxTextEncoder(ModelStore(File(tmp.root, "b").also { it.mkdirs() }, missing), slot, onSessionClosed = { closed.incrementAndGet() })
        repeat(5) { withTimeout(5_000) { enc.release() } }
        assertEquals(0, closed.get())
    }

    @Test
    fun invalidTurkishQueries_neverTouchSessionOrCallback() = runBlocking {
        val closed = AtomicInteger()
        val enc = OnnxTextEncoder(
            ModelStore(File(tmp.root, "c").also { it.mkdirs() }, AssetOpener { error("dokunulmamalı") }),
            SingleSessionSlot(),
            onSessionClosed = { closed.incrementAndGet() },
        )
        for (q in listOf("", "   ", "İ".repeat(QueryPreprocessor.MAX_QUERY_LENGTH + 1))) {
            try {
                enc.encode(q)
                fail()
            } catch (_: com.ktu.aigaleri.domain.InvalidQueryException) {
            }
        }
        assertEquals(0, closed.get())
    }
}
