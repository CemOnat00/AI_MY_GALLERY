package com.ktu.aigaleri.ml

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ktu.aigaleri.data.AiGaleriDatabase
import com.ktu.aigaleri.data.MediaPhoto
import com.ktu.aigaleri.data.MediaPhotoSource
import com.ktu.aigaleri.data.MediaStorePhotoSource
import com.ktu.aigaleri.data.RoomPhotoIndexer
import com.ktu.aigaleri.data.RoomSearchRepository
import com.ktu.aigaleri.data.RoomTransactionRunner
import com.ktu.aigaleri.data.SearchPolicy
import com.ktu.aigaleri.data.VectorCodec
import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.domain.IndexPhase
import com.ktu.aigaleri.domain.InvalidQueryException
import com.ktu.aigaleri.domain.TextEncoder
import com.ktu.aigaleri.ui.AppDependencies
import com.ktu.aigaleri.ui.GalleryPermission
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-013: arama sonuç eşiği KALİBRASYON ölçümü ([SearchPolicy] seçimi için). GERÇEK modeller, GERÇEK [RoomPhotoIndexer] ve
 * MediaStore ile 60 etiketli açık lisanslı test fotoğrafı (10 sınıf, dosya adı `<etiket>_NN.jpg`) bellek içi Room'a
 * indekslenir; aramanın kullandığı kodlayıcı ([AppDependencies.searchTextEncoder], T-033 topluluğu) ile sabit bir sorgu kümesi
 * skorlanır. Önkoşul ([SearchQualityDeviceTest] ile aynı; yoksa ATLANIR): görseller `/sdcard/Pictures/aigaleri-test/` altında
 * ve MediaStore'da; görseller repoda YOKTUR. Uygulama veritabanına dokunulmaz.
 *
 * Sorgu grupları: VAR (galeride etiketi olan sınıflar: çıplak, "bir X fotoğrafı", çok sözcüklü), YOK (galeride olmayan
 * nesneler), YAKIN (galeride olmayan ama bir sınıfa yakın kavramlar: tekne/deniz gibi), ANLAMSIZ, GENEL.
 *
 * Çıktı: logcat etiketi [TAG] ve `getExternalFilesDir/th-report.txt` (adb pull). Tablolar: (1) sorgu başına top1, 2./5./10./20./50.
 * skor, en düşük ilgili ve en yüksek ilgisiz skor; (2) grup top1 dağılımı; (3) mutlak taban tablosu; (4) göreli marj tablosu;
 * (5) [SearchPolicy.DEFAULT] ile gerçek [RoomSearchRepository] öncesi/sonrası (hit@5, MRR, sonuç sayısı). Yalnızca sabit
 * test sorguları ve sayılar yazılır (fotoğraf yolu/URI yok); uygulama koduna log eklenmedi. Emülatör SME/KleidiAI SIGILL
 * geçici çözümü: [OrtEmulatorWorkaround]. Emülatör arm64 çekirdeği gerçek telefonu TEMSİL ETMEZ.
 */
@RunWith(AndroidJUnit4::class)
class SearchThresholdCalibrationDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val deps = AppDependencies.get(context)
    private lateinit var db: AiGaleriDatabase
    private val report = StringBuilder()

    private val labels = listOf("bardak", "kedi", "kopek", "araba", "deniz", "dag", "cicek", "pizza", "bisiklet", "kitap")

    private class Q(val group: String, val text: String, val gold: String?)

    private val queries: List<Q> = buildList {
        // VAR: çıplak TR, "bir X fotoğrafı", iki çok sözcüklü doğal cümle (etiket = beklenen sınıf).
        val var3 = listOf(
            Triple("bardak", "bardak", listOf("masada duran bir bardak", "bardakta su")),
            Triple("kedi", "kedi", listOf("çimde yatan kedi", "kanepede uyuyan sevimli kedi")),
            Triple("kopek", "köpek", listOf("parkta koşan köpek", "çimde oturan bir köpek")),
            Triple("araba", "araba", listOf("yolda giden kırmızı araba", "park halindeki araba")),
            Triple("deniz", "deniz", listOf("denizde gün batımı", "sahilde dalgalar")),
            Triple("dag", "dağ", listOf("karlı dağ manzarası", "bulutların arasındaki dağ")),
            Triple("cicek", "çiçek", listOf("bahçede açan sarı çiçek", "pembe çiçekler")),
            Triple("pizza", "pizza", listOf("peynirli pizza dilimi", "masadaki sıcak pizza")),
            Triple("bisiklet", "bisiklet", listOf("yolda bisiklet süren adam", "duvara dayalı bisiklet")),
            Triple("kitap", "kitap", listOf("masada açık kitap", "kitaplıktaki kitaplar")),
        )
        for ((gold, tr, multi) in var3) {
            add(Q("VAR_CIPLAK", tr, gold))
            add(Q("VAR_BIR_FOTO", "bir $tr fotoğrafı", gold))
            for (m in multi) add(Q("VAR_COKSOZ", m, gold))
        }
        for (t in listOf(
            "zürafa", "uçak", "gitar", "pasta", "saat", "ayakkabı", "bilgisayar", "fil", "helikopter", "telefon", "tren",
            "piyano", "şemsiye", "balon", "kamyon", "buzdolabı", "çanta", "gözlük", "futbol topu", "ceket",
        )) add(Q("YOK", t, null))
        for (t in listOf("gökyüzünde uçan uçak", "gitar çalan adam", "doğum günü pastası", "bir zürafa fotoğrafı", "masadaki laptop bilgisayar")) {
            add(Q("YOK_COKSOZ", t, null))
        }
        for (t in listOf("tekne", "aslan", "kurt", "motosiklet", "dergi", "kahve fincanı", "tepe", "gül")) add(Q("YAKIN", t, null))
        for (t in listOf(
            "asdfgh", "xqzvk", "aaaaaa", "123456", ". , !", "😀", "🔥🔥🔥", "a", "x", "ş", "qwertyuiop", "zzzzzzzz",
            "lorem ipsum dolor", "%%%%%", "k k k k k", "kjhgfd lkjh", "ğğğğ", "???", "12:45",
        )) add(Q("ANLAMSIZ", t, null))
        for (t in listOf(
            "fotoğraf", "resim", "güzel bir manzara", "bir fotoğraf", "manzara", "doğa", "renkli bir resim", "insanlar",
            "eski bir fotoğraf", "güzel bir gün",
        )) add(Q("GENEL", t, null))
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AiGaleriDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        runBlocking { deps.sessionSlot.closeAll() }
        db.close()
    }

    private fun out(line: String = "") {
        report.appendLine(line)
        Log.i(TAG, line)
    }

    private class FilteredSource(private val inner: MediaPhotoSource, private val ids: Set<Long>) : MediaPhotoSource {
        override suspend fun count() = ids.size
        override suspend fun loadPhotos(): List<MediaPhoto> = inner.loadPhotos().filter { it.id in ids }
        override suspend fun loadIds(): LongArray = inner.loadIds().filter { it in ids }.toLongArray()
        override suspend fun loadByIds(ids: List<Long>): List<MediaPhoto> = inner.loadByIds(ids.filter { it in this.ids })
    }

    /** MediaStore kimliği -> dosya adı öneki (etiket). Yol/ad yazdırılmaz. */
    private fun testImageLabels(): Map<Long, String> {
        val result = HashMap<Long, String>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("%aigaleri-test%"),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val label = (c.getString(1) ?: continue).substringBefore('_')
                if (label in labels) result[c.getLong(0)] = label
            }
        }
        return result
    }

    private fun dot(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i].toDouble() * b[i]
        return s
    }

    private fun f(x: Double) = String.format(Locale.ROOT, "%.4f", x)
    private fun f2(x: Double) = String.format(Locale.ROOT, "%.2f", x)

    /** Bir sorgunun azalan sıralı skoru ve her sıradaki görüntünün sorgunun etiketiyle eşleşip eşleşmediği. */
    private class Row(val q: Q, val s: DoubleArray, val rel: BooleanArray, val labelsSorted: List<String>) {
        val top1 get() = s[0]
        val words get() = q.text.trim().split(Regex("\\s+")).size
        val relCount get() = rel.count { it }
        fun count(margin: Double): Int { // top1 - margin altındakiler elenir (>= korunur)
            val th = s[0] - margin
            return s.count { it >= th }
        }
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    @Test
    fun calibrateSearchThreshold() = runBlocking {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, GalleryPermission.requiredPermission())
        val idToLabel = testImageLabels()
        assumeTrue("test görselleri MediaStore'da yok (adb push + scan gerekir)", idToLabel.size >= 10)

        // ---- İndeksleme (gerçek RoomPhotoIndexer + gerçek görüntü modeli) ----
        val store = deps.modelStore
        val slot = deps.sessionSlot
        OrtEmulatorWorkaround.seedVision(store, slot)
        val indexer = RoomPhotoIndexer(
            source = FilteredSource(MediaStorePhotoSource(context.contentResolver), idToLabel.keys),
            photoDao = db.photoDao(),
            embeddingDao = db.photoEmbeddingDao(),
            stateDao = db.indexStateDao(),
            transactions = RoomTransactionRunner(db),
            encoder = OnnxImageEncoder.create(context, store, slot),
        )
        val progress = indexer.index(IndexMode.FULL).toList()
        assertEquals(IndexPhase.COMPLETED, progress.last().phase)
        val rows = db.photoEmbeddingDao().getAllForModel(ModelManifest.MODEL_VERSION)
        assertEquals(progress.last().processed - progress.last().failed, rows.size)
        assertTrue(rows.size >= 10)
        val ids = rows.map { it.photoId }
        val imgs = rows.map { VectorCodec.decode(it.vector) }
        val lab = ids.map { idToLabel.getValue(it) }
        val n = ids.size
        out("== T-013 KALIBRASYON: galeri=$n foto, ${labels.size} sinif, sorgu=${queries.size} (emulator; gercek telefonu TEMSIL ETMEZ)")

        // ---- Skorlama (arama yolu kodlayıcısı: T-033 topluluğu) ----
        OrtEmulatorWorkaround.seedText(store, slot)
        val ens: TextEncoder = deps.searchTextEncoder
        val data = ArrayList<Row>()
        val invalid = ArrayList<Q>()
        for (q in queries) {
            val v = try {
                ens.encode(q.text)
            } catch (e: InvalidQueryException) {
                invalid += q
                continue
            }
            val sc = DoubleArray(n) { dot(v, imgs[it]) }
            val ord = (0 until n).sortedWith(compareByDescending<Int> { sc[it] }.thenBy { ids[it] })
            data += Row(q, DoubleArray(n) { sc[ord[it]] }, BooleanArray(n) { q.gold != null && lab[ord[it]] == q.gold }, ord.map { lab[it] })
        }
        for (q in invalid) out("GECERSIZ istem (InvalidQueryException, aramaya hic girmez): grup=${q.group} '${q.text}'")

        // ---- (1) sorgu başına tablo ----
        out("")
        out("== (1) SORGU BASINA: top1 s2 s5 s10 s20 s50 | ilgiliMin ilgisizMax | ilkIlgiliSira ilk5'teIlgili | top1-s5 top1-s10")
        for (r in data) {
            val s = r.s
            val relMin = if (r.relCount > 0) f(s.filterIndexed { i, _ -> r.rel[i] }.min()) else "-"
            val irrMax = if (r.relCount > 0) f(s.filterIndexed { i, _ -> !r.rel[i] }.max()) else "-"
            val first = if (r.relCount > 0) (r.rel.indexOfFirst { it } + 1).toString() else "-"
            val in5 = if (r.relCount > 0) r.rel.take(5).count { it }.toString() else "-"
            out(
                "${r.q.group.padEnd(11)} ${("'" + r.q.text + "'").padEnd(34)} w=${r.words} " +
                    "${f(s[0])} ${f(s[1])} ${f(s[4])} ${f(s[9])} ${f(s[19])} ${if (n >= 50) f(s[49]) else "-"} | $relMin $irrMax | " +
                    "$first $in5 | ${f(s[0] - s[4])} ${f(s[0] - s[9])}",
            )
        }

        // ---- (2) grup top1 dağılımı ----
        val groups = listOf("VAR_CIPLAK", "VAR_BIR_FOTO", "VAR_COKSOZ", "YOK", "YOK_COKSOZ", "YAKIN", "ANLAMSIZ", "GENEL")
        out("")
        out("== (2) GRUP top1 dagilimi: n min medyan maks | top1-s10 medyan | top1-s50 medyan")
        fun groupRows(g: String) = data.filter { it.q.group == g }
        for (g in groups) {
            val rs = groupRows(g)
            if (rs.isEmpty()) continue
            val t = rs.map { it.top1 }
            out(
                "${g.padEnd(13)} n=${rs.size} min=${f(t.min())} med=${f(median(t))} max=${f(t.max())} | " +
                    "top1-s10 med=${f(median(rs.map { it.s[0] - it.s[9] }))} | top1-s50 med=${f(median(rs.map { it.s[0] - it.s[minOf(49, n - 1)] }))}",
            )
        }
        val varRows = data.filter { it.q.group.startsWith("VAR_") }
        val varShort = varRows.filter { it.words <= 3 }
        val varLong = varRows.filter { it.words > 3 }
        val noRows = data.filter { it.q.group in setOf("YOK", "YOK_COKSOZ", "ANLAMSIZ") }
        fun dist(name: String, rs: List<Row>) {
            if (rs.isEmpty()) return
            val t = rs.map { it.top1 }
            out("$name n=${rs.size} min=${f(t.min())} med=${f(median(t))} max=${f(t.max())}")
        }
        dist("VAR tum         ", varRows)
        dist("VAR kisa(<=3)   ", varShort)
        dist("VAR uzun(>3)    ", varLong)
        dist("YOK+ANLAMSIZ    ", noRows)
        dist("YOK+ANLAMSIZ+YAKIN+GENEL", data.filter { !it.q.group.startsWith("VAR_") })
        if (varRows.isNotEmpty() && noRows.isNotEmpty()) {
            val gap = varRows.minOf { it.top1 } - noRows.maxOf { it.top1 }
            out("mutlak taban AYRISMA: min(VAR top1)=${f(varRows.minOf { it.top1 })} - maks(YOK+ANLAMSIZ top1)=${f(noRows.maxOf { it.top1 })} = ${f(gap)} " +
                if (gap > 0) "(AYRISIYOR)" else "(ORTUSUYOR)")
        }
        out("kisa sorgu icin: min(VAR kisa top1)=${f(varShort.minOf { it.top1 })} vs maks(YOK/ANLAMSIZ kisa top1)=${f(noRows.filter { it.words <= 3 }.maxOf { it.top1 })}")

        // ---- (3) mutlak taban tablosu ----
        out("")
        out("== (3) MUTLAK TABAN f: top1 < f ise bos. VARbosOran (YANLIS bos; 0 olmali) | YOK/ANLAMSIZ bos oran | YAKIN bos | GENEL bos")
        var f0 = 0.20
        while (f0 <= 0.30001) {
            fun rate(rs: List<Row>) = if (rs.isEmpty()) 0.0 else rs.count { it.top1 < f0 }.toDouble() / rs.size
            out("f=${f(f0)} VAR=${f2(rate(varRows))} VARkisa=${f2(rate(varShort))} VARuzun=${f2(rate(varLong))} YOK+ANLAMSIZ=${f2(rate(noRows))} " +
                "YAKIN=${f2(rate(groupRows("YAKIN")))} GENEL=${f2(rate(groupRows("GENEL")))}")
            f0 += 0.005
        }

        // ---- (4) göreli marj tablosu ----
        out("")
        out("== (4) GORELI MARJ m: skor < top1-m elenir. VAR: tumIlgiliKorunanSorgu | ilgiliKorunmaOrt | hit@5 | MRR | ort sonuc sayisi | ort ilgisiz kalan")
        val needed = varRows.map { r -> r.s[0] - r.s.filterIndexed { i, _ -> r.rel[i] }.min() }
        out("VAR sorgu basina TUM ilgilileri korumak icin gereken marj (top1 - en dusuk ilgili): min=${f(needed.min())} medyan=${f(median(needed))} maks=${f(needed.max())}")
        val needFirst = varRows.map { r -> r.s[0] - r.s[r.rel.indexOfFirst { it }] }
        out("VAR sorgu basina ILK ilgiliyi korumak icin gereken marj (top1 - ilk ilgili skor): maks=${f(needFirst.max())}")
        val need5 = varRows.map { r -> val i5 = r.rel.take(5).indexOfFirst { it }; if (i5 < 0) Double.NaN else r.s[0] - r.s[i5] }
        out("VAR hit@5'i korumak icin gereken marj (top1 - ilk5'teki ilk ilgili): maks=${f(need5.filter { !it.isNaN() }.maxOrNull() ?: Double.NaN)}")
        val margins = listOf(0.005, 0.01, 0.015, 0.02, 0.025, 0.03, 0.04, 0.05, 0.06, 0.08, 0.10)
        fun varLine(name: String, rs: List<Row>, m: Double) {
            if (rs.isEmpty()) return
            var allKept = 0
            var relKept = 0.0
            var h5 = 0
            var mrr = 0.0
            var cnt = 0.0
            var irr = 0.0
            for (r in rs) {
                val th = r.s[0] - m
                val keptRel = r.s.indices.count { r.rel[it] && r.s[it] >= th }
                if (keptRel == r.relCount) allKept++
                relKept += keptRel.toDouble() / r.relCount
                val firstKept = r.s.indices.firstOrNull { r.rel[it] && r.s[it] >= th }
                if (firstKept != null && firstKept < 5) h5++
                if (firstKept != null) mrr += 1.0 / (firstKept + 1)
                cnt += r.count(m)
                irr += r.s.indices.count { !r.rel[it] && r.s[it] >= th }
            }
            val k = rs.size.toDouble()
            out("m=${f(m)} $name n=${rs.size} tumKorunan=${f2(allKept / k)} ilgiliKorunma=${f2(relKept / k)} hit@5=${f2(h5 / k)} MRR=${f2(mrr / k)} " +
                "sonuc=${f2(cnt / k)} ilgisizKalan=${f2(irr / k)}")
        }
        fun noLine(name: String, rs: List<Row>, m: Double) {
            if (rs.isEmpty()) return
            val c = rs.map { it.count(m).toDouble() }
            out("m=${f(m)} $name n=${rs.size} sonuc: ort=${f2(c.average())} medyan=${f2(median(c))} maks=${c.max().toInt()} <=5 oran=${f2(c.count { it <= 5 }.toDouble() / c.size)} " +
                "<=10 oran=${f2(c.count { it <= 10 }.toDouble() / c.size)} (n=$n)")
        }
        for (m in margins) {
            varLine("VAR_TUM  ", varRows, m)
            varLine("VAR_KISA ", varShort, m)
            varLine("VAR_UZUN ", varLong, m)
            noLine("YOK       ", groupRows("YOK") + groupRows("YOK_COKSOZ"), m)
            noLine("YAKIN     ", groupRows("YAKIN"), m)
            noLine("ANLAMSIZ  ", groupRows("ANLAMSIZ"), m)
            noLine("GENEL     ", groupRows("GENEL"), m)
            out("")
        }

        // ---- (5) SearchPolicy.DEFAULT ile gerçek arama yolu: öncesi / sonrası ----
        val policy = SearchPolicy.DEFAULT
        out("== (5) POLITIKA: $policy")
        val before = RoomSearchRepository(encoder = ens, dao = db.photoEmbeddingDao())
        val after = RoomSearchRepository(encoder = ens, dao = db.photoEmbeddingDao(), policy = policy)
        class Agg { var n = 0; var h5 = 0; var mrr = 0.0; var cnt = 0.0; var empty = 0; val counts = ArrayList<Int>() }
        val aggB = HashMap<String, Agg>()
        val aggA = HashMap<String, Agg>()
        var relDropped = 0
        for (q in queries) {
            if (q in invalid) continue
            val rb = before.search(q.text, 50)
            val ra = after.search(q.text, 50)
            // sıra korunur: sonrası, öncesinin önek'idir
            assertEquals("${q.group}: politika sırayı bozmamalı", rb.take(ra.size).map { it.photoId }, ra.map { it.photoId })
            assertTrue("sonuç sayısı <= limit", ra.size <= 50)
            if (ra.isEmpty()) assertTrue("boş sonuç yalnızca taban altı olabilir", rb.isNotEmpty())
            for ((res, agg) in listOf(rb to aggB, ra to aggA)) {
                val a = agg.getOrPut(q.group) { Agg() }
                a.n++
                a.cnt += res.size
                a.counts += res.size
                if (res.isEmpty()) a.empty++
                if (q.gold != null) {
                    val rank = res.indexOfFirst { idToLabel[it.photoId] == q.gold } + 1
                    if (rank in 1..5) a.h5++
                    if (rank > 0) a.mrr += 1.0 / rank
                }
            }
            if (q.gold != null) {
                val relBefore = rb.count { idToLabel[it.photoId] == q.gold }
                val relAfter = ra.count { idToLabel[it.photoId] == q.gold }
                relDropped += relBefore - relAfter
            }
        }
        out("grup          | ONCE: n hit@5 MRR ort.sonuc bos | SONRA: hit@5 MRR ort.sonuc medyan maks bos")
        for (g in groups) {
            val b = aggB[g] ?: continue
            val a = aggA.getValue(g)
            val isVar = g.startsWith("VAR_")
            fun sc(x: Agg) = if (isVar) "hit@5=${f2(x.h5.toDouble() / x.n)} MRR=${f2(x.mrr / x.n)}" else "hit@5=- MRR=-"
            val cs = a.counts.sorted()
            out("${g.padEnd(13)} | n=${b.n} ${sc(b)} sonuc=${f2(b.cnt / b.n)} bos=${b.empty} | ${sc(a)} sonuc=${f2(a.cnt / a.n)} " +
                "medyan=${cs[cs.size / 2]} maks=${cs.last()} bos=${a.empty}")
        }
        out("VAR sorgularda politikanin eledigi toplam ilgili sonuc sayisi: $relDropped (50 limitli listede; ilgili = etiketi dogru)")
        assertEquals("politika ölçüm kümesinde ilgili sonuç elememeli", 0, relDropped)
        // Muhafazakar kapı: VAR sorgularda hit@5 ve MRR düşmemeli.
        for (g in groups.filter { it.startsWith("VAR_") }) {
            val b = aggB.getValue(g)
            val a = aggA.getValue(g)
            assertEquals("$g hit@5 politika ile düşmemeli", b.h5, a.h5)
            assertTrue("$g MRR politika ile düşmemeli", a.mrr >= b.mrr - 1e-9)
        }

        File(context.getExternalFilesDir(null), "th-report.txt").writeText(report.toString())
        Log.i(TAG, "rapor dosyasi: th-report.txt (uygulamanin harici files dizini)")
        Unit
    }

    private companion object {
        const val TAG = "ThresholdCalib"
    }
}
