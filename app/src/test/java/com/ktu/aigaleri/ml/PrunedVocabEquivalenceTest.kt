package com.ktu.aigaleri.ml

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.Random
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-027 kapısı (b): budanmış sözlük (Katman A, K-14) ile ORİJİNAL sözlük, GERÇEK [WordPieceTokenizer] ile büyük sentetik
 * TR+EN kümede ve altın sette karşılaştırılır: token METNİ eşit, id eşlemesi (eski -> yeni) tutarlı, [UNK] sayısı AYNI.
 *
 * Model dosyası, `.model-cache` veya assets GEREKMEZ ve test ATLANMAZ: orijinal sözlük metni depoda gzip olarak durur
 * (`src/test/resources/ml/original_vocab.txt.gz`, distilbert-base-multilingual-cased `vocab.txt`, Apache-2.0; model AĞIRLIĞI
 * değildir) ve SHA-256'sı orijinalle doğrulanır. Budanmış sözlük, Katman A kuralının testteki (Kotlin, JVM Unicode veritabanı)
 * uygulamasıyla yeniden hesaplanır ve ModelManifest'teki boyut/SHA-256 ile birebir eşit olmak zorundadır (kural Python
 * aracı `tools/prune_text_model.py` ile aynı çıktıyı üretir). assets/models/text_vocab.txt varsa (git dışı) ayrıca satır satır
 * eşitliği denetlenir. Kosinüs/top-K (ONNX) ölçümü yalnızca geliştirici makinesindedir: `tools/verify_pruned_text.py`.
 * Sabit seed; fotoğraf ve kullanıcı verisi yok.
 */
class PrunedVocabEquivalenceTest {
    companion object {
        const val ORIGINAL_VOCAB_SHA256 = "fe0fda7c425b48c516fc8f160d594c8022a0808447475c1a7c6d6479763f310c"
        private val SPECIALS = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]")
        private val COMMON_RANGES = listOf(
            0x21..0x7E, 0xA1..0xBF, 0xD7..0xD7, 0xF7..0xF7, 0x2B0..0x2FF, 0x300..0x36F, 0x1D00..0x1DFF, 0x2000..0x2BFF,
        )

        private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        private fun splitLines(bytes: ByteArray): List<String> =
            bytes.toString(Charsets.UTF_8).split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it }

        /** Orijinal sözlük (depodaki gzip); SHA-256 ve satır sayısı doğrulanır. */
        val originalLines: List<String> by lazy {
            val bytes = GZIPInputStream(PrunedVocabEquivalenceTest::class.java.getResourceAsStream("/ml/original_vocab.txt.gz")!!)
                .use { it.readBytes() }
            assertEquals("orijinal sözlük SHA-256", ORIGINAL_VOCAB_SHA256, sha256(bytes))
            splitLines(bytes).also { assertEquals(GoldenText.ORIGINAL_VOCAB_SIZE, it.size) }
        }

        /** Katman A: Latin betiği harfi + rakam + TÜM noktalama/sembol (P*, S*) + birkaç ortak blok; atılan: Latin dışı harfli token. */
        fun keepChar(cp: Int): Boolean {
            if (Character.getName(cp)?.split(' ')?.contains("LATIN") == true) return true
            when (Character.getType(cp).toByte()) {
                Character.DECIMAL_DIGIT_NUMBER, Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
                Character.START_PUNCTUATION, Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION,
                Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL,
                Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
                -> return true
            }
            return COMMON_RANGES.any { cp in it }
        }

        fun keepToken(t: String): Boolean {
            if (t in SPECIALS) return true
            if (t.startsWith("[") && t.endsWith("]")) return false // [unusedN]
            val body = t.removePrefix("##")
            return body.isNotEmpty() && body.codePoints().toArray().all(::keepChar)
        }

        /** Kuraldan yeniden hesaplanan budanmış sözlük (satır sırası korunur). */
        val prunedLines: List<String> by lazy { originalLines.filter(::keepToken) }
    }

    private fun fixture() = Fixture(originalLines, prunedLines)

    private class Fixture(val orig: List<String>, val pruned: List<String>) {
        val origTok = WordPieceTokenizer(orig.withIndex().reversed().associate { it.value to it.index })
        val prunedTok = WordPieceTokenizer(pruned.withIndex().reversed().associate { it.value to it.index })
        val unkOrig = orig.indexOf("[UNK]")
        val unkPruned = pruned.indexOf("[UNK]")
        val oldToNew: IntArray = IntArray(orig.size) { -1 }.also { m ->
            var j = 0
            for (i in orig.indices) if (j < pruned.size && orig[i] == pruned[j]) m[i] = j++
            require(j == pruned.size) { "budanmış sözlük, orijinalin sırayı koruyan alt dizisi değil" }
        }
    }

    @Test
    fun recomputedPrunedVocab_equalsManifest_andShippedAssetVocabLineByLine() {
        val bytes = prunedLines.joinToString("") { it + "\n" }.toByteArray(Charsets.UTF_8)
        assertEquals(ModelManifest.TEXT_VOCAB.sizeBytes, bytes.size.toLong())
        assertEquals(ModelManifest.TEXT_VOCAB.sha256, sha256(bytes))
        assertEquals(69_830, prunedLines.size)
        // Yerelde assets sözlüğü varsa (git dışı) satır satır eşit olmak zorunda.
        val assets = TokenVocab.vocabFile()
        if (assets.isFile) assertEquals(prunedLines, splitLines(assets.readBytes()))
        // Özel tokenlar ve id eşlemesi: id'ler dizeyle bulunur
        assertEquals(SPECIALS, prunedLines.take(5))
        assertTrue(prunedLines.none { it.startsWith("[unused") })
    }

    @Test
    fun everyPunctuationAndSymbolToken_isKept_andAloneTokenizesIdentically() {
        val f = fixture()
        val ps = setOf(
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
            Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
        )
        var n = 0
        for (t in f.orig) {
            val cps = t.codePoints().toArray()
            if (cps.isEmpty() || cps.any { Character.getType(it).toByte() !in ps }) continue
            if (t.startsWith("[")) continue
            n++
            assertTrue("noktalama/sembol token'ı atıldı: $t", t in f.pruned)
            if (cps.size == 1) {
                val o = f.origTok.encode(t)
                val p = f.prunedTok.encode(t)
                assertEquals(listOf("[CLS]", t, "[SEP]"), o.map { f.orig[it] })
                assertEquals("tek başına noktalama [UNK] oldu: $t", o.map { f.orig[it] }, p.map { f.pruned[it] })
            }
        }
        assertTrue("noktalama/sembol token sayısı makul: $n", n > 500)
        // Tam genişlikli ve CJK/Arapça noktalama açıkça
        for (t in listOf("！", "（", "）", "？", "，", "：", "；", "＠", "、", "。", "「", "」", "《", "》", "،", "।", "՚", "։")) {
            if (t in f.orig) assertTrue(t, t in f.pruned)
        }
    }

    @Test
    fun goldenSet_tokenTexts_equalForOriginalAndPrunedVocab() {
        val f = fixture()
        val golden = GoldenText.queries()
        assertEquals(GoldenText.QUERY_COUNT, golden.size)
        var unkO = 0
        var unkP = 0
        for (g in golden) {
            val q = QueryPreprocessor.normalize(g.query)
            val o = f.origTok.encode(q).map { f.orig[it] }
            val p = f.prunedTok.encode(q).map { f.pruned[it] }
            assertEquals("orijinal tokenizer altınla ayrıştı: ${g.query}", g.tokens, o)
            assertEquals("budanmış tokenizer altınla ayrıştı: ${g.query} [${g.category}]", g.tokens, p)
            unkO += o.count { it == "[UNK]" }
            unkP += p.count { it == "[UNK]" }
        }
        assertEquals(GoldenText.BASELINE_UNK_COUNT, unkO)
        assertEquals(unkO, unkP)
    }

    @Test
    fun nonLatinInput_becomesUnk_acceptedBehaviour() {
        val f = fixture()
        for (w in listOf("привет", "猫", "한국어", "مرحبا", "שלום")) {
            assertTrue("$w orijinalde [UNK] olmamalı (kontrol)", f.origTok.encode(w).map { f.orig[it] }.let { "[UNK]" !in it })
            assertEquals(listOf("[CLS]", "[UNK]", "[SEP]"), f.prunedTok.encode(w).map { f.pruned[it] })
        }
    }

    /** Eşit olmayan ilk örnekleri biriktirir. */
    private class Tally {
        var texts = 0
        var tokens = 0L
        var unkOrig = 0L
        var unkPruned = 0L
        val diffs = ArrayList<String>()
    }

    private fun compare(f: Fixture, text: String, t: Tally) {
        val o = f.origTok.encode(text)
        val p = f.prunedTok.encode(text)
        t.texts++
        t.tokens += o.size - 2
        t.unkOrig += o.count { it == f.unkOrig }
        t.unkPruned += p.count { it == f.unkPruned }
        val textsEqual = o.map { f.orig[it] } == p.map { f.pruned[it] }
        val idsMapped = o.map { f.oldToNew[it] } == p.toList()
        if (!(textsEqual && idsMapped) && t.diffs.size < 10) t.diffs.add("${text.take(60)} -> ${o.map { f.orig[it] }} vs ${p.map { f.pruned[it] }}")
    }

    private val trLocale = Locale.forLanguageTag("tr-TR")
    private fun trUpper(s: String) = s.uppercase(trLocale)
    private fun trTitle(s: String) = s.take(1).uppercase(trLocale) + s.drop(1)
    private fun asciiFold(s: String) = s.map {
        when (it) {
            'ç' -> 'c'; 'ğ' -> 'g'; 'ı' -> 'i'; 'ö' -> 'o'; 'ş' -> 's'; 'ü' -> 'u'; 'â' -> 'a'; 'î' -> 'i'; 'û' -> 'u'
            'Ç' -> 'C'; 'Ğ' -> 'G'; 'İ' -> 'I'; 'Ö' -> 'O'; 'Ş' -> 'S'; 'Ü' -> 'U'; 'Â' -> 'A'; 'Î' -> 'I'; 'Û' -> 'U'
            else -> it
        }
    }.joinToString("")

    private val trStems = ("çocuk köpek kedi plaj deniz gün batım ağaç çiçek öğrenci şehir göl dağ yağmur kar ışık kırmızı mavi yeşil " +
        "sarı İstanbul Ankara Çanakkale Şişli Üsküdar Iğdır Diyarbakır ağabey hâlâ kâğıt rüzgâr âşık îtimat mûsiki şûra Âdem lâzım " +
        "hükûmet güneş bulut yıldız orman nehir köprü sokak araba bisiklet otobüs uçak gemi tren yemek kahvaltı çay kahve pasta " +
        "meyve sebze bahçe kuş balık inek koyun tavuk aslan kaplan tilki ayı maymun zürafa anne baba kardeş arkadaş aile bebek " +
        "yaşlı öğretmen doktor işçi sporcu müzisyen ressam yazar kitap defter kalem masa sandalye pencere kapı mutfak salon banyo " +
        "balkon çatı duvar şölen düğün tatil gezi konser maç futbol basketbol yüzme koşu yürüyüş dans şarkı resim fotoğraf video " +
        "gülen koşan oynayan uyuyan yüzen").split(' ')
    private val trSuffixes = ("ler lar de da den dan te ta in ın un ün nin nın nun nün imiz ımız umuz ümüz leri ları yor muş miş mış " +
        "mak mek lik lık luk lük ci cı cu cü çi çı çu çü siz sız suz süz li lı lu lü e a ye ya i ı u ü yi yı ndan nde nda ki daki " +
        "ile yle yla sı si su sü m n k z ız iz uz üz dır dir dur dür lerimizden larımızdan").split(' ')
    private val enWords = ("beach child dog cat sunset mountain snow red blue green city lake rain tree flower house car bike bus plane " +
        "ship train food breakfast coffee cake fruit garden bird fish horse lion wolf bear family baby teacher doctor book table " +
        "window door kitchen wedding holiday concert football swimming running smiling playing sleeping").split(' ')
    private val punct = listOf(",", ".", "!", "?", "'", "-", ":", ";", "(", ")", "\"", "/", "&", "#", "%", "+")

    private fun isLatinAlphaToken(t: String): Boolean {
        val body = t.removePrefix("##")
        return body.isNotEmpty() && body.codePoints().toArray().all {
            Character.isLetter(it) && Character.UnicodeScript.of(it) == Character.UnicodeScript.LATIN
        }
    }

    private fun corpus(f: Fixture): List<String> {
        val rnd = Random(20261013L)
        val stems = f.orig.filter { !it.startsWith("##") && it.length >= 2 && isLatinAlphaToken(it) }
        val sufs = f.orig.filter { it.startsWith("##") && isLatinAlphaToken(it) }.map { it.removePrefix("##") }
        val out = ArrayList<String>(80_000)
        fun <T> pick(l: List<T>) = l[rnd.nextInt(l.size)]
        // 1) sözlükten: gövde + 0-3 ek
        repeat(20_000) { out += pick(stems) + (0 until pick(listOf(0, 1, 1, 2, 2, 3))).joinToString("") { pick(sufs) } }
        // 2) Türkçe gövde + ek zinciri: ham, BÜYÜK, Başlık, aksansız, aksansız BÜYÜK
        val trWords = List(8_000) { pick(trStems) + (0 until pick(listOf(0, 1, 2, 2, 3))).joinToString("") { pick(trSuffixes) } }
        for (w in trWords) { out += w; out += trUpper(w); out += trTitle(w); out += asciiFold(w); out += asciiFold(trUpper(w)) }
        // 3) İngilizce
        for (w in enWords) { out += w; out += w.replaceFirstChar { it.uppercase() }; out += w.uppercase() }
        // 4) sözcük + rakam + noktalama
        repeat(3_000) { out += pick(stems) + rnd.nextInt(10_000) + pick(punct) }
        // 5) rastgele cümleler
        repeat(10_000) {
            val n = 1 + rnd.nextInt(14)
            out += (0 until n).joinToString(" ") {
                var w = when (rnd.nextInt(10)) { in 0..4 -> pick(trWords); in 5..6 -> pick(enWords); else -> pick(stems) }
                val r = rnd.nextInt(100)
                if (r < 15) w = trUpper(w) else if (r < 30) w = trTitle(w) else if (r < 40) w = asciiFold(w)
                if (rnd.nextInt(100) < 12) w += pick(punct)
                if (rnd.nextInt(100) < 5) w = rnd.nextInt(3000).toString()
                w
            }
        }
        // 6) tarama: tutulan her token tek başına / ## tokenları bir gövdeye eklenmiş
        for (t in f.pruned) {
            if (t.startsWith("[")) continue
            out += if (t.startsWith("##")) "ev" + t.removePrefix("##") else t
            out += "ağaç" + t.removePrefix("##")
        }
        return out
    }

    @Test
    fun synthetic_tr_en_corpus_tokenTextsIdMappingAndUnkCountsEqual() {
        val f = fixture()
        val texts = corpus(f)
        assertTrue("en az 20.000 sözcük/ek kombinasyonu", texts.size >= 20_000 + 40_000)
        val ham = Tally()
        val norm = Tally()
        for (s in texts) {
            compare(f, s, ham)
            // Üretim yolu: QueryPreprocessor.normalize (boş/uzun sorgu InvalidQuery olabilir; sözcük tek başına geçerli)
            val q = try { QueryPreprocessor.normalize(s) } catch (_: Exception) { null }
            if (q != null) compare(f, q, norm)
        }
        assertEquals("ham metin ayrışmaları: ${ham.diffs}", 0, ham.diffs.size)
        assertEquals("normalize edilmiş metin ayrışmaları: ${norm.diffs}", 0, norm.diffs.size)
        assertEquals(ham.unkOrig, ham.unkPruned)
        assertEquals(norm.unkOrig, norm.unkPruned)
        assertTrue("yeterli büyüklük: ${ham.texts + norm.texts} metin", ham.texts + norm.texts >= 200_000)
        println("T-027 sentetik kume: ${ham.texts} ham + ${norm.texts} normalize metin, ${ham.tokens + norm.tokens} token, " +
            "[UNK] ham ${ham.unkOrig}=${ham.unkPruned}, norm ${norm.unkOrig}=${norm.unkPruned}")
    }

    @Test
    fun everyLatinScriptToken_ofOriginalVocab_isKept_acrossJvmUnicodeScripts() {
        val f = fixture()
        val kept = f.pruned.toHashSet()
        fun latinish(cp: Int) = (Character.isLetter(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN) ||
            cp in '0'.code..'9'.code || cp in 0x21..0x7E
        var latinTokens = 0
        val lost = ArrayList<String>()
        for (t in f.orig) {
            if (t.startsWith("[")) continue
            val body = t.removePrefix("##")
            if (body.isEmpty() || !body.codePoints().toArray().all(::latinish)) continue
            latinTokens++
            if (t !in kept) lost += t
        }
        assertTrue("Latin token sayısı makul: $latinTokens", latinTokens > 60_000)
        assertEquals("kaybedilen Latin tokenlar: ${lost.take(20)}", emptyList<String>(), lost)
        // Türkçe harfler ve ## devam parçaları (â î û dahil) ayrıca açıkça
        for (c in "çğıöşüâîûÇĞİÖŞÜÂÎÛ") {
            val fam = f.orig.filter { c in it }
            assertTrue("orijinalde $c içeren token yok", fam.isNotEmpty() || c in "ÂÎÛ")
            assertTrue("$c içeren Latin token atıldı", fam.filter { t -> t.removePrefix("##").all { ch -> ch in 'a'..'z' || ch in 'A'..'Z' || ch in "çğıöşüâîûÇĞİÖŞÜÂÎÛ" } }.all { it in kept })
        }
    }

    @Test
    fun negativeControl_aggressivePruning_isDetectedByTheSameComparison() {
        val f = fixture()
        // "ASCII + Türkçe" (B prototipi) gibi daha agresif bir budama; â î û ve diğer aksanlı Latin harfleri kaybeder.
        val trAscii = "çğıöşüÇĞİÖŞÜ"
        val b = f.pruned.filter { t -> t.startsWith("[") || t.removePrefix("##").all { it.code < 128 || it in trAscii } }
        assertTrue(b.size < f.pruned.size)
        val lossy = Fixture(f.orig, b)
        val tally = Tally()
        for (s in listOf("hâlâ", "kâğıt", "rüzgâr", "café", "naïve", "São Paulo", "Åre", "Łódź", "İstanbul", "ığdır")) compare(lossy, s, tally)
        assertTrue("karşılaştırma aracı kayıpları yakalamalı: ${tally.diffs}", tally.diffs.isNotEmpty())
        // ... fakat Türkçe ASCII+TR sorguları bu agresif sette de eşit kalır (kontrolün yalnızca gerçekten kayıp varken düştüğünü gösterir)
        val ok = Tally()
        for (s in listOf("kedi", "plajda gülen çocuk", "İstanbul")) compare(lossy, s, ok)
        assertEquals(0, ok.diffs.size)
    }

    @Test
    fun prunedModelSize_matchesRemovedRowsTimes768() {
        // Orijinal ONNX 135_336_307 bayt; embedding satırı 768 bayt (uint8). Boyut farkı tam olarak atılan satır x 768.
        assertEquals(135_336_307L - (GoldenText.ORIGINAL_VOCAB_SIZE - prunedLines.size) * 768L, ModelManifest.TEXT_ONNX.sizeBytes)
    }
}
