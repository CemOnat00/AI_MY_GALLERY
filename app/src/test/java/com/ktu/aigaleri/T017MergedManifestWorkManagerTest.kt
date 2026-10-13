package com.ktu.aigaleri

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * T-017 (QA): birleşik manifestte WorkManager öğeleri. DiagnosticsReceiver çıkmış (tools:node=remove), ama
 * RescheduleReceiver, SystemJobService, WAKE_LOCK ve RECEIVE_BOOT_COMPLETED YERİNDE olmalı (MergedManifestTest
 * izinleri yalnızca beyaz listeyle sınırlar; varlığını burada ayrıca zorunlu kılıyoruz). INTERNET ve
 * ACCESS_NETWORK_STATE yok. Debug ve release ayrı. Dosya yoksa test başarısız olur.
 */
class T017MergedManifestWorkManagerTest {
    private val appDir = File(System.getProperty("user.dir")!!)
    private val ns = "http://schemas.android.com/apk/res/android"
    private val diag = "androidx.work.impl.diagnostics.DiagnosticsReceiver"

    private fun parse(f: File): Document {
        assertTrue("dosya yok: $f", f.isFile)
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(f)
    }

    private fun merged(variant: String): Document {
        val cap = variant.replaceFirstChar { it.uppercase() }
        return parse(File(appDir, "build/intermediates/merged_manifest/$variant/process${cap}MainManifest/AndroidManifest.xml"))
    }

    private fun Document.all(tag: String): List<Element> =
        getElementsByTagName(tag).let { nl -> (0 until nl.length).map { nl.item(it) as Element } }

    private fun Element.a(name: String) = getAttributeNS(ns, name).takeIf { it.isNotEmpty() }

    private fun perms(d: Document) =
        listOf("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m").flatMap { d.all(it) }.mapNotNull { it.a("name") }

    private fun check(variant: String) {
        val d = merged(variant)
        val p = perms(d)
        assertTrue("$variant: WAKE_LOCK yok", "android.permission.WAKE_LOCK" in p)
        assertTrue("$variant: RECEIVE_BOOT_COMPLETED yok", "android.permission.RECEIVE_BOOT_COMPLETED" in p)
        assertFalse("$variant: INTERNET", "android.permission.INTERNET" in p)
        assertFalse("$variant: ACCESS_NETWORK_STATE", "android.permission.ACCESS_NETWORK_STATE" in p)

        val receivers = d.all("receiver")
        assertTrue("$variant: DiagnosticsReceiver kaldı", receivers.none { it.a("name") == diag })
        // Hiçbir bileşen türünde (receiver/service/provider/activity) diagnostics adı geçmemeli.
        val comps = listOf("receiver", "service", "provider", "activity", "activity-alias").flatMap { d.all(it) }
        assertTrue("$variant: diagnostics bileşeni", comps.none { it.a("name")!!.contains("diagnostics") })
        // DUMP korumalı bileşen yalnızca ProfileInstallReceiver olabilir (DiagnosticsReceiver'ın korumasıydı, T-017 ile gitti).
        val dumpGuarded = comps.filter { it.a("permission") == "android.permission.DUMP" }.map { it.a("name") }
        assertTrue("$variant: beklenmeyen DUMP korumalı bileşen: $dumpGuarded", dumpGuarded.all { it == "androidx.profileinstaller.ProfileInstallReceiver" })
        assertFalse("$variant: DUMP kullanım izni", "android.permission.DUMP" in p)

        val reschedule = receivers.singleOrNull { it.a("name") == "androidx.work.impl.background.systemalarm.RescheduleReceiver" }
        assertTrue("$variant: RescheduleReceiver yok", reschedule != null)
        assertEquals("false", reschedule!!.a("exported"))
        val actions = reschedule.getElementsByTagName("action").let { nl ->
            (0 until nl.length).map { (nl.item(it) as Element).a("name") }
        }
        assertTrue("$variant: RescheduleReceiver BOOT_COMPLETED dinlemiyor: $actions", "android.intent.action.BOOT_COMPLETED" in actions)

        val job = d.all("service").singleOrNull { it.a("name") == "androidx.work.impl.background.systemjob.SystemJobService" }
        assertTrue("$variant: SystemJobService yok", job != null)
        assertEquals("android.permission.BIND_JOB_SERVICE", job!!.a("permission"))
        // Foreground servis çıkarılmış kalmalı (T-006 kararı, T-017 ile bozulmamalı).
        assertTrue("$variant: SystemForegroundService", d.all("service").none { it.a("name")!!.endsWith("SystemForegroundService") })
    }

    @Test fun debug_workManagerPartsPresent_diagnosticsGone() = check("debug")

    @Test fun release_workManagerPartsPresent_diagnosticsGone() = check("release")

    @Test
    fun sourceManifest_removesDiagnosticsReceiver_andKeepsRescheduleAndPermissions() {
        val xml = File(appDir, "src/main/AndroidManifest.xml").readText()
        // Kaynak manifestte DiagnosticsReceiver yalnızca tools:node="remove" ile geçer.
        val m = Regex("<receiver\\b[^>]*DiagnosticsReceiver[^>]*>", RegexOption.DOT_MATCHES_ALL).find(xml)
        assertTrue("kaynak manifestte DiagnosticsReceiver remove girdisi yok", m != null)
        assertTrue(m!!.value, m.value.contains("tools:node=\"remove\""))
        assertTrue(m.value.contains(diag))
        // Kaldırma girdisi RescheduleReceiver/SystemJobService/WAKE_LOCK/BOOT_COMPLETED'a uzanmamalı.
        for (kept in listOf("RescheduleReceiver", "SystemJobService", "WAKE_LOCK", "RECEIVE_BOOT_COMPLETED")) {
            val removing = Regex("<[^>]*$kept[^>]*tools:node=\"remove\"[^>]*>", RegexOption.DOT_MATCHES_ALL).containsMatchIn(xml) ||
                Regex("<[^>]*tools:node=\"remove\"[^>]*$kept[^>]*>", RegexOption.DOT_MATCHES_ALL).containsMatchIn(xml)
            assertFalse("$kept kaynak manifestte kaldırılıyor", removing)
        }
        assertTrue(xml.contains("android.permission.INTERNET\" tools:node=\"remove\""))
        assertTrue(xml.contains("android.permission.ACCESS_NETWORK_STATE\" tools:node=\"remove\""))
    }
}
