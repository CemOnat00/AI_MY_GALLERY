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
 * Birleşik (merged) manifest güvencesi: bağımlılıkların (ör. onnxruntime-android AAR) manifestle sızdırdığı
 * izin, provider, receiver, service ve activity'ler. Debug VE release ayrı doğrulanır.
 * Dosya yoksa test BAŞARISIZ olur (sessiz atlama yok): build.gradle.kts unit test görevlerini
 * processDebugMainManifest ve processReleaseMainManifest'e bağlar, dosyalar her çalıştırmada tazedir.
 * Çalışma dizini mobile/app.
 */
class MergedManifestTest {
    private val appDir = File(System.getProperty("user.dir")!!)
    private val pkg = "com.ktu.aigaleri"

    private fun merged(variant: String): Document {
        val cap = variant.replaceFirstChar { it.uppercase() }
        val file = File(appDir, "build/intermediates/merged_manifest/$variant/process${cap}MainManifest/AndroidManifest.xml")
        assertTrue("birleşik manifest yok (process${cap}MainManifest çalışmadı): $file", file.isFile)
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return f.newDocumentBuilder().parse(file)
    }

    private fun Element.androidAttr(name: String): String? =
        getAttributeNS(ANDROID_NS, name).takeIf { it.isNotEmpty() }

    private fun Document.elements(tag: String): List<Element> =
        getElementsByTagName(tag).let { nl -> (0 until nl.length).map { nl.item(it) as Element } }

    private fun names(doc: Document, tag: String) = doc.elements(tag).mapNotNull { it.androidAttr("name") }.toSet()

    private val allowedPermissions = setOf(
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_EXTERNAL_STORAGE",
        // T-006: WorkManager AAR'ı (bilinçli kabul): iş sürekliliği için normal izinler. FOREGROUND_SERVICE ve
        // ACCESS_NETWORK_STATE manifestte tools:node=remove ile çıkarılır (aşağıda ayrıca doğrulanır).
        "android.permission.WAKE_LOCK",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "$pkg.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", // AndroidX imza izni
    )

    private fun check(variant: String) {
        val doc = merged(variant)

        // İzinler: üç etiket türü de taranır; beyaz liste dışı hiçbir şey olamaz.
        val declared = listOf("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m")
            .flatMap { tag -> doc.elements(tag) }
        val declaredNames = declared.mapNotNull { it.androidAttr("name") }
        assertTrue("$variant: beyaz liste dışı izin: ${declaredNames - allowedPermissions}", allowedPermissions.containsAll(declaredNames))
        for (net in listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE")) {
            assertFalse("$variant: $net birleşik manifestte", declaredNames.contains(net))
        }
        // Foreground servis/bildirim kapsam dışı (T-006): izin yok, WorkManager'ın foreground servisi de yok.
        assertFalse("$variant: FOREGROUND_SERVICE*", declaredNames.any { it.startsWith("android.permission.FOREGROUND_SERVICE") })
        assertFalse("$variant: POST_NOTIFICATIONS", declaredNames.contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue(declaredNames.contains("android.permission.READ_MEDIA_IMAGES"))
        val readExt = declared.single { it.androidAttr("name") == "android.permission.READ_EXTERNAL_STORAGE" }
        assertEquals("32", readExt.androidAttr("maxSdkVersion"))
        // permission tanımları: yalnızca AndroidX imza izni.
        assertEquals(setOf("$pkg.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"), names(doc, "permission"))

        // Bileşenler: ORT TelemetryInitializer (ve ai.onnxruntime.*) hiçbir yerde olamaz.
        val allNames = listOf("provider", "receiver", "service", "activity", "activity-alias")
            .flatMap { names(doc, it) }
        assertTrue("$variant: ORT bileşeni kaldı", allNames.none { it.startsWith("ai.onnxruntime") })
        assertEquals(setOf("androidx.startup.InitializationProvider"), names(doc, "provider"))
        assertEquals(
            setOf("androidx.room.MultiInstanceInvalidationService", "androidx.work.impl.background.systemjob.SystemJobService"),
            names(doc, "service"),
        )
        assertEquals(
            setOf(
                "androidx.profileinstaller.ProfileInstallReceiver",
                "androidx.work.impl.utils.ForceStopRunnable\$BroadcastReceiver",
                "androidx.work.impl.background.systemalarm.RescheduleReceiver",
                "androidx.work.impl.diagnostics.DiagnosticsReceiver",
            ),
            names(doc, "receiver"),
        )
        val debugOnlyActivities = setOf("androidx.compose.ui.tooling.PreviewActivity", "androidx.activity.ComponentActivity")
        val expectedActivities = setOf("$pkg.ui.MainActivity") + if (variant == "debug") debugOnlyActivities else emptySet()
        assertEquals(expectedActivities, names(doc, "activity"))
        assertEquals(emptySet<String>(), names(doc, "activity-alias"))

        val app = doc.elements("application").single()
        assertEquals("false", app.androidAttr("allowBackup"))
    }

    @Test
    fun debugMergedManifest_hasOnlyWhitelistedPermissionsAndComponents() = check("debug")

    @Test
    fun releaseMergedManifest_hasOnlyWhitelistedPermissionsAndComponents() = check("release")

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
