package com.ktu.aigaleri.ui

import androidx.compose.ui.graphics.vector.addPathNodes
import com.ktu.aigaleri.domain.SearchResult
import com.ktu.aigaleri.ui.search.SearchStatus
import com.ktu.aigaleri.ui.theme.AppIcons
import com.ktu.aigaleri.ui.viewer.isValidPhotoId
import com.ktu.aigaleri.ui.viewer.isViewerNotFound
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-022 reviewer turu 1: viewer rotası/tarihi, sayı-tarih biçimi, "bulunamadı" mantığı, simge yolları. */
class ViewerRouteAndFormatTest {
    private val tr = Locale.forLanguageTag("tr-TR")

    @Test
    fun viewerRoute_buildsOptionalDateArgument() {
        assertEquals("viewer/5", Routes.viewer(5))
        assertEquals("viewer/5", Routes.viewer(5, null))
        assertEquals("viewer/5", Routes.viewer(5, -1L))
        assertEquals("viewer/5?dateTaken=1790000000000", Routes.viewer(5, 1_790_000_000_000L))
        assertEquals("viewer/5?dateTaken=0", Routes.viewer(5, 0L))
        assertEquals("viewer/{photoId}?dateTaken={dateTaken}", Routes.VIEWER)
    }

    @Test
    fun routeDateArgument_defaultMeansUnknown() {
        assertEquals(-1L, Routes.NO_DATE)
        assertNull(Routes.dateTakenOrNull(Routes.NO_DATE))
        assertNull(Routes.dateTakenOrNull(null))
        assertEquals(0L, Routes.dateTakenOrNull(0L))
        assertEquals(123L, Routes.dateTakenOrNull(123L))
    }

    @Test
    fun dateTakenOf_findsResultInSuccessOnly() {
        val results = listOf(SearchResult(1L, "u1", 0.9f, 111L), SearchResult(2L, "u2", 0.5f, null))
        val success = SearchStatus.Success(results)
        assertEquals(111L, dateTakenOf(success, 1L))
        assertNull(dateTakenOf(success, 2L)) // tarihi bilinmeyen
        assertNull(dateTakenOf(success, 3L)) // listede yok
        assertNull(dateTakenOf(SearchStatus.Idle, 1L))
        assertNull(dateTakenOf(SearchStatus.Empty, 1L))
        assertNull(dateTakenOf(SearchStatus.Loading, 1L))
    }

    @Test
    fun formatCount_groupsTurkishStyle_forBannerAndStatusScreen() {
        assertEquals("340", formatCount(340, tr))
        assertEquals("1.200", formatCount(1200, tr))
        assertEquals("12.345", formatCount(12345, tr))
        assertEquals("0", formatCount(0, tr))
        // Varsayılan yerel ayar cihazdan bağımsız Türkçedir.
        assertEquals("1.200", formatCount(1200))
    }

    @Test
    fun formatDateTime_isTurkish_andSharedWithLastRun() {
        val text = formatDateTime(1_790_000_000_000L, tr)
        assertTrue(text, text.contains("2026"))
        assertEquals(text, com.ktu.aigaleri.ui.index.formatLastRun(1_790_000_000_000L, tr))
    }

    @Test
    fun viewerNotFound_whenIdInvalidOrLoadFailed() {
        assertTrue(isViewerNotFound(null, false))
        assertTrue(isViewerNotFound(0, false))
        assertTrue(isViewerNotFound(-1, false))
        assertTrue(isViewerNotFound(7, true)) // dosya silinmiş/okunamıyor: onError
        assertFalse(isViewerNotFound(7, false))
        assertTrue(isValidPhotoId(7))
    }

    @Test
    fun appIcons_pathDataParsesToDrawableVectors() {
        assertTrue(AppIcons.PATHS.size >= 11)
        for ((name, d) in AppIcons.PATHS) {
            val nodes = addPathNodes(d)
            assertTrue("$name yol düğümü yok", nodes.size > 2)
            val vector = when (name) {
                "Search" -> AppIcons.Search
                "Close" -> AppIcons.Close
                "ArrowBack" -> AppIcons.ArrowBack
                "ArrowForward" -> AppIcons.ArrowForward
                "ChevronRight" -> AppIcons.ChevronRight
                "Check" -> AppIcons.Check
                "Refresh" -> AppIcons.Refresh
                "Info" -> AppIcons.Info
                "ErrorOutline" -> AppIcons.ErrorOutline
                "Lock" -> AppIcons.Lock
                "PhotoLibrary" -> AppIcons.PhotoLibrary
                else -> error("tanımsız simge: $name")
            }
            assertEquals(24f, vector.viewportWidth, 0f)
            assertEquals(24f, vector.viewportHeight, 0f)
            assertEquals(name, vector.name)
        }
    }

    @Test
    fun sources_haveNoMagicAlphaOrZeroDp_andBannerDoesNotClipText() {
        val ui = File(System.getProperty("user.dir")!!, "src/main/java/com/ktu/aigaleri/ui")
        val banner = File(ui, "index/IndexingBanner.kt").readText()
        assertFalse("banner metni kırpılmamalı (büyük yazı)", banner.contains("maxLines") || banner.contains("TextOverflow"))
        for (f in listOf("search/SearchScreen.kt", "index/IndexStatusScreen.kt", "index/IndexingBanner.kt")) {
            val t = File(ui, f).readText()
            assertFalse("$f: 0.dp sihirli sayı", t.contains("0.dp"))
            assertFalse("$f: sabit alfa", Regex("""const val \w*ALPHA""").containsMatchIn(t))
        }
        val search = File(ui, "search/SearchScreen.kt").readText()
        assertTrue(search.contains("windowInsetsPadding(bottomInsets)")) // IME boşluğu
        assertTrue(search.contains("focusRequester")) // pill'e dokununca odak
        assertTrue(File(ui, "MainScreen.kt").readText().contains("fun MainContent("))
    }
}
