package com.ktu.aigaleri.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-003 QA: izin akışı tam geçiş tablosu, ayarlar yönlendirme metni, kaynak taraması (log yok, IO). */
class GalleryPermissionEdgeCaseTest {
    private val appDir = File(System.getProperty("user.dir")!!).let { if (File(it, "src/main").exists()) it else File(it, "app") }

    private fun readMain(path: String) = File(appDir, "src/main/$path").readText()

    @Test
    fun afterRequest_grantedWinsEvenWithRationaleFlag() {
        assertEquals(PermissionState.Granted, GalleryPermission.afterRequest(true, true))
    }

    @Test
    fun afterResume_grantedFromEveryState_isGranted() {
        PermissionState.values().forEach {
            assertEquals(PermissionState.Granted, GalleryPermission.afterResume(it, true))
        }
    }

    @Test
    fun afterResume_notGranted_onlyGrantedIsReset() {
        PermissionState.values().forEach {
            val expected = if (it == PermissionState.Granted) PermissionState.NotRequested else it
            assertEquals(expected, GalleryPermission.afterResume(it, false))
        }
    }

    @Test
    fun requiredPermission_boundaryAndExtremeSdkValues() {
        assertEquals("android.permission.READ_MEDIA_IMAGES", GalleryPermission.requiredPermission(33))
        assertEquals("android.permission.READ_EXTERNAL_STORAGE", GalleryPermission.requiredPermission(32))
        assertEquals("android.permission.READ_EXTERNAL_STORAGE", GalleryPermission.requiredPermission(0))
        assertEquals("android.permission.READ_MEDIA_IMAGES", GalleryPermission.requiredPermission(Int.MAX_VALUE))
    }

    @Test
    fun permanentDenialMessage_pointsToSettings_withTurkishCharsIntact() {
        val strings = readMain("res/values/strings.xml")
        val msg = Regex("<string name=\"permission_permanently_denied_message\">(.*?)</string>").find(strings)!!.groupValues[1]
        assertTrue(msg, msg.contains("Ayarlar"))
        assertTrue(msg, msg.contains("kalıcı"))
        assertTrue(msg.contains("izin", ignoreCase = true))
        val button = Regex("<string name=\"permission_open_settings_button\">(.*?)</string>").find(strings)!!.groupValues[1]
        assertTrue(button.contains("Ayarları"))
    }

    @Test
    fun allPermissionStatesHaveUserFacingStrings() {
        val strings = readMain("res/values/strings.xml")
        listOf(
            "permission_needed_message", "permission_denied_message", "permission_permanently_denied_message",
            "permission_grant_button", "permission_open_settings_button", "photo_count_error", "photo_count",
        ).forEach { assertTrue("$it eksik", strings.contains("name=\"$it\"")) }
    }

    @Test
    fun mainSources_doNotLog() {
        File(appDir, "src/main/java").walkTopDown().filter { it.extension == "kt" }.forEach {
            val text = it.readText()
            assertFalse("${it.name}: android.util.Log", text.contains("android.util.Log"))
            assertFalse("${it.name}: Log.", Regex("\\bLog\\.[dievw]\\(").containsMatchIn(text))
            assertFalse("${it.name}: println", Regex("\\bprintln\\(").containsMatchIn(text))
        }
    }

    @Test
    fun mediaStoreSource_runsOnIoDispatcherByDefault_andNeverOnMainThread() {
        val text = readMain("java/com/ktu/aigaleri/data/MediaStorePhotoSource.kt")
        assertTrue(text.contains("dispatcher: CoroutineDispatcher = Dispatchers.IO"))
        // loadPhotos, count, loadIds ve loadByIds (T-006) dahil dört okuma.
        assertEquals(4, Regex("withContext\\(dispatcher\\)").findAll(text).count())
        assertFalse(text.contains("Dispatchers.Main"))
    }

    @Test
    fun mediaStoreSource_queryHasNoUserInputOrSelection() {
        val text = readMain("java/com/ktu/aigaleri/data/MediaStorePhotoSource.kt")
        assertTrue(text.contains("query(collection, projection, null, null, null)"))
    }
}
