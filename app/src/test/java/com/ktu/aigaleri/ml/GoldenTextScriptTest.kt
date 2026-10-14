package com.ktu.aigaleri.ml

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T-026 (QA): `tools/golden_text.py` kapı davranışı (`--help`, model SHA-256/boyut doğrulaması, hata çıkışları).
 * Betik python3 -I ile çalıştırılır; python3 yoksa atlanır. numpy ve onnxruntime bu makinede/CI'da olmayabilir: betik
 * `-c` sarmalayıcısıyla çalıştırılır, sarmalayıcı numpy/onnxruntime için SAHTE modül koyar (betik kodu değişmez, gerçek
 * çıkarım yapılmaz). Betik, model dosyalarını kendi konumuna göre bulduğundan (HERE/..), geçici bir proje ağacı kurulur.
 * Gerçek ağaçta (varsa model dosyalarıyla) betik ONNX sürüm kapısında durur: altın dosyalar ASLA yazılmaz.
 */
class GoldenTextScriptTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val realRoot = File(System.getProperty("user.dir")!!).parentFile!!
    private val realScript = File(realRoot, "tools/golden_text.py")

    private val python: File? = run {
        val dirs = (System.getenv("PATH") ?: "").split(File.pathSeparator) + listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
        dirs.map { File(it, "python3") }.firstOrNull { it.canExecute() }
    }

    private class Result(val exit: Int, val out: String, val err: String)

    /** Sahte numpy (+ isteğe bağlı sahte onnxruntime) koyup betiği `__main__` olarak çalıştırır. */
    private val wrapper = """
        import sys, types, runpy
        np = types.ModuleType('numpy')
        np.fromfile = lambda *a, **k: types.SimpleNamespace(reshape=lambda *a, **k: None)
        sys.modules['numpy'] = np
        ort = types.ModuleType('onnxruntime'); ort.__version__ = '0.0.stub'
        sys.modules['onnxruntime'] = ort
        script = sys.argv[1]
        sys.argv = sys.argv[1:]
        runpy.run_path(script, run_name='__main__')
    """.trimIndent()

    private fun run(script: File, vararg args: String): Result {
        assumeTrue("python3 yok", python != null)
        val pb = ProcessBuilder(listOf(python!!.path, "-I", "-c", wrapper, script.path) + args)
        val outF = tmp.newFile()
        val errF = tmp.newFile()
        pb.redirectOutput(outF).redirectError(errF)
        pb.environment().remove("PYTHONPATH")
        val p = pb.start()
        assertTrue("zaman aşımı", p.waitFor(180, TimeUnit.SECONDS))
        return Result(p.exitValue(), outF.readText(), errF.readText())
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Küçük sahte proje ağacı: betik kopyası + verilen ModelManifest.kt + modeller. */
    private class Tree(val root: File) {
        val script = File(root, "tools/golden_text.py")
        val models = File(root, "app/src/main/assets/models")
        val res = File(root, "app/src/test/resources/ml")
    }

    private fun entry(name: String, file: String, bytes: ByteArray, shaOverride: String? = null, sizeOverride: Long? = null) =
        """
    val $name = ModelFile(
        assetPath = "models/$file",
        fileName = "$file",
        sizeBytes = ${sizeOverride ?: bytes.size}L,
        sha256 = "${shaOverride ?: sha(bytes)}",
    )
"""

    private val files = mapOf(
        "text_model_qint8_arm64.onnx" to "onnx-sahte".toByteArray(),
        "text_dense_768x512_f32.bin" to "dense-sahte".toByteArray(),
        "text_vocab.txt" to "[PAD]\n[UNK]\n".toByteArray(),
    )

    private fun tree(
        manifestEntries: Map<String, String>? = null,
        write: Map<String, ByteArray> = files,
        manifestBytes: Map<String, ByteArray> = files,
        dropEntry: String? = null,
    ): Tree {
        val root = tmp.newFolder()
        val t = Tree(root)
        t.script.parentFile!!.mkdirs()
        realScript.copyTo(t.script)
        val names = mapOf(
            "TEXT_ONNX" to "text_model_qint8_arm64.onnx",
            "TEXT_DENSE" to "text_dense_768x512_f32.bin",
            "TEXT_VOCAB" to "text_vocab.txt",
        )
        val manifest = File(root, "app/src/main/java/com/ktu/aigaleri/ml/ModelManifest.kt")
        manifest.parentFile!!.mkdirs()
        manifest.writeText(
            "package com.ktu.aigaleri.ml\nobject ModelManifest {\n" +
                names.filterKeys { it != dropEntry }.entries.joinToString("") { (k, f) ->
                    manifestEntries?.get(k) ?: entry(k, f, manifestBytes.getValue(f))
                } + "}\n",
        )
        t.models.mkdirs()
        for ((f, b) in write) File(t.models, f).writeBytes(b)
        return t
    }

    private fun noOutputsWritten(t: Tree) = assertFalse("altın dosya yazıldı", File(t.res, "golden_queries.tsv").exists() || File(t.res, "golden_text_vectors.tsv").exists())

    @Test
    fun help_exitsZero_documentsNoiseFlag_andTouchesNothing() {
        val before = File(realRoot, "app/src/test/resources/ml").listFiles()!!.associate { it.name to it.lastModified() }
        val r = run(realScript, "--help")
        assertEquals(r.err, 0, r.exit)
        assertTrue(r.out, r.out.contains("--noise"))
        assertTrue(r.out, r.out.contains("python3 -I tools/golden_text.py"))
        assertFalse("model doğrulama --help'te çalışmamalı", r.out.contains("SHA-256 tamam"))
        assertEquals(before, File(realRoot, "app/src/test/resources/ml").listFiles()!!.associate { it.name to it.lastModified() })
    }

    @Test
    fun unknownFlag_isRejected_withExit2() {
        val r = run(realScript, "--yazmayi-zorla")
        assertEquals(r.out + r.err, 2, r.exit)
        assertTrue(r.err, r.err.contains("unrecognized arguments") || r.err.contains("tanınmayan"))
    }

    @Test
    fun missingModelFile_exits1_namingTheFile_andWritesNothing() {
        val t = tree(write = files - "text_vocab.txt")
        val r = run(t.script)
        assertEquals(1, r.exit)
        assertTrue(r.err, r.err.contains("HATA") && r.err.contains("text_vocab.txt yok"))
        noOutputsWritten(t)
    }

    @Test
    fun wrongSizeModel_isRejected_asNotOriginal() {
        val t = tree(write = files + ("text_dense_768x512_f32.bin" to "kisa".toByteArray()))
        val r = run(t.script)
        assertEquals(1, r.exit)
        assertTrue(r.err, r.err.contains("text_dense_768x512_f32.bin boyutu") && r.err.contains("ORIJINAL model dosyasi degil"))
        assertTrue("önceki dosya doğrulanmış olmalı", r.out.contains("SHA-256 tamam: text_model_qint8_arm64.onnx"))
        noOutputsWritten(t)
    }

    @Test
    fun sameSizeButTamperedContent_failsShaGate_andNeverReportsOk() {
        // Boyut aynı, tek bayt farklı: yalnızca SHA-256 yakalar (budanmış/değişmiş modelle altın üretilmez).
        val original = files.getValue("text_vocab.txt")
        val tampered = original.copyOf().also { it[0] = 'X'.code.toByte() }
        val t = tree(write = files + ("text_vocab.txt" to tampered))
        val r = run(t.script)
        assertEquals(1, r.exit)
        assertTrue(r.err, r.err.contains("text_vocab.txt SHA-256 uyusmuyor") && r.err.contains("ORIJINAL model dosyasi degil"))
        assertFalse(r.out.contains("SHA-256 tamam: text_vocab.txt"))
        assertEquals(2, Regex("SHA-256 tamam").findAll(r.out).count())
        noOutputsWritten(t)
    }

    @Test
    fun manifestWithWrongSha_failsForOnnxFirst() {
        val bad = entry("TEXT_ONNX", "text_model_qint8_arm64.onnx", files.getValue("text_model_qint8_arm64.onnx"), shaOverride = "0".repeat(64))
        val t = tree(manifestEntries = mapOf("TEXT_ONNX" to bad))
        val r = run(t.script)
        assertEquals(1, r.exit)
        assertTrue(r.err, r.err.contains("text_model_qint8_arm64.onnx SHA-256 uyusmuyor"))
        assertFalse(r.out.contains("SHA-256 tamam"))
    }

    @Test
    fun matchingFiles_passShaGate_thenStopAtVocabLineCheck_withoutWriting() {
        val t = tree()
        val r = run(t.script)
        assertEquals(1, r.exit)
        assertEquals(r.out, 3, Regex("SHA-256 tamam").findAll(r.out).count())
        assertTrue(r.err, r.err.contains("sozluk satir sayisi 2, beklenen 119547"))
        noOutputsWritten(t)
    }

    @Test
    fun manifestMissingEntry_orUnparsableFields_exitCleanly() {
        val missing = tree(dropEntry = "TEXT_DENSE")
        val r1 = run(missing.script)
        assertEquals(1, r1.exit)
        assertTrue(r1.err, r1.err.contains("TEXT_DENSE bulunamadi"))
        val broken = tree(manifestEntries = mapOf("TEXT_VOCAB" to "\n    val TEXT_VOCAB = ModelFile(\n        fileName = \"text_vocab.txt\",\n    )\n"))
        val r2 = run(broken.script)
        assertEquals(1, r2.exit)
        assertTrue(r2.err, r2.err.contains("TEXT_VOCAB alanlari ayristirilamadi"))
        assertFalse("traceback yok", r1.err.contains("Traceback") || r2.err.contains("Traceback"))
    }

    @Test
    fun realTree_withOriginalModels_passesShaTokenizerAndQueryGates_butStopsBeforeAnyWrite() {
        val modelsDir = File(realRoot, "app/src/main/assets/models")
        assumeTrue("model dosyaları yok", File(modelsDir, "text_model_qint8_arm64.onnx").isFile && File(modelsDir, "text_vocab.txt").isFile && File(modelsDir, "text_dense_768x512_f32.bin").isFile)
        // Budanmış (T-027 sonrası) sözlük/model ile bu kapı düşer: altın yenileme yalnızca ORİJİNAL dosyalarla mümkündür.
        val resDir = File(realRoot, "app/src/test/resources/ml")
        val snapshot = listOf("golden_queries.tsv", "golden_text_vectors.tsv").associateWith { File(resDir, it).readBytes() }
        val r = run(realScript)
        assertEquals(r.out + r.err, 1, r.exit)
        assertEquals(r.out, 3, Regex("SHA-256 tamam").findAll(r.out).count())
        val refLines = File(resDir, "tokenizer_reference.tsv").readText().split('\n').count { it.isNotEmpty() }
        assertTrue(r.out, r.out.contains("tokenizer_reference.tsv: $refLines satir, 0 ayrisma"))
        assertTrue(r.out, r.out.contains("[UNK]: ${GoldenText.BASELINE_UNK_COUNT} / ${GoldenText.BASELINE_BODY_TOKENS} icerik tokeni"))
        assertTrue(r.err, r.err.contains("onnxruntime==1.30.0 gerekli"))
        for ((n, b) in snapshot) assertTrue("$n değişti", b.contentEquals(File(resDir, n).readBytes()))
        assertFalse(File(resDir, "golden_queries.tsv.tmp").exists())
    }
}
