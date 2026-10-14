package com.ktu.aigaleri

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * T-027: `tools/prune_text_model.py` (stdlib-only sözlük/gömme budama aracı) birim testleri
 * (`tools/test_prune_text_model.py`, sentetik ONNX, ağ ve gerçek model yok) `python3 -I` ile çalıştırılır.
 * python3 yoksa atlanır. Çalışma dizini app/ (Gradle proje kökünün altında).
 */
class T027PruneToolScriptTest {
    private val toolsDir = File(File(System.getProperty("user.dir")!!).parentFile!!, "tools")

    private val python: File? = run {
        val dirs = (System.getenv("PATH") ?: "").split(File.pathSeparator) + listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
        dirs.map { File(it, "python3") }.firstOrNull { it.canExecute() }
    }

    @Test
    fun pythonUnitTests_ofPruneTool_pass() {
        assumeTrue("python3 yok", python != null)
        val test = File(toolsDir, "test_prune_text_model.py")
        assertTrue(test.isFile)
        val pb = ProcessBuilder(python!!.path, "-I", test.path).redirectErrorStream(true)
        pb.environment().remove("PYTHONPATH")
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("zaman aşımı", p.waitFor(120, TimeUnit.SECONDS))
        assertEquals(out, 0, p.exitValue())
        assertTrue(out, Regex("(?m)^OK$").containsMatchIn(out))
    }

    @Test
    fun pruneTool_sourceHasNoNetworkAndOnlyDeletesOwnTempFiles() {
        val src = File(toolsDir, "prune_text_model.py").readText()
        for (bad in listOf("socket", "urllib", "http", "requests", "subprocess", "shutil.rmtree", "os.system")) {
            assertTrue("$bad kullanılmamalı", !Regex("""^\s*(import|from)\s+$bad\b""", RegexOption.MULTILINE).containsMatchIn(src) && !src.contains("$bad("))
        }
        // Silme yalnızca tek yardımcıdan (_remove_quiet: kendi geçici dosyaları ve çıktı hedefleri) yapılır, başka yerde yok.
        val removes = src.lines().withIndex().filter { it.value.contains("os.remove(") || it.value.contains("os.unlink(") }
        assertEquals(1, removes.size)
        val helperStart = src.lines().indexOfFirst { it.startsWith("def _remove_quiet") }
        assertTrue("os.remove yalnızca _remove_quiet içinde", helperStart >= 0 && removes.single().index in helperStart..helperStart + 8)
    }
}
