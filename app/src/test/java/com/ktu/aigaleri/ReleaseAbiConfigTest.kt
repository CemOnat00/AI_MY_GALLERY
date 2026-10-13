package com.ktu.aigaleri

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * T-029 (K-13): release yalnızca arm64-v8a; debug tüm ABI'lerde kalır.
 * APK testleri `assembleRelease`/`assembleDebug` sonrası anlamlıdır; APK yoksa atlanır.
 * Çalışma dizini mobile/app.
 */
class ReleaseAbiConfigTest {
    private val appDir = File(System.getProperty("user.dir")!!)

    /** `buildTypes { ... }` içindeki `name { ... }` bloğunun gövdesi (süslü parantez dengeli). */
    private fun buildTypeBlock(gradle: String, name: String): String {
        val buildTypes = Regex("buildTypes\\s*\\{").find(gradle)!!
        val start = Regex("\\b$name\\s*\\{").find(gradle, buildTypes.range.last)?.range?.last ?: return ""
        var depth = 1
        var i = start + 1
        while (i < gradle.length && depth > 0) {
            when (gradle[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return gradle.substring(start + 1, i - 1)
    }

    @Test
    fun gradle_releaseFiltersToArm64Only() {
        val gradle = File(appDir, "build.gradle.kts").readText()
        // Yorum satırları (ör. "x86 emülatör") ABI sızıntısı sayılmasın.
        val release = buildTypeBlock(gradle, "release").lines().joinToString("\n") { it.substringBefore("//") }
        assertTrue(Regex("ndk\\s*\\{\\s*abiFilters\\s*\\+=\\s*\"arm64-v8a\"\\s*\\}").containsMatchIn(release))
        // Başka ABI release'e sızmasın.
        assertFalse(release.contains("x86"))
        assertFalse(release.contains("armeabi"))
    }

    @Test
    fun gradle_debugAndDefaultConfigHaveNoAbiFilter() {
        val gradle = File(appDir, "build.gradle.kts").readText()
        // abiFilters yalnızca release bloğunda; debug/defaultConfig/splits'te yok (debug tüm ABI).
        assertEquals(1, Regex("abiFilters").findAll(gradle).count())
        assertFalse(buildTypeBlock(gradle, "debug").contains("abiFilters"))
        assertFalse(Regex("\\bsplits\\s*\\{").containsMatchIn(gradle))
    }

    private fun libAbis(apk: File): Set<String> = ZipFile(apk).use { zip ->
        zip.entries().asSequence().map { it.name }
            .filter { it.startsWith("lib/") && it.endsWith(".so") }
            .map { it.split('/')[1] }.toSet()
    }

    @Test
    fun releaseApk_containsOnlyArm64Libs() {
        val apk = File(appDir, "build/outputs/apk/release/app-release-unsigned.apk")
        assumeTrue("assembleRelease çalışmadı: release APK yok", apk.isFile)
        assertEquals(setOf("arm64-v8a"), libAbis(apk))
    }

    @Test
    fun debugApk_keepsAllAbis() {
        val apk = File(appDir, "build/outputs/apk/debug/app-debug.apk")
        assumeTrue("assembleDebug çalışmadı: debug APK yok", apk.isFile)
        assertEquals(setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"), libAbis(apk))
    }
}
