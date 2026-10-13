package com.ktu.aigaleri

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * T-029 (QA): ReleaseAbiConfigTest'in kapsamadığı kenar durumlar. Çalışma dizini mobile/app.
 * Gradle dosyası yorumlardan arındırılır (// satır sonu ve blok yorumu); ABI'yi başka yoldan (productFlavors, splits,
 * ndk'yı defaultConfig'e taşıma, kaldırma/sıfırlama, packaging dışlaması) değiştiren her şey yakalanır.
 * APK testleri APK yoksa atlanır (assembleRelease/assembleDebug sonrası anlamlı).
 */
class ReleaseAbiQaTest {
    private val appDir = File(System.getProperty("user.dir")!!)
    private val rawGradle = File(appDir, "build.gradle.kts").readText()

    /** Blok yorumları (/* ... */) ve satır yorumları (//) atılmış kaynak; dize içindeki "//" (URL) bu projede yok. */
    private val code: String = rawGradle.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private fun block(src: String, name: String, from: Int = 0): String {
        val start = Regex("\\b$name\\s*\\{").find(src, from)?.range?.last ?: return ""
        var depth = 1
        var i = start + 1
        while (i < src.length && depth > 0) {
            when (src[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return src.substring(start + 1, i - 1)
    }

    @Test
    fun abiConfig_existsExactlyOnce_andOnlyForArm64() {
        val all = Regex("abiFilters?[^\\n]*").findAll(code).map { it.value.trim() }.toList()
        assertEquals("abiFilters tam bir kez geçmeli: $all", 1, all.size)
        assertEquals("abiFilters+=\"arm64-v8a\"}", all.single().replace(" ", ""))
        // ABI adı içeren her dize yalnızca arm64-v8a olmalı (kodda başka ABI adı yok)
        for (abi in listOf("armeabi", "x86", "mips", "riscv")) assertFalse("kodda $abi var", code.contains(abi))
        assertEquals(1, Regex("\"arm64-v8a\"").findAll(code).count())
    }

    @Test
    fun abiFilter_isNotRemovedResetOrOverriddenAfterwards() {
        // abiFilters -= / clear() / set() / addAll / setOf ile sonradan genişletme/silme yok.
        assertFalse(Regex("abiFilters\\s*(-=|\\.clear|\\.remove|\\.addAll|\\.add\\(|\\.set|=)").containsMatchIn(code.replace("+=", "")))
        assertFalse(code.contains("abiFilters.clear"))
        // '+=' dışında atama biçimi yok ('=' olmadan +=): tek eşleşme "abiFilters +="
        assertTrue(Regex("abiFilters\\s*\\+=\\s*\"arm64-v8a\"").containsMatchIn(code))
        // ndk, defaultConfig'e (debug'ı da etkiler) taşınmamış.
        assertFalse("defaultConfig içinde ndk", block(code, "defaultConfig").contains("ndk"))
        assertFalse(Regex("defaultConfig\\s*\\.\\s*ndk").containsMatchIn(code))
    }

    @Test
    fun noFlavorsSplitsOrPackagingThatAlterAbis() {
        assertFalse(Regex("\\bproductFlavors\\b").containsMatchIn(code))
        assertFalse(Regex("\\bflavorDimensions\\b").containsMatchIn(code))
        assertFalse(Regex("\\bsplits\\b").containsMatchIn(code))
        assertFalse(code.contains("isUniversalApk"))
        assertFalse(code.contains("externalNativeBuild"))
        // packaging/jniLibs ile .so dışlama (ör. excludes += "**/x86/**") ABI'yi gizlice değiştirir.
        assertFalse(Regex("\\bjniLibs\\b").containsMatchIn(code))
        assertFalse(code.contains("excludes"))
        assertFalse(code.contains("useLegacyPackaging")) // T-024 yasağı
    }

    @Test
    fun releaseBlock_isTheOnlyOneHoldingNdk_andDebugBlockIsAbsentOrClean() {
        val buildTypes = block(code, "buildTypes")
        assertTrue(buildTypes.isNotEmpty())
        val release = block(buildTypes, "release")
        assertTrue(release.contains("ndk"))
        // ndk, buildTypes dışındaki hiçbir yerde yok (defaultConfig, android, üst düzey)
        val withoutRelease = code.replace(release, "")
        assertFalse("ndk release dışında geçiyor", Regex("\\bndk\\b").containsMatchIn(withoutRelease))
        // debug bloğu varsa filtre içermez (şu an tanımlı değil)
        assertFalse(block(buildTypes, "debug").contains("abiFilters"))
    }

    @Test
    fun commentedOutAbiLines_doNotCountAsConfig_stripperWorks() {
        // Yorum ayıklayıcının kendi doğrulaması: gerçek dosyada release yorumunda "x86" geçer (kasıtlı), kodda geçmez.
        assertTrue("yorum satırı bekleniyordu", rawGradle.contains("x86"))
        assertFalse(code.contains("x86"))
        val synthetic = "release { // ndk { abiFilters += \"x86\" }\n /* abiFilters += \"armeabi-v7a\" */ }"
        val stripped = synthetic.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lines().joinToString("\n") { it.substringBefore("//") }
        assertFalse(stripped.contains("abiFilters"))
    }

    @Test
    fun otherGradleFiles_doNotInjectAbi() {
        val root = appDir.parentFile!!
        val files = listOf("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle/libs.versions.toml")
        for (name in files) {
            val f = File(root, name)
            if (!f.isFile) continue
            val text = f.readText()
            assertFalse("$name: abiFilters", text.contains("abiFilters"))
            assertFalse("$name: android.injected.build.abi", text.contains("android.injected.build.abi"))
            assertFalse("$name: splits", Regex("\\bsplits\\b").containsMatchIn(text))
        }
        assertFalse(File(root, "buildSrc").exists())
    }

    private fun entries(apk: File): List<String> = ZipFile(apk).use { z -> z.entries().asSequence().map { it.name }.toList() }

    @Test
    fun releaseApk_everyLibEntryIsUnderArm64_andOnnxRuntimeNativeLibsPresent() {
        val apk = File(appDir, "build/outputs/apk/release/app-release-unsigned.apk")
        assumeTrue("release APK yok", apk.isFile)
        val libs = entries(apk).filter { it.startsWith("lib/") && !it.endsWith("/") }
        assertTrue(libs.isNotEmpty())
        // .so olmayan lib/ girdisi (ör. gdbserver) de yalnızca arm64-v8a altında olmalı.
        for (e in libs) assertTrue(e, e.startsWith("lib/arm64-v8a/"))
        // Boşaltma yanlışlıkla her şeyi atmamış olmalı: ONNX Runtime yerel kütüphaneleri duruyor.
        assertTrue(libs.contains("lib/arm64-v8a/libonnxruntime.so"))
        assertTrue(libs.contains("lib/arm64-v8a/libonnxruntime4j_jni.so"))
        // Başka ABI dizini adı hiçbir yerde yok.
        val others = listOf("armeabi-v7a", "x86", "x86_64", "armeabi", "mips", "riscv64")
        for (e in entries(apk)) for (o in others) assertFalse("$e ($o)", e.startsWith("lib/$o/"))
    }

    @Test
    fun debugApk_hasSameNativeLibSetInEveryAbi() {
        val apk = File(appDir, "build/outputs/apk/debug/app-debug.apk")
        assumeTrue("debug APK yok", apk.isFile)
        val perAbi = entries(apk).filter { it.startsWith("lib/") && it.endsWith(".so") }
            .groupBy({ it.split('/')[1] }, { it.substringAfterLast('/') }).mapValues { it.value.toSet() }
        assertEquals(setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"), perAbi.keys)
        val arm = perAbi.getValue("arm64-v8a")
        assertTrue(arm.contains("libonnxruntime.so"))
        for ((abi, set) in perAbi) assertEquals(abi, arm, set)
    }

    @Test
    fun releaseApk_isSmallerThanDebugApk_byAtLeast90MB() {
        val rel = File(appDir, "build/outputs/apk/release/app-release-unsigned.apk")
        val dbg = File(appDir, "build/outputs/apk/debug/app-debug.apk")
        assumeTrue("APK yok", rel.isFile && dbg.isFile)
        // Belge: -102 MB (yalnızca ABI farkı değil, minify de katkı verir); ABI daraltma geri alınırsa fark kapanır.
        assertTrue("fark ${(dbg.length() - rel.length()) / 1_000_000} MB", dbg.length() - rel.length() > 90_000_000L)
    }
}
