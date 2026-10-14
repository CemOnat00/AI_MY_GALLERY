package com.ktu.aigaleri

import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * T-024 (QA): release çıktısı ve birleşik manifest regresyonları. ReleaseShrinkConfigTest/MergedManifestTest'in
 * kapsamadığı kenarlar: dışa açık bileşen kümesi, tam izin kümesi, manifest bileşenlerinin R8 tarafından korunması,
 * ORT sınıflarının adı/sayısı, strings.xml'deki her dizenin kaynak küçültmeden sağ çıkması, release APK dex bütçesi.
 *
 * R8/APK çıktısına bağlı testler `./gradlew assembleRelease` sonrası anlamlıdır; dosya yoksa assumeTrue ile atlanır.
 * Birleşik manifest testleri atlanmaz (MergedManifestTest ile aynı build bağımlılığı). Çalışma dizini app/ (Gradle proje kökünün altında).
 */
class ReleaseOutputQaTest {
    private val appDir = File(System.getProperty("user.dir")!!)
    private val ns = "http://schemas.android.com/apk/res/android"
    private val pkg = "com.ktu.aigaleri"

    private val mappingDir = File(appDir, "build/outputs/mapping/release")
    private val apk = File(appDir, "build/outputs/apk/release/app-release-unsigned.apk")

    // ---- Birleşik manifest ----

    private fun merged(variant: String): Document {
        val cap = variant.replaceFirstChar { it.uppercase() }
        val f = File(appDir, "build/intermediates/merged_manifest/$variant/process${cap}MainManifest/AndroidManifest.xml")
        assertTrue("birleşik manifest yok: $f", f.isFile)
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(f)
    }

    private fun Element.a(name: String): String? = getAttributeNS(ns, name).takeIf { it.isNotEmpty() }

    private fun Document.all(tag: String): List<Element> =
        getElementsByTagName(tag).let { nl -> (0 until nl.length).map { nl.item(it) as Element } }

    private fun components(doc: Document): List<Element> =
        listOf("activity", "activity-alias", "service", "receiver", "provider").flatMap { doc.all(it) }

    private val debugOnly = setOf("androidx.compose.ui.tooling.PreviewActivity", "androidx.activity.ComponentActivity")

    @Test
    fun release_exportedComponentSet_isExactlyMainActivityJobServiceProfileInstall() {
        val doc = merged("release")
        val exported = components(doc).filter { it.a("exported") == "true" }.associateBy { it.a("name")!! }
        assertEquals(
            setOf(
                "$pkg.ui.MainActivity",
                "androidx.work.impl.background.systemjob.SystemJobService",
                "androidx.profileinstaller.ProfileInstallReceiver",
            ),
            exported.keys,
        )
        assertEquals(null, exported.getValue("$pkg.ui.MainActivity").a("permission"))
        assertEquals("android.permission.BIND_JOB_SERVICE", exported.getValue("androidx.work.impl.background.systemjob.SystemJobService").a("permission"))
        assertEquals("android.permission.DUMP", exported.getValue("androidx.profileinstaller.ProfileInstallReceiver").a("permission"))
        // DiagnosticsReceiver (T-017) ve SystemForegroundService hiçbir biçimde (exported=false dahil) bulunmaz.
        val names = components(doc).mapNotNull { it.a("name") }
        assertFalse(names.any { it.endsWith("DiagnosticsReceiver") })
        assertFalse(names.any { it.endsWith("SystemForegroundService") })
        // MainActivity yalnızca MAIN/LAUNCHER filtresi taşır (başka dış giriş yok).
        val main = exported.getValue("$pkg.ui.MainActivity")
        val actions = (0 until main.getElementsByTagName("action").length)
            .map { (main.getElementsByTagName("action").item(it) as Element).a("name") }
        assertEquals(listOf("android.intent.action.MAIN"), actions)
    }

    @Test
    fun debug_exportedComponentSet_isReleaseSetPlusToolingActivities() {
        val doc = merged("debug")
        val exported = components(doc).filter { it.a("exported") == "true" }.mapNotNull { it.a("name") }.toSet()
        val release = merged("release").let { d -> components(d).filter { it.a("exported") == "true" }.mapNotNull { it.a("name") }.toSet() }
        assertEquals(release + debugOnly, exported)
    }

    @Test
    fun permissionSet_isExactInBothVariants_noNewPermission() {
        val expected = setOf(
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WAKE_LOCK",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "$pkg.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
        for (variant in listOf("debug", "release")) {
            val doc = merged(variant)
            val declared = listOf("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m").flatMap { doc.all(it) }
                .mapNotNull { it.a("name") }
            assertEquals("$variant: izin kümesi", expected, declared.toSet())
            assertEquals("$variant: yinelenen izin", declared.size, declared.toSet().size)
            assertFalse(declared.any { it.contains("INTERNET") || it.contains("NETWORK") })
        }
    }

    @Test
    fun startupProvider_releaseKeepsThreeInitializersNoEmoji_notExported() {
        val doc = merged("release")
        val provider = doc.all("provider").single()
        assertEquals("androidx.startup.InitializationProvider", provider.a("name"))
        assertEquals("false", provider.a("exported"))
        assertEquals("$pkg.androidx-startup", provider.a("authorities"))
        val metas = provider.getElementsByTagName("meta-data")
        val values = (0 until metas.length).associate {
            val e = metas.item(it) as Element
            e.a("name")!! to e.a("value")
        }
        assertEquals(3, values.size)
        // androidx.startup meta-data değeri "androidx.startup" olmalı (initializer olarak tanınması için).
        assertTrue(values.values.all { it == "androidx.startup" })
        assertFalse(values.containsKey("androidx.emoji2.text.EmojiCompatInitializer"))
    }

    // ---- R8 çıktıları ----

    private fun seeds(): Set<String> {
        val f = File(mappingDir, "seeds.txt")
        assumeTrue("assembleRelease çalışmadı: seeds.txt yok", f.isFile)
        return f.readLines().map { it.trim() }.toSet()
    }

    @Test
    fun seeds_keepOrtCoreClassesAndTheirMembers() {
        val seeds = seeds()
        for (cls in listOf("OrtSession", "OrtEnvironment", "OrtException", "OnnxTensor", "OnnxValue", "OrtSession\$SessionOptions")) {
            assertTrue("seeds.txt: ai.onnxruntime.$cls yok", "ai.onnxruntime.$cls" in seeds)
        }
        // JNI üyeleri ad ile arar: sınıf yalnızca sınıf olarak değil, üyeleriyle de korunmalı.
        assertTrue(seeds.any { it.startsWith("ai.onnxruntime.OrtSession: ") })
        assertTrue(seeds.any { it.startsWith("ai.onnxruntime.OnnxTensor: ") })
        assertTrue(seeds.any { it.startsWith("ai.onnxruntime.OrtEnvironment: ") })
    }

    @Test
    fun mapping_ortClassesKeepNamesAndCountIsPreserved() {
        val mapping = File(mappingDir, "mapping.txt")
        assumeTrue("assembleRelease çalışmadı: mapping.txt yok", mapping.isFile)
        val seeds = seeds()
        val line = Regex("^(ai\\.onnxruntime\\.[^ ]+) -> ([^ ]+):$")
        val ort = mapping.useLines { seq -> seq.mapNotNull { line.find(it)?.destructured }.map { it.component1() to it.component2() }.toList() }
        // R8'in ürettiği sentetik lambda sınıfları (JNI ile ilgisi yok) yeniden adlandırılabilir; diğerleri aynı adda kalmalı.
        val real = ort.filter { !it.first.contains("ExternalSyntheticLambda") }
        val renamed = real.filter { it.first != it.second }
        assertTrue("ORT sınıfları yeniden adlandırıldı: $renamed", renamed.isEmpty())
        // Gevşek regresyon alt sınırı (ölçüm: 79 gerçek sınıf; AAR sürümü değişirse bilinçli güncellenir).
        assertTrue("ORT sınıf sayısı düştü: ${real.size}", real.size >= 70)
        // seeds'teki her ORT sınıfı mapping'te hayatta.
        val classSeeds = seeds.filter { it.startsWith("ai.onnxruntime.") && !it.contains(": ") && !it.contains(" ") }
        val mapped = real.map { it.first }.toSet()
        assertTrue("seeds ORT sınıfı mapping'te yok: ${classSeeds - mapped}", mapped.containsAll(classSeeds))
    }

    @Test
    fun usage_noOrtClassRemovedByR8() {
        val usage = File(mappingDir, "usage.txt")
        assumeTrue("assembleRelease çalışmadı: usage.txt yok", usage.isFile)
        // usage.txt kaldırılan kodu listeler; -keep class ai.onnxruntime.** { *; } ile hiçbiri listelenmemeli.
        val removed = usage.useLines { seq -> seq.filter { it.startsWith("ai.onnxruntime.") }.take(5).toList() }
        assertTrue("R8 ORT kodu sildi: $removed", removed.isEmpty())
    }

    @Test
    fun mapping_reflectedAndManifestClassesKeepTheirNames() {
        val mapping = File(mappingDir, "mapping.txt")
        assumeTrue("assembleRelease çalışmadı: mapping.txt yok", mapping.isFile)
        val identity = mapping.useLines { seq ->
            seq.filter { it.endsWith(":") && !it.startsWith(" ") && it.contains(" -> ") }
                .map { it.removeSuffix(":").split(" -> ") }
                .filter { it[0] == it[1] }.map { it[0] }.toSet()
        }
        val expected = listOf(
            "com.ktu.aigaleri.work.IndexWorker",
            "com.ktu.aigaleri.data.AiGaleriDatabase_Impl",
            "androidx.work.impl.WorkDatabase_Impl",
            "com.ktu.aigaleri.ui.MainActivity",
            "androidx.work.impl.background.systemjob.SystemJobService",
            "androidx.work.impl.background.systemalarm.RescheduleReceiver",
            "androidx.work.impl.utils.ForceStopRunnable\$BroadcastReceiver",
            "androidx.profileinstaller.ProfileInstallReceiver",
            "androidx.room.MultiInstanceInvalidationService",
            "androidx.startup.InitializationProvider",
        )
        for (c in expected) assertTrue("mapping.txt: $c adı değişmiş veya sınıf silinmiş", c in identity)
    }

    @Test
    fun seeds_keepStartupInitializersReflectedByInitializationProvider() {
        val seeds = seeds()
        // InitializationProvider, meta-data'daki sınıfları Class.forName ile açar: yeniden adlandırılırsa açılış çöker.
        for (c in listOf(
            "androidx.work.WorkManagerInitializer",
            "androidx.lifecycle.ProcessLifecycleInitializer",
            "androidx.profileinstaller.ProfileInstallerInitializer",
        )) assertTrue("seeds.txt: $c yok", c in seeds)
        // Bilinen durum (hata değil): EmojiCompatInitializer ve ORT TelemetryInitializer manifestten çıkarıldı ama
        // kütüphanelerin kendi consumer kuralları sınıfı seeds'te tutar (ölü kod, dex'te kalır). Bu yüzden seeds'te
        // yokluk aranmaz; çalışma zamanında yalnızca manifest (merged_manifest testleri) belirleyicidir.
        assertFalse(seeds.any { it.contains("DiagnosticsReceiver") && !it.contains(": ") })
    }

    // ---- Kaynak küçültme ----

    private fun appStringNames(): List<String> {
        val f = File(appDir, "src/main/res/values/strings.xml")
        assertTrue(f.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val nl = doc.getElementsByTagName("string")
        return (0 until nl.length).map { (nl.item(it) as Element).getAttribute("name") }
    }

    @Test
    fun resourceShrinking_keepsEveryAppString() {
        val res = File(mappingDir, "resources.txt")
        assumeTrue("assembleRelease çalışmadı: resources.txt yok", res.isFile)
        val names = appStringNames()
        assertTrue("strings.xml boş görünüyor", names.size >= 50)
        val lines = res.readLines().filter { it.startsWith("string:") }
        assertTrue("resources.txt içinde string kaydı yok", lines.size > names.size)
        val kept = lines.filter { !it.contains("is not reachable") }
            .mapNotNull { Regex("^string:([^:]+):").find(it)?.groupValues?.get(1) }.toSet()
        val dropped = names.filter { it !in kept }
        assertTrue("kaynak küçültme uygulama dizesini sildi: $dropped", dropped.isEmpty())
        // Her dizenin kimliği benzersiz ve kaydı tek (kopya ad yok).
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun resourceShrinking_removesUnusedLibraryStrings_proofShrinkingActuallyRan() {
        val res = File(mappingDir, "resources.txt")
        assumeTrue("assembleRelease çalışmadı: resources.txt yok", res.isFile)
        assertTrue(res.useLines { seq -> seq.any { it.startsWith("string:") && it.endsWith("is not reachable.") } })
    }

    // ---- Release APK bütçesi ----

    @Test
    fun releaseApk_hasSingleDexUnderBudgetAndNoSecondaryDex() {
        assumeTrue("assembleRelease çalışmadı: release APK yok", apk.isFile)
        ZipFile(apk).use { zip ->
            val dex = zip.entries().asSequence().filter { Regex("classes\\d*\\.dex").matches(it.name) }.toList()
            assertEquals("release APK'da tek classes.dex beklenir: ${dex.map { it.name }}", listOf("classes.dex"), dex.map { it.name })
            // Ölçüm: ~2,7 MB (küçültmesiz 24,6 MB). 8 MB gevşek üst sınır: R8 devre dışı kalırsa kırılır.
            assertTrue("classes.dex ${dex.single().size} bayt >= 8 MB", dex.single().size < 8L * 1024 * 1024)
        }
    }

    @Test
    fun releaseApk_hasNoNetworkPermissionOrEmojiInitializerStrings_andKeepsNativeOrtLibs() {
        assumeTrue("assembleRelease çalışmadı: release APK yok", apk.isFile)
        ZipFile(apk).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            // JNI kitaplıkları paketlenmiş (arm64-v8a); ORT çalışması bunlara bağlı.
            assertTrue("lib/arm64-v8a/libonnxruntime.so yok", "lib/arm64-v8a/libonnxruntime.so" in names)
            assertTrue("lib/arm64-v8a/libonnxruntime4j_jni.so yok", "lib/arm64-v8a/libonnxruntime4j_jni.so" in names)
            assertTrue("AndroidManifest.xml yok", "AndroidManifest.xml" in names)
            // Manifest ikili (AXML) biçimdedir; izin adı UTF-16/UTF-8 dizi havuzunda düz metin olarak aranır.
            val manifest = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
            val utf16 = String(manifest, Charsets.UTF_16LE)
            val latin = String(manifest, Charsets.ISO_8859_1)
            for (bad in listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "EmojiCompatInitializer", "TelemetryInitializer", "DiagnosticsReceiver")) {
                assertFalse("release APK manifestinde $bad var", utf16.contains(bad) || latin.contains(bad))
            }
            // Beklenen izinlerden biri bulunur: ayrıştırma boş çıktıyla yanlış geçmesin.
            assertTrue(utf16.contains("android.permission.READ_MEDIA_IMAGES"))
        }
    }

    @Test
    fun releaseApk_releaseManifestPackageStringsAreUtf16_sanity() {
        // Yardımcı kontrol: AXML dizi havuzu UTF-16 ise düz arama anlamlıdır; değilse (UTF-8) latin yolu devreye girer.
        assumeTrue("assembleRelease çalışmadı: release APK yok", apk.isFile)
        ZipFile(apk).use { zip ->
            val b = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
            val hay = String(b, Charsets.UTF_16LE) + String(b, Charsets.ISO_8859_1)
            assertTrue(hay.contains(pkg))
        }
    }
}
