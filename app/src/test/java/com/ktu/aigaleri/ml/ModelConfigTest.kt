package com.ktu.aigaleri.ml

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-007 yapılandırma sözleşmeleri. Çalışma dizini app/ (Gradle proje kökünün altında). */
class ModelConfigTest {
    private val appDir = File(System.getProperty("user.dir")!!)
    private val projectDir = appDir.parentFile!!

    private val gradle get() = File(appDir, "build.gradle.kts").readText()
    private val toml get() = File(projectDir, "gradle/libs.versions.toml").readText()

    @Test
    fun buildScript_hasOnnxRuntimeAndNoCompressOnnx() {
        assertTrue(gradle.contains("libs.onnxruntime.android"))
        assertTrue(Regex("noCompress\\s*\\+=\\s*\"onnx\"").containsMatchIn(gradle))
        assertTrue(toml.contains("com.microsoft.onnxruntime"))
        assertTrue(Regex("onnxruntime\\s*=\\s*\"1\\.30\\.0\"").containsMatchIn(toml))
    }

    @Test
    fun noMlKitOrNetworkArtifacts_inDependencies() {
        // Yorum satırları (ör. "okhttp EKLENMEZ" açıklamaları) hariç.
        val all = (gradle + "\n" + toml).lines().filterNot { it.trimStart().startsWith("#") || it.trimStart().startsWith("//") }
            .joinToString("\n").lowercase()
        assertFalse(all.contains("mlkit"))
        assertFalse(all.contains("play-services"))
        assertFalse(all.contains("okhttp"))
        assertFalse(all.contains("retrofit"))
    }

    @Test
    fun sourceManifest_removesNetworkPermissionsAndTelemetryProviderInjectedByOrtAar() {
        // Öznitelik sırasından bağımsız: DOM ile okunur.
        val f = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = f.newDocumentBuilder().parse(File(appDir, "src/main/AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val tools = "http://schemas.android.com/tools"
        fun removed(tag: String): Set<String> = doc.getElementsByTagName(tag).let { nl ->
            (0 until nl.length).map { nl.item(it) as org.w3c.dom.Element }
                .filter { it.getAttributeNS(tools, "node") == "remove" }
                .map { it.getAttributeNS(android, "name") }.toSet()
        }
        assertEquals(
            // T-006: WorkManager'ın FOREGROUND_SERVICE izni de çıkarılır (foreground servis kapsam dışı).
            setOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.FOREGROUND_SERVICE"),
            removed("uses-permission"),
        )
        assertEquals(setOf("ai.onnxruntime.TelemetryInitializer"), removed("provider"))
        assertEquals(setOf("androidx.work.impl.foreground.SystemForegroundService"), removed("service"))
    }

    @Test
    fun modelFiles_areGitIgnored() {
        val ignore = File(projectDir, ".gitignore").readText()
        assertTrue(ignore.lines().any { it.trim() == "app/src/main/assets/models/" })
        // T-027: orijinal (budanmamış) model önbelleği de git dışıdır (ağırlıklar asla commit'lenmez).
        assertTrue(ignore.lines().any { it.trim() == ".model-cache/" })
    }

    @Test
    fun fetchScript_prunesWithIsolatedStdlibPython_andDocumentsOriginalAndPrunedHashes() {
        val script = File(projectDir, "tools/fetch_models.sh").readText()
        val code = script.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
        // Orijinal SHA'lar budama girdisidir, budanmışlar ModelManifest ile aynıdır.
        assertTrue(code.contains("SHA_TEXT_ONNX=\"3bed77e83926519660b5c0833cb3321cfde952331edec4f7d501c7d384b8a8a7\""))
        assertTrue(code.contains("SHA_VOCAB=\"fe0fda7c425b48c516fc8f160d594c8022a0808447475c1a7c6d6479763f310c\""))
        assertTrue(code.contains("SHA_TEXT_ONNX_PRUNED=\"${ModelManifest.TEXT_ONNX.sha256}\""))
        assertTrue(code.contains("SHA_VOCAB_PRUNED=\"${ModelManifest.TEXT_VOCAB.sha256}\""))
        assertTrue(code.contains("python3 -I \"\$HERE/prune_text_model.py\""))
        assertFalse("python3 -I olmadan çağrı", code.lines().any { Regex("""\bpython3\s+(?!-I)""").containsMatchIn(it) })
        // Aynı SHA'lar prune_text_model.py'deki orijinal sabitlerle de eşit.
        val py = File(projectDir, "tools/prune_text_model.py").readText()
        assertTrue(py.contains("3bed77e83926519660b5c0833cb3321cfde952331edec4f7d501c7d384b8a8a7"))
        assertTrue(py.contains("fe0fda7c425b48c516fc8f160d594c8022a0808447475c1a7c6d6479763f310c"))
        // Budanmış çıktı SHA-256'ları araçta da manifest ile aynıdır (araç yazmadan önce doğrular).
        assertTrue(py.contains("PRUNED_ONNX_SHA = \"${ModelManifest.TEXT_ONNX.sha256}\""))
        assertTrue(py.contains("PRUNED_VOCAB_SHA = \"${ModelManifest.TEXT_VOCAB.sha256}\""))
        // Python aracı yalnızca standart kütüphane kullanır: içe aktarılan her modül izinli stdlib kümesinin içinde, yasaklı üçüncü taraf yok.
        val imports = py.lines().filter { it.startsWith("import ") || it.startsWith("from ") }
            .map { it.removePrefix("import ").removePrefix("from ").substringBefore(' ').substringBefore('.') }.toSet()
        val allowedStdlib = setOf("argparse", "hashlib", "os", "sys", "unicodedata", "shutil", "tempfile", "re", "json", "struct", "collections", "itertools")
        assertTrue("izinli stdlib dışında içe aktarma: ${imports - allowedStdlib}", allowedStdlib.containsAll(imports))
        for (banned in listOf("numpy", "onnx", "onnxruntime", "requests", "urllib", "socket", "http")) assertFalse(banned, banned in imports)
    }

    @Test
    fun fetchScript_usesSameHashesAsManifest_andIsNotAppCode() {
        val script = File(projectDir, "tools/fetch_models.sh").readText()
        for (m in listOf(ModelManifest.TEXT_ONNX, ModelManifest.TEXT_VOCAB, ModelManifest.TEXT_DENSE, ModelManifest.VISION_ONNX)) {
            assertTrue("betikte ${m.fileName} SHA-256 yok", script.contains(m.sha256))
        }
        assertTrue(script.contains("58edf8cada9e398793dca955574a48cbb7f18be2"))
        assertTrue(script.contains("d15189d7028b43f1d3e65039190477f6af591c2a"))
    }

    @Test
    fun mlSources_doNotLogAndDoNotUseNetwork() {
        val dir = File(appDir, "src/main/java/com/ktu/aigaleri/ml")
        val sources = dir.listFiles { f -> f.extension == "kt" }!!
        assertTrue(sources.isNotEmpty())
        for (f in sources) {
            val text = f.readText()
            assertFalse("${f.name}: log çağrısı", Regex("\\bLog\\.|println\\(|Timber").containsMatchIn(text))
            assertFalse("${f.name}: ağ", Regex("java\\.net\\.|HttpURLConnection|okhttp").containsMatchIn(text))
        }
        assertEquals(true, File(dir, "OnnxTextEncoder.kt").exists())
    }
}
