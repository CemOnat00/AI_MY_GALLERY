package com.ktu.aigaleri

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * T-027 QA: `tools/test_t027_qa.py` (bağımsız protobuf okuyucu, bozuk/kesik ONNX, tek bayt, yanlış SHA, yazma izni yok,
 * idempotans, fetch_models.sh ağsız) `python3 -I` ile çalıştırılır. python3 yoksa atlanır. Gerçek orijinal model
 * (`.model-cache/`) yoksa betik içindeki gerçek-dosya testleri atlanır ("OK (skipped=N)"); varsa ATLANMAMALI.
 * Çalışma dizini app/ (Gradle proje kökünün altında).
 */
class T027QaPruneToolScriptTest {
    private val projectDir = File(System.getProperty("user.dir")!!).parentFile!!

    private val python: File? = run {
        val dirs = (System.getenv("PATH") ?: "").split(File.pathSeparator) + listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
        dirs.map { File(it, "python3") }.firstOrNull { it.canExecute() }
    }

    @Test
    fun pythonQaTests_pass_andRealModelTestsAreNotSkippedWhenCacheExists() {
        assumeTrue("python3 yok", python != null)
        val script = File(projectDir, "tools/test_t027_qa.py")
        assertTrue(script.isFile)
        val pb = ProcessBuilder(python!!.path, "-I", script.path).redirectErrorStream(true)
        pb.environment().keys.removeAll { it.startsWith("PYTHON") }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("zaman aşımı", p.waitFor(280, TimeUnit.SECONDS))
        assertEquals(out, 0, p.exitValue())
        assertTrue(out, Regex("(?m)^OK").containsMatchIn(out))
        val cache = File(projectDir, ".model-cache")
        if (File(cache, "text_model_qint8_arm64.onnx").isFile && File(cache, "text_vocab.txt").isFile) {
            // Gerçek orijinal model varsa gerçek-dosya testleri çalışmış olmalı (yalnızca assets dense .bin / izin / shipped-vocab atlamaları olabilir).
            val skipped = Regex("skipped=([0-9]+)").find(out)?.groupValues?.get(1)?.toInt() ?: 0
            assertTrue("gerçek model var ama çok test atlandı ($skipped): $out", skipped <= 4)
        }
    }
}
