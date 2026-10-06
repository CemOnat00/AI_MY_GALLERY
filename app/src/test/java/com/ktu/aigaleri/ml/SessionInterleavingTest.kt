package com.ktu.aigaleri.ml

import com.ktu.aigaleri.data.FakeEmbeddingDao
import com.ktu.aigaleri.data.RoomSearchRepository
import com.ktu.aigaleri.data.TEST_DIM
import com.ktu.aigaleri.data.TEST_SPEC
import com.ktu.aigaleri.data.e0
import com.ktu.aigaleri.data.row
import com.ktu.aigaleri.domain.EmbeddingSpec
import com.ktu.aigaleri.domain.TextEncoder
import com.ktu.aigaleri.ui.search.SearchWarmUp
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * T-008: indeksleyici (görüntü oturumu) ile arama/ısınma (metin oturumu) AYNI [SingleSessionSlot]'u eşzamanlı ister.
 * Sahte oturumlarla (ORT yok) tek oturum kuralı, sıralama (serileşme), iptal ve eviction sonrası yeniden açma sınanır.
 */
class SessionInterleavingTest {
    private class Tracker {
        val openNow = AtomicInteger()
        val maxOpen = AtomicInteger()
        val inBlock = AtomicInteger()
        val maxInBlock = AtomicInteger()
        val opens = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

        fun opened(key: String) {
            opens.getOrPut(key) { AtomicInteger() }.incrementAndGet()
            maxOpen.accumulateAndGet(openNow.incrementAndGet(), ::maxOf)
        }
        fun closed() { openNow.decrementAndGet() }
        fun enterBlock() { maxInBlock.accumulateAndGet(inBlock.incrementAndGet(), ::maxOf) }
        fun exitBlock() { inBlock.decrementAndGet() }
        fun opensOf(key: String) = opens[key]?.get() ?: 0
    }

    private class FakeSession(val tracker: Tracker) : AutoCloseable {
        override fun close() = tracker.closed()
    }

    /** Görüntü kodlayıcı taklidi: "vision" anahtarı, çıkarım süresi kilidi tutar. */
    private class SlotImageWork(val slot: SingleSessionSlot, val t: Tracker, val workMs: Long = 2) {
        suspend fun encode() = slot.withSession("vision", open = { t.opened("vision"); FakeSession(t) }) {
            t.enterBlock(); try { Thread.sleep(workMs) } finally { t.exitBlock() }
        }
    }

    private class SlotTextEncoder(val slot: SingleSessionSlot, val t: Tracker, val workMs: Long = 1) : TextEncoder {
        override val embeddingSpec: EmbeddingSpec = TEST_SPEC
        override suspend fun encode(query: String): FloatArray =
            slot.withSession("text", open = { t.opened("text"); FakeSession(t) }) {
                t.enterBlock(); try { Thread.sleep(workMs) } finally { t.exitBlock() }
                e0()
            }
    }

    @Test
    fun concurrentIndexingSearchesAndWarmUp_neverHaveTwoSessionsOrOverlappingWork() = runBlocking {
        val slot = SingleSessionSlot()
        val t = Tracker()
        val image = SlotImageWork(slot, t)
        val text = SlotTextEncoder(slot, t)
        val repo = RoomSearchRepository(text, FakeEmbeddingDao(listOf(row(1, e0()), row(2, e0()))), dispatcher = Dispatchers.Default)
        val warm = SearchWarmUp({ true }, { false }, { text.encode("w") })

        val indexer = async(Dispatchers.Default) { repeat(60) { image.encode() } }
        val searches = (1..20).map { async(Dispatchers.Default) { repo.search("x") } }
        val warms = (1..5).map { async(Dispatchers.Default) { warm.run(); warm.onSessionReleased() } }
        indexer.await()
        searches.awaitAll().forEach { assertEquals(listOf(1L, 2L), it.map { r -> r.photoId }) }
        warms.awaitAll()

        assertEquals(1, t.maxOpen.get())      // tek oturum kuralı
        assertEquals(1, t.maxInBlock.get())   // çıkarımlar serileşti
        assertTrue("görüntü oturumu yeniden açılmalı", t.opensOf("vision") >= 1)
        assertTrue("metin oturumu açılmalı", t.opensOf("text") >= 1)
        slot.closeAll()
        assertEquals(0, t.openNow.get())
    }

    @Test
    fun searchDuringIndexing_evictsVision_andIndexerReopensIt() = runBlocking {
        val slot = SingleSessionSlot()
        val t = Tracker()
        val image = SlotImageWork(slot, t)
        val text = SlotTextEncoder(slot, t)
        image.encode()
        assertEquals(1, t.opensOf("vision"))
        text.encode("x") // arama: görüntü kapanır, metin açılır
        assertEquals(1, t.openNow.get())
        assertEquals(1, t.opensOf("text"))
        text.encode("x") // ardışık arama: yeniden açma yok
        assertEquals(1, t.opensOf("text"))
        image.encode() // indeksleyici metni kapatıp görüntüyü yeniden açar
        assertEquals(2, t.opensOf("vision"))
        assertEquals(1, t.openNow.get())
        text.encode("x") // ve metin yeniden açılır
        assertEquals(2, t.opensOf("text"))
        assertEquals(1, t.maxOpen.get())
    }

    @Test
    fun cancelledSearchWaitingForSlot_neverOpensTextSession_andSlotStaysUsable() = runBlocking {
        val slot = SingleSessionSlot()
        val t = Tracker()
        val started = CompletableDeferred<Unit>()
        // Uzun görüntü çıkarımı kilidi tutar
        val longImage = launch(Dispatchers.Default) {
            slot.withSession("vision", open = { t.opened("vision"); FakeSession(t) }) {
                started.complete(Unit)
                Thread.sleep(300)
            }
        }
        started.await()
        val repo = RoomSearchRepository(SlotTextEncoder(slot, t), FakeEmbeddingDao(listOf(row(1, e0()))), dispatcher = Dispatchers.Default)
        val search = async(Dispatchers.Default) { repo.search("x") }
        delay(50)
        search.cancel()
        try {
            search.await()
            fail()
        } catch (_: CancellationException) {
        }
        longImage.join()
        assertEquals(0, t.opensOf("text")) // iptal edilen arama oturumu açmadı
        assertEquals(1, t.openNow.get())   // görüntü oturumu sağlam
        // yuva hâlâ kullanılabilir
        assertEquals(1, repo.search("x").size)
        assertEquals(1, t.opensOf("text"))
        assertEquals(1, t.maxOpen.get())
    }

    @Test
    fun warmUpDeferredWhileIndexing_doesNotEvictVisionSession() = runBlocking {
        val slot = SingleSessionSlot()
        val t = Tracker()
        val image = SlotImageWork(slot, t)
        val text = SlotTextEncoder(slot, t)
        var indexing = true
        val warm = SearchWarmUp({ true }, { indexing }, { text.encode("w") })
        image.encode()
        assertEquals(false, warm.run())          // ertelendi
        assertEquals(0, t.opensOf("text"))
        image.encode()
        assertEquals(1, t.opensOf("vision"))     // görüntü oturumu kapanmadı, yeniden açılmadı
        indexing = false
        assertEquals(true, warm.run())
        assertEquals(1, t.opensOf("text"))
        assertEquals(1, t.maxOpen.get())
    }

    @Test
    fun trimRelease_closesOnlyTextSession_visionUntouched() = runBlocking {
        val slot = SingleSessionSlot()
        val t = Tracker()
        val text = SlotTextEncoder(slot, t)
        text.encode("x")
        var dropped = 0
        slot.close("text", afterClose = { dropped++ }) // OnnxTextEncoder.release ile aynı çağrı
        assertEquals(0, t.openNow.get())
        assertEquals(1, dropped)
        slot.withSession("vision", open = { t.opened("vision"); FakeSession(t) }) { }
        slot.close("text") // başka anahtar: görüntü oturumu açık kalır
        assertEquals(1, t.openNow.get())
        text.encode("x") // yeniden açma
        assertEquals(2, t.opensOf("text"))
        assertEquals(TEST_DIM, e0().size)
    }
}
