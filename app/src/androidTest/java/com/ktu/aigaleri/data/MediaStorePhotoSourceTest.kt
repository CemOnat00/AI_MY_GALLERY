package com.ktu.aigaleri.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ktu.aigaleri.ui.GalleryPermission
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MediaStore cihaz testi: izin verilmiş cihazda sorgu hata vermez, sayı ve liste tutarlıdır,
 * kayıtlar content URI'si ve pozitif kimlik taşır.
 *
 * Not: emülatör/cihaz yok, çalıştırılmadı; yalnızca derlendiği doğrulandı.
 */
@RunWith(AndroidJUnit4::class)
class MediaStorePhotoSourceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun grantPermission() {
        instrumentation.uiAutomation
            .grantRuntimePermission(context.packageName, GalleryPermission.requiredPermission())
    }

    @Test
    fun loadPhotos_isConsistentWithCount() = runBlocking {
        val source = MediaStorePhotoSource(context.contentResolver)
        val photos = source.loadPhotos()
        assertEquals(photos.size, source.count())
        assertTrue(photos.all { it.id > 0 && it.uri.startsWith("content://") && it.uri.endsWith("/${it.id}") })
    }
}
