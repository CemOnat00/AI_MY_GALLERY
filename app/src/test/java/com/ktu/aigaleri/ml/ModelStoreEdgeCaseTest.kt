package com.ktu.aigaleri.ml

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
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
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** T-007 QA: ModelStore sınır durumları (sahte assets, geçici klasör). */
class ModelStoreEdgeCaseTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun model(bytes: ByteArray, name: String = "m.bin") =
        ModelFile("models/$name", name, bytes.size.toLong(), sha(bytes))

    private class Assets(val files: Map<String, ByteArray>) : AssetOpener {
        val opens = AtomicInteger()
        override fun open(assetPath: String): InputStream {
            opens.incrementAndGet()
            return ByteArrayInputStream(files[assetPath] ?: throw FileNotFoundException(assetPath))
        }
    }

    private fun bytes(n: Int, seed: Int = 1) = ByteArray(n) { ((it * 31 + seed) xor (it ushr 8)).toByte() }

    private fun modelsDir() = File(tmp.root, "models")

    @Test
    fun sizesAroundBufferBoundaries_copyExactly() {
        for (n in listOf(1, 65_535, 65_536, 65_537, 131_072, 2 * 65_536 + 1)) {
            val data = bytes(n)
            val root = tmp.newFolder("r$n")
            val f = ModelStore(root, Assets(mapOf("models/m.bin" to data))).ensure(model(data))
            assertArrayEquals("n=$n", data, f.readBytes())
        }
    }

    @Test
    fun largeModel_20MB_copiesAndVerifies() {
        val data = bytes(20 * 1024 * 1024, seed = 5)
        val f = ModelStore(tmp.root, Assets(mapOf("models/m.bin" to data))).ensure(model(data))
        assertEquals(data.size.toLong(), f.length())
        assertEquals(sha(data), sha(f.readBytes()))
    }

    @Test
    fun emptyAsset_isIntegrityFailure_andLeavesNothing() {
        val m = ModelFile("models/m.bin", "m.bin", 10, "0".repeat(64))
        try {
            ModelStore(tmp.root, Assets(mapOf("models/m.bin" to ByteArray(0)))).ensure(m)
            fail()
        } catch (e: ModelException.IntegrityFailure) {
            assertTrue(e.message!!.contains("m.bin"))
        }
        assertEquals(emptyList<String>(), modelsDir().list()!!.toList())
    }

    @Test
    fun correctSizeWrongHash_isIntegrityFailure_andRetryAfterFixSucceeds() {
        val good = bytes(100_000)
        val bad = good.copyOf().also { it[99_999] = (it[99_999] + 1).toByte() }
        val m = model(good)
        try {
            ModelStore(tmp.root, Assets(mapOf("models/m.bin" to bad))).ensure(m)
            fail()
        } catch (_: ModelException.IntegrityFailure) {
        }
        assertFalse(File(modelsDir(), "m.bin").exists())
        assertFalse(File(modelsDir(), "m.bin.ok").exists())
        assertArrayEquals(good, ModelStore(tmp.root, Assets(mapOf("models/m.bin" to good))).ensure(m).readBytes())
    }

    @Test
    fun existingEmptyTarget_isReplaced() {
        val data = bytes(70_000)
        modelsDir().mkdirs()
        File(modelsDir(), "m.bin").writeBytes(ByteArray(0))
        val f = ModelStore(tmp.root, Assets(mapOf("models/m.bin" to data))).ensure(model(data))
        assertArrayEquals(data, f.readBytes())
    }

    @Test
    fun garbageOrMismatchedMarker_isIgnoredAndTargetRevalidated() {
        val data = bytes(70_000)
        val m = model(data)
        modelsDir().mkdirs()
        File(modelsDir(), "m.bin").writeBytes(data)
        File(modelsDir(), "m.bin.ok").writeText("çöp \u0000 içerik")
        val assets = Assets(emptyMap())
        ModelStore(tmp.root, assets).ensure(m)
        assertEquals(0, assets.opens.get()) // dosya geçerli: yeniden hash'lenip kabul edildi
        assertEquals("${m.sizeBytes}:${m.sha256}", File(modelsDir(), "m.bin.ok").readText())
    }

    @Test
    fun markerWithoutTarget_isDiscarded_andModelIsCopied() {
        val data = bytes(70_000)
        val m = model(data)
        modelsDir().mkdirs()
        File(modelsDir(), "m.bin.ok").writeText("${m.sizeBytes}:${m.sha256}")
        val assets = Assets(mapOf("models/m.bin" to data))
        val f = ModelStore(tmp.root, assets).ensure(m)
        assertEquals(1, assets.opens.get())
        assertArrayEquals(data, f.readBytes())
    }

    @Test
    fun targetTruncatedAfterSuccessfulCopy_isRecopied() {
        val data = bytes(200_000)
        val m = model(data)
        val assets = Assets(mapOf("models/m.bin" to data))
        val s = ModelStore(tmp.root, assets)
        val f = s.ensure(m)
        f.writeBytes(data.copyOf(1000)) // bozulma: işaretçi var ama boyut tutmuyor
        assertArrayEquals(data, s.ensure(m).readBytes())
        assertEquals(2, assets.opens.get())
    }

    @Test
    fun missingAsset_thenAssetAppears_recovers() {
        val data = bytes(80_000)
        val m = model(data)
        try {
            ModelStore(tmp.root, Assets(emptyMap())).ensure(m)
            fail()
        } catch (_: ModelException.Missing) {
        }
        assertEquals(emptyList<String>(), modelsDir().list()!!.toList())
        assertArrayEquals(data, ModelStore(tmp.root, Assets(mapOf("models/m.bin" to data))).ensure(m).readBytes())
    }

    @Test
    fun modelsPathBlockedByRegularFile_isIoNotCrash() {
        File(tmp.root, "models").writeText("ben bir dosyayım")
        try {
            ModelStore(tmp.root, Assets(emptyMap())).ensure(model(bytes(10)))
            fail()
        } catch (_: ModelException.Io) {
        }
    }

    @Test
    fun targetPathIsDirectory_isModelException_andPartCleaned() {
        val data = bytes(70_000)
        File(modelsDir(), "m.bin").mkdirs()
        File(modelsDir(), "m.bin/içerik").writeText("x")
        try {
            ModelStore(tmp.root, Assets(mapOf("models/m.bin" to data))).ensure(model(data))
            fail()
        } catch (_: ModelException) {
        }
        assertFalse(File(modelsDir(), "m.bin.part").exists())
    }

    /** Hedef klasör yazılamazsa (FileOutputStream FileNotFoundException) Io döner; asset yokluğu Missing kalır. */
    @Test
    fun unwritableModelsDir_shouldBeIo_notMissing() {
        val data = bytes(70_000)
        modelsDir().mkdirs()
        assumeTrue(modelsDir().setWritable(false))
        try {
            ModelStore(tmp.root, Assets(mapOf("models/m.bin" to data))).ensure(model(data))
            fail()
        } catch (e: ModelException) {
            assertTrue("Io bekleniyordu: $e", e is ModelException.Io)
        } finally {
            modelsDir().setWritable(true)
        }
    }

    @Test
    fun cancellation_onFirstCheck_leavesNoFiles_andCancellationExceptionPropagates() {
        val data = bytes(200_000)
        try {
            ModelStore(tmp.root, Assets(mapOf("models/m.bin" to data))).ensure(model(data)) {
                throw kotlinx.coroutines.CancellationException("iptal")
            }
            fail()
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
        assertEquals(emptyList<String>(), modelsDir().list()!!.toList())
    }

    @Test
    fun cancellation_justBeforeEnd_neverPublishesTarget() {
        val data = bytes(65_536 * 3)
        var calls = 0
        try {
            ModelStore(tmp.root, Assets(mapOf("models/m.bin" to data))).ensure(model(data)) {
                // 3 blok + EOF kontrolü = 4. çağrı: veri tamamen yazıldı ama henüz yayımlanmadı.
                if (++calls == 4) throw kotlinx.coroutines.CancellationException("geç iptal")
            }
            fail()
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
        assertEquals(emptyList<String>(), modelsDir().list()!!.toList())
    }

    @Test
    fun concurrentCall_waitsForCancelledFirst_thenSucceeds() {
        val data = bytes(300_000)
        val m = model(data)
        val assets = Assets(mapOf("models/m.bin" to data))
        val store = ModelStore(tmp.root, assets)
        val firstInside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val first = pool.submit<Throwable?> {
            try {
                store.ensure(m) {
                    firstInside.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    throw kotlinx.coroutines.CancellationException("iptal")
                }
                null
            } catch (e: Throwable) {
                e
            }
        }
        assertTrue(firstInside.await(10, TimeUnit.SECONDS))
        val second = pool.submit<File> { store.ensure(m) }
        Thread.sleep(100) // ikincinin kilitte beklemesi için
        assertFalse(second.isDone)
        release.countDown()
        assertTrue(first.get(10, TimeUnit.SECONDS) is kotlinx.coroutines.CancellationException)
        assertArrayEquals(data, second.get(30, TimeUnit.SECONDS).readBytes())
        pool.shutdown()
        assertEquals(2, assets.opens.get())
    }

    @Test
    fun concurrentDifferentModels_bothCopiedOnce_noCrossContamination() {
        val a = bytes(150_000, 1)
        val b = bytes(160_000, 2)
        val assets = Assets(mapOf("models/a.bin" to a, "models/b.bin" to b))
        val store = ModelStore(tmp.root, assets)
        val pool = Executors.newFixedThreadPool(6)
        val fs = (0 until 6).map { i ->
            pool.submit<Pair<Int, ByteArray>> {
                if (i % 2 == 0) 0 to store.ensure(model(a, "a.bin")).readBytes() else 1 to store.ensure(model(b, "b.bin")).readBytes()
            }
        }
        for (f in fs) {
            val (k, got) = f.get(30, TimeUnit.SECONDS)
            assertArrayEquals(if (k == 0) a else b, got)
        }
        pool.shutdown()
        assertEquals(2, assets.opens.get())
        assertEquals(setOf("a.bin", "a.bin.ok", "b.bin", "b.bin.ok"), modelsDir().list()!!.toSet())
    }

    @Test
    fun modelFile_validation() {
        for (bad in listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64))) {
            try {
                ModelFile("a", "a", 1, bad)
                fail("sha='$bad' reddedilmeliydi")
            } catch (_: IllegalArgumentException) {
            }
        }
        for (size in listOf(0L, -1L)) {
            try {
                ModelFile("a", "a", size, "a".repeat(64))
                fail()
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
