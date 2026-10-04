package com.ktu.aigaleri.ui

import android.Manifest
import android.os.Build

/** Galeri izninin ekrandaki durumu. */
enum class PermissionState {
    /** Henüz istenmedi (veya süreç yeniden başladı); "izin ver" butonu gösterilir. */
    NotRequested,
    Granted,

    /** Reddedildi ama sistem diyaloğu tekrar gösterilebilir. */
    Denied,

    /** Reddedildi ve sistem diyaloğu artık gösterilmez; yalnızca ayarlardan verilebilir. */
    PermanentlyDenied,
}

/** İzin akışının saf (Android'e bağımsız test edilebilir) mantığı. */
object GalleryPermission {
    /** Android 13+ (API 33) READ_MEDIA_IMAGES, altında READ_EXTERNAL_STORAGE. */
    fun requiredPermission(sdkInt: Int = Build.VERSION.SDK_INT): String =
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    /**
     * Sistem izin diyaloğu sonucu. Reddedilmişse ve [shouldShowRationale] false ise sistem artık
     * diyalog göstermiyordur (kalıcı red); true ise tekrar istenebilir.
     */
    fun afterRequest(granted: Boolean, shouldShowRationale: Boolean): PermissionState = when {
        granted -> PermissionState.Granted
        shouldShowRationale -> PermissionState.Denied
        else -> PermissionState.PermanentlyDenied
    }

    /**
     * Ekran ön plana gelince (ör. ayarlardan dönünce) gerçek durumla uzlaştırır. İzin verilmişse
     * Granted; ayarlardan geri alınmışsa NotRequested; aksi halde mevcut red durumu korunur.
     */
    fun afterResume(current: PermissionState, granted: Boolean): PermissionState = when {
        granted -> PermissionState.Granted
        current == PermissionState.Granted -> PermissionState.NotRequested
        else -> current
    }
}
