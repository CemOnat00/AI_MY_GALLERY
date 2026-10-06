package com.ktu.aigaleri.data

import com.ktu.aigaleri.domain.SearchRepository
import com.ktu.aigaleri.domain.SearchResult
import com.ktu.aigaleri.domain.TextEncoder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Gerçek [SearchRepository] (T-008): istemi [TextEncoder] ile vektöre çevirir, Room'daki ÖNCEDEN HESAPLANMIŞ
 * görüntü vektörleriyle kosinüs benzerliğini hesaplar. Arama sırasında yalnızca metin kodlayıcı çalışır; görüntü
 * analizi, ML Kit, ağ yoktur. İstem, yol ve vektör log'a yazılmaz.
 *
 * Sözleşme ([SearchRepository] KDoc'una birebir uyar):
 * - Yalnızca `encoder.embeddingSpec.modelVersion` ile yazılmış kayıtlar taranır (eski sürüm aramaya girmez).
 * - Sıra: skor azalan, eşitlikte `photoId` artan; ilk `limit` sonuç. İndeks boşsa boş liste.
 * - `limit <= 0` -> [IllegalArgumentException] (kodlayıcı çağrılmadan önce).
 * - Geçersiz istem -> [com.ktu.aigaleri.domain.InvalidQueryException] kodlayıcıdan aynen geçer.
 * - İptal -> `CancellationException` (kodlayıcı çağrısında ve her sayfa döngüsünde `ensureActive`).
 * - Kodlayıcı `spec.dimension` dışında uzunlukta, sonlu olmayan veya birim normdan sapan (|norm - 1| > [QUERY_NORM_TOLERANCE])
 *   bir vektör dönerse [IllegalStateException] (spec/model uyuşmazlığı veya bozuk kodlayıcı; sorgu metni mesajda yer
 *   almaz). Bu sessizce yanlış sıralama/skor üretmekten iyidir. Norm denetimi bilinçli seçildi: skor "kosinüs" olarak
 *   belgelenir ([-1, 1]) ve [TextEncoder] sözleşmesi norm 1 der; sözleşmenin ihlali burada yakalanır.
 * - İndeks kaydı bozuksa (BLOB uzunluğu `dimension*4` değil, skor sonlu değil, uri boş) o kayıt aramada ATLANIR, arama
 *   düşmez. Bozuk kayıt otomatik düzelmez: `idsNeedingIndex` doğru `modelVersion`/`indexVersion` taşıyan bozuk satırı
 *   INCREMENTAL'da yeniden işlemez; yalnızca FULL yeniden indeksleme düzeltir. Taranan kayıtların TAMAMI atlanırsa
 *   (taranan > 0 ve atlanan == taranan; ör. vektör boyutu `modelVersion` artırılmadan değişti) sessizce "bulunamadı"
 *   dönmek yerine [IllegalStateException] fırlatılır (UI genel hata gösterir). Atlanan sayısı log'a yazılmaz.
 * - Sınır: Room, tek satırı CursorWindow'a (~2 MB) sığmayan BLOB için `SQLiteBlobTooBigException` fırlatabilir; 512
 *   boyutlu vektör ~2 KB olduğundan normalde olmaz, ama bozuk/dev boyutlu bir BLOB sayfayı ve aramayı düşürür (atlama
 *   mekanizması bu durumu kapsamaz; istisna olduğu gibi yayılır).
 *
 * Skor: vektörler L2 normalize olduğundan kosinüs = nokta çarpımı ([-1, 1]); sorgu normu yukarıdaki denetimle ~1'dir.
 *
 * ## Bellek ve hız kararı: sayfalı tarama, ÖNBELLEK YOK
 * Hedef 50-100 bin fotoğraf. Tüm vektörleri tutan önbellek 512 x 4 B x 100 bin = ~205 MB (kayıtlar + uri ile
 * daha fazla) eder; tipik Android Java yığını (256-512 MB) ve ayrıca açık duran ~135 MB metin modeli ile
 * birlikte bu OOM/low-memory-kill riski taşır. Önbellek ayrıca indeksleme sürerken sürekli geçersizleşir
 * (WorkManager işi yazar) ve geçersiz kılma/çok thread senkronizasyonu karmaşıklığı getirir. Bu yüzden her arama
 * [PhotoEmbeddingDao.getPageForModel] ile [pageSize] (varsayılan 256) satırlık keyset sayfalarla tarar:
 * - Tepe ek bellek: bir sayfa (~256 x 2,1 KB ≈ 0,55 MB BLOB) + tek `FloatArray(512)` tarama tamponu
 *   ([VectorCodec.decodeInto], satır başına tahsis yok) + top-K heap ([TopKCollector]: `limit` giriş, vektör yok).
 *   Toplam birkaç MB, indeks boyutundan bağımsız.
 * - Süre (ÖLÇÜLMEDİ; cihazda DOĞRULANMADI): 100 bin kayıtta ~51 milyon çarpma-toplama + ~51 milyon bayt->float
 *   çözme (CPU) ve SQLite okuması. Room her satır için ~2 KB `ByteArray` + `String` ayırır ve CursorWindow'dan kopyalar:
 *   arama başına ~205 MB kısa ömürlü çöp, GC baskısı oluşur. Tahmin: 10 bin kayıtta ~0,1-0,5 sn, 100 bin kayıtta
 *   orta seviye cihazda 1-4 sn aralığı (ölçüm yok; yalnızca büyüklük mertebesi). Ölçümde 100 bin için ~2-3 sn
 *   aşılırsa bir sonraki adım int8 nicemlenmiş bellek önbelleği (~50 MB) olur; şimdi erken optimizasyon sayıldı.
 *   Tarama thread'i [dispatcher] (varsayılan Default) üzerindedir, ana thread değil.
 * - Tutarlılık: sayfalar arası işlem yoktur; tarama sürerken indeksleyici yazarsa yeni satır görünebilir veya
 *   görünmeyebilir, hiçbir kimlik iki kez işlenmez.
 *
 * ## Asgari skor eşiği kararı: varsayılan YOK ([minScore] = null), her zaman top-K döner
 * CLIP türü modellerde metin-görüntü kosinüs skorları genelde dar bir aralıkta toplanır ve sorguya göre kayar; bu,
 * literatür/genel deneyime dayanan bir beklentidir, BU model ve bu galeri için ÖLÇÜLMEMİŞTİR (sayısal aralık verilmez).
 * Doğru ve yanlış eşleşmelerin skor aralıklarının örtüşmesi, çok dilli damıtılmış metin kodlayıcının (Türkçe) ve int8
 * görüntü nicemlemesinin dağılımı kaydırması olasıdır. Gerçek galeri/Türkçe istem ölçümü yok (model-research.md
 * Bölüm 4); ölçümsüz mutlak bir eşik ya iyi sonuçları keser ya da hiçbir şeyi elemez. Bu yüzden eşik yoktur, sıralama sorumluluğu rank'tadır.
 * Ölçüm yapıldığında eşik [minScore] ile (yapıcı parametresi) kod değişmeden açılabilir.
 * UI etkisi: eşik yokken "sonuç yok" (`SearchStatus.Empty`) yalnızca indeks boşken (hiç/henüz güncel modelle
 * indekslenmiş fotoğraf yok) görünür; indeksli galeride geçerli her istem en çok `limit` sonuç döndürür,
 * anlamsız istemde de alakasız sonuçlar listelenir. Eşik açılırsa "Empty" gerçek "eşleşme yok" anlamı da kazanır.
 *
 * ## Oturum yönetimi ve indeksleme sürerken arama maliyeti
 * Metin (arama) ve görüntü (indeksleme) ONNX oturumları tek oturum kuralıyla ([com.ktu.aigaleri.ml.SingleSessionSlot])
 * dönüşümlüdür; uygulamada tek `ModelStore` ve tek slot paylaşılır (`AppDependencies`). İndeksleme sürerken:
 * (1) arama, çalışan tek fotoğraf çıkarımının bitmesini bekler (kilit yalnızca çıkarım boyunca tutulur; çözme ve
 * ön işleme kilit dışındadır), (2) metin oturumu kapalıysa açılır (model dosyası ilk kullanımda assets'ten kopyalanıp
 * SHA-256 ile doğrulanır; sonrası yalnızca oturum yükleme maliyeti, ~135 MB, ÖLÇÜLMEDİ) ve görüntü oturumu kapatılır, (3) bir sonraki fotoğrafta indeksleyici
 * görüntü oturumunu yeniden açar (~89 MB, ÖLÇÜLMEDİ) ve metin oturumunu kapatır. Yani indeksleme sürerken her
 * arama bir metin oturumu açılışı, indeksleyiciye de bir görüntü oturumu açılışı maliyeti ekler.
 * SEÇİLEN POLİTİKA: aramaya her zaman izin verilir ve indeksleme beklemez/duraklatılmaz (kullanıcı beklentisi:
 * arama anında çalışmalı); maliyet kabul edilir. Ardışık aramalar arasında indeksleyici bir fotoğraf işlemediyse
 * metin oturumu açık kalır ve maliyet oluşmaz.
 * Isınma ([com.ktu.aigaleri.ml.OnnxTextEncoder.warmUp]) `ui.search.SearchWarmUp` ile yalnızca indeks boş değilken ve
 * indeksleme ÇALIŞMIYORKEN, ilk metin alanı odağında yapılır (indeksleyicinin görüntü oturumunu boşuna kapatmaz).
 * Metin oturumunun (~135 MB) süreç boyunca açık kalmaması için uygulama arka plana alınınca/bellek baskısında
 * `ui.TextSessionTrimHandler` oturumu bırakır; sonraki arama (veya ısınma) yeniden açar.
 *
 * Testte [dao] sahte, [encoder] sahte kullanılır; model dosyası gerekmez.
 */
class RoomSearchRepository(
    private val encoder: TextEncoder,
    private val dao: PhotoEmbeddingDao,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val minScore: Float? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SearchRepository {
    init {
        require(pageSize in 1..MAX_PAGE_SIZE) { "pageSize 1..$MAX_PAGE_SIZE olmalı" }
        require(minScore == null || minScore.isFinite()) { "minScore sonlu olmalı" }
    }

    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        require(limit > 0) { "limit > 0 olmalı" }
        val spec = encoder.embeddingSpec
        val q = encoder.encode(query) // InvalidQueryException ve CancellationException aynen geçer
        check(q.size == spec.dimension) { "sorgu vektörü boyutu spec ile uyuşmuyor" }
        check(q.all { it.isFinite() }) { "sorgu vektörü sonlu değil" }
        check(abs(norm(q) - 1.0) <= QUERY_NORM_TOLERANCE) { "sorgu vektörü birim norma sahip değil" }
        return withContext(dispatcher) { scan(spec.modelVersion, q, limit) }
    }

    private suspend fun scan(modelVersion: String, q: FloatArray, limit: Int): List<SearchResult> {
        val dim = q.size
        val expectedBytes = dim * Float.SIZE_BYTES
        val collector = TopKCollector(limit)
        val scratch = FloatArray(dim)
        var scanned = 0L
        var skipped = 0L
        var after = Long.MIN_VALUE
        while (true) {
            coroutineContext.ensureActive()
            val page = dao.getPageForModel(modelVersion, after, pageSize)
            if (page.isEmpty()) break
            for (row in page) {
                scanned++
                if (row.vector.size != expectedBytes || row.uri.isBlank()) { // bozuk kayıt: atla
                    skipped++
                    continue
                }
                VectorCodec.decodeInto(row.vector, scratch)
                val score = dot(q, scratch)
                if (!score.isFinite()) {
                    skipped++
                    continue
                }
                if (minScore != null && score < minScore) continue
                collector.offer(row.photoId, score, row.uri, row.dateTaken)
            }
            val last = page.last().photoId
            check(last > after) { "sayfalı okuma ilerlemiyor" } // sonsuz döngü koruması
            after = last
            if (page.size < pageSize) break
        }
        // Hepsi bozuksa (ör. boyut değişti ama modelVersion artmadı) sessiz "bulunamadı" yanıltıcıdır.
        check(!(scanned > 0 && skipped == scanned)) { "indeksteki tüm kayıtlar okunamadı (spec/indeks uyuşmazlığı)" }
        return collector.drainSorted()
    }

    companion object {
        /** Sayfa başına satır: 512 boyutta ~0,55 MB (SQLite CursorWindow 2 MB sınırının altında). */
        const val DEFAULT_PAGE_SIZE = 256
        const val MAX_PAGE_SIZE = 500

        /** Sorgu vektörü normunun 1'den en fazla sapması (kodlayıcı float L2 normalize eder; sapma ~1e-7). */
        const val QUERY_NORM_TOLERANCE = 1e-3

        private fun norm(v: FloatArray): Double {
            var s = 0.0
            for (x in v) s += x.toDouble() * x.toDouble()
            return sqrt(s)
        }

        /** Aynı vektör uzunluğu için nokta çarpımı (float birikimi; test referansı da bunu kullanır). */
        internal fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }
    }
}
