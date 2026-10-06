package com.ktu.aigaleri

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-001 kabul kriterleri: manifest, minSdk, bağımlılıklar, wrapper. Çalışma dizini mobile/app. */
class ProjectConfigTest {
    private val appDir = File(System.getProperty("user.dir")!!)
    private val mobileDir = appDir.parentFile!!

    private fun manifest(file: File) = file.readText()

    private fun sourceManifest() = manifest(File(appDir, "src/main/AndroidManifest.xml"))

    private fun usesPermissions(xml: String) =
        // tools:node="remove" girdileri (ORT AAR'ının eklediği izinleri kaldırır, T-007) izin sayılmaz.
        Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"[^>]*>").findAll(xml)
            .filterNot { it.value.contains("tools:node=\"remove\"") }
            .map { it.groupValues[1] }.toList()

    @Test
    fun sourceManifest_hasNoInternetPermission() {
        val perms = usesPermissions(sourceManifest())
        assertFalse(perms.toString(), perms.any { it.endsWith("INTERNET") })
    }

    @Test
    fun sourceManifest_allowBackupIsFalse() {
        assertTrue(sourceManifest().contains("android:allowBackup=\"false\""))
    }

    // Birleşik manifest denetimi (debug ve release, sessiz atlamasız): MergedManifestTest.

    @Test
    fun buildScript_minSdk26_andPackage() {
        val gradle = File(appDir, "build.gradle.kts").readText()
        assertTrue(Regex("minSdk\\s*=\\s*26\\b").containsMatchIn(gradle))
        assertTrue(gradle.contains("namespace = \"com.ktu.aigaleri\""))
        assertTrue(gradle.contains("applicationId = \"com.ktu.aigaleri\""))
    }

    @Test
    fun buildScript_hasComposeAndRoomDependencies() {
        val gradle = File(appDir, "build.gradle.kts").readText()
        assertTrue(gradle.contains("libs.androidx.compose.material3"))
        assertTrue(gradle.contains("libs.androidx.room.runtime"))
        assertTrue(gradle.contains("libs.androidx.room.compiler"))
        assertTrue(gradle.contains("kotlin.compose"))
    }

    @Test
    fun gradleWrapper_isPresent() {
        assertTrue(File(mobileDir, "gradlew").exists())
        assertTrue(File(mobileDir, "gradlew.bat").exists())
        assertTrue(File(mobileDir, "gradle/wrapper/gradle-wrapper.jar").exists())
        assertTrue(File(mobileDir, "gradle/wrapper/gradle-wrapper.properties").exists())
    }

    @Test
    fun applicationId_matchesPackage() {
        assertEquals("com.ktu.aigaleri", BuildConfig.APPLICATION_ID)
    }
}
