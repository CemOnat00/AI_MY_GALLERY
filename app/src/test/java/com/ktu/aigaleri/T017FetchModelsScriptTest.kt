package com.ktu.aigaleri

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * T-017 (QA): mobile/tools/fetch_models.sh sertleştirmesi. Gerçek denetim sahte curl/python3/sha256sum kullanan
 * mobile/tools/test_fetch_models.sh betiğindedir (ağ yok); burada o betik çalıştırılır. bash yoksa (Windows) atlanır.
 * Ayrıca betik kaynağında ağ sertleştirme bayrakları statik olarak doğrulanır. Çalışma dizini mobile/app.
 */
class T017FetchModelsScriptTest {
    private val toolsDir = File(File(System.getProperty("user.dir")!!).parentFile!!, "tools")
    private val script = File(toolsDir, "fetch_models.sh").readText()

    @Test
    fun curlCalls_restrictedToHttps() {
        val curlLines = script.lines().filter { Regex("^\\s*curl\\s").containsMatchIn(it) }
        assertTrue("curl çağrısı yok", curlLines.isNotEmpty())
        for (l in curlLines) {
            assertTrue(l, l.contains("--proto '=https'"))
            assertTrue(l, l.contains("--proto-redir '=https'"))
        }
        assertFalse("düz http:// URL", Regex("http://").containsMatchIn(script.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")))
    }

    @Test
    fun cleanup_isLimitedToOwnFiles_noWildcard() {
        val cleanup = script.lines().single { it.startsWith("cleanup()") }
        assertFalse(cleanup, cleanup.contains("*.part"))
        assertTrue(cleanup, cleanup.contains("OWN_TEMP"))
        assertFalse("rm -f \"\$DEST\"/*.part", script.lines().filterNot { it.trimStart().startsWith("#") }.any { it.contains("/*.part") })
    }

    @Test
    fun badConvertedBin_isRemoved_inSource() {
        val idx = script.indexOf("dönüştürülen Dense .bin SHA-256 uyuşmuyor")
        assertTrue(idx > 0)
        val before = script.substring(0, idx)
        assertTrue(before.substringAfterLast("if [[").contains("rm -f \"\$DEST/text_dense_768x512_f32.bin\""))
    }

    @Test
    fun shellTestSuite_passes() {
        val bash = File("/bin/bash").takeIf { it.canExecute() } ?: File("/usr/bin/bash").takeIf { it.canExecute() }
        assumeTrue("bash yok", bash != null)
        val shellTest = File(toolsDir, "test_fetch_models.sh")
        assertTrue(shellTest.isFile)
        val p = ProcessBuilder(bash!!.path, shellTest.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("zaman aşımı", p.waitFor(120, TimeUnit.SECONDS))
        assertEquals(out, 0, p.exitValue())
        assertTrue(out, Regex("kalan: 0").containsMatchIn(out))
    }
}
