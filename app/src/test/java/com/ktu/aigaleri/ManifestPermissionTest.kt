package com.ktu.aigaleri

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/** T-003: manifest'te galeri izinleri doğru ve INTERNET yok. Çalışma dizini mobile/app. */
class ManifestPermissionTest {
    private val xml = File(System.getProperty("user.dir")!!, "src/main/AndroidManifest.xml").readText()

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
        assertEquals(true, Regex("android:maxSdkVersion=\"32\"").containsMatchIn(t!!))
    }

    @Test
    fun noInternetPermission() {
        assertFalse(Regex("<uses-permission[^>]*INTERNET").containsMatchIn(xml))
    }
}
