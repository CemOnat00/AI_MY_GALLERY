package com.ktu.aigaleri.data

import com.ktu.aigaleri.domain.SearchRepository
import com.ktu.aigaleri.domain.SearchResult
import com.ktu.aigaleri.domain.TextEncoder
import kotlin.coroutines.coroutineContext
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
 * - Kodlayıcı `spec.dimension` dışında uzunlukta veya sonlu olmayan bir vektör dönerse [IllegalStateException]
 *   (spec/model uyuşmazlığı; sorgu metni mesajda yer almaz). Bu sessizce yanlış sıralama üretmekten iyidir.
 * - İndeks kaydı bozuksa (BLOB uzunluğu `dimension*4` değil, skor sonlu değil, uri boş) o kayıt ATLANIR, arama
 *   düşmez; kayıt bir sonraki indekslemede yenilenir.
 *
 * Skor: vektörler L2 normalize olduğundan kosinüs = nokta çarpımı ([-1, 1]; kodlayıcı normalize etmese bile
 * sıralama aynı kalır, çünkü sorgu normu tüm adaylarda ortak çarpandır).
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
 * - Süre (ÖLÇÜLMEDİ, tahmin): 100 bin kayıtta ~51 milyon çarpma-toplama + ~51 milyon bayt->float çözme; ART'ta
 *   yaklaşık 0,2-0,6 sn CPU; SQLite'ın ~205 MB BLOB'u (sayfa önbelleğinden) okuması yaklaşık 0,3-1 sn; toplam
 *   10 bin kayıtta ~0,1-0,2 sn, 100 bin kayıtta ~1-2 sn mertebesi. Gerçek cihazda ölçülmedi (cihaz yok).
 *   Ölçümde 100 bin için ~2 sn aşılırsa bir sonraki adım int8 nicemlenmiş bellek önbelleği (~50 MB) olur; şimdi
 *   erken optimizasyon sayıldı. Tarama thread'i [dispatcher] (varsayılan Default) üzerindedir, ana thread değil.
 * - Tutarlılık: sayfalar arası işlem yoktur; tarama sürerken indeksleyici yazarsa yeni satır görünebilir veya
 *   görünmeyebilir, hiçbir kimlik iki kez işlenmez.
 *
 * ## Asgari skor eşiği kararı: varsayılan YOK ([minScore] = null), her zaman top-K döner
 * CLIP kosinüs skorları dar ve sorguya göre kayan bir aralıktadır (tipik 0.1-0.35); doğru ve yanlış eşleşmelerin
 * skor aralıkları örtüşür, çok dilli damıtılmış metin kodlayıcı (Türkçe) ve int8 görüntü nicemlemesi dağılımı
 * ayrıca kaydırır. Bu modelle gerçek galeri/Türkçe istem ölçümü yok (model-research.md Bölüm 4); ölçümsüz mutlak
 * bir eşik ya iyi sonuçları keser ya da hiçbir şeyi elemez. Bu yüzden eşik yoktur, sıralama sorumluluğu rank'tadır.
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
 * metin oturumu açık kalır ve maliyet oluşmaz. Isınma için bkz. [com.ktu.aigaleri.ml.OnnxTextEncoder.warmUp].
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
        return withContext(dispatcher) { scan(spec.modelVersion, q, limit) }
    }

    private suspend fun scan(modelVersion: String, q: FloatArray, limit: Int): List<SearchResult> {
        val dim = q.size
        val expectedBytes = dim * Float.SIZE_BYTES
        val collector = TopKCollector(limit)
        val scratch = FloatArray(dim)
        var after = Long.MIN_VALUE
        while (true) {
            coroutineContext.ensureActive()
            val page = dao.getPageForModel(modelVersion, after, pageSize)
            if (page.isEmpty()) break
            for (row in page) {
                if (row.vector.size != expectedBytes || row.uri.isBlank()) continue // bozuk kayıt: atla
                VectorCodec.decodeInto(row.vector, scratch)
                val score = dot(q, scratch)
                if (!score.isFinite()) continue
                if (minScore != null && score < minScore) continue
                collector.offer(row.photoId, score, row.uri, row.dateTaken)
            }
            val last = page.last().photoId
            check(last > after) { "sayfalı okuma ilerlemiyor" } // sonsuz döngü koruması
            after = last
            if (page.size < pageSize) break
        }
        return collector.drainSorted()
    }

    companion object {
        /** Sayfa başına satır: 512 boyutta ~0,55 MB (SQLite CursorWindow 2 MB sınırının altında). */
        const val DEFAULT_PAGE_SIZE = 256
        const val MAX_PAGE_SIZE = 500

        /** Aynı vektör uzunluğu için nokta çarpımı (float birikimi; test referansı da bunu kullanır). */
        internal fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }
    }
}
