package com.ktu.aigaleri.ml

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-027 QA: BAĞIMSIZ ek doğrulama (qa). `PrunedVocabEquivalenceTest`'ten farklar:
 *  - Katman A kuralı burada `Character.UnicodeScript` tablosuyla yeniden yazıldı (üretim aracı ve o test `Character.getName`
 *    içinde "LATIN" arar); atılan 49.717 satırın hepsi ve tutulanların hepsi bu kurala karşı denetlenir.
 *  - Yeni, farklı üreteçli (sabit seed) >= 100.000 sorgu: ekli Türkçe, İ/ı, aksansız, â î û, sayı, tarih/saat, URL/e-posta,
 *    hashtag+emoji, kesme/kısaltma, tire/alt çizgi, noktalama yığını, >100 karakterli sözcük, boşluksuz uzun dizgi,
 *    128 token sınırı yakını, Latin dışı karışık sözcük.
 *  - Sözlük bütünlüğü ve özel token konumları.
 * Model dosyası gerekmez, ATLANMAZ (orijinal sözlük depoda gzip). assets/models/text_vocab.txt varsa o da denetlenir.
 */
class T027QaIndependentVocabTest {
    companion object {
        private val orig: List<String> get() = PrunedVocabEquivalenceTest.originalLines

        /** Gerçek (varsa assets) yoksa yeniden hesaplanan budanmış sözlük baytları; her durumda manifest SHA/boyutuyla doğrulanır. */
        private val prunedBytes: ByteArray by lazy {
            val f = TokenVocab.vocabFile()
            if (f.isFile) f.readBytes()
            else PrunedVocabEquivalenceTest.prunedLines.joinToString("") { it + "\n" }.toByteArray(Charsets.UTF_8)
        }

        private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        private val pruned: List<String> by lazy {
            prunedBytes.toString(Charsets.UTF_8).split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it }
        }

        /** Birinci eşleşme kazanır (WordPieceTokenizer.fromVocabStream putIfAbsent ile aynı). */
        private fun mapOf(lines: List<String>): Map<String, Int> =
            HashMap<String, Int>().also { m -> lines.forEachIndexed { i, t -> m.putIfAbsent(t, i) } }

        private val origTok by lazy { WordPieceTokenizer(mapOf(orig)) }
        private val prunedTok by lazy { WordPieceTokenizer(mapOf(pruned)) }

        /** eski satır -> yeni satır (atılan: -1); sırayı koruyan alt dizi olmak zorunda. */
        private val oldToNew: IntArray by lazy {
            val m = IntArray(orig.size) { -1 }
            var j = 0
            for (i in orig.indices) if (j < pruned.size && orig[i] == pruned[j]) m[i] = j++
            assertEquals("budanmış sözlük, orijinalin sırayı koruyan alt dizisi olmalı", pruned.size, j)
            m
        }

        // ---- Katman A kuralının BAĞIMSIZ yeniden yazımı: Unicode betik tablosu (script) ile ----
        private val DOC_RANGES = listOf(
            0x21..0x7E, 0xA1..0xBF, 0xD7..0xD7, 0xF7..0xF7, 0x2B0..0x2FF, 0x300..0x36F, 0x1D00..0x1DFF, 0x2000..0x2BFF,
        )

        private fun isPunctOrSymbol(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL,
            Character.OTHER_SYMBOL,
            -> true
            else -> false
        }

        /** true: karakter "güvenli sınıf" (Latin betiği, rakam, noktalama/sembol, belgelenmiş ortak blok). */
        fun r2KeepChar(cp: Int): Boolean =
            Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN ||
                Character.getType(cp).toByte() == Character.DECIMAL_DIGIT_NUMBER ||
                isPunctOrSymbol(cp) || DOC_RANGES.any { cp in it }

        private val SPECIALS = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]")

        fun r2KeepToken(t: String): Boolean {
            if (t in SPECIALS) return true
            if (t.startsWith("[") && t.endsWith("]")) return false
            val body = t.removePrefix("##")
            return body.isNotEmpty() && body.codePoints().toArray().all(::r2KeepChar)
        }

        private fun hasDroppedChar(text: String): Boolean = text.codePoints().toArray().any {
            // Kontrol/boşluk karakterleri tokenizer tarafından zaten atılır/boşluğa çevrilir; sınıflama dışı.
            !Character.isWhitespace(it) && Character.getType(it).toByte() != Character.CONTROL &&
                Character.getType(it).toByte() != Character.FORMAT && !r2KeepChar(it)
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // (2) atılan 49.717 satır + tutulanlar: kuralın bağımsız yeniden hesabı

    @Test
    fun droppedRows_areExactlyUnusedOrNonLatinLetter_andEveryKeptRowObeysRule_scriptTableIndependent() {
        val dropped = orig.indices.filter { oldToNew[it] < 0 }
        assertEquals(119_547, orig.size)
        assertEquals(69_830, pruned.size)
        assertEquals(49_717, dropped.size)
        var unused = 0
        val offenders = ArrayList<String>()
        for (i in dropped) {
            val t = orig[i]
            if (Regex("""\[unused\d+]""").matches(t)) { unused++; continue }
            val body = t.removePrefix("##")
            // gerçekten Latin dışı HARFLİ/işaretli bir karakter içermeli: güvenli sınıfın DIŞINDA bir karakter
            val foreign = body.codePoints().toArray().filter { !r2KeepChar(it) }
            if (foreign.isEmpty()) offenders += "atıldı ama tüm karakterleri güvenli sınıfta: $t"
            // ... ve bu karakter noktalama/sembol/rakam olmamalı (P*, S*, Nd asla atılmaz)
            if (foreign.any { isPunctOrSymbol(it) || Character.getType(it).toByte() == Character.DECIMAL_DIGIT_NUMBER }) {
                offenders += "atılan token noktalama/rakam karakteri çıkarıyor: $t"
            }
            if (body.isEmpty()) offenders += "boş gövdeli atıldı: $t"
        }
        assertEquals("[unusedN] satırı", 99, unused)
        assertEquals("kural dışı atılanlar: ${offenders.take(10)}", emptyList<String>(), offenders)
        // tutulanların hepsi kurala uyar (özel tokenlar hariç); [unusedN] tutulmaz
        val badKept = pruned.filter { it !in SPECIALS && !r2KeepToken(it) }
        assertEquals("kurala uymayan tutulan: ${badKept.take(10)}", emptyList<String>(), badKept)
        // bağımsız kuralla yeniden hesap: tam eşitlik (sıra dahil)
        assertEquals(pruned, orig.filter(::r2KeepToken))
    }

    @Test
    fun noKeptToken_containsLetterOrMarkOfCyrillicGreekArabicHebrewCjkHangulEtc_outsideDocumentedBlocks() {
        // Tutulan token'larda Latin/Common/Inherited dışı betikli HARF veya işaret yalnızca belgelenmiş ortak bloklardan (1D00-1DFF fonetik
        // ek, 2000-2BFF simge blokları) gelebilir; aksi halde "Latin dışı harfli token atılır" kuralı delinmiştir.
        val leaks = ArrayList<String>()
        for (t in pruned) {
            if (t in SPECIALS) continue
            for (cp in t.removePrefix("##").codePoints().toArray()) {
                val ty = Character.getType(cp).toByte()
                val letterOrMark = Character.isLetter(cp) || ty == Character.NON_SPACING_MARK ||
                    ty == Character.COMBINING_SPACING_MARK || ty == Character.ENCLOSING_MARK
                if (!letterOrMark) continue
                val sc = Character.UnicodeScript.of(cp)
                if (sc == Character.UnicodeScript.LATIN || sc == Character.UnicodeScript.COMMON || sc == Character.UnicodeScript.INHERITED) continue
                if (cp in 0x1D00..0x1DFF || cp in 0x2000..0x2BFF) continue
                leaks += "$t U+%04X $sc".format(cp)
            }
        }
        assertEquals("Latin dışı harf sızıntısı: ${leaks.take(10)}", emptyList<String>(), leaks)
        // Kiril, Yunan, Arapça, İbranice, CJK, Hangul, Hiragana, Devanagari harfli token'ların hiçbiri tutulmadı
        for (script in listOf("CYRILLIC", "GREEK", "ARABIC", "HEBREW", "HAN", "HANGUL", "HIRAGANA", "KATAKANA", "DEVANAGARI", "THAI", "ARMENIAN", "GEORGIAN")) {
            val sc = Character.UnicodeScript.valueOf(script)
            val kept = pruned.filter { t ->
                t !in SPECIALS && t.removePrefix("##").codePoints().toArray().any {
                    Character.isLetter(it) && Character.UnicodeScript.of(it) == sc && it !in 0x1D00..0x1DFF
                }
            }
            assertEquals("$script harfli token tutuldu: ${kept.take(5)}", 0, kept.size)
        }
    }

    @Test
    fun turkishLetters_everyTokenOfLatinOnlyContent_isKept_andTurkishOnlySubsetCounts() {
        val tr = "çğıöşüâîûÇĞİÖŞÜÂÎÛ"
        val fam = orig.filter { t -> t.any { it in tr } }
        assertEquals("Türkçe harf içeren token sayısı (docs: 1978)", 1978, fam.size)
        val kept = fam.count { it in pruned.toHashSet() }
        assertEquals("docs: 1962 tutuldu, 16 atıldı (Kiril karışık)", 1962, kept)
        // atılan 16'sı gerçekten Latin dışı harf içeriyor
        for (t in fam.filter { it !in pruned.toHashSet() }) {
            assertTrue("Türkçe harfli token yanlışlıkla atıldı: $t", t.codePoints().toArray().any { !r2KeepChar(it) })
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // (3) bütünlük, özel token'lar

    @Test
    fun prunedVocabFile_integrity_bytes_specials_andIdRemap() {
        val b = prunedBytes
        assertEquals(ModelManifest.TEXT_VOCAB.sizeBytes, b.size.toLong())
        assertEquals(ModelManifest.TEXT_VOCAB.sha256, sha(b))
        // UTF-8 SIKI çözüm (bozuk bayt/yarım dizi hata verir), BOM yok, CR yok, tek bitiş '\n'
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()
        assertFalse("BOM", text.startsWith("\uFEFF"))
        assertFalse("CR", text.contains('\r'))
        assertTrue("son bayt \\n", b.last() == '\n'.code.toByte())
        assertFalse("sonda boş satır (\\n\\n)", text.endsWith("\n\n"))
        val lines = text.removeSuffix("\n").split('\n')
        assertEquals(69_830, lines.size)
        assertEquals(lines, pruned)
        assertTrue("boş satır", lines.none { it.isEmpty() })
        assertTrue("başta/sonda boşluklu satır", lines.none { it != it.trim() })
        assertEquals("tekrar eden token", lines.size, lines.toSet().size)
        // özel token'lar: her biri TAM bir kez ve 0..4'te; id'ler dizeyle bulunur
        for ((i, s) in SPECIALS.withIndex()) {
            assertEquals("$s sayısı", 1, lines.count { it == s })
            assertEquals("$s konumu", i, lines.indexOf(s))
        }
        assertEquals(1, lines.indexOf("[UNK]"))
        assertEquals(2, lines.indexOf("[CLS]"))
        assertEquals(3, lines.indexOf("[SEP]"))
        // eski id -> yeni id (orijinal 0,100,101,102,103)
        assertEquals(listOf(0, 100, 101, 102, 103), SPECIALS.map { orig.indexOf(it) })
        assertEquals(listOf(0, 1, 2, 3, 4), SPECIALS.map { oldToNew[orig.indexOf(it)] })
        // tüm eşleme: monoton artan, tutulan satır metni değişmez
        var prev = -1
        for (i in orig.indices) {
            val n = oldToNew[i]
            if (n < 0) continue
            assertTrue("eşleme monoton değil", n > prev)
            prev = n
            assertEquals(orig[i], pruned[n])
        }
        assertTrue(pruned.none { it.startsWith("[unused") })
        // Tokenizer'ın GERÇEK yükleme yolu (fromVocabStream: readLine) ile map yolu aynı kimlikleri verir
        val streamTok = WordPieceTokenizer.fromVocabStream(ByteArrayInputStream(b))
        for (q in listOf("", "kedi", "plajda gülen çocuk", "Türkiye'nin İstanbul'da hâlâ kâğıt", "привет 猫 🐱", "x".repeat(150))) {
            assertArrayEquals(q, prunedTok.encode(q), streamTok.encode(q))
        }
        assertArrayEquals(intArrayOf(2, 3), streamTok.encode(""))
    }

    @Test
    fun originalVocab_hasNoDuplicatesOrBlank_soFirstWinsIsIrrelevant() {
        assertEquals("orijinalde tekrar eden satır", orig.size, orig.toSet().size)
        assertTrue(orig.none { it.isEmpty() })
    }

    // ------------------------------------------------------------------------------------------------------------
    // (1) >= 100.000 yeni sorgu: orijinal vs budanmış, METİN düzeyi

    private val tr: Locale = Locale.forLanguageTag("tr-TR")

    private val stems = ("ev okul kitap çocuk köpek kedi balık göl dağ deniz gün ışık yağmur kar bulut ağaç çiçek öğretmen öğrenci şehir " +
        "köy yol köprü araba bisiklet vapur uçak tren yemek çay kahve ekmek süt peynir zeytin üzüm elma armut kiraz çilek " +
        "İstanbul Ankara İzmir Trabzon Diyarbakır Iğdır Şanlıurfa Çanakkale Eskişehir Kırşehir Ağrı Ürgüp Ödemiş " +
        "hâlâ kâğıt rüzgâr âşık îtimat mûsiki şûra Âdem lâzım hükûmet kâinat mektûb dîvan resmî millî asırlar " +
        "güneş yıldız ay gökyüzü orman nehir sahil kumsal plaj dalga rüya masal müzik şarkı dans resim fotoğraf video").split(' ')

    private val suffixes = ("ler lar de da den dan te ta nin nın nun nün in ın un ün im ım um üm imiz ımız umuz ümüz iniz ınız " +
        "leri ları ki ndeki daki deki taki yor muş miş mış mak mek lik lık luk lük ci cı cu cü çi çı çu çü siz sız suz süz li lı lu lü " +
        "e a ye ya i ı u ü yi yı yu yü ndan nde nda ile yle yla dır dir dur dür tır tir tur tür ymış ydi ysa sa se ma me").split(' ')

    private val enWords = ("beach child dog cat sunset mountain snow red blue green city lake rain tree flower house car bike party " +
        "family birthday wedding holiday concert football swimming running smiling playing sleeping photo picture screenshot").split(' ')

    private val punctPool = ("! ? . , ; : ' \" - _ / \\ ( ) [ ] { } < > @ # $ % ^ & * + = | ~ ` … « » ‘ ’ “ ” ‚ „ ¿ ¡ § ¶ • · ° ± × ÷ € £ ¥ ₺ " +
        "， 。 ！ ？ 、 「 」 《 》 （ ） ； ： ، ؟ ۔ । ॥ ― – — ‐ ‑ ™ © ® ← → ↑ ↓ ∞ ≈ ≠ ≤ ≥ √ ∑ ★ ☆ ♥ ✓ ✗ ░ ■ □ ▲").split(' ')

    private val emojiPool = listOf("🐱", "🐶", "🏖️", "😀", "😂", "❤️", "👍", "🎉", "🌅", "📷", "👨‍👩‍👧", "🇹🇷", "☀️", "🍕", "🚗", "⭐", "🔥", "🥳", "🏔", "🌊")

    private val nonLatinWords = listOf(
        "привет", "кошка", "мир", "猫", "東京", "日本語", "한국어", "مرحبا", "שלום", "γειά", "σχολείο", "नमस्ते", "ไทย", "հայ", "ქართული",
        "ひらがな", "カタカナ", "ТÜRKİYE", "ёж", "ß", "ǆ",
    )

    private fun Random.pick(l: List<String>) = l[nextInt(l.size)]
    private fun Random.int(n: Int) = nextInt(n)

    private fun suffixChain(r: Random, n: Int, sep: String = "") = (0 until n).joinToString("") { sep + r.pick(suffixes) }

    private fun randomCase(r: Random, s: String) = s.map { if (r.nextBoolean()) it.uppercaseChar() else it.lowercaseChar() }.joinToString("")

    private fun asciiFold(s: String) = s.map {
        when (it) {
            'ç' -> 'c'; 'ğ' -> 'g'; 'ı' -> 'i'; 'ö' -> 'o'; 'ş' -> 's'; 'ü' -> 'u'; 'â' -> 'a'; 'î' -> 'i'; 'û' -> 'u'
            'Ç' -> 'C'; 'Ğ' -> 'G'; 'İ' -> 'I'; 'Ö' -> 'O'; 'Ş' -> 'S'; 'Ü' -> 'U'; 'Â' -> 'A'; 'Î' -> 'I'; 'Û' -> 'U'
            else -> it
        }
    }.joinToString("")

    /** Her üreteç (ad, üretim). Her ad için en az `PER` sorgu üretilir. */
    private fun generators(r: Random, vocabWords: List<String>): List<Pair<String, () -> String>> = listOf(
        "ekli" to { r.pick(stems) + suffixChain(r, 1 + r.int(6)) },
        "ekli-tire" to { r.pick(stems) + suffixChain(r, 1 + r.int(6), "-") },
        "ekli-kesme" to { r.pick(stems) + "'" + suffixChain(r, 1 + r.int(3)) },
        "ekli-kesme-tipografik" to { r.pick(stems) + "\u2019" + suffixChain(r, 1 + r.int(3)) },
        "buyuk-kucuk-tr" to {
            val w = r.pick(stems) + suffixChain(r, r.int(3))
            when (r.int(5)) { 0 -> w.uppercase(tr); 1 -> w.replaceFirstChar { it.uppercase(tr) }; 2 -> w.uppercase(Locale.ROOT); 3 -> w.lowercase(Locale.ROOT); else -> randomCase(r, w) }
        },
        "I-i-karisik" to { r.pick(listOf("I", "İ", "ı", "i", "Iı", "İi", "IĞDIR", "ığdır", "İSTANBUL", "ISPARTA", "Isparta", "iI", "I\u0307", "i\u0307")) + r.pick(stems) },
        "aksansiz" to { asciiFold(r.pick(stems) + suffixChain(r, r.int(4))).let { if (r.nextBoolean()) it.uppercase(Locale.ROOT) else it } },
        "sapkali" to { r.pick(listOf("â", "î", "û", "Â", "Î", "Û")) + r.pick(stems) + r.pick(listOf("â", "î", "û", "")) + suffixChain(r, r.int(3)) },
        "sayi" to { r.pick(listOf("", "%", "₺", "$", "€", "#", "No:", "x", "+")) + r.int(100_000_000) + r.pick(listOf("", ".", ",5", "'inci", "'nci", "lik", "-lik", "kg", "TL", "%", ".000")) },
        "sayi-ondalik-binlik" to { "${r.int(1000)}.${r.int(1000)}.${r.int(1000)},${r.int(100)}" },
        "tarih-saat" to {
            r.pick(listOf(
                "%02d.%02d.%04d".format(1 + r.int(31), 1 + r.int(12), 1900 + r.int(200)),
                "%04d-%02d-%02d".format(1900 + r.int(200), 1 + r.int(12), 1 + r.int(31)),
                "%02d/%02d/%04d %02d:%02d".format(1 + r.int(31), 1 + r.int(12), 2000 + r.int(30), r.int(24), r.int(60)),
                "%02d:%02d:%02d".format(r.int(24), r.int(60), r.int(60)),
                "%02d:%02d'de".format(r.int(24), r.int(60)),
                "${1 + r.int(31)} Ekim ${2000 + r.int(30)} Salı", "Ocak-${1990 + r.int(40)}", "${r.int(12)}pm", "${1 + r.int(12)}.${r.int(60)}",
                "T${r.int(24)}:${r.int(60)}Z", "2026-10-13T14:30:00+03:00",
            ))
        },
        "url-eposta" to {
            r.pick(listOf(
                "https://www.${r.pick(stems)}.com/${r.pick(stems)}/${r.pick(enWords)}?q=${r.pick(stems)}&id=${r.int(9999)}#${r.pick(stems)}",
                "http://${r.pick(enWords)}.org.tr:${r.int(65536)}/a_b-c/d.html", "ftp://x.y/${r.pick(stems)}.jpg",
                "${r.pick(stems)}.${r.pick(stems)}@${r.pick(enWords)}.com.tr", "www.ktu.edu.tr/${r.pick(stems)}", "${r.pick(enWords)}@${r.pick(stems)}.k12.tr",
                "C:\\Users\\${r.pick(stems)}\\Resimler\\IMG_${r.int(9999)}.JPG", "/sdcard/DCIM/Camera/IMG_2026${r.int(9999)}_${r.int(999999)}.jpg",
                "IMG-20261013-WA${r.int(9999)}.jpg", "192.168.${r.int(256)}.${r.int(256)}", "localhost:8080/${r.pick(stems)}",
            ))
        },
        "hashtag-emoji-bitisik" to {
            r.pick(listOf(
                "#${r.pick(stems)}${r.int(3000)}${r.pick(emojiPool)}", "${r.pick(stems)}${r.pick(emojiPool)}${r.pick(stems)}", "${r.pick(emojiPool)}${r.pick(emojiPool)}${r.pick(emojiPool)}",
                "@${r.pick(enWords)}_${r.pick(stems)}", "#${r.pick(stems)} #${r.pick(enWords)}${r.pick(emojiPool)}", "${r.pick(emojiPool)}${r.pick(stems)}", "${r.pick(stems)}${r.pick(emojiPool)}",
            ))
        },
        "kesme-kisaltma" to {
            r.pick(listOf(
                "Türkiye'nin", "İstanbul'da", "T.C.", "Dr.", "Prof.Dr.", "vs.", "A.Ş.", "Ltd.Şti.", "KTÜ'lü", "TBMM'de", "NATO'ya", "don't", "can't", "O'Brien",
                "‘tırnak’", "“çift”", "«fr»", "„de“", "'${r.pick(stems)}'", "\"${r.pick(stems)}\"", "${r.pick(stems)}'${r.pick(suffixes)}'${r.pick(suffixes)}",
                "${r.pick(listOf("TL", "KG", "cm", "mm", "GB", "TB", "kWh"))}.", "${r.pick(stems)}.${r.pick(stems)}.",
            ))
        },
        "tire-altcizgi" to {
            r.pick(listOf(
                "${r.pick(stems)}_${r.pick(enWords)}-${r.pick(stems)}", "__${r.pick(enWords)}__", "a--b---c", "${r.pick(stems)}—${r.pick(stems)}", "${r.pick(stems)}–${r.pick(stems)}",
                "snake_case_${r.pick(enWords)}_${r.int(99)}", "kebab-case-${r.pick(stems)}", "_${r.pick(stems)}", "${r.pick(stems)}_", "-${r.pick(stems)}-", "x-y_z-${r.int(10)}",
            ))
        },
        "noktalama-yigini" to { (0 until 1 + r.int(14)).joinToString("") { r.pick(punctPool) } },
        "noktalama-arasi-sozcuk" to { (0 until 1 + r.int(5)).joinToString("") { r.pick(punctPool) + r.pick(stems) } + r.pick(punctPool) },
        "uzun-sozcuk-100" to {
            val n = 97 + r.int(8)
            val unit = r.pick(listOf("a", "ç", "ev", "ğ", "ışık", "ü", "kedi", "Ö", "0", "İ"))
            unit.repeat(n / unit.length + 1).substring(0, n)
        },
        "uzun-sozcuk-ekli" to { r.pick(stems) + suffixChain(r, 20 + r.int(60)) },
        "boslukssuz-uzun" to { val n = 300 + r.int(900); (0 until n).joinToString("") { r.pick(listOf(r.pick(vocabWords).take(4), "x", "ç", "1", ".", "-")) } },
        "128-siniri" to {
            val k = 100 + r.int(60)
            (0 until k).joinToString(" ") { r.pick(listOf(r.pick(stems) + suffixChain(r, r.int(3)), r.pick(enWords), r.int(999).toString(), r.pick(vocabWords), r.pick(punctPool))) }
        },
        "128-siniri-uzun-parcali" to {
            val k = 20 + r.int(25)
            (0 until k).joinToString(" ") { r.pick(stems) + suffixChain(r, 3 + r.int(5)) }
        },
        "karisik-latin-disi" to {
            val w = r.pick(nonLatinWords)
            when (r.int(6)) { 0 -> r.pick(enWords) + w; 1 -> w + r.pick(stems); 2 -> r.pick(stems) + " " + w; 3 -> "iPhone" + w; 4 -> w + "!" + r.pick(stems); else -> r.pick(stems) + w + r.pick(enWords) }
        },
        "karisik-cumle" to {
            (0 until 1 + r.int(12)).joinToString(" ") {
                when (r.int(12)) {
                    0, 1, 2 -> r.pick(stems) + suffixChain(r, r.int(3)); 3 -> r.pick(enWords); 4 -> r.int(5000).toString()
                    5 -> r.pick(punctPool); 6 -> r.pick(emojiPool); 7 -> r.pick(stems).uppercase(tr); 8 -> asciiFold(r.pick(stems))
                    9 -> r.pick(vocabWords); 10 -> r.pick(stems) + "'" + r.pick(suffixes); else -> r.pick(stems).replaceFirstChar { c -> c.uppercase(tr) }
                }
            }
        },
        "bosluk-turleri" to {
            val sp = r.pick(listOf(" ", "  ", "\t", "\n", "\u00A0", "\u2003", "\u3000", " \u200B ", "\u200D", "\u0085", " \r\n "))
            r.pick(stems) + sp + r.pick(stems) + sp + r.int(100)
        },
        "ayrisik-aksan-NFD" to { java.text.Normalizer.normalize(r.pick(stems) + r.pick(suffixes), java.text.Normalizer.Form.NFD) },
    )

    /**
     * Budanmış dizi, orijinal dizinin ardışık dilimlere bölünüp her dilimin ya AYNEN korunması ya da TEK [UNK] ile
     * değiştirilmesiyle elde edilebilir mi (başka fark kabul edilmez)? Küme DP'si, belirsiz hizalamadan etkilenmez.
     */
    private fun onlyDiffIsUnkRuns(a: List<String>, b: List<String>): Boolean {
        val n = a.size
        var cur = BooleanArray(n + 1).also { it[0] = true }
        for (tok in b) {
            val nxt = BooleanArray(n + 1)
            val minI = cur.indexOfFirst { it }
            if (minI < 0) return false
            for (i in 0 until n) if (cur[i] && a[i] == tok) nxt[i + 1] = true
            if (tok == "[UNK]") for (k in minI + 1..n) nxt[k] = true
            cur = nxt
        }
        return cur[n]
    }

    private class Stats {
        var texts = 0L
        var eq = 0L
        var lenient = 0L
        var droppedChar = 0L
        var len128 = 0L
        var tokens = 0L
        val failures = ArrayList<String>()
    }

    private fun check(name: String, text: String, st: Stats) {
        val o = origTok.encode(text)
        val p = prunedTok.encode(text)
        st.texts++
        st.tokens += o.size
        val ot = o.map { orig[it] }
        val pt = p.map { pruned[it] }
        fun fail(why: String) { if (st.failures.size < 15) st.failures += "[$name] $why :: ${text.take(80).replace("\n", "\\n")} :: ${ot.take(12)} vs ${pt.take(12)}" }
        if (o.size > 128 || p.size > 128) fail("128 sınırı aşıldı")
        if (o.first() != 101 || o.last() != 102 || p.first() != 2 || p.last() != 3) fail("CLS/SEP konumu")
        if (o.size == 128) st.len128++
        if (hasDroppedChar(text)) {
            st.droppedChar++
            if (!onlyDiffIsUnkRuns(ot, pt)) fail("Latin dışı: fark yalnızca [UNK] değil")
            else st.lenient++
        } else {
            if (ot != pt) fail("METİN eşit değil")
            else if (o.map { oldToNew[it] } != p.toList()) fail("id eşlemesi tutarsız")
            else st.eq++
        }
    }

    @Test
    fun hundredThousandNewQueries_tokenTextsEqual_rawAndNormalized_acrossAllCategories() {
        val r = Random(0x7027_0A11L)
        val vocabWords = orig.filter { t -> !t.startsWith("##") && t.length in 3..9 && t.all { it.isLetter() && it.code < 0x250 } }
        assertTrue(vocabWords.size > 20_000)
        val gens = generators(r, vocabWords)
        val per = 5_000
        val total = Stats()
        val byName = LinkedHashMap<String, Stats>()
        for ((name, gen) in gens) {
            val st = Stats()
            repeat(per) {
                val text = gen()
                check(name, text, st)
                // Üretim yolu: QueryPreprocessor.normalize (geçersizse atlanır)
                val q = try { QueryPreprocessor.normalize(text) } catch (_: Exception) { null }
                if (q != null) check("$name/norm", q, st)
            }
            byName[name] = st
            total.texts += st.texts; total.eq += st.eq; total.lenient += st.lenient; total.droppedChar += st.droppedChar
            total.len128 += st.len128; total.tokens += st.tokens
            total.failures += st.failures
        }
        println("T-027 QA: ${gens.size} kategori, ${total.texts} metin, ${total.tokens} token, eşit ${total.eq}, [UNK]-farkı ${total.lenient}, " +
            "128 uzunluk ${total.len128}; kategori başına: " + byName.entries.joinToString { "${it.key}=${it.value.texts}/${it.value.droppedChar}" })
        assertEquals("ayrışma: ${total.failures}", 0, total.failures.size)
        assertTrue("en az 100.000 sorgu: ${total.texts}", total.texts >= 100_000)
        assertTrue("en az 25 kategori: ${gens.size}", gens.size >= 25)
        // Her kategori kendi payını üretti ve Latin-dışı olmayan kategorilerde Latin dışı karakter çıkmadı
        for ((name, st) in byName) assertTrue("$name az: ${st.texts}", st.texts >= per)
        for (name in listOf("ekli", "ekli-tire", "ekli-kesme", "buyuk-kucuk-tr", "I-i-karisik", "aksansiz", "sapkali", "sayi", "tarih-saat", "url-eposta",
            "kesme-kisaltma", "tire-altcizgi", "noktalama-yigini", "128-siniri", "uzun-sozcuk-100", "boslukssuz-uzun")) {
            assertEquals("$name: Türkçe/Latin kategoride Latin dışı karakter olmamalı", 0L, byName.getValue(name).droppedChar)
        }
        // emoji kategorisinde "güvenli sınıf dışı" yalnızca U+FE0F (varyasyon seçici, Mn/INHERITED) olabilir; o da orijinalde eşleşmez
        assertTrue(byName.getValue("hashtag-emoji-bitisik").droppedChar < per / 2)
        assertTrue("karisik-latin-disi gerçekten Latin dışı üretti", byName.getValue("karisik-latin-disi").droppedChar > per / 2)
        assertTrue("128 sınırına ulaşan çok sayıda metin: ${total.len128}", total.len128 >= 3_000)
    }

    @Test
    fun noDroppedCharacters_inTurkishAndLatinCategories_evenForHugeRandomLatinStrings_twentyThousandMore() {
        // Orijinal sözlükten rastgele ardışık tokenların (## dahil) birleştirilmiş uçları: en uzun eşleşme seçimini zorlar.
        val r = Random(20261013L xor 0x5A5A)
        val pool = pruned.filter { it !in SPECIALS }
        val st = Stats()
        repeat(20_000) {
            val n = 1 + r.nextInt(6)
            val s = (0 until n).joinToString("") { pool[r.nextInt(pool.size)].removePrefix("##") }
            check("tokenbirlesim", s, st)
            check("tokenbirlesim-bosluklu", (0 until n).joinToString(" ") { pool[r.nextInt(pool.size)].removePrefix("##") }, st)
        }
        assertEquals("ayrışma: ${st.failures}", 0, st.failures.size)
        assertEquals(40_000L, st.texts)
    }

    // ------------------------------------------------------------------------------------------------------------
    // Latin dışı / karışık: orijinaldeki ve budanmıştaki davranışı SABİTLE (kabul edilen fark: [UNK])

    private fun toks(t: WordPieceTokenizer, vocab: List<String>, s: String) = t.encode(s).map { vocab[it] }

    @Test
    fun mixedScript_behaviourIsPinned_originalSplits_prunedGivesUnkForWholeWord() {
        // CJK karakterler çevresine boşlukla ayrılır (handle_chinese_chars): iPhone ayrı sözcük, 東 ve 京 ayrı.
        val iphone = toks(origTok, orig, "iPhone").drop(1).dropLast(1)
        assertEquals(iphone, toks(prunedTok, pruned, "iPhone").drop(1).dropLast(1))
        assertFalse(iphone.contains("[UNK]"))
        assertEquals(listOf("[CLS]") + iphone + listOf("東", "京", "[SEP]"), toks(origTok, orig, "iPhone東京"))
        assertEquals(listOf("[CLS]") + iphone + listOf("[UNK]", "[UNK]", "[SEP]"), toks(prunedTok, pruned, "iPhone東京"))
        // Latin + Kiril bitişik: orijinalde parçalanır (UNK değil), budanmışta tüm sözcük tek [UNK] (karışık betik, K-14'te kabul)
        val o1 = toks(origTok, orig, "kediкошка")
        assertFalse("orijinalde [UNK] olmamalı: $o1", o1.contains("[UNK]"))
        assertEquals(listOf("[CLS]", "[UNK]", "[SEP]"), toks(prunedTok, pruned, "kediкошка"))
        // Aynı sorguda Latin kısım etkilenmez, yalnızca Kiril sözcük [UNK]
        assertEquals(toks(origTok, orig, "kedi") .dropLast(1) + listOf("[UNK]", "[SEP]"), toks(prunedTok, pruned, "kedi привет"))
        assertFalse(toks(origTok, orig, "kedi привет").contains("[UNK]"))
        // Saf Latin dışı sözcükler: orijinalde [UNK] olmaz, budanmışta tek [UNK]
        for (w in listOf("привет", "مرحبا", "שלום", "한국어", "γειά", "नमस्ते", "ქართული", "հայ")) {
            assertFalse("$w orijinalde [UNK]", toks(origTok, orig, w).contains("[UNK]"))
            assertEquals(w, listOf("[CLS]", "[UNK]", "[SEP]"), toks(prunedTok, pruned, w))
        }
        // Latin dışı noktalama KALIR (K-14 düzeltmesi): tek başına aynı token
        for (p in listOf("。", "，", "！", "？", "،", "؟", "।", "「", "」")) {
            assertEquals(p, toks(origTok, orig, p), toks(prunedTok, pruned, p))
            assertFalse(p, toks(prunedTok, pruned, p).contains("[UNK]"))
        }
        // Türkçe harfler ve ayrışık/birleşik biçimler (T-032 öncesi davranış: NFD girdi [UNK], aynı iki tokenizer'da)
        for (w in listOf("çğıöşü", "ÇĞİÖŞÜ", "âîû", "ÂÎÛ", "hâlâ", "kâğıt", "İstanbul", "Iğdır", "ığdır", "c\u0327ocuk", "ka\u0302g\u0306ıt")) {
            assertEquals(w, toks(origTok, orig, w), toks(prunedTok, pruned, w))
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Sınır durumları: boş, boşluk, kontrol, çok uzun, 128

    @Test
    fun edgeInputs_emptyWhitespaceControlHuge_behaveIdentically() {
        val cases = listOf(
            "", " ", "   ", "\t\n", "\u00A0", "\u3000", "\u200B", "\u0000", "\uFFFD", "\u0007\u001B", "\uD800", "a", ".", "İ", "ı", "I", "i",
            "ç".repeat(100), "ç".repeat(101), "ğ".repeat(99), "a ".repeat(500), "a".repeat(100_000), "kedi.".repeat(2_000), "🐱".repeat(1_000),
            "ev-de-ki-ler-in-ki-ler-den", "ev" + "ler".repeat(40), ("kedi " + "ç".repeat(101) + " ").repeat(60),
        )
        for (c in cases) {
            val o = toks(origTok, orig, c)
            val p = toks(prunedTok, pruned, c)
            assertEquals("kasa: ${c.take(30)}", o, p)
            assertTrue(origTok.encode(c).size <= 128 && prunedTok.encode(c).size <= 128)
        }
        assertEquals(listOf("[CLS]", "[SEP]"), toks(prunedTok, pruned, ""))
        assertEquals(listOf("[CLS]", "[SEP]"), toks(prunedTok, pruned, " \u00A0\u200B "))
        assertEquals(listOf("[CLS]", "[UNK]", "[SEP]"), toks(prunedTok, pruned, "ç".repeat(101)))
        assertEquals(128, prunedTok.encode("a ".repeat(500)).size)
        // tam 126 içerik tokenı: kesme yok; 127'de kesilir
        assertEquals(128, prunedTok.encode("a ".repeat(126)).size)
        assertEquals(128, prunedTok.encode("a ".repeat(127)).size)
        assertEquals(127, prunedTok.encode("a ".repeat(125)).size)
        // üretim ön işlemesi: boş/yalnız boşluk/çok uzun girdi InvalidQuery (tokenizer'a hiç ulaşmaz)
        for (bad in listOf("", "   ", "\u200B\u0000", "a".repeat(QueryPreprocessor.MAX_RAW_LENGTH + 1), "ç".repeat(QueryPreprocessor.MAX_QUERY_LENGTH + 1))) {
            try { QueryPreprocessor.normalize(bad); org.junit.Assert.fail("InvalidQuery bekleniyordu: ${bad.take(10)}") } catch (_: com.ktu.aigaleri.domain.InvalidQueryException) {}
        }
        // Türkçe küçük harf (I -> ı, İ -> i) sonrası eşitlik
        for (s in listOf("IĞDIR", "İSTANBUL", "ISPARTA", "ÇOCUK", "ÖĞRENCİ", "ŞİŞLİ", "ÜSKÜDAR", "ÂŞIK", "HÂLÂ")) {
            val q = QueryPreprocessor.normalize(s)
            assertEquals(s, toks(origTok, orig, q), toks(prunedTok, pruned, q))
            assertFalse("$s -> $q [UNK]", toks(prunedTok, pruned, q).contains("[UNK]"))
        }
    }

    @Test
    fun negativeControl_comparisonHelpers_detectLossAndAcceptOnlyUnkRuns() {
        // 'â' içeren token'ları atan hatalı bir budama, aynı eşitlik ölçütüyle yakalanır (Türkçe kâğıt/hâlâ)
        val lossy = pruned.filter { 'â' !in it }
        val lossyTok = WordPieceTokenizer(mapOf(lossy))
        for (w in listOf("hâlâ", "kâğıt", "âşık")) {
            assertTrue(w, toks(lossyTok, lossy, w) != toks(origTok, orig, w))
        }
        assertEquals(toks(lossyTok, lossy, "kedi"), toks(origTok, orig, "kedi"))
        // [UNK]-dilimi denetleyicisi: yalnızca TEK [UNK] ile değişim kabul, başka token farkı ret
        assertTrue(onlyDiffIsUnkRuns(listOf("a", "b", "c"), listOf("a", "[UNK]")))
        assertTrue(onlyDiffIsUnkRuns(listOf("a", "b", "c"), listOf("[UNK]", "[UNK]", "c")))
        assertTrue(onlyDiffIsUnkRuns(listOf("a", "b"), listOf("a", "b")))
        assertFalse(onlyDiffIsUnkRuns(listOf("a", "b", "c"), listOf("a", "x", "c")))
        assertFalse(onlyDiffIsUnkRuns(listOf("a", "b"), listOf("a", "b", "[UNK]")))
        assertFalse(onlyDiffIsUnkRuns(listOf("a", "b", "c"), listOf("a", "b")))
    }
}
