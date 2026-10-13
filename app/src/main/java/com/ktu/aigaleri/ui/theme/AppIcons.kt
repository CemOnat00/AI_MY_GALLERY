package com.ktu.aigaleri.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Tasarımda kullanılan Material simgeleri (24dp, Apache-2.0). `material-icons-core` bağımlılığı eklenmemesi için
 * yalnızca gereken yol verisi burada tutulur; simge renkleri çağıran tarafta (Icon tint) verilir.
 */
object AppIcons {
    private fun icon(name: String, pathData: String, autoMirror: Boolean = false): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = autoMirror,
        ).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black)).build()

    internal val PATHS: Map<String, String> = mapOf(
        "Search" to "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z",
        "Close" to "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z",
        "ArrowBack" to "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z",
        "ArrowForward" to "M12,4l-1.41,1.41L16.17,11H4v2h12.17l-5.58,5.59L12,20l8,-8z",
        "ChevronRight" to "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z",
        "Check" to "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z",
        "Refresh" to "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z",
        "Info" to "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6zM13,9h-2L11,7h2v2z",
        "ErrorOutline" to "M11,15h2v2h-2zM11,7h2v6h-2zM11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8z",
        "Lock" to "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM15.1,8H8.9V6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z",
        "PhotoLibrary" to "M22,16V4c0,-1.1 -0.9,-2 -2,-2H8c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2zM11,12l2.03,2.71L16,11l4,5H8l3,-4zM2,6v14c0,1.1 0.9,2 2,2h14v-2H4V6H2z",
    )

    val Search: ImageVector by lazy { icon("Search", PATHS.getValue("Search")) }
    val Close: ImageVector by lazy { icon("Close", PATHS.getValue("Close")) }
    val ArrowBack: ImageVector by lazy { icon("ArrowBack", PATHS.getValue("ArrowBack"), autoMirror = true) }
    val ArrowForward: ImageVector by lazy { icon("ArrowForward", PATHS.getValue("ArrowForward"), autoMirror = true) }
    val ChevronRight: ImageVector by lazy { icon("ChevronRight", PATHS.getValue("ChevronRight"), autoMirror = true) }
    val Check: ImageVector by lazy { icon("Check", PATHS.getValue("Check")) }
    val Refresh: ImageVector by lazy { icon("Refresh", PATHS.getValue("Refresh")) }
    val Info: ImageVector by lazy { icon("Info", PATHS.getValue("Info")) }
    val ErrorOutline: ImageVector by lazy { icon("ErrorOutline", PATHS.getValue("ErrorOutline")) }
    val Lock: ImageVector by lazy { icon("Lock", PATHS.getValue("Lock")) }
    val PhotoLibrary: ImageVector by lazy { icon("PhotoLibrary", PATHS.getValue("PhotoLibrary")) }
}
