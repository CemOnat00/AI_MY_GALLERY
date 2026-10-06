package com.ktu.aigaleri

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-003: manifest'te galeri izinleri doğru ve INTERNET yok. Çalışma dizini mobile/app. */
class ManifestPermissionTest {
    // Gradle çalışma dizini mobile/app'tir (diğer testler gibi user.dir); IDE'den mobile/ kökünden
    // çalıştırılırsa da bulunsun diye app/ alt dizinine geri düşülür.
    private val xml = run {
        val root = File(System.getProperty("user.dir")!!)
        listOf(File(root, "src/main/AndroidManifest.xml"), File(root, "app/src/main/AndroidManifest.xml"))
            .first { it.exists() }
            .readText()
    }

    private fun tag(name: String): String? =
        Regex("<uses-permission[^>]*android:name=\"android.permission.$name\"[^>]*/>").find(xml)?.value

    @Test
    fun readMediaImages_declared() {
        assertNotNull(tag("READ_MEDIA_IMAGES"))
    }

    @Test
    fun readExternalStorage_limitedToApi32() {
        val t = tag("READ_EXTERNAL_STORAGE")
        assertNotNull(t)
        assertTrue(Regex("android:maxSdkVersion=\"32\"").containsMatchIn(t!!))
    }

    @Test
    fun noInternetPermission() {
        // tools:node="remove" girdileri (ORT AAR'ının eklediği izni kaldırır, T-007) izin DEĞİL, kaldırmadır.
        val declared = Regex("<uses-permission[^>]*INTERNET[^>]*>").findAll(xml).map { it.value }
            .filterNot { it.contains("tools:node=\"remove\"") }.toList()
        assertTrue(declared.toString(), declared.isEmpty())
    }
}
