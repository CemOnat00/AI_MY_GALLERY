package com.ktu.aigaleri

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * T-006 (QA): birleşik manifestte dışarıya açık (exported) bileşenler. Yalnızca MainActivity serbest; sistem/ADB
 * erişimli bileşenler imza/sistem izniyle korunmalı; WorkManager yeniden planlama alıcısı dışa kapalı olmalı.
 * Dosya yoksa test başarısız olur (MergedManifestTest ile aynı build bağımlılığı).
 */
class MergedManifestExposureTest {
    private val appDir = File(System.getProperty("user.dir")!!)
    private val ns = "http://schemas.android.com/apk/res/android"
    private val pkg = "com.ktu.aigaleri"

    private fun merged(variant: String): Document {
        val cap = variant.replaceFirstChar { it.uppercase() }
        val f = File(appDir, "build/intermediates/merged_manifest/$variant/process${cap}MainManifest/AndroidManifest.xml")
        assertTrue("birleşik manifest yok: $f", f.isFile)
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(f)
    }

    private fun Element.a(name: String): String? = getAttributeNS(ns, name).takeIf { it.isNotEmpty() }

    private fun components(doc: Document): List<Element> =
        listOf("activity", "activity-alias", "service", "receiver", "provider").flatMap { tag ->
            doc.getElementsByTagName(tag).let { nl -> (0 until nl.length).map { nl.item(it) as Element } }
        }

    private fun check(variant: String) {
        val doc = merged(variant)
        val systemGuarded = setOf("android.permission.BIND_JOB_SERVICE", "android.permission.DUMP")
        val exported = components(doc).filter { it.a("exported") == "true" }
        for (c in exported) {
            val name = c.a("name")!!
            if (name == "$pkg.ui.MainActivity") continue
            if (variant == "debug" && name.startsWith("androidx.")) { // debug-only test/preview activity'leri
                if (c.tagName == "activity") continue
            }
            assertTrue("$variant: $name dışa açık ama izinsiz", c.a("permission") in systemGuarded)
        }
        // Intent-filter'sız bileşen dışa açık olamaz (varsayılan çıkarım da dahil: explicit exported zorunlu, API 31+).
        for (c in components(doc)) {
            val hasFilter = c.getElementsByTagName("intent-filter").length > 0
            if (c.a("exported") == null && hasFilter) fail("$variant: ${c.a("name")} exported belirtilmemiş")
        }
        val reschedule = components(doc).single { it.a("name") == "androidx.work.impl.background.systemalarm.RescheduleReceiver" }
        assertEquals("false", reschedule.a("exported"))
        val job = components(doc).single { it.a("name") == "androidx.work.impl.background.systemjob.SystemJobService" }
        assertEquals("android.permission.BIND_JOB_SERVICE", job.a("permission"))
        val provider = components(doc).single { it.tagName == "provider" }
        assertEquals("false", provider.a("exported"))
        // Uygulama düzeyi: kayıt tutulan, ağ ve test bayrakları.
        val app = doc.getElementsByTagName("application").item(0) as Element
        assertEquals(null, app.a("usesCleartextTraffic"))
        assertEquals(null, app.a("testOnly"))
        if (variant == "release") assertTrue(app.a("debuggable") != "true")
    }

    private fun fail(msg: String): Nothing = throw AssertionError(msg)

    @Test fun debug_exportedComponentsAreGuarded() = check("debug")

    @Test fun release_exportedComponentsAreGuarded() = check("release")
}
