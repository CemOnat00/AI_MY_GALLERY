package com.ktu.aigaleri.ml

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Sahte assets ve geçici klasörle; gerçek model dosyası gerekmez. */
class ModelStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val payload = ByteArray(300_000) { (it * 7 + 3).toByte() }

    private fun sha(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun model(bytes: ByteArray = payload) = ModelFile("models/m.bin", "m.bin", bytes.size.toLong(), sha(bytes))

    private class CountingAssets(private val files: Map<String, ByteArray>) : AssetOpener {
        val opens = AtomicInteger()
        override fun open(assetPath: String): InputStream {
            opens.incrementAndGet()
            return ByteArrayInputStream(files[assetPath] ?: throw FileNotFoundException(assetPath))
        }
    }

    private fun store(assets: AssetOpener, root: File = tmp.root) = ModelStore(root, assets)

    private fun modelsDir() = File(tmp.root, "models")

    @Test
    fun ensure_copiesVerifiesAndLeavesNoTempFiles() {
        val assets = CountingAssets(mapOf("models/m.bin" to payload))
        val f = store(assets).ensure(model())
        assertArrayEquals(payload, f.readBytes())
        assertEquals(setOf("m.bin", "m.bin.ok"), modelsDir().list()!!.toSet())
    }

    @Test
    fun ensure_secondCallDoesNotReadAssetsAgain() {
        val assets = CountingAssets(mapOf("models/m.bin" to payload))
        val s = store(assets)
        s.ensure(model())
        s.ensure(model())
        assertEquals(1, assets.opens.get())
        // Yeni ModelStore örneği (süreç yeniden başlaması) de işaretçiyi kullanır.
        store(assets).ensure(model())
        assertEquals(1, assets.opens.get())
    }

    @Test
    fun shaMismatch_isRejected_andNothingIsLeftBehind() {
        val tampered = payload.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        val assets = CountingAssets(mapOf("models/m.bin" to tampered))
        try {
            store(assets).ensure(model())
            fail()
        } catch (e: ModelException.IntegrityFailure) {
            assertTrue(e.message!!.contains("m.bin"))
        }
        assertEquals(emptyList<String>(), modelsDir().list()!!.toList())
    }

    @Test
    fun truncatedAsset_halfCopy_isRejected() {
        val half = payload.copyOf(payload.size / 2)
        val assets = CountingAssets(mapOf("models/m.bin" to half))
        try {
            store(assets).ensure(model())
            fail()
        } catch (_: ModelException.IntegrityFailure) {
        }
        assertEquals(emptyList<String>(), modelsDir().list()!!.toList())
    }

    @Test
    fun oversizedAsset_isRejected() {
        val big = payload + byteArrayOf(1)
        try {
            store(CountingAssets(mapOf("models/m.bin" to big))).ensure(model())
            fail()
        } catch (_: ModelException.IntegrityFailure) {
        }
        assertFalse(File(modelsDir(), "m.bin").exists())
    }

    @Test
    fun ioErrorMidCopy_leavesNoPartialTarget() {
        val failing = AssetOpener {
            object : InputStream() {
                var n = 0
                override fun read(): Int = throw IOException("disk")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (n >= 100_000) throw IOException("yarıda kesildi")
                    val c = minOf(len, 50_000)
                    n += c
                    return c
                }
            }
        }
        try {
            store(failing).ensure(model())
            fail()
        } catch (_: ModelException.Io) {
        }
        assertEquals(emptyList<String>(), modelsDir().list()!!.toList())
    }

    @Test
    fun staleStalePartFile_isRemovedAndCopyProceeds() {
        modelsDir().mkdirs()
        File(modelsDir(), "m.bin.part").writeBytes(byteArrayOf(1, 2, 3))
        val f = store(CountingAssets(mapOf("models/m.bin" to payload))).ensure(model())
        assertArrayEquals(payload, f.readBytes())
        assertFalse(File(modelsDir(), "m.bin.part").exists())
    }

    @Test
    fun existingTargetWithWrongSize_isReplaced() {
        modelsDir().mkdirs()
        File(modelsDir(), "m.bin").writeBytes(payload.copyOf(1234))
        val assets = CountingAssets(mapOf("models/m.bin" to payload))
        val f = store(assets).ensure(model())
        assertArrayEquals(payload, f.readBytes())
        assertEquals(1, assets.opens.get())
    }

    @Test
    fun existingTargetWithoutMarker_isRehashed_corruptOneIsReplaced() {
        modelsDir().mkdirs()
        val corrupt = payload.copyOf().also { it[5] = (it[5] + 1).toByte() } // aynı boyut, yanlış içerik
        File(modelsDir(), "m.bin").writeBytes(corrupt)
        val assets = CountingAssets(mapOf("models/m.bin" to payload))
        val f = store(assets).ensure(model())
        assertArrayEquals(payload, f.readBytes())
        assertEquals(1, assets.opens.get())
    }

    @Test
    fun existingValidTargetWithoutMarker_isAcceptedWithoutCopy_andMarked() {
        modelsDir().mkdirs()
        File(modelsDir(), "m.bin").writeBytes(payload)
        val assets = CountingAssets(emptyMap()) // assets'e gidilirse FileNotFound
        store(assets).ensure(model())
        assertEquals(0, assets.opens.get())
        assertTrue(File(modelsDir(), "m.bin.ok").exists())
    }

    @Test
    fun markerForDifferentSha_triggersVerification() {
        val s = store(CountingAssets(mapOf("models/m.bin" to payload)))
        s.ensure(model())
        // Yeni sürüm: aynı dosya adı, farklı içerik/SHA -> eski işaretçi geçersiz, yeniden kopyalanır.
        val v2 = ByteArray(payload.size) { (it * 11).toByte() }
        val assets2 = CountingAssets(mapOf("models/m.bin" to v2))
        val f = store(assets2).ensure(model(v2))
        assertArrayEquals(v2, f.readBytes())
        assertEquals(1, assets2.opens.get())
    }

    @Test
    fun missingAsset_reportsMissing() {
        try {
            store(CountingAssets(emptyMap())).ensure(model())
            fail()
        } catch (e: ModelException.Missing) {
            assertEquals("models/m.bin", e.assetPath)
        }
        assertFalse(File(modelsDir(), "m.bin").exists())
    }

    @Test
    fun insufficientStorage_isReportedBeforeCopying() {
        val huge = ModelFile("models/m.bin", "m.bin", Long.MAX_VALUE / 2, "0".repeat(64))
        val assets = CountingAssets(mapOf("models/m.bin" to payload))
        try {
            store(assets).ensure(huge)
            fail()
        } catch (_: ModelException.InsufficientStorage) {
        }
        assertEquals(0, assets.opens.get())
    }

    @Test
    fun concurrentCalls_copyOnlyOnce() {
        val assets = CountingAssets(mapOf("models/m.bin" to payload))
        val s = store(assets)
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val futures = (1..4).map {
            pool.submit<File> {
                start.await()
                s.ensure(model())
            }
        }
        start.countDown()
        futures.forEach { assertArrayEquals(payload, it.get(30, TimeUnit.SECONDS).readBytes()) }
        pool.shutdown()
        assertEquals(1, assets.opens.get())
    }

    @Test
    fun modelFile_rejectsMalformedSha() {
        try {
            ModelFile("a", "a", 1, "XYZ")
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun manifest_hasExpectedSpecAndResearchHashes() {
        assertEquals(512, ModelManifest.EMBEDDING_SPEC.dimension)
        assertEquals(135_336_307L, ModelManifest.TEXT_ONNX.sizeBytes)
        assertEquals("3bed77e83926519660b5c0833cb3321cfde952331edec4f7d501c7d384b8a8a7", ModelManifest.TEXT_ONNX.sha256)
        assertEquals("fe0fda7c425b48c516fc8f160d594c8022a0808447475c1a7c6d6479763f310c", ModelManifest.TEXT_VOCAB.sha256)
        assertEquals(512L * 768 * 4, ModelManifest.TEXT_DENSE.sizeBytes)
        assertEquals("583fd1110a514667812fee7d684952aaf82a99b959760c8d7dca7e0ab9839299", ModelManifest.VISION_ONNX.sha256)
    }
}
