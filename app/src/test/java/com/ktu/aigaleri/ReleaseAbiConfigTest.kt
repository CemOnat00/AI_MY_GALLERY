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
 * Çalışma dizini app/ (Gradle proje kökünün altında).
 */
class ReleaseAbiConfigTest {
    private val appDir = File(System.getProperty("user.dir")!!)

    /** `buildTypes { ... }` içindeki `name { ... }` bloğunun gövdesi (süslü parantez dengeli). */
    private fun buildTypeBlock(gradle: String, name: String): String {
        val buildTypes = Regex("buildTypes\\s*\\{").find(gradle)!!
        return block(gradle, name, buildTypes.range.last)
    }

    /** `from` konumundan sonraki ilk `name { ... }` gövdesi; blok yoksa boş. */
    private fun block(gradle: String, name: String, from: Int = 0): String {
        val start = Regex("\\b$name\\s*\\{").find(gradle, from)?.range?.last ?: return ""
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
        // Yorum satırları sayılmasın: önce `//` kısmı atılır.
        val code = gradle.lines().joinToString("\n") { it.substringBefore("//") }
        // abiFilters yalnızca release bloğunda; defaultConfig/debug/splits'te yok (debug tüm ABI).
        assertEquals(1, Regex("abiFilters").findAll(code).count())
        assertFalse(block(code, "defaultConfig").contains("abiFilters"))
        // Şu an `debug { }` bloğu tanımlı değil (boş döner); ileride eklenirse abiFilters içermemeli.
        assertFalse(buildTypeBlock(code, "debug").contains("abiFilters"))
        assertFalse(Regex("\\bsplits\\s*\\{").containsMatchIn(code))
    }

    private fun libAbis(apk: File): Set<String> = ZipFile(apk).use { zip ->
        zip.entries().asSequence().map { it.name }
            .filter { it.startsWith("lib/") && it.endsWith(".so") }
            .map { it.split('/')[1] }.toSet()
    }

    @Test
    fun releaseApk_containsOnlyArm64Libs() {
        val apk = File(appDir, "build/outputs/apk/release/app-release-unsigned.apk")
        assumeTrue("release APK yok (bayat APK uyarısı: yalnızca assembleRelease sonrası geçerli)", apk.isFile)
        assertEquals(setOf("arm64-v8a"), libAbis(apk))
    }

    @Test
    fun debugApk_keepsAllAbis() {
        val apk = File(appDir, "build/outputs/apk/debug/app-debug.apk")
        assumeTrue("debug APK yok (bayat APK uyarısı: yalnızca assembleDebug sonrası geçerli)", apk.isFile)
        assertEquals(setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"), libAbis(apk))
    }
}
