package com.ktu.aigaleri.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryPermissionTest {
    @Test
    fun requiredPermission_api33AndAbove_isReadMediaImages() {
        assertEquals("android.permission.READ_MEDIA_IMAGES", GalleryPermission.requiredPermission(33))
        assertEquals("android.permission.READ_MEDIA_IMAGES", GalleryPermission.requiredPermission(36))
    }

    @Test
    fun requiredPermission_below33_isReadExternalStorage() {
        assertEquals("android.permission.READ_EXTERNAL_STORAGE", GalleryPermission.requiredPermission(32))
        assertEquals("android.permission.READ_EXTERNAL_STORAGE", GalleryPermission.requiredPermission(26))
    }

    @Test
    fun afterRequest_cases() {
        assertEquals(PermissionState.Granted, GalleryPermission.afterRequest(true, false))
        assertEquals(PermissionState.Denied, GalleryPermission.afterRequest(false, true))
        assertEquals(PermissionState.PermanentlyDenied, GalleryPermission.afterRequest(false, false))
    }

    @Test
    fun afterResume_cases() {
        assertEquals(PermissionState.Granted, GalleryPermission.afterResume(PermissionState.PermanentlyDenied, true))
        assertEquals(PermissionState.NotRequested, GalleryPermission.afterResume(PermissionState.Granted, false))
        assertEquals(PermissionState.PermanentlyDenied, GalleryPermission.afterResume(PermissionState.PermanentlyDenied, false))
        assertEquals(PermissionState.Denied, GalleryPermission.afterResume(PermissionState.Denied, false))
        assertEquals(PermissionState.NotRequested, GalleryPermission.afterResume(PermissionState.NotRequested, false))
    }
}
