package com.ktu.aigaleri.ui

import com.ktu.aigaleri.R
import com.ktu.aigaleri.ml.ModelManifest
import com.ktu.aigaleri.ui.index.IndexStatusUiState
import com.ktu.aigaleri.ui.index.IndexingBanner
import com.ktu.aigaleri.ui.index.formatLastRun
import com.ktu.aigaleri.ui.index.percentOf
import com.ktu.aigaleri.ui.index.statusHeadline
import com.ktu.aigaleri.ui.search.MAX_QUERY_LENGTH
import com.ktu.aigaleri.ui.search.SearchSuggestions
import com.ktu.aigaleri.ui.viewer.isValidPhotoId
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-022: PO tasarım analizindeki metin/durum kararları. Uygulanacak metinler vardır; uygulanmayacak tasarım
 * aracı ögeleri ve yanlış içerik metinleri ASLA bulunmaz.
 */
class DesignContentTest {
    private val appDir = File(System.getProperty("user.dir")!!)
    private val strings = File(appDir, "src/main/res/values/strings.xml").readText()
    private val uiDir = File(appDir, "src/main/java/com/ktu/aigaleri/ui")

    private fun text(name: String): String {
        val m = Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(strings)
        assertNotNull("string yok: $name", m)
        return m!!.groupValues[1].replace("&gt;", ">").replace("&lt;", "<")
    }

    private fun allStringValues(): List<Pair<String, String>> =
        Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(strings)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()

    private fun uiSources(): List<File> = uiDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    // ---- sonuç sayısı Türkçe biçim (T-034) ----

    @Test
    fun searchResultCount_usesFormatCount_regardlessOfDeviceLocale() {
        val template = text("search_result_count")
        assertTrue("sayı %s olmalı (cihaz yerelinde %d biçimlemesin)", template.contains("%s") && !template.contains("%d"))
        // Cihaz yerelinde (en-US dahil) biçimleme yapılsa bile metin Türkçe gruplamalı kalır.
        for (device in listOf(Locale.US, Locale.GERMANY, Locale.forLanguageTag("tr-TR"))) {
            fun render(n: Int) = String.format(device, template, formatCount(n))
            assertEquals("1.200 fotoğraf", render(1200))
            assertEquals("0 fotoğraf", render(0))
            assertEquals("1 fotoğraf", render(1))
            assertEquals("999 fotoğraf", render(999)) // gruplama sınırının altı
            assertEquals("1.000 fotoğraf", render(1000)) // gruplama sınırı
            assertEquals("123.456 fotoğraf", render(123_456))
        }
        // Kaynağı kullanan ekran sayıyı formatCount ile verir; ham Int vermez.
        val screen = File(uiDir, "search/SearchScreen.kt").readText()
        assertTrue(screen.contains("R.string.search_result_count, formatCount("))
    }

    // ---- uygulanacak içerik ----

    @Test
    fun suggestions_arePoApprovedList() {
        assertEquals(
            listOf("gün batımında deniz", "kırmızı araba", "doğum günü pastası", "parkta koşan köpek", "kahve ve kitap"),
            SearchSuggestions.ALL,
        )
        assertEquals(SearchSuggestions.ALL.size, SearchSuggestions.ALL.toSet().size)
        for (s in SearchSuggestions.ALL) assertTrue(s.isNotBlank() && s.length <= MAX_QUERY_LENGTH && s == s.trim())
    }

    @Test
    fun searchStateTexts_matchPoDecisions() {
        assertEquals("Aranıyor...", text("search_loading"))
        assertEquals("Eşleşen fotoğraf bulunamadı.", text("search_empty"))
        assertEquals("İndeksleme durumunu gör", text("search_empty_index_link"))
        assertEquals("Arama şu an yapılamadı.", text("search_error"))
        assertEquals("Tekrar dene", text("search_error_retry"))
        assertEquals("Lütfen bir şey yaz.", text("search_invalid_empty"))
        assertEquals("En yakın eşleşmeler", text("search_results_title"))
        assertEquals("%s fotoğraf", text("search_result_count"))
        assertEquals("Ne arıyorsun? Örn. plajda gülen çocuk", text("search_hint"))
        assertEquals("Bu istemle ara", text("search_suggestion_action")) // T-036: görünür "Arama önerileri" başlığı yok, çipler eylem etiketi taşır
        assertEquals("Detay", text("indexing_banner_detail"))
        // Hata ve boş durum açıklaması çelişmez ve birbirinden farklıdır.
        assertTrue(text("search_empty") != text("search_error"))
    }

    @Test
    fun viewerAndIndexAndPermissionTexts_matchPoDecisions() {
        assertEquals("Fotoğraf bulunamadı", text("viewer_not_found"))
        assertEquals("Dosya cihazdan silinmiş veya yeri değiştirilmiş olabilir.", text("viewer_not_found_detail"))
        assertEquals("Geri dön", text("viewer_back_action"))
        assertEquals("İndeks durumu", text("index_status_open"))
        assertEquals("Yeniden indeksle", text("reindex_button"))
        assertEquals("Yeniden dene", text("retry_button"))
        assertEquals("öğe işlendi", text("index_processed_unit"))
        assertEquals("Fotoğraflarınıza erişim izni", text("permission_title_needed"))
        assertEquals("İzin ver", text("permission_grant_button"))
        assertEquals("Ayarları aç", text("permission_open_settings_button"))
        assertEquals("Nasıl etkinleştirilir?", text("permission_howto_title"))
    }

    @Test
    fun permissionText_hasNoFaces_andKeepsPrivacySentence() {
        val permissionTexts = allStringValues().filter { it.first.startsWith("permission_") }.map { it.second }
        assertTrue(permissionTexts.isNotEmpty())
        for (t in permissionTexts) assertFalse("izin metninde 'yüz' ifadesi: $t", t.lowercase(Locale.forLanguageTag("tr-TR")).contains("yüz"))
        val privacy = "Fotoğraflarınız ve arama verileriniz asla cihazınızdan dışarı çıkmaz; sunucu, bulut veya hesap bağlantısı yoktur."
        assertTrue(text("permission_needed_message").contains(privacy))
        assertTrue(text("permission_denied_message").contains(privacy))
        // Kalıcı red metni mevcut sözleşmeyi korur (Ayarlar, kalıcı, izin).
        val perm = text("permission_permanently_denied_message")
        assertTrue(perm.contains("Ayarlar") && perm.contains("kalıcı") && perm.contains("izin"))
    }

    @Test
    fun bannerTexts_t016_areKeptVerbatim() {
        assertEquals("İndeksleniyor (%1\$s / %2\$s); sonuçlar eksik olabilir. Ayrıntı için dokun.", text("indexing_progress"))
        assertEquals("İndeksleniyor; sonuçlar eksik olabilir. Ayrıntı için dokun.", text("indexing_running"))
        assertEquals("İndeksleme sırada; birazdan ya da cihaz uygun olunca başlayacak. Sonuçlar eksik olabilir.", text("indexing_waiting"))
        assertEquals("Durum: Çalışıyor", text("index_run_state_running"))
        assertEquals("Durum: Beklemede; birazdan ya da cihaz uygun olunca başlayacak", text("index_run_state_waiting"))
    }

    // ---- uygulanmayacak / yanlış içerik ----

    @Test
    fun forbiddenDesignToolAndWrongContentTexts_neverAppear() {
        val forbidden = listOf(
            // yanlış içerik
            "MobileCLIP", "48,2", "şarjdayken", "boştayken", "yapay zeka", "AI ", "Kaldırıldı",
            // tasarım aracı ögeleri
            "Görünüm durumu", "Durum simülasyonu", "Canlı önizleme", "Sistem test", "Koyu Mod", "Açık Mod", "Koyu Tema", "Açık Tema",
            // uygulanmayan ögeler
            "Doğa", "Portre", "Mimari", "Şimdi değil", "Daha sonra", "Eşleşme terimi", "Albums", "On device", "Albüm",
        )
        // AppIcons.kt: yol verisi sayı dizileri içerir ("48,2" gibi eşleşmeler metin değildir), taranmaz.
        val haystacks = listOf("strings.xml" to strings) +
            uiSources().filter { it.name != "AppIcons.kt" }.map { it.name to it.readText() }
        for ((name, content) in haystacks) {
            for (f in forbidden) assertFalse("$name yasak metin içeriyor: $f", content.contains(f))
        }
        // İngilizce yer tutucular yalnızca tam değişmez dize olarak aranır (SearchContent gibi tanımlayıcılar serbest).
        for (name in uiSources().filter { it.name != "AppIcons.kt" }) {
            val t = name.readText()
            for (w in listOf("Search", "Photos", "Albums", "Detail", "On device")) {
                assertFalse("${name.name}: \"$w\" yer tutucusu", t.contains("\"$w\""))
            }
        }
        for ((key, value) in allStringValues()) {
            for (w in listOf("Search", "Photos", "Albums", "Detail", "On device")) assertFalse("$key: $w", value == w)
        }
    }

    @Test
    fun noUnimplementedControls_inSources() {
        val all = uiSources().joinToString("\n") { it.readText() }
        // Alt gezinti, profil avatarı, oynat/önceki/sonraki, tema anahtarı yok.
        for (word in listOf("NavigationBar", "BottomAppBar", "NavigationBarItem", "PlayArrow", "SkipNext", "SkipPrevious", "Avatar", "AccountCircle")) {
            assertFalse("uygulanmayan öğe: $word", Regex("\\b$word\\b").containsMatchIn(all)) // tam sözcük: isAppearanceLightNavigationBars serbest
        }
        // Yeni bağımlılık: ikon kütüphanesi eklenmedi.
        val gradle = File(appDir, "build.gradle.kts").readText()
        assertFalse(gradle.contains("material-icons") || gradle.contains("materialIcons") || gradle.contains("icons"))
    }

    @Test
    fun indexModelName_isCorrect_andMatchesManifest() {
        val name = text("index_detail_model_value")
        assertTrue(name.contains("CLIP ViT-B/32"))
        assertTrue(name.contains("int8"))
        assertTrue(ModelManifest.MODEL_VERSION.startsWith("clip-b32"))
        assertTrue(ModelManifest.MODEL_VERSION.contains("qint8") && ModelManifest.MODEL_VERSION.contains("int8"))
        // Dizin boyutu için veri kaynağı yok: satır yok.
        assertFalse(strings.contains("Dizin boyutu"))
    }

    // ---- durum mantığı (yalnızca sunum eşlemeleri) ----

    @Test
    fun statusHeadline_mapsRunStateAndData() {
        val partial = IndexStatusUiState.Data(total = 10, processed = 4, lastRunAt = 1L)
        val full = IndexStatusUiState.Data(total = 10, processed = 10, lastRunAt = 1L)
        val emptyGallery = IndexStatusUiState.Data(total = 0, processed = 0, lastRunAt = 1L)
        assertNull(statusHeadline(IndexStatusUiState.Loading, IndexRunState.IDLE))
        assertNull(statusHeadline(IndexStatusUiState.Error, IndexRunState.RUNNING))
        assertEquals(R.string.index_never_run, statusHeadline(IndexStatusUiState.NeverRun, IndexRunState.IDLE))
        assertEquals(R.string.index_headline_running, statusHeadline(IndexStatusUiState.NeverRun, IndexRunState.RUNNING))
        assertEquals(R.string.index_headline_running, statusHeadline(partial, IndexRunState.RUNNING))
        assertEquals(R.string.index_headline_waiting, statusHeadline(partial, IndexRunState.WAITING))
        assertEquals(R.string.index_headline_partial, statusHeadline(partial, IndexRunState.IDLE))
        assertEquals(R.string.index_headline_done, statusHeadline(full, IndexRunState.IDLE))
        assertEquals(R.string.index_headline_done, statusHeadline(emptyGallery, IndexRunState.IDLE))
        // Tamamlanmış sayılar çalışırken "güncel" denmez.
        assertEquals(R.string.index_headline_running, statusHeadline(full, IndexRunState.RUNNING))
    }

    @Test
    fun percentOf_roundsDown_andClamps() {
        assertEquals(28, percentOf(340f / 1200f))
        assertEquals(100, percentOf(1f))
        assertEquals(99, percentOf(0.999f))
        assertEquals(0, percentOf(0f))
        assertEquals(0, percentOf(-1f))
        assertEquals(100, percentOf(2f))
    }

    @Test
    fun formatLastRun_isTurkish() {
        val text = formatLastRun(1_790_000_000_000L, Locale.forLanguageTag("tr-TR"))
        assertTrue(text, text.contains("2026"))
        assertTrue(text, text.any { it.isLetter() }) // ay adı kısaltması
    }

    @Test
    fun bannerFraction_onlyWithValidCounts() {
        assertEquals(0.5f, IndexingBanner(5, 10).fraction!!, 0.001f)
        assertNull(IndexingBanner(null, null).fraction)
        assertNull(IndexingBanner(null, null, waiting = true).fraction)
        assertNull(IndexingBanner(3, 0).fraction)
        assertEquals(1f, IndexingBanner(11, 10).fraction!!, 0.001f)
    }

    @Test
    fun viewerPhotoId_validation() {
        assertFalse(isValidPhotoId(null))
        assertFalse(isValidPhotoId(0))
        assertFalse(isValidPhotoId(-5))
        assertTrue(isValidPhotoId(1))
    }

    @Test
    fun touchTargetsAndLiveRegions_keptInSources() {
        val banner = File(uiDir, "index/IndexingBanner.kt").readText()
        assertTrue(banner.contains("liveRegion = LiveRegionMode.Polite"))
        assertTrue(banner.contains("onClickLabel = stringResource(R.string.indexing_banner_open_label)"))
        assertTrue(banner.contains("MinTouchTarget"))
        val status = File(uiDir, "index/IndexStatusScreen.kt").readText()
        assertTrue(status.contains("liveRegion = LiveRegionMode.Polite") && status.contains("TAG_RUN_STATE"))
    }
}
