package com.ktu.aigaleri.data

import com.ktu.aigaleri.domain.EmbeddingSpec
import com.ktu.aigaleri.domain.ImageEncoder
import com.ktu.aigaleri.domain.IndexException
import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.domain.IndexPhase
import com.ktu.aigaleri.domain.IndexProgress
import com.ktu.aigaleri.domain.PhotoIndexer
import com.ktu.aigaleri.ml.ModelException
import com.ktu.aigaleri.ml.ModelFailures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * [PhotoIndexer]'ın Room + MediaStore + [ImageEncoder] gerçeklemesi (T-006; sözleşme için bkz. [PhotoIndexer]
 * ve architecture.md Bölüm 5-6). Yalnızca WorkManager worker'ında toplanır; arama sırasında çağrılmaz.
 *
 * Akış: SCANNING, fotoğraf başına INDEXING, COMPLETED. Ağır iş [dispatcher]'da (IO). İptale duyarlı: her
 * fotoğraf öncesi ve DAO/kodlayıcı çağrılarında. İptalde/hatada o ana kadar yazılan kayıtlar korunur; model
 * oturumu ve IndexState son durumu `NonCancellable` bloğunda bırakılır/yazılır.
 *
 * Tarama (SCANNING): MediaStore kimlikleri ([MediaPhotoSource.loadIds]) ve Room kimlikleri alınır; galeride
 * artık olmayanların kayıtları toplu silinir (<= [DELETE_CHUNK]/sorgu; CASCADE embedding'i siler). Sonra işlenecek
 * kimlik listesi çıkarılır (en yeni kimlik önce):
 * - INCREMENTAL: hedef sürüm = `max` = mevcut en yüksek `Photo.indexVersion` (en az [PIPELINE_VERSION]); işlenenler:
 *   Room'da olmayanlar + `indexVersion < max` + güncel `modelVersion` ile vektörü olmayanlar ([PhotoDao.idsNeedingIndex]).
 *   Başarısız fotoğraflar kalıcı işaretlenmedikleri için her çalıştırmada yeniden denenir. Aynı model/sürümde yazılmış
 *   kayıt yeniden işlenmez.
 * - FULL: kalıcı tur durumu `IndexState.fullTargetVersion` (şema v2) ile tutulur. Alan doluysa (yarım FULL) ve kalan
 *   varsa aynı hedefle yalnızca kalan (+ Room'da olmayan yeni fotoğraflar) işlenir: FULL, hiç yazmadan kesilse de ya da
 *   iş sistem tarafından yeniden başlatılsa da baştan başlamaz, ilerler. Alan boşsa (ya da kalan kalmamışsa, yani tur
 *   INCREMENTAL ile tamamlanmışsa) yeni tur başlar: hedef `max + 1` (`max` = Room'daki en yüksek `indexVersion`, en az
 *   [PIPELINE_VERSION]), alan hedefe ayarlanır, galerideki tüm fotoğraflar işlenir. FULL çalıştırması bitince (başarısız
 *   fotoğraflarla bile) alan null'a çekilir: kalıcı bozuk bir fotoğraf yeni FULL'u kilitlemez, tamamlanmış her FULL'dan
 *   sonra istenen FULL gerçekten yeni tur açar. Kayıtlar fotoğraf bazında YERİNE yazılır (önce silinmez); başarısız veya
 *   yarıda kalan fotoğrafın eski kaydı ve vektörü korunur, aranabilir kalır. INCREMENTAL alana dokunmaz; hedefi
 *   `max(max, fullTargetVersion)` olduğundan yarım FULL'un kalanını tamamlar.
 * Tarama güvenliği: MediaStore HİÇ kimlik döndürmezse (izin hatası fırlatılmadan; Android 14 kısmi erişim, geçici
 * sağlayıcı hatası) ve Room doluysa temizleme VE işleme atlanır (hiçbir kayıt silinmez; yanlışlıkla tüm indeksi
 * silmemek için). Galeri gerçekten boşaldıysa eski kayıtlar bir sonraki dolu taramaya kadar kalır.
 *
 * Bellek: yalnızca kimlikler tutulur. 100 bin fotoğrafta MediaStore kimlikleri ~0.8 MB (LongArray), Room kimlikleri
 * ~3 MB (List<Long>), işlenecek liste ~0.8 MB; tepe ~8-10 MB. URI/tarih dizileri [BATCH_SIZE] fotoğraflık parçalarla
 * ([MediaPhotoSource.loadByIds]) okunur. Görüntü başına tek çözme yapılır (bkz. [ImageEncoder]).
 *
 * Hata politikası: tek fotoğraf kodlanamazsa (çözme, ONNX çıkarımı) atlanır, `failed` artar, devam edilir; ham istisna,
 * yol veya içerik log'lanmaz (hiç log yazılmaz). Model düzeyi hatalar ([ModelFailures.isModelLevel]), veritabanı
 * ve tarama hataları akışı [IndexException.Unexpected] ile sonlandırır. Fotoğraf okurken [SecurityException]
 * (izin geri alındı) tek fotoğraf hatası sayılmaz, akışı [IndexException.PermissionMissing] ile keser.
 * Model hata eşiği: bu çalıştırmada hiç başarı yokken [MAX_MODEL_FAILURES_WITHOUT_SUCCESS] model kaynaklı hata
 * ([com.ktu.aigaleri.ml.ModelException], yani ONNX çalıştırma/çıktı hataları) birikirse akış [IndexException.Unexpected]
 * ile kesilir; aksi halde bozuk/uyumsuz model tüm galeriyi sessizce COMPLETED yapardı. Fotoğrafa özgü çözme hataları
 * ([com.ktu.aigaleri.ml.ImageDecodeException]) sayılmaz ve sayacı sıfırlamaz: en yeni fotoğraflar çözülemese de eskiler
 * indekslenmeye devam eder. SINIR: ilk başarıdan sonra bozulan model bu eşikle yakalanmaz (kalan fotoğraflar `failed`
 * sayılır, akış COMPLETED olur). Eşiği aşan fotoğraf `processed`/IndexState'e sayılmaz (akış ondan önce kesilir; ondan
 * önce atlanan çözme hataları sayılır).
 * `OutOfMemoryError` (ve diğer `Error`'lar) yakalanmaz: yayılır, çalıştırma durur (çözücü yalnızca kendi çözme
 * OOM'sini fotoğraf hatasına çevirir, bkz. `ContentResolverImageLoader`). Taramadan MediaStore'dan kaybolmuş fotoğraf hata sayılmaz: kaydı silinir ve işlenmiş sayılır.
 *
 * IndexState: tarama sonunda (`processed = 0`), her [STATE_WRITE_EVERY] fotoğrafta, bitişte ve (iptal/hata) çıkışta yazılır;
 * `lastRunAt` yalnızca tam tamamlanınca güncellenir (boş-MediaStore atlamasında da tamamlanmış sayılır ve güncellenir). Photo + PhotoEmbedding yazımı [TransactionRunner] ile tek işlemdir.
 */
class RoomPhotoIndexer(
    private val source: MediaPhotoSource,
    private val photoDao: PhotoDao,
    private val embeddingDao: PhotoEmbeddingDao,
    private val stateDao: IndexStateDao,
    private val transactions: TransactionRunner,
    private val encoder: ImageEncoder,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PhotoIndexer {
    override val embeddingSpec: EmbeddingSpec get() = encoder.embeddingSpec

    private class Plan(
        val ids: LongArray,
        val targetVersion: Int,
        val lastRunAt: Long?,
        /** Çalıştırma boyunca IndexState'e yazılan devam eden FULL hedefi (FULL: bu tur; INCREMENTAL: mevcut değer). */
        val fullTarget: Int?,
    )

    override fun index(mode: IndexMode): Flow<IndexProgress> = flow {
        emit(IndexProgress(IndexPhase.SCANNING, total = 0, processed = 0))
        val plan = guarded { buildPlan(mode) }
        val total = plan.ids.size
        var processed = 0
        var failed = 0
        var successes = 0
        var modelFailures = 0
        var completed = false
        try {
            guarded { writeState(total, 0, plan.lastRunAt, plan.fullTarget) }
            var from = 0
            while (from < total) {
                val to = minOf(from + BATCH_SIZE, total)
                val chunk = ArrayList<Long>(to - from)
                for (i in from until to) chunk.add(plan.ids[i])
                val photos = guarded { source.loadByIds(chunk) }.associateBy { it.id }
                for (id in chunk) {
                    currentCoroutineContext().ensureActive()
                    val media = photos[id]
                    if (media == null) {
                        // Tarama ile işleme arasında galeriden silindi: hata değil, kaydı temizle.
                        guarded { photoDao.deleteById(id) }
                    } else {
                        val encoded = encodeOrNull(media)
                        val vector = encoded.vector
                        if (vector == null) {
                            failed++
                            if (encoded.modelFailure) modelFailures++
                            if (successes == 0 && modelFailures >= MAX_MODEL_FAILURES_WITHOUT_SUCCESS) {
                                throw IndexException.Unexpected(IllegalStateException("model hata eşiği aşıldı"))
                            }
                        } else {
                            guarded { write(media, vector, plan.targetVersion) }
                            successes++
                        }
                    }
                    processed++
                    if (processed % STATE_WRITE_EVERY == 0 && processed < total) {
                        guarded { writeState(total, processed, plan.lastRunAt, plan.fullTarget) }
                    }
                    emit(IndexProgress(IndexPhase.INDEXING, total, processed, failed))
                }
                from = to
            }
            // FULL turu (başarısız fotoğraflarla bile) bitti: devam eden tur işareti kalkar. INCREMENTAL dokunmaz.
            val fullAfter = if (mode == IndexMode.FULL) null else plan.fullTarget
            guarded { writeState(total, total, clock(), fullAfter) }
            completed = true
            emit(IndexProgress(IndexPhase.COMPLETED, total, total, failed))
        } finally {
            // İptal veya hata: ilerlemeyi ve model oturumunu iptalden bağımsız bırak; ikincil hata asıl istisnayı örtmesin.
            withContext(NonCancellable) {
                if (!completed) {
                    try {
                        writeState(total, processed, plan.lastRunAt, plan.fullTarget)
                    } catch (_: Exception) {
                    }
                }
                try {
                    encoder.release()
                } catch (_: Exception) {
                }
            }
        }
    }.flowOn(dispatcher)

    private suspend fun buildPlan(mode: IndexMode): Plan {
        val mediaIds = source.loadIds().also { it.sort() }
        val dbList = photoDao.allIds()
        val dbIds = LongArray(dbList.size) { dbList[it] }
        val savedState = stateDao.get()
        val pendingFull = savedState?.fullTargetVersion

        // Güvenli taraf: galeri boş görünüp Room doluysa (kısmi erişim/geçici hata) hiçbir şey silme ve işleme.
        if (mediaIds.isEmpty() && dbIds.isNotEmpty()) {
            val max = maxOf(photoDao.maxIndexVersion() ?: 0, PIPELINE_VERSION, pendingFull ?: 0)
            return Plan(LongArray(0), max, savedState?.lastRunAt, pendingFull)
        }

        // Galeride artık olmayanlar: toplu sil (CASCADE embedding'i siler).
        val stale = IdSets.difference(dbIds, mediaIds)
        var i = 0
        while (i < stale.size) {
            val end = minOf(i + DELETE_CHUNK, stale.size)
            photoDao.deleteByIds(stale.asList().subList(i, end))
            i = end
        }

        // Silmeler sonrası: silinen kayıtların sürümü tabanı yükseltmesin. Devam eden FULL varsa hedef en az onun hedefidir
        // (FULL hiç yazmadan kesilmişse `max` henüz yükselmemiştir; kalan yine hedefin altındaki kayıtlardır).
        val max = maxOf(photoDao.maxIndexVersion() ?: 0, PIPELINE_VERSION, pendingFull ?: 0)
        val missing = IdSets.difference(mediaIds, dbIds)
        val needing = photoDao.idsNeedingIndex(max, embeddingSpec.modelVersion).toLongArray().also { it.sort() }
        val remainder = IdSets.mergeDescending(missing, needing)
        // INCREMENTAL her zaman kalanı işler (devam eden FULL'un hedefiyle). FULL: devam eden tur işareti varsa ve kalan
        // varsa aynı hedefle sürdürür; işaret yoksa ya da kalan kalmamışsa (tur INCREMENTAL ile tamamlanmış) yeni tur
        // açar: hedef max+1, tüm galeri işlenir, işaret IndexState'e yazılır.
        val resume = mode == IndexMode.INCREMENTAL || (pendingFull != null && remainder.isNotEmpty())
        val target = if (resume) max else max + 1
        val ids = if (resume) remainder else IdSets.mergeDescending(mediaIds, LongArray(0))
        val fullTarget = if (mode == IndexMode.FULL) target else pendingFull
        return Plan(ids, target, savedState?.lastRunAt, fullTarget)
    }

    private class Encoded(val vector: FloatArray?, val modelFailure: Boolean = false)

    /**
     * Başarılıysa vektör; tek fotoğraf hatasıysa vektör null ([Encoded.modelFailure]: hata modelden mi geldi). Model
     * düzeyi hata [IndexException.Unexpected], izin hatası [IndexException.PermissionMissing] olur.
     */
    private suspend fun encodeOrNull(media: MediaPhoto): Encoded =
        try {
            Encoded(encoder.encode(media.uri))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            throw IndexException.PermissionMissing()
        } catch (e: Exception) {
            if (ModelFailures.isModelLevel(e)) throw IndexException.Unexpected(e)
            Encoded(null, modelFailure = e is ModelException)
        }

    private suspend fun write(media: MediaPhoto, vector: FloatArray, targetVersion: Int) {
        check(vector.size == embeddingSpec.dimension) { "vektör boyutu spec ile uyuşmuyor" }
        val photo = media.toPhoto(indexedAt = clock(), indexVersion = targetVersion)
        val embedding = PhotoEmbedding(media.id, VectorCodec.encode(vector), embeddingSpec.modelVersion)
        transactions.run {
            photoDao.upsert(photo)
            embeddingDao.upsert(embedding)
        }
    }

    private suspend fun writeState(total: Int, processed: Int, lastRunAt: Long?, fullTarget: Int?) {
        stateDao.upsert(
            IndexState(total = total, processed = processed, lastRunAt = lastRunAt, fullTargetVersion = fullTarget),
        )
    }

    /** Fotoğraftan bağımsız hataları [IndexException]'a çevirir; iptal ve zaten [IndexException] olanlar aynen geçer. */
    private inline fun <T> guarded(block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IndexException) {
            throw e
        } catch (e: SecurityException) {
            throw IndexException.PermissionMissing()
        } catch (e: Exception) {
            throw IndexException.Unexpected(e)
        }

    companion object {
        /** İndeksleme hattı sürümü: bu sayı artırılırsa tüm kayıtlar bir sonraki INCREMENTAL'da yeniden işlenir. */
        const val PIPELINE_VERSION = 1

        /** Her turda MediaStore'dan okunan fotoğraf sayısı. */
        const val BATCH_SIZE = 50

        /** SQLite değişken sınırının altında toplu silme parçası. */
        const val DELETE_CHUNK = 500

        /** Bu çalıştırmada hiç başarı yokken bu kadar model kaynaklı hata olursa akış kesilir (bkz. sınıf KDoc'u). */
        const val MAX_MODEL_FAILURES_WITHOUT_SUCCESS = 20

        /** IndexState yazım aralığı (fotoğraf sayısı). */
        const val STATE_WRITE_EVERY = 10
    }
}
