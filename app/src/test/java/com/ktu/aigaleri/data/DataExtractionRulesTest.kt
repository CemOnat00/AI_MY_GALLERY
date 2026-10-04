package com.ktu.aigaleri.data

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** T-002: yedekleme/aktarım kurallarının indeks verisini hariç tuttuğunu doğrular. Çalışma dizini mobile/app. */
class DataExtractionRulesTest {
    private val appDir = File(System.getProperty("user.dir")!!)
    private val domains = setOf("database", "sharedpref", "file")

    private fun parse(path: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File(appDir, path)).documentElement

    private fun excludedDomains(parent: Element): Set<String> {
        val nodes = parent.getElementsByTagName("exclude")
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("domain") }.toSet()
    }

    private fun child(parent: Element, tag: String): Element {
        val nodes = parent.getElementsByTagName(tag)
        assertEquals("<$tag> bulunamadı", 1, nodes.length)
        return nodes.item(0) as Element
    }

    @Test
    fun cloudBackupAndDeviceTransfer_excludeAllIndexDomains() {
        val root = parse("src/main/res/xml/data_extraction_rules.xml")
        assertEquals("data-extraction-rules", root.tagName)
        for (tag in listOf("cloud-backup", "device-transfer")) {
            assertEquals(tag, domains, excludedDomains(child(root, tag)))
        }
    }

    @Test
    fun fullBackupContent_excludesAllIndexDomains() {
        val root = parse("src/main/res/xml/backup_rules.xml")
        assertEquals("full-backup-content", root.tagName)
        assertEquals(domains, excludedDomains(root))
    }

    @Test
    fun manifest_linksRules() {
        val app = child(parse("src/main/AndroidManifest.xml"), "application")
        val ns = "http://schemas.android.com/apk/res/android"
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(ns, "dataExtractionRules"))
        assertEquals("@xml/backup_rules", app.getAttributeNS(ns, "fullBackupContent"))
    }

    @Test
    fun schemaExport_isEnabled() {
        assertTrue(File(appDir, "build.gradle.kts").readText().contains("room.schemaLocation"))
        assertTrue(File(appDir, "src/main/java/com/ktu/aigaleri/data/AiGaleriDatabase.kt").readText()
            .contains("exportSchema = true"))
    }
}
