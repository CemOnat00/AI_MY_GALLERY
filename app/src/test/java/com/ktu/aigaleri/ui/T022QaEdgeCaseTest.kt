package com.ktu.aigaleri.ui

import com.ktu.aigaleri.domain.SearchResult
import com.ktu.aigaleri.ui.index.IndexStatusUiState
import com.ktu.aigaleri.ui.index.IndexingBanner
import com.ktu.aigaleri.ui.index.indexingBanner
import com.ktu.aigaleri.ui.index.formatLastRun
import com.ktu.aigaleri.ui.index.percentOf
import com.ktu.aigaleri.ui.search.SearchStatus
import com.ktu.aigaleri.ui.viewer.isValidPhotoId
import com.ktu.aigaleri.ui.viewer.isViewerNotFound
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-022 qa: sayı/tarih biçimi, viewer rotası, dateTakenOf, isViewerNotFound, banner sınırları, manifest/kaynak değişmezleri. */
class T022QaEdgeCaseTest {
    private val tr = Locale.forLanguageTag("tr-TR")
    private val appDir = File(System.getProperty("user.dir")!!)

    // ---- formatCount ----

    @Test
    fun formatCount_extremes_turkish() {
        assertEquals("2.147.483.647", formatCount(Int.MAX_VALUE, tr))
        assertEquals("-2.147.483.648", formatCount(Int.MIN_VALUE, tr).replace('−', '-'))
        assertEquals("-5", formatCount(-5, tr).replace('−', '-'))
        assertEquals("999", formatCount(999, tr))
        assertEquals("1.000", formatCount(1000, tr))
        assertEquals("100.000", formatCount(100_000, tr)) // çok büyük galeri
        assertEquals("1.000.000", formatCount(1_000_000, tr))
    }

    @Test
    fun formatCount_defaultIsTurkish_regardlessOfJvmDefaultLocale() {
        val saved = Locale.getDefault()
        try {
            for (l in listOf(Locale.US, Locale.GERMANY, Locale.FRANCE, Locale.ENGLISH)) {
                Locale.setDefault(l)
                assertEquals("varsayılan yerel: $l", "1.200", formatCount(1200))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun formatCount_otherLocalesDiffer_soTrIsExplicit() {
        assertEquals("1,200", formatCount(1200, Locale.US))
        assertNotEquals(formatCount(1200, Locale.US), formatCount(1200, tr))
        assertEquals("1.200", formatCount(1200, Locale.GERMANY))
        // Fransızca binlik ayracı boşluk türevi: noktalı Türkçe biçimle aynı olmamalı.
        assertNotEquals("1.200", formatCount(1200, Locale.FRANCE))
    }

    @Test
    fun formatCount_hasNoFractionOrPercent() {
        for (v in listOf(0, 1, 340, 1200, Int.MAX_VALUE)) {
            val s = formatCount(v, tr)
            assertFalse(s, s.contains(',') || s.contains('%'))
        }
    }

    // ---- tarih biçimi ----

    @Test
    fun formatDateTime_boundaries_doNotThrow() {
        assertTrue(formatDateTime(0L, tr).contains("1970"))
        assertTrue(formatDateTime(Long.MAX_VALUE, tr).isNotBlank())
        assertTrue(formatDateTime(Long.MIN_VALUE, tr).isNotBlank())
        assertTrue(formatDateTime(1L, tr).isNotBlank())
    }

    @Test
    fun formatDateTime_turkishMonthAbbreviation_notEnglish() {
        // 15 Mart 2026 12:00 UTC ve 15 Ekim 2026: ay kısaltmasında Türkçe harf/ad (Mar/Eki), İngilizce "Oct" değil.
        val oct = formatDateTime(1_792_065_600_000L, tr) // 2026-10-15
        assertTrue(oct, oct.contains("2026"))
        assertFalse(oct, oct.contains("Oct"))
        assertTrue(oct, oct.contains("Eki"))
        assertEquals(formatDateTime(1_792_065_600_000L), oct)
    }

    @Test
    fun formatLastRun_sameAsFormatDateTime_forBoundaries() {
        for (t in listOf(0L, 1L, 1_790_000_000_000L, Long.MAX_VALUE)) {
            assertEquals(formatDateTime(t, tr), formatLastRun(t, tr))
        }
    }

    // ---- rota ----

    @Test
    fun viewerRoute_dateBoundaries() {
        assertEquals("viewer/5", Routes.viewer(5, Long.MIN_VALUE))
        assertEquals("viewer/5", Routes.viewer(5, -2L))
        assertEquals("viewer/5?dateTaken=9223372036854775807", Routes.viewer(5, Long.MAX_VALUE))
        assertEquals("viewer/9223372036854775807?dateTaken=1", Routes.viewer(Long.MAX_VALUE, 1L))
        // Geçersiz kimlikler de rotada bozulmadan taşınır; ekran "bulunamadı" gösterir.
        assertEquals("viewer/0", Routes.viewer(0))
        assertEquals("viewer/-3?dateTaken=7", Routes.viewer(-3, 7L))
    }

    @Test
    fun viewerRoute_templateAndBuiltRoutesParseBack() {
        // JVM'de NavController yok: şablondan türetilen ifadeyle (photoId, isteğe bağlı dateTaken) ayrıştırma.
        val regex = Regex("""^viewer/(-?\d+)(?:\?dateTaken=(-?\d+))?$""")
        val cases = listOf(
            Triple(5L, null, "viewer/5"),
            Triple(5L, 0L, "viewer/5?dateTaken=0"),
            Triple(42L, 1_790_000_000_000L, "viewer/42?dateTaken=1790000000000"),
            Triple(1L, Long.MAX_VALUE, "viewer/1?dateTaken=${Long.MAX_VALUE}"),
        )
        for ((id, date, expected) in cases) {
            val route = Routes.viewer(id, date)
            assertEquals(expected, route)
            val m = regex.matchEntire(route)!!
            assertEquals(id, m.groupValues[1].toLong())
            val parsedDate = m.groupValues[2].takeIf { it.isNotEmpty() }?.toLong() ?: Routes.NO_DATE
            assertEquals(date, Routes.dateTakenOrNull(parsedDate))
        }
        // Şablon: yol argümanı + isteğe bağlı sorgu argümanı, varsayılan NO_DATE.
        assertEquals("viewer/{photoId}?dateTaken={dateTaken}", Routes.VIEWER)
        assertEquals("photoId", Routes.PHOTO_ARG)
        assertEquals("dateTaken", Routes.DATE_ARG)
        assertTrue(Routes.VIEWER.contains("{${Routes.PHOTO_ARG}}") && Routes.VIEWER.contains("{${Routes.DATE_ARG}}"))
    }

    @Test
    fun navHostSource_declaresLongArgsAndNoDateDefault() {
        val src = File(appDir, "src/main/java/com/ktu/aigaleri/ui/MainScreen.kt").readText()
        assertTrue(src.contains("navArgument(Routes.PHOTO_ARG) { type = NavType.LongType }"))
        assertTrue(src.contains("navArgument(Routes.DATE_ARG)"))
        assertTrue(src.contains("defaultValue = Routes.NO_DATE"))
        assertTrue(src.contains("Routes.dateTakenOrNull(entry.arguments?.getLong(Routes.DATE_ARG))"))
    }

    @Test
    fun dateTakenOrNull_boundaries() {
        assertNull(Routes.dateTakenOrNull(Long.MIN_VALUE))
        assertNull(Routes.dateTakenOrNull(-2L))
        assertEquals(Long.MAX_VALUE, Routes.dateTakenOrNull(Long.MAX_VALUE))
        assertEquals(0L, Routes.dateTakenOrNull(0L))
    }

    // ---- dateTakenOf ----

    @Test
    fun dateTakenOf_edgeCases() {
        assertNull(dateTakenOf(SearchStatus.Success(emptyList()), 1L)) // boş galeri/sonuç
        assertNull(dateTakenOf(SearchStatus.Error, 1L))
        assertNull(dateTakenOf(SearchStatus.InvalidQuery(com.ktu.aigaleri.domain.InvalidQueryException.Reason.EMPTY), 1L))
        val results = List(100_000) { SearchResult(it + 1L, "u$it", 0.1f, it * 10L) }
        val big = SearchStatus.Success(results)
        assertEquals(0L, dateTakenOf(big, 1L))
        assertEquals(999_990L, dateTakenOf(big, 100_000L))
        assertNull(dateTakenOf(big, 100_001L)) // eşleşmeyen kimlik
        assertNull(dateTakenOf(big, 0L))
        assertNull(dateTakenOf(big, -1L))
        // Yinelenen kimlik: ilk eşleşme.
        val dup = SearchStatus.Success(listOf(SearchResult(9L, "a", 1f, 1L), SearchResult(9L, "b", 0.5f, 2L)))
        assertEquals(1L, dateTakenOf(dup, 9L))
        // Negatif/anlamsız tarih sonuçtan gelirse rota onu taşımaz.
        val neg = SearchStatus.Success(listOf(SearchResult(3L, "a", 1f, -50L)))
        assertEquals(-50L, dateTakenOf(neg, 3L))
        assertEquals("viewer/3", Routes.viewer(3L, dateTakenOf(neg, 3L)))
    }

    // ---- isViewerNotFound ----

    @Test
    fun isViewerNotFound_matrix() {
        for (id in listOf(null, 0L, -1L, Long.MIN_VALUE)) {
            assertTrue("id=$id", isViewerNotFound(id, false))
            assertTrue("id=$id", isViewerNotFound(id, true))
            assertFalse(isValidPhotoId(id))
        }
        for (id in listOf(1L, 987_654_321L, Long.MAX_VALUE)) {
            assertFalse("id=$id", isViewerNotFound(id, false))
            assertTrue("id=$id", isViewerNotFound(id, true))
            assertTrue(isValidPhotoId(id))
        }
    }

    // ---- banner / durum ekranı sınırları ----

    @Test
    fun indexingBanner_boundaries() {
        fun data(p: Int, t: Int) = IndexStatusUiState.Data(total = t, processed = p, lastRunAt = null)
        assertNull(indexingBanner(data(1, 2), IndexRunState.IDLE))
        assertEquals(IndexingBanner(0, 1), indexingBanner(data(0, 1), IndexRunState.RUNNING))
        assertEquals(IndexingBanner(null, null), indexingBanner(data(-1, 10), IndexRunState.RUNNING))
        assertEquals(IndexingBanner(null, null), indexingBanner(data(10, 10), IndexRunState.RUNNING))
        assertEquals(IndexingBanner(null, null), indexingBanner(data(0, 0), IndexRunState.RUNNING))
        assertEquals(IndexingBanner(null, null), indexingBanner(IndexStatusUiState.Loading, IndexRunState.RUNNING))
        assertEquals(IndexingBanner(null, null), indexingBanner(IndexStatusUiState.Error, IndexRunState.RUNNING))
        assertEquals(IndexingBanner(null, null, true), indexingBanner(IndexStatusUiState.Error, IndexRunState.WAITING))
        val huge = indexingBanner(data(Int.MAX_VALUE - 1, Int.MAX_VALUE), IndexRunState.RUNNING)!!
        assertEquals(Int.MAX_VALUE - 1, huge.processed)
        assertTrue(huge.fraction!! in 0f..1f)
        assertEquals("2.147.483.646 / 2.147.483.647", "${formatCount(huge.processed!!, tr)} / ${formatCount(huge.total!!, tr)}")
        // Banner örneği: "340 / 1.200".
        assertEquals("340 / 1.200", "${formatCount(340, tr)} / ${formatCount(1200, tr)}")
    }

    @Test
    fun percentOf_nan_andInfinity_doNotThrow() {
        assertEquals(100, percentOf(Float.POSITIVE_INFINITY))
        assertEquals(0, percentOf(Float.NEGATIVE_INFINITY))
        percentOf(Float.NaN) // çökmemeli
        assertEquals(28, percentOf(340f / 1200f))
    }

    // ---- manifest / izin değişmezleri ----

    private val manifest = File(appDir, "src/main/AndroidManifest.xml").readText()

    @Test
    fun manifest_noInternet_andOnlyGalleryPermissionsDeclared() {
        val declared = Regex("<uses-permission[^>]*?/>", RegexOption.DOT_MATCHES_ALL).findAll(manifest).map { it.value }
            .filterNot { it.contains("tools:node=\"remove\"") }
            .map { Regex("android:name=\"android.permission.([A-Z_]+)\"").find(it)!!.groupValues[1] }.toSet()
        assertEquals(setOf("READ_MEDIA_IMAGES", "READ_EXTERNAL_STORAGE"), declared)
        assertFalse(declared.contains("INTERNET"))
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
    }

    @Test
    fun themeChange_addedNoNetworkOrAnalyticsDependency() {
        val gradle = File(appDir, "build.gradle.kts").readText().lowercase(Locale.ROOT)
        for (bad in listOf("okhttp", "retrofit", "firebase", "crashlytics", "analytics", "ktor", "volley", "coil-network", "material-icons")) {
            assertFalse("yasak bağımlılık: $bad", gradle.contains(bad))
        }
    }

    @Test
    fun uiSources_haveNoNetworkApi() {
        val ui = File(appDir, "src/main/java/com/ktu/aigaleri/ui")
        for (f in ui.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val t = f.readText()
            for (bad in listOf("HttpURLConnection", "java.net.URL(", "okhttp3", "android.net.http", "ConnectivityManager")) {
                assertFalse("${f.name}: $bad", t.contains(bad))
            }
        }
    }
}
