package com.ktu.aigaleri.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-002: yedekleme/aktarım kurallarının indeks verisini hariç tuttuğunu doğrular. Çalışma dizini mobile/app. */
class DataExtractionRulesTest {
    private val appDir = File(System.getProperty("user.dir")!!)
    private val rules = File(appDir, "src/main/res/xml/data_extraction_rules.xml").readText()

    private fun section(tag: String): String =
        Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(rules)!!.groupValues[1]

    @Test
    fun cloudBackupAndDeviceTransfer_excludeAllIndexDomains() {
        for (tag in listOf("cloud-backup", "device-transfer")) {
            val body = section(tag)
            for (domain in listOf("database", "sharedpref", "file")) {
                assertTrue("$tag/$domain", body.contains("<exclude domain=\"$domain\" />"))
            }
        }
    }

    @Test
    fun manifest_linksRules() {
        val manifest = File(appDir, "src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
    }

    @Test
    fun schemaExport_isEnabled() {
        assertTrue(File(appDir, "build.gradle.kts").readText().contains("room.schemaLocation"))
        assertTrue(File(appDir, "src/main/java/com/ktu/aigaleri/data/AiGaleriDatabase.kt").readText()
            .contains("exportSchema = true"))
    }
}
