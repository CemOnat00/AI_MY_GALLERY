package com.ktu.aigaleri.ui.license

import androidx.annotation.StringRes
import com.ktu.aigaleri.R
import java.io.IOException
import java.io.InputStream

/** Üçüncü taraf bir bileşen (assets/licenses/components.txt kaydı). */
data class LicenseComponent(
    val name: String,
    val version: String,
    val license: String,
    /** Lisans metni dosyası (assets/licenses/ altında). */
    val textFile: String,
    val sourceUrl: String,
    val note: String,
)

/** Ekranda gösterilen tam lisans metni; [paragraphs] ham metnin satır sonlarından arındırılmış hâlidir. */
data class LicenseText(@StringRes val titleRes: Int, val fileName: String, val paragraphs: List<String>)

/** Lisanslar ekranının bütün içeriği; hepsi assets/licenses/ altındaki düz metin dosyalarından okunur. */
data class LicenseDocument(
    val appLicense: List<String>,
    val modifiedNotice: List<String>,
    val components: List<LicenseComponent>,
    val texts: List<LicenseText>,
)

/**
 * Lisanslar içeriğinin okunması ve biçimlenmesi (Android'den bağımsız, JVM'de test edilir). Uzun metinler kodda DEĞİL,
 * `app/src/main/assets/licenses/` altındaki dosyalardadır; uygulamanın kendi lisansı yalnızca [APP_LICENSE_FILE]
 * değiştirilerek güncellenir.
 */
object LicenseCatalog {
    const val ASSET_DIR = "licenses"
    const val APP_LICENSE_FILE = "app-license.txt"
    const val MODIFIED_NOTICE_FILE = "modified-notice.txt"
    const val COMPONENTS_FILE = "components.txt"

    /** Ekranda tam metni gösterilen lisans dosyaları ve başlıkları (sıra ekran sırasıdır). */
    val TEXT_FILES: List<Pair<String, Int>> = listOf(
        "apache-2.0.txt" to R.string.license_text_apache,
        "mit-openai-clip.txt" to R.string.license_text_mit_openai,
        "mit-onnxruntime.txt" to R.string.license_text_mit_onnxruntime,
    )

    private val KEYS = setOf("ad", "surum", "lisans", "metin", "kaynak", "not")

    /**
     * components.txt'i ayrıştırır: kayıtlar boş satırla ayrılır, `#` ile başlayan satırlar yorumdur, satır biçimi
     * "anahtar: değer". Eksik/bilinmeyen anahtar ya da çift anahtar [IllegalArgumentException] verir.
     */
    fun parseComponents(raw: String): List<LicenseComponent> {
        val records = mutableListOf<Map<String, String>>()
        var current = linkedMapOf<String, String>()
        fun flush() {
            if (current.isNotEmpty()) records += current
            current = linkedMapOf()
        }
        for (line in raw.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> flush()
                trimmed.startsWith("#") -> Unit
                else -> {
                    val colon = trimmed.indexOf(':')
                    require(colon > 0) { "Geçersiz satır biçimi" }
                    val key = trimmed.substring(0, colon).trim()
                    require(key in KEYS) { "Bilinmeyen anahtar: $key" }
                    require(key !in current) { "Çift anahtar: $key" }
                    current[key] = trimmed.substring(colon + 1).trim()
                }
            }
        }
        flush()
        return records.map { r ->
            fun field(key: String) = requireNotNull(r[key]?.takeIf { it.isNotEmpty() }) { "Eksik anahtar: $key" }
            LicenseComponent(
                name = field("ad"),
                version = field("surum"),
                license = field("lisans"),
                textFile = field("metin"),
                sourceUrl = field("kaynak"),
                note = field("not"),
            )
        }
    }

    /**
     * Sert satır sonlu düz metni ekran genişliğine uyacak paragraflara çevirir: ardışık dolu satırlar tek paragraf olur
     * (kenar boşlukları atılır, satır sonu boşluğa dönüşür); boş satır paragraf ayırır. Sözcükler ve sırası DEĞİŞMEZ.
     */
    fun paragraphs(raw: String): List<String> {
        val result = mutableListOf<String>()
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) result += buf.toString()
            buf.setLength(0)
        }
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.isEmpty()) {
                flush()
            } else {
                if (buf.isNotEmpty()) buf.append(' ')
                buf.append(t)
            }
        }
        flush()
        return result
    }

    /** [open] adı verilen dosyanın akışını açar (assets ya da test için dosya sistemi). Hata [IOException]/[IllegalArgumentException]. */
    fun load(open: (fileName: String) -> InputStream): LicenseDocument {
        fun read(name: String): String = open(name).use { it.readBytes().toString(Charsets.UTF_8) }
        val components = parseComponents(read(COMPONENTS_FILE))
        require(components.isNotEmpty()) { "Bileşen listesi boş" }
        val texts = TEXT_FILES.map { (file, title) -> LicenseText(title, file, paragraphs(read(file))) }
        if (texts.any { it.paragraphs.isEmpty() }) throw IOException("Boş lisans metni")
        return LicenseDocument(
            appLicense = paragraphs(read(APP_LICENSE_FILE)),
            modifiedNotice = paragraphs(read(MODIFIED_NOTICE_FILE)),
            components = components,
            texts = texts,
        )
    }
}
