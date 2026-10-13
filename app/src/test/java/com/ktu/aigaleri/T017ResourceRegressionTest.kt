package com.ktu.aigaleri

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-017 (QA): kullanılmayan `image_load_failed` string'i kaldırıldı ve geri gelmemeli; kodda/XML'de
 * başvurusu olmamalı. libs.versions.toml Coil notu: sürüm 3.4.0, ağ artefaktı yok. Çalışma dizini mobile/app.
 */
class T017ResourceRegressionTest {
    private val appDir = File(System.getProperty("user.dir")!!)
    private val mobileDir = appDir.parentFile!!
    private val id = "image_load_failed"

    private fun textFiles(root: File, exts: Set<String>): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension in exts }.toList()

    @Test
    fun stringsXml_hasNoImageLoadFailed_inAnyValuesDir() {
        val resDir = File(appDir, "src/main/res")
        val valueFiles = resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!
            .flatMap { it.listFiles { x -> x.extension == "xml" }!!.toList() }
        assertTrue("strings.xml bulunamadı", valueFiles.any { it.name == "strings.xml" })
        for (f in valueFiles) assertFalse("$f içinde $id var", f.readText().contains(id))
    }

    @Test
    fun noSourceReferencesImageLoadFailed() {
        // main, test ve androidTest: Kotlin, Java, XML. Bu test dosyası kendi adını içerdiği için hariç.
        val self = "T017ResourceRegressionTest.kt"
        val hits = textFiles(File(appDir, "src"), setOf("kt", "java", "xml")).filter { it.name != self }
            .filter { it.readText().contains(id) }
        assertEquals("başvuru var: $hits", emptyList<File>(), hits)
    }

    @Test
    fun otherStrings_stillPresent_andUsedResourcesResolve() {
        // Yanlış string silinmediğini doğrula: komşu kaynaklar yerinde.
        val xml = File(appDir, "src/main/res/values/strings.xml").readText()
        for (name in listOf("photo_content_description", "viewer_not_found", "back_button", "search_invalid_empty")) {
            assertTrue("$name yok", xml.contains("name=\"$name\""))
        }
        // Kodda R.string.X ile başvurulan her string strings.xml'de tanımlı olmalı.
        val defined = Regex("<string\\s+name=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toSet()
        val used = textFiles(File(appDir, "src/main"), setOf("kt", "java"))
            .flatMap { Regex("R\\.string\\.([A-Za-z0-9_]+)").findAll(it.readText()).map { m -> m.groupValues[1] }.toList() }
            .toSet()
        assertEquals("tanımsız string başvurusu", emptySet<String>(), used - defined)
        // Manifest/XML başvuruları da.
        val xmlUsed = textFiles(File(appDir, "src/main"), setOf("xml"))
            .flatMap { Regex("@string/([A-Za-z0-9_]+)").findAll(it.readText()).map { m -> m.groupValues[1] }.toList() }
            .toSet()
        assertEquals("XML'de tanımsız string", emptySet<String>(), xmlUsed - defined)
    }

    @Test
    fun versionCatalog_coilNote_matchesSelectedVersion_andHasNoNetworkArtifacts() {
        val toml = File(mobileDir, "gradle/libs.versions.toml").readText()
        assertTrue(Regex("(?m)^coil\\s*=\\s*\"3\\.4\\.0\"\\s*$").containsMatchIn(toml))
        val comment = toml.lines().filter { it.startsWith("#") && it.contains("Coil") || it.contains("3.4.0 (AAR") }.joinToString("\n")
        assertTrue("Coil notu 3.4.0'ı belirtmiyor", toml.contains("Seçili sürüm 3.4.0"))
        assertTrue(toml.contains("minCompileSdk=35"))
        assertTrue(toml.contains("3.5.0 (minCompileSdk=36)"))
        assertTrue(toml.contains("3.6.x minCompileSdk=37"))
        // Eski, yanlış iddia ("3.5.0: compileSdk 36 ile uyumlu en yeni sürüm" seçili gibi) geri gelmemeli.
        assertFalse(comment, toml.contains("3.5.0: compileSdk 36 ile uyumlu en yeni sürüm"))
        // Ağ artefaktı bağımlılığı (satır başı yorum olmayan tanımlar): coil-network, okhttp, ktor.
        val decl = toml.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
        for (net in listOf("coil-network", "okhttp", "ktor")) assertFalse("$net katalogda", decl.contains(net, ignoreCase = true))
        val gradle = File(appDir, "build.gradle.kts").readText()
        assertTrue(gradle.contains("libs.coil.compose"))
        for (net in listOf("coil.network", "okhttp", "ktor")) assertFalse("$net build.gradle.kts'de", gradle.contains(net, ignoreCase = true))
    }
}
