package com.ktu.aigaleri.ml

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T-007 QA: geliştirme makinesindeki gerçek model dosyalarıyla (src/main/assets/models/, git'te yok) doğrulama.
 * Dosyalar yoksa Assume ile ATLANIR (raporda "skipped" görünür; tools/fetch_models.sh ile indirilir).
 * ORT yerel kütüphanesi Android'e özgüdür; gerçek çıkarım yalnızca androidTest'te (OnnxTextEncoderDeviceTest) yapılır.
 */
class RealModelAssetsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val assetsDir = File("src/main/assets")

    private fun fileOpener() = AssetOpener { path ->
        val f = File(assetsDir, path)
        if (!f.isFile) throw FileNotFoundException(path)
        f.inputStream()
    }

    private fun requireFile(m: ModelFile) =
        assumeTrue("${m.fileName} yok (tools/fetch_models.sh)", File(assetsDir, m.assetPath).isFile)

    @Test
    fun smallModelFiles_passModelStoreIntegrityCheck_andSecondEnsureSkipsCopy() {
        for (m in listOf(ModelManifest.TEXT_VOCAB, ModelManifest.TEXT_DENSE)) {
            requireFile(m)
            val store = ModelStore(tmp.root, fileOpener())
            val f = store.ensure(m)
            assertEquals(m.sizeBytes, f.length())
            assertArrayEquals(File(assetsDir, m.assetPath).readBytes(), f.readBytes())
            // İkinci çağrı: asset yok bile olsa işaretçi sayesinde geçerli.
            assertEquals(f, ModelStore(tmp.root, AssetOpener { throw FileNotFoundException(it) }).ensure(m))
        }
    }

    @Test
    fun textOnnx_matchesManifestSizeAndSha256() {
        requireFile(ModelManifest.TEXT_ONNX)
        val f = ModelStore(tmp.root, fileOpener()).ensure(ModelManifest.TEXT_ONNX)
        assertEquals(135_336_307L, f.length())
        assertFalse(File(tmp.root, "models/text_model_qint8_arm64.onnx.part").exists())
    }

    @Test
    fun visionOnnx_matchesManifest() {
        requireFile(ModelManifest.VISION_ONNX)
        val f = ModelStore(tmp.root, fileOpener()).ensure(ModelManifest.VISION_ONNX)
        assertEquals(89_117_001L, f.length())
    }

    @Test
    fun realVocab_structure_andTurkishQueries() {
        val vocabFile = File(assetsDir, ModelManifest.TEXT_VOCAB.assetPath)
        assumeTrue("text_vocab.txt yok (tools/fetch_models.sh)", vocabFile.isFile)
        val lines = vocabFile.readLines()
        assertEquals(119_547, lines.size)
        val tok = vocabFile.inputStream().use { WordPieceTokenizer.fromVocabStream(it) }
        val idOf = lines.withIndex().associate { it.value to it.index }
        val unk = idOf.getValue("[UNK]")
        val cls = idOf.getValue("[CLS]")
        val sep = idOf.getValue("[SEP]")
        assertEquals(listOf(100, 101, 102), listOf(unk, cls, sep))

        assertArrayEquals(intArrayOf(cls, sep), tok.encode(""))

        // Türkçe sorgular: küçük harfe çevrilmiş metinde yaygın sözcükler [UNK] olmamalı.
        for (q in listOf("plajda gülen çocuk", "ışık altında kedi", "İstanbul'da gün batımı", "ÇOCUK ve KÖPEK", "ığdır ısparta")) {
            val ids = tok.encode(QueryPreprocessor.normalize(q))
            assertEquals(cls, ids.first())
            assertEquals(sep, ids.last())
            assertTrue("$q: ${ids.toList()}", ids.none { it == unk })
            assertTrue(ids.all { it in 0 until lines.size })
        }
        // Büyük/küçük İ-I: önişlem olmadan ve olmadan farklı kimlikler (cased sözlük) ama ikisi de geçerli aralıkta.
        assertTrue(!tok.encode("kedi").contentEquals(tok.encode("Kedi")))
        // Sınırlar: 128'e kesme ve tüm-[UNK] sorgu.
        assertEquals(128, tok.encode("kedi ".repeat(500)).size)
        assertEquals(128, tok.encode("🐱 ".repeat(500)).size)
        assertTrue(tok.encode("🐱").contentEquals(intArrayOf(cls, unk, sep)))
        assertArrayEquals(intArrayOf(cls, unk, sep), tok.encode("a".repeat(150)))
    }
}
