package com.ktu.aigaleri.ml

import android.Manifest
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
import com.ktu.aigaleri.data.VectorCodec
import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.domain.IndexPhase
import com.ktu.aigaleri.domain.TextEncoder
import com.ktu.aigaleri.ui.AppDependencies
import com.ktu.aigaleri.ui.GalleryPermission
import java.io.File
import kotlin.math.sqrt
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
 * T-012 resimli arama kalitesi ÖLÇÜMÜ (T-033 öncesi/sonrası karşılaştırma dahil). GERÇEK modeller, GERÇEK
 * [RoomPhotoIndexer] ve MediaStore ile etiketli (dosya adı öneki `<etiket>_NN.jpg`) açık lisanslı nesne/hayvan/manzara
 * fotoğraflarını indeksler (bellek içi Room; uygulama veritabanına dokunmaz), sonra sorgu varyantlarını sıralar.
 *
 * Önkoşul (yoksa test ATLANIR): görseller `/sdcard/Pictures/aigaleri-test/` altında ve MediaStore'a işlenmiş
 * (`adb push` + `am broadcast MEDIA_SCANNER_SCAN_FILE`); görseller repoda YOKTUR (scratchpad, bkz. T-012 raporu).
 *
 * Varyantlar: (1) çıplak sorgu = `AppDependencies.textEncoder`; (2) T-033 topluluğu = `AppDependencies.searchTextEncoder`
 * (aramanın kullandığı kodlayıcı); (3) elle şablon "bir X fotoğrafı" (çıplak kodlayıcı); ayrıca İngilizce çıplak ve
 * "a photo of a X". Çıktı: logcat etiketi [TAG] ve `getExternalFilesDir/sq-report.txt` (adb pull). Yalnızca etiket ve sayı yazılır
 * (fotoğraf yolu/URI yok). Uygulama koduna log eklenmedi. Emülatör SME/KleidiAI SIGILL geçici çözümü: [OrtEmulatorWorkaround].
 */
@RunWith(AndroidJUnit4::class)
class SearchQualityDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val deps = AppDependencies.get(context)
    private lateinit var db: AiGaleriDatabase
    private val report = StringBuilder()

    private val labels = listOf("bardak", "kedi", "kopek", "araba", "deniz", "dag", "cicek", "pizza", "bisiklet", "kitap")
    private val tr = listOf("bardak", "kedi", "köpek", "araba", "deniz", "dağ", "çiçek", "pizza", "bisiklet", "kitap")
    private val en = listOf("cup", "cat", "dog", "car", "sea", "mountain", "flower", "pizza", "bicycle", "book")

    private class Query(val variant: String, val text: String, val gold: String, val vector: FloatArray)

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

    /** Yalnızca test klasöründeki görseller (MediaStore'da başka görsel olsa bile). */
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
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("%aigaleri-test%"),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val label = name.substringBefore('_')
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

    private fun norm(a: FloatArray) = sqrt(dot(a, a))

    private fun f(x: Double) = String.format(java.util.Locale.ROOT, "%.4f", x)

    @Test
    fun measureSearchQuality_labelledImages() = runBlocking {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, GalleryPermission.requiredPermission())
        val idToLabel = testImageLabels()
        assumeTrue("test görselleri MediaStore'da yok (adb push + scan gerekir)", idToLabel.size >= 10)

        // ---- İndeksleme (gerçek RoomPhotoIndexer + gerçek görüntü modeli) ----
        val store = deps.modelStore
        val slot = deps.sessionSlot
        val t0 = System.nanoTime()
        OrtEmulatorWorkaround.seedVision(store, slot)
        val source = FilteredSource(MediaStorePhotoSource(context.contentResolver), idToLabel.keys)
        val indexer = RoomPhotoIndexer(
            source = source,
            photoDao = db.photoDao(),
            embeddingDao = db.photoEmbeddingDao(),
            stateDao = db.indexStateDao(),
            transactions = RoomTransactionRunner(db),
            encoder = OnnxImageEncoder.create(context, store, slot),
        )
        val progress = indexer.index(IndexMode.FULL).toList()
        val indexMs = (System.nanoTime() - t0) / 1_000_000
        val last = progress.last()
        assertEquals(IndexPhase.COMPLETED, last.phase)
        out("== INDEKS: foto=${idToLabel.size} islenen=${last.processed} basarisiz=${last.failed} sure=${indexMs}ms " +
            "(${indexMs / maxOf(1, last.processed)} ms/foto; emulator, gercek telefonu TEMSIL ETMEZ)")
        val rows = db.photoEmbeddingDao().getAllForModel(ModelManifest.MODEL_VERSION)
        assertEquals("indekslenen vektor sayisi", last.processed - last.failed, rows.size)
        assertTrue(rows.size >= 10)
        val ids = rows.map { it.photoId }
        val imgs = rows.map { VectorCodec.decode(it.vector) }
        val lab = ids.map { idToLabel.getValue(it) }
        val n = ids.size
        for (v in imgs) assertEquals(512, v.size)

        // ---- Sorgu vektörleri ----
        OrtEmulatorWorkaround.seedText(store, slot)
        val bare: TextEncoder = deps.textEncoder
        val ens: TextEncoder = deps.searchTextEncoder

        val tOpen = System.nanoTime()
        bare.encode("ısınma")
        val coldMs = (System.nanoTime() - tOpen) / 1_000_000
        val lat = LongArray(30) { i ->
            val t = System.nanoTime()
            bare.encode(tr[i % tr.size])
            (System.nanoTime() - t) / 1000
        }.sorted()
        val latEns = LongArray(20) { i ->
            val t = System.nanoTime()
            ens.encode(tr[i % tr.size])
            (System.nanoTime() - t) / 1000
        }.sorted()
        out("== SURE (emulator; T-020 on hazirlik, gercek telefonu TEMSIL ETMEZ): ilk kodlama(oturum acik, sozluk+dense yukleme dahil)=${coldMs}ms " +
            "tek sorgu cikarimi medyan=${lat[lat.size / 2] / 1000.0}ms p90=${lat[(lat.size * 9) / 10] / 1000.0}ms; " +
            "T-033 toplulugu medyan=${latEns[latEns.size / 2] / 1000.0}ms")

        val queries = ArrayList<Query>()
        for (i in labels.indices) {
            queries += Query("TR_BARE", tr[i], labels[i], bare.encode(tr[i]))
            queries += Query("TR_ENS_T033", tr[i], labels[i], ens.encode(tr[i]))
            queries += Query("TR_TEMPLATE", "bir ${tr[i]} fotoğrafı", labels[i], bare.encode("bir ${tr[i]} fotoğrafı"))
            queries += Query("EN_BARE", en[i], labels[i], bare.encode(en[i]))
            queries += Query("EN_TEMPLATE", "a photo of a ${en[i]}", labels[i], bare.encode("a photo of a ${en[i]}"))
        }
        // "bardak" ek sorgular (hem çıplak hem T-033)
        for (text in listOf("bir bardak fotoğrafı", "bardak fotoğrafı", "su bardağı", "bir bardak")) {
            queries += Query("EXTRA_BARE", text, "bardak", bare.encode(text))
            queries += Query("EXTRA_ENS_T033", text, "bardak", ens.encode(text))
        }

        fun scores(q: Query) = DoubleArray(n) { dot(q.vector, imgs[it]) }
        fun order(s: DoubleArray) = (0 until n).sortedWith(compareByDescending<Int> { s[it] }.thenBy { ids[it] })

        // ---- Varyant tablosu ----
        out("")
        out("== VARYANT TABLOSU (galeri=$n foto, etiket basina ~${n / labels.size}); hit@1, hit@5, MRR, P@5 (ilk 5'te dogru etiket orani)")
        val variants = listOf("TR_BARE", "TR_ENS_T033", "TR_TEMPLATE", "EN_BARE", "EN_TEMPLATE")
        val summary = LinkedHashMap<String, DoubleArray>()
        for (v in variants) {
            out("-- $v")
            var h1 = 0.0
            var h5 = 0.0
            var mrr = 0.0
            var p5 = 0.0
            for (q in queries.filter { it.variant == v }) {
                val s = scores(q)
                val ord = order(s)
                val rank = ord.indexOfFirst { lab[it] == q.gold } + 1
                val top5 = ord.take(5)
                val a1 = if (rank == 1) 1.0 else 0.0
                val a5 = if (rank in 1..5) 1.0 else 0.0
                val pr = top5.count { lab[it] == q.gold } / 5.0
                h1 += a1; h5 += a5; mrr += if (rank > 0) 1.0 / rank else 0.0; p5 += pr
                out("   ${q.gold.padEnd(9)} ilk5=${top5.joinToString(",") { lab[it] }} ilkDogruSira=$rank skor1=${f(s[ord[0]])}")
            }
            val m = labels.size.toDouble()
            summary[v] = doubleArrayOf(h1 / m, h5 / m, mrr / m, p5 / m)
            out("   >> $v: hit@1=${f(h1 / m)} hit@5=${f(h5 / m)} MRR=${f(mrr / m)} P@5=${f(p5 / m)}")
        }
        out("")
        out("== OZET (10 etiket ortalamasi)")
        out("varyant        hit@1   hit@5   MRR     P@5")
        for ((v, a) in summary) out("${v.padEnd(13)}  ${f(a[0])}  ${f(a[1])}  ${f(a[2])}  ${f(a[3])}")

        // ---- bardak odak: ilk 10 etiket dağılımı + skorlar ----
        out("")
        out("== 'bardak' ILK 10 (etiket:skor) ve etiket dagilimi")
        val bardakQs = queries.filter { it.gold == "bardak" }
        for (q in bardakQs) {
            val s = scores(q)
            val ord = order(s)
            val top10 = ord.take(10)
            val dist = top10.groupingBy { lab[it] }.eachCount().entries.sortedByDescending { it.value }
                .joinToString(",") { "${it.key}=${it.value}" }
            out("${q.variant.padEnd(14)} '${q.text}': " + top10.joinToString(" ") { "${lab[it]}:${f(s[it])}" })
            out("   dagilim: $dist")
        }
        out("")
        out("== 'bardak' sorgu vektorunun etiket basina ORTALAMA skoru (hangi etiket kumesine yakin)")
        for (q in bardakQs.filter { it.variant in setOf("TR_BARE", "TR_ENS_T033", "TR_TEMPLATE", "EN_BARE", "EN_TEMPLATE") }) {
            val s = scores(q)
            out("${q.variant.padEnd(14)} " + labels.joinToString(" ") { l ->
                val idx = (0 until n).filter { lab[it] == l }
                "$l=${f(idx.map { s[it] }.average())}"
            })
        }

        // ---- Hubness ----
        out("")
        out("== HUBNESS: goruntu vektoru normu (L2 normalize bekleniyor)")
        val norms = imgs.map { norm(it) }
        out("norm min=${f(norms.min())} max=${f(norms.max())} ort=${f(norms.average())}")
        out("== goruntu-goruntu ortalama kosinus (satir/sutun = etiket; kosegen = etiket ici, kendisi haric)")
        out("          " + labels.joinToString(" ") { it.take(8).padEnd(8) })
        val inter = ArrayList<Double>()
        val intra = ArrayList<Double>()
        val meanToOthers = HashMap<String, Double>()
        for (a in labels) {
            val ia = (0 until n).filter { lab[it] == a }
            val cells = labels.map { b ->
                val ib = (0 until n).filter { lab[it] == b }
                val vals = ArrayList<Double>()
                for (x in ia) for (y in ib) if (x != y) vals += dot(imgs[x], imgs[y])
                val mean = vals.average()
                if (a == b) intra += mean else inter += mean
                mean
            }
            out(a.take(9).padEnd(10) + cells.joinToString(" ") { f(it).padEnd(8) })
            val others = ia.flatMap { x -> (0 until n).filter { lab[it] != a }.map { y -> dot(imgs[x], imgs[y]) } }
            meanToOthers[a] = others.average()
        }
        out("etiket ici ort=${f(intra.average())}  etiketler arasi ort=${f(inter.average())}")
        out("etiket -> diger etiketlerin goruntulerine ort kosinus (yuksek = hub): " +
            meanToOthers.entries.sortedByDescending { it.value }.joinToString(" ") { "${it.key}=${f(it.value)}" })

        out("")
        out("== SORGU SKORLARI: 1. / 10. / 50. skor (galeri $n; 50. yoksa '-')")
        for (q in queries) {
            val s = scores(q).sortedDescending()
            val s50 = if (n >= 50) f(s[49]) else "-"
            out("${q.variant.padEnd(14)} ${q.gold.padEnd(9)} s1=${f(s[0])} s10=${f(s[9])} s50=$s50 fark(1-10)=${f(s[0] - s[9])}")
        }

        out("")
        out("== K-OCCURRENCE (N5): hangi etiketin goruntuleri tum SORGULARIN ilk-5'inde kac kez gorunuyor (hub gostergesi)")
        val nq = queries.size
        val occ = IntArray(n)
        for (q in queries) order(scores(q)).take(5).forEach { occ[it]++ }
        out("toplam sorgu=$nq, her sorgu 5 slot; beklenen etiket payi ~${5 * nq / labels.size}")
        out(labels.joinToString(" ") { l -> "$l=${(0 until n).filter { lab[it] == l }.sumOf { occ[it] }}" })
        val worstImg = (0 until n).sortedByDescending { occ[it] }.take(5)
        out("en cok gorunen 5 goruntu (etiket:sayi): " + worstImg.joinToString(" ") { "${lab[it]}:${occ[it]}" })
        out("hic gorunmeyen goruntu orani: ${f(occ.count { it == 0 }.toDouble() / n)}")

        out("")
        out("== METIN-METIN kosinus (cok dilli hizalama / TR tek sozcuk cokusu)")
        fun q(variant: String, i: Int) = queries.filter { it.variant == variant }[i].vector
        out("TR_BARE <-> EN_BARE (ayni kavram): " + labels.indices.joinToString(" ") { "${labels[it]}=${f(dot(q("TR_BARE", it), q("EN_BARE", it)))}" })
        out("TR_BARE <-> TR_TEMPLATE: " + labels.indices.joinToString(" ") { "${labels[it]}=${f(dot(q("TR_BARE", it), q("TR_TEMPLATE", it)))}" })
        out("TR_BARE <-> TR_ENS_T033: " + labels.indices.joinToString(" ") { "${labels[it]}=${f(dot(q("TR_BARE", it), q("TR_ENS_T033", it)))}" })
        val pairs = ArrayList<Double>()
        for (i in labels.indices) for (j in i + 1 until labels.size) pairs += dot(q("TR_BARE", i), q("TR_BARE", j))
        val pairsEn = ArrayList<Double>()
        for (i in labels.indices) for (j in i + 1 until labels.size) pairsEn += dot(q("EN_BARE", i), q("EN_BARE", j))
        out("farkli kavramlar arasi ort kosinus: TR_BARE=${f(pairs.average())} EN_BARE=${f(pairsEn.average())}")
        out("bardak<->kedi: TR_BARE=${f(dot(q("TR_BARE", 0), q("TR_BARE", 1)))} TR_ENS=${f(dot(q("TR_ENS_T033", 0), q("TR_ENS_T033", 1)))} EN_BARE=${f(dot(q("EN_BARE", 0), q("EN_BARE", 1)))}")

        // ---- Gerçek arama yolu ile tutarlılık (RoomSearchRepository + searchTextEncoder) ----
        val repo = RoomSearchRepository(encoder = ens, dao = db.photoEmbeddingDao())
        val repoTop = repo.search("bardak", 5).map { it.photoId }
        val manual = order(scores(queries.first { it.variant == "TR_ENS_T033" && it.gold == "bardak" })).take(5).map { ids[it] }
        out("")
        out("== RoomSearchRepository('bardak') ilk5 etiket=${repoTop.map { idToLabel.getValue(it) }} (manuel ile ayni=${repoTop == manual})")
        assertEquals("repository sıralaması elle hesapla aynı olmalı", manual, repoTop)

        // Boru hattı sağlamlık bekçisi (kalite iddiası değil): İngilizce şablon ortalama hit@5 anlamlı olmalı.
        assertTrue("EN_TEMPLATE hit@5 çok düşük: ${summary.getValue("EN_TEMPLATE")[1]} (görüntü hattı bozuk olabilir)", summary.getValue("EN_TEMPLATE")[1] >= 0.5)

        val file = File(context.getExternalFilesDir(null), "sq-report.txt")
        file.writeText(report.toString())
        Log.i(TAG, "rapor dosyasi: sq-report.txt (uygulamanin harici files dizini)")
        Unit
    }

    private companion object {
        const val TAG = "SearchQuality"
    }
}
