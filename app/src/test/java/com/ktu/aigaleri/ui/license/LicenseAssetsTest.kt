package com.ktu.aigaleri.ui.license

import com.ktu.aigaleri.ui.Routes
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * T-037 (K-12): Lisanslar içeriği (assets/licenses/) bütünlüğü. Çalışma dizini mobile/app (diğer testler gibi).
 * Apache-2.0 metninin kaynağı: Gradle önbelleğindeki aapt2 NOTICE dosyası (satır 7-208), gövdesi ayrıca
 * androidx.compose.ui LICENSE.txt ile eşleşir (docs/licenses-screen.md).
 */
class LicenseAssetsTest {
    private val appDir = File(System.getProperty("user.dir")!!).let { if (File(it, "src/main").isDirectory) it else File(it, "app") }
    private val dir = File(appDir, "src/main/assets/licenses")
    private val mobileDir = appDir.parentFile

    private fun text(name: String) = File(dir, name).readText(Charsets.UTF_8)

    private fun load() = LicenseCatalog.load { File(dir, it).inputStream() }

    private fun versions(): Map<String, String> {
        val lines = File(mobileDir, "gradle/libs.versions.toml").readLines()
        val section = lines.dropWhile { it.trim() != "[versions]" }.drop(1).takeWhile { !it.trim().startsWith("[") }
        return section.mapNotNull { Regex("^([A-Za-z0-9_]+)\\s*=\\s*\"([^\"]+)\"").find(it.trim()) }
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun words(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }

    @Test
    fun everyAssetFileExists_andIsNotBlank() {
        val names = listOf("apache-2.0.txt", "mit-openai-clip.txt", "mit-onnxruntime.txt", "app-license.txt", "modified-notice.txt", "components.txt")
        for (n in names) {
            val f = File(dir, n)
            assertTrue("$n yok", f.isFile)
            assertTrue("$n boş", f.readText(Charsets.UTF_8).isNotBlank())
        }
    }

    @Test
    fun apache_isCompleteAndUnaltered() {
        val t = text("apache-2.0.txt")
        assertEquals("Apache-2.0 tam metni 202 satırdır", 202, t.lines().dropLastWhile { it.isEmpty() }.size)
        for (phrase in listOf(
            "Version 2.0, January 2004",
            "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION",
            "1. Definitions.",
            "4. Redistribution.",
            "(b) You must cause any modified files to carry prominent notices",
            "END OF TERMS AND CONDITIONS",
            "APPENDIX: How to apply the Apache License to your work.",
            "limitations under the License.",
        )) assertTrue("eksik: $phrase", t.contains(phrase))
        // Yerel kaynakla (aapt2 NOTICE 7-208) birebir doğrulanan metnin SHA-256 değeri: tek bayt değişirse kırılır.
        val sha = MessageDigest.getInstance("SHA-256").digest(t.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        assertEquals("cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30", sha)
    }

    @Test
    fun mitTexts_haveStandardBodyAndRightCopyright() {
        val clip = text("mit-openai-clip.txt")
        val ort = text("mit-onnxruntime.txt")
        assertTrue(clip.contains("Copyright (c) 2021 OpenAI"))
        assertTrue(ort.contains("Copyright (c) Microsoft Corporation"))
        for (t in listOf(clip, ort)) {
            assertTrue(t.startsWith("MIT License"))
            assertTrue(t.contains("Permission is hereby granted, free of charge"))
            assertTrue(t.contains("THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND"))
        }
        // Standart MIT gövdesi iki dosyada aynıdır (yalnızca telif satırı farklıdır).
        fun body(t: String) = t.substringAfter("Permission is hereby granted")
        assertEquals(body(clip), body(ort))
    }

    @Test
    fun components_parse_andEveryLicenseTypeAndTextFileIsListed() {
        val doc = load()
        val shown = LicenseCatalog.TEXT_FILES.map { it.first }
        assertEquals(setOf("MIT", "Apache-2.0"), doc.components.map { it.license }.toSet())
        for (c in doc.components) {
            assertTrue("${c.name}: metin dosyası ekranda yok (${c.textFile})", c.textFile in shown)
            assertTrue(File(dir, c.textFile).isFile)
            when (c.license) {
                "MIT" -> assertTrue(c.name, c.textFile.startsWith("mit-"))
                "Apache-2.0" -> assertEquals(c.name, "apache-2.0.txt", c.textFile)
                else -> fail("bilinmeyen lisans ${c.license}")
            }
            assertTrue(c.name, c.sourceUrl.startsWith("https://"))
        }
        assertEquals(shown.size, doc.texts.size)
        doc.texts.forEach { assertTrue(it.fileName, it.paragraphs.isNotEmpty()) }
    }

    @Test
    fun requiredComponents_arePresent_withExpectedLicense() {
        val c = load().components
        fun find(part: String) = c.first { it.name.contains(part, ignoreCase = true) }
        assertEquals("MIT", find("OpenAI CLIP").license)
        assertEquals("Apache-2.0", find("sentence-transformers").license)
        assertEquals("Apache-2.0", find("distilbert").license)
        assertEquals("MIT", find("ONNX Runtime").license)
        for (n in listOf("Coil", "Jetpack Compose", "Material 3", "Room", "WorkManager", "Navigation", "Lifecycle", "Kotlin", "coroutines")) {
            assertEquals(n, "Apache-2.0", find(n).license)
        }
    }

    @Test
    fun versions_matchLibsVersionsToml() {
        val v = versions()
        val c = load().components
        val expectations = mapOf(
            "Coil" to "coil",
            "ONNX Runtime" to "onnxruntime",
            "AndroidX Room" to "room",
            "WorkManager" to "work",
            "Navigation Compose" to "navigationCompose",
            "Lifecycle" to "lifecycle",
            "Activity Compose" to "activityCompose",
            "Core KTX" to "coreKtx",
            "ExifInterface" to "exifinterface",
            "Jetpack Compose" to "composeBom",
            "Material 3" to "composeBom",
            "Kotlin" to "kotlin",
            "kotlinx.coroutines" to "coroutines",
        )
        for ((name, key) in expectations) {
            val expected = requireNotNull(v[key]) { "libs.versions.toml'da $key yok" }
            val comp = c.first { it.name == name || it.name.contains(name) }
            assertTrue("$name sürümü ($key=$expected) bulunamadı: ${comp.version}", comp.version.contains(expected))
        }
    }

    @Test
    fun clipEntry_isHonestAboutConversionLicense() {
        val clip = load().components.single { it.name.contains("OpenAI CLIP") }
        assertTrue(clip.note.contains("2021 OpenAI"))
        assertTrue(clip.note.contains("Xenova"))
        assertTrue(clip.note.contains("lisans beyanı yoktur"))
        assertTrue(clip.note.contains("çıkarılmıştır"))
        assertFalse("Xenova'nın MIT verdiği iddia edilmemeli", clip.note.contains("Xenova MIT"))
    }

    @Test
    fun modifiedModelNotice_exists_andDescribesChange() {
        val n = text("modified-notice.txt")
        assertTrue(n.contains("DEĞİŞTİRİLMİŞ"))
        assertTrue(n.contains("Apache License 2.0"))
        assertTrue(n.contains("sentence-transformers/clip-ViT-B-32-multilingual-v1"))
        assertTrue(n.contains("119547") && n.contains("69830"))
        assertTrue(n.contains("Değişiklik tarihi"))
        val c = load().components
        for (part in listOf("sentence-transformers", "distilbert")) {
            assertTrue(part, c.single { it.name.contains(part) }.note.contains("DEĞİŞTİRİLMİŞ"))
        }
        assertEquals(load().modifiedNotice.size, LicenseCatalog.paragraphs(n).size)
    }

    @Test
    fun appLicense_isMitOfOwnSourceOnly_andDoesNotClaimThirdPartyLicenses() {
        val t = text("app-license.txt")
        assertTrue(t.contains("KTÜ Mobil Programlama dersi dönem projesidir"))
        assertTrue(t.contains("MIT License"))
        assertTrue(t.contains("Copyright (c) 2026 Cem Onat SATIR"))
        assertTrue(t.contains("Permission is hereby granted, free of charge"))
        assertTrue(t.contains("yalnızca Ai-galeri'nin kendi kaynak kodunu kapsar"))
        // Üçüncü taraf lisansı karışmaz: Apache metni ya da başka telif sahibi yok.
        assertFalse(Regex("Apache License|Licensed under|OpenAI|Microsoft").containsMatchIn(t))
        assertEquals(1, Regex("Copyright \\(c\\)").findAll(t).count())
        // Yazım tüm asset'lerde tutarlı ("SATIR", büyük harf).
        for (f in dir.listFiles()!!) assertFalse("${f.name}: eski yazım", f.readText(Charsets.UTF_8).contains("Cem Onat Satır"))
    }

    @Test
    fun appLicense_mitBodyMatchesRepositoryLicenseWhenPresent() {
        val repoLicense = File("/Users/cem/Projects/AI_MY_GALLERY/LICENSE")
        org.junit.Assume.assumeTrue("depo LICENSE dosyası bu makinede yok", repoLicense.isFile)
        assertTrue(text("app-license.txt").contains(repoLicense.readText(Charsets.UTF_8).trimEnd()))
    }

    @Test
    fun componentCount_isPinned() {
        assertEquals("components.txt kayıt sayısı değişti; ekran ve test birlikte güncellenmeli", 17, load().components.size)
    }

    @Test
    fun transitiveEntry_isPresent_withResolvedVersions() {
        val e = load().components.single { it.name.contains("geçişli", ignoreCase = true) }
        assertEquals("Apache-2.0", e.license)
        for (part in listOf("startup", "profileinstaller", "emoji2", "sqlite", "savedstate", "collection", "tracing", "graphics-path", "Okio", "kotlinx.serialization", "JetBrains Compose")) {
            assertTrue(part, (e.version + e.note).contains(part))
        }
        assertTrue(load().components.single { it.name == "Kotlin" }.note.contains("2.3.10"))
    }

    @Test
    fun ortEntry_hasNoUnverifiedClaims() {
        val ort = load().components.single { it.name.contains("ONNX Runtime") }
        assertFalse(ort.note.contains("telemetri kapalıdır"))
        assertTrue(ort.note.contains("ağ izni yoktur"))
        assertTrue(ort.note.contains("ek telif bildirimleri içerebilir"))
        assertFalse(text("mit-onnxruntime.txt").contains("Oracle"))
    }

    /** libs.versions.toml'a eklenen her dağıtılan kütüphane components.txt'te (sürümüyle) karşılık bulmalıdır. */
    @Test
    fun everyShippedLibraryInVersionCatalog_isCoveredByAComponentVersion() {
        // Dağıtılan APK'ya girmeyen (test, araç, derleyici) kütüphane takma adları.
        val notShipped = setOf(
            "androidx-compose-ui-test-junit4", "androidx-compose-ui-test-manifest", "androidx-compose-ui-tooling",
            "androidx-room-compiler", "androidx-room-testing", "androidx-work-testing", "junit",
            "kotlinx-coroutines-test", "androidx-test-ext-junit", "androidx-test-runner",
        )
        val v = versions()
        val libs = File(mobileDir, "gradle/libs.versions.toml").readLines()
            .dropWhile { it.trim() != "[libraries]" }.drop(1).takeWhile { !it.trim().startsWith("[") }
        val entries = libs.mapNotNull { Regex("^([A-Za-z0-9_-]+)\\s*=.*version\\.ref\\s*=\\s*\"([^\"]+)\"").find(it.trim()) }
            .map { it.groupValues[1] to it.groupValues[2] }
        assertTrue(entries.size >= 10)
        val comps = load().components
        for ((alias, ref) in entries) {
            if (alias in notShipped) continue
            val ver = requireNotNull(v[ref]) { "$ref sürümü yok" }
            assertTrue("$alias ($ver) components.txt'te yok: kayıt ekleyin ya da notShipped'e alın", comps.any { it.version.contains(ver) })
        }
        // BOM ile yönetilen (sürüm.ref'siz) ui/material3 için BOM sürümü kayıtlıdır.
        assertTrue(comps.any { it.version.contains(requireNotNull(v["composeBom"])) })
    }

    @Test
    fun paragraphs_keepEveryWordInOrder() {
        for ((file, _) in LicenseCatalog.TEXT_FILES + listOf("app-license.txt" to 0, "modified-notice.txt" to 0)) {
            val raw = text(file)
            assertEquals(file, words(raw), words(LicenseCatalog.paragraphs(raw).joinToString(" ")))
        }
    }

    @Test
    fun paragraphs_splitOnBlankLines_andTrimIndent() {
        assertEquals(listOf("a b", "c"), LicenseCatalog.paragraphs("  a\n   b\n\n\n c \n"))
        assertEquals(emptyList<String>(), LicenseCatalog.paragraphs("\n  \n"))
    }

    @Test
    fun parseComponents_rejectsBadInput() {
        val ok = "ad: A\nsurum: 1\nlisans: MIT\nmetin: m.txt\nkaynak: https://x\nnot: n\n"
        assertEquals(1, LicenseCatalog.parseComponents("# yorum\n$ok").size)
        assertEquals(2, LicenseCatalog.parseComponents("$ok\n$ok").size)
        for (bad in listOf(ok.replace("surum: 1\n", ""), "$ok" + "x: y\n", "$ok" + "ad: B\n", "satır iki nokta yok")) {
            try {
                LicenseCatalog.parseComponents(bad)
                fail("hata beklenirdi: $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun urlWithColon_isKeptWhole() {
        val c = LicenseCatalog.parseComponents("ad: A\nsurum: 1\nlisans: MIT\nmetin: m\nkaynak: https://a.b/c:d\nnot: n: o")
        assertEquals("https://a.b/c:d", c.single().sourceUrl)
        assertEquals("n: o", c.single().note)
    }

    @Test
    fun route_isLicenses() {
        assertEquals("licenses", Routes.LICENSES)
    }

    @Test
    fun noInternetPermission_andNoNetworkOrLogInLicenseCode() {
        val manifest = File(appDir, "src/main/AndroidManifest.xml").readText()
        val declared = Regex("<uses-permission[^>]*INTERNET[^>]*>").findAll(manifest).map { it.value }
            .filterNot { it.contains("tools:node=\"remove\"") }.toList()
        assertTrue(declared.toString(), declared.isEmpty())
        val sources = File(appDir, "src/main/java/com/ktu/aigaleri/ui/license").listFiles { f -> f.extension == "kt" }!!
        assertNotNull(sources)
        assertTrue(sources.isNotEmpty())
        for (f in sources) {
            val code = f.readText()
            for (banned in listOf("android.util.Log", "Log.d(", "Log.e(", "Log.w(", "Log.i(", "java.net", "okhttp", "HttpURLConnection", "Intent.ACTION_VIEW")) {
                assertFalse("${f.name}: $banned", code.contains(banned))
            }
        }
    }

    @Test
    fun licensesAssets_areNotGitIgnored_butModelsAre() {
        val ignore = File(mobileDir, ".gitignore").readText()
        assertTrue(ignore.contains("app/src/main/assets/models/"))
        assertFalse(ignore.contains("licenses"))
    }
}
