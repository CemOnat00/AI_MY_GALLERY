package com.ktu.aigaleri.data

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ktu.aigaleri.ui.GalleryPermission
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MediaStore cihaz testi: MediaStore.Images'a bilinen bir kayıt (kimlik, DATE_TAKEN) eklenir ve
 * [MediaStorePhotoSource] ile okunduğu doğrulanır; boş emülatörde de anlamlıdır. Test sonunda kayıt silinir.
 * API 29+ gerekir (kendi eklediğimiz kayıt için ek yazma izni istemez; altında test atlanır).
 *
 * Not: emülatör/cihaz yok, çalıştırılmadı; yalnızca derlendiği doğrulandı.
 */
@RunWith(AndroidJUnit4::class)
class MediaStorePhotoSourceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val knownTakenMs = 1_600_000_000_000L
    private var insertedUri: Uri? = null

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        instrumentation.uiAutomation
            .grantRuntimePermission(context.packageName, GalleryPermission.requiredPermission())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "aigaleri_test_${System.nanoTime()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, knownTakenMs)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AiGaleriTest")
        }
        insertedUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }

    @After
    fun tearDown() {
        insertedUri?.let { context.contentResolver.delete(it, null, null) }
    }

    @Test
    fun insertedRecord_isReadWithIdUriAndDateTaken() = runBlocking {
        val inserted = insertedUri
        assertNotNull("MediaStore kaydı eklenemedi", inserted)
        val id = ContentUris.parseId(inserted!!)
        val source = MediaStorePhotoSource(context.contentResolver)

        val photos = source.loadPhotos()
        val found = photos.singleOrNull { it.id == id }

        assertNotNull("eklenen kayıt listede olmalı", found)
        assertEquals(inserted.toString(), found!!.uri)
        assertEquals(knownTakenMs, found.dateTaken)
        assertEquals(photos.size, source.count())
        assertTrue(photos.all { it.id > 0 && it.uri.startsWith("content://") })
    }
}
