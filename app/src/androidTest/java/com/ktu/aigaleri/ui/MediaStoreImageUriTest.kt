package com.ktu.aigaleri.ui

import android.content.ContentUris
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.ktu.aigaleri.ui.viewer.PhotoViewerScreen
import com.ktu.aigaleri.ui.viewer.mediaStoreImageUri
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** android.net.Uri JVM'de çalışmadığı için cihaz testi; emülatör yok, yalnızca DERLENİR. */
class MediaStoreImageUriTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun uri_isCollectionSlashId() {
        val uri = mediaStoreImageUri(42L)
        assertEquals("content", uri.scheme)
        assertEquals(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 42L), uri)
        assertEquals(42L, ContentUris.parseId(uri))
    }

    @Test
    fun uri_longMaxValue_roundTrips() {
        val uri = mediaStoreImageUri(Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, ContentUris.parseId(uri))
        assertEquals("9223372036854775807", uri.lastPathSegment)
    }

    @Test
    fun uri_zeroAndNegative_doNotThrow() {
        // Fonksiyon kendisi korumaz; ekran <= 0 için URI üretmez (aşağıdaki test).
        assertEquals("0", mediaStoreImageUri(0L).lastPathSegment)
        assertEquals("-1", mediaStoreImageUri(-1L).lastPathSegment)
    }

    private fun assertNotFoundFor(id: Long?) {
        rule.setContent { PhotoViewerScreen(photoId = id, onBack = {}) }
        rule.onNodeWithText("Fotoğraf bulunamadı", substring = true).assertExists()
    }

    @Test fun viewer_nullId_showsNotFound() = assertNotFoundFor(null)

    @Test fun viewer_zeroId_showsNotFound() = assertNotFoundFor(0L)

    @Test fun viewer_negativeId_showsNotFound() = assertNotFoundFor(-7L)
}
