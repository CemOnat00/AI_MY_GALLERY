package com.ktu.aigaleri

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** T-001 kabul kriterleri: manifest, minSdk, bağımlılıklar, wrapper. Çalışma dizini mobile/app. */
class ProjectConfigTest {
    private val appDir = File(System.getProperty("user.dir")!!)
    private val mobileDir = appDir.parentFile!!

    private fun manifest(file: File) = file.readText()

    private fun sourceManifest() = manifest(File(appDir, "src/main/AndroidManifest.xml"))

    private fun usesPermissions(xml: String) =
        Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toList()

    @Test
    fun sourceManifest_hasNoInternetPermission() {
        val perms = usesPermissions(sourceManifest())
        assertFalse(perms.toString(), perms.any { it.endsWith("INTERNET") })
    }

    @Test
    fun sourceManifest_allowBackupIsFalse() {
        assertTrue(sourceManifest().contains("android:allowBackup=\"false\""))
    }

    @Test
    fun mergedManifest_hasNoInternetAndNoBackup_whenAvailable() {
        val merged = File(appDir, "build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml")
            .takeIf { it.exists() }
            ?: File(appDir, "build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml")
        assumeTrue("merged manifest yok (assemble henüz çalışmadı)", merged.exists())
        val xml = manifest(merged)
        assertFalse(usesPermissions(xml).any { it.endsWith("INTERNET") })
        assertTrue(xml.contains("android:allowBackup=\"false\""))
    }

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
