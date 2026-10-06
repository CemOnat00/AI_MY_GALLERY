package com.ktu.aigaleri.data

import com.ktu.aigaleri.domain.EmbeddingSpec
import com.ktu.aigaleri.domain.ImageEncoder
import com.ktu.aigaleri.domain.IndexException
import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.domain.IndexPhase
import com.ktu.aigaleri.domain.IndexProgress
import com.ktu.aigaleri.domain.PhotoIndexer
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
 * - FULL: FULL tamamlanana dek aynı hedefle devam eder. Room'da işlenecek kalan varsa
 *   ([PhotoDao.idsNeedingIndex]`(max)` boş değil) hedef `max` kalır ve yalnızca kalan (+ Room'da olmayan yeni fotoğraflar)
 *   işlenir: yarım FULL baştan başlamaz, böylece iş sistem tarafından yeniden başlatılsa da ilerler. Kalan yoksa (indeks
 *   güncel veya boş) yeni tur başlar: hedef `max + 1`, galerideki tüm fotoğraflar işlenir. Kayıtlar fotoğraf bazında YERİNE yazılır (önce silinmez); başarısız veya
 *   yarıda kalan fotoğrafın eski kaydı ve vektörü korunur, aranabilir kalır. FULL hiçbir şey yazmadan kesilirse
 *   (ör. taramada) `max` değişmemiştir: yeniden istenen FULL gerçekten yeni tur olarak çalışır.
 * Şema değişmez: "FULL hedefi" ayrı bir sütun/tablo değil, `Photo.indexVersion`'ın azami değeridir.
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
 * (izin geri alındı) tek fotoğraf hatası sayılmaz, akışı [IndexException.PermissionMissing] ile keser. Bu çalıştırmada
 * hiç başarı yokken [MAX_FAILURES_WITHOUT_SUCCESS] fotoğraf üst üste başarısız olursa (bozuk/uyumsuz model, tüm
 * fotoğrafları etkileyen sorun) akış [IndexException.Unexpected] ile kesilir; aksi halde bozuk model tüm galeriyi
 * sessizce COMPLETED yapardı. İlk başarıdan sonra bu sınır uygulanmaz.
 * Taramadan sonra MediaStore'dan kaybolmuş fotoğraf hata sayılmaz: kaydı silinir ve işlenmiş sayılır.
 *
 * IndexState: tarama sonunda (`processed = 0`), her [STATE_WRITE_EVERY] fotoğrafta, bitişte ve (iptal/hata) çıkışta yazılır;
 * `lastRunAt` yalnızca tam tamamlanınca güncellenir. Photo + PhotoEmbedding yazımı [TransactionRunner] ile tek işlemdir.
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

    private class Plan(val ids: LongArray, val targetVersion: Int, val lastRunAt: Long?)

    override fun index(mode: IndexMode): Flow<IndexProgress> = flow {
        emit(IndexProgress(IndexPhase.SCANNING, total = 0, processed = 0))
        val plan = guarded { buildPlan(mode) }
        val total = plan.ids.size
        var processed = 0
        var failed = 0
        var successes = 0
        var completed = false
        try {
            guarded { writeState(total, 0, plan.lastRunAt) }
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
                        val vector = encodeOrNull(media)
                        if (vector == null) {
                            failed++
                            if (successes == 0 && failed >= MAX_FAILURES_WITHOUT_SUCCESS) {
                                throw IndexException.Unexpected(IllegalStateException("ardışık kodlama hatası sınırı aşıldı"))
                            }
                        } else {
                            guarded { write(media, vector, plan.targetVersion) }
                            successes++
                        }
                    }
                    processed++
                    if (processed % STATE_WRITE_EVERY == 0 && processed < total) {
                        guarded { writeState(total, processed, plan.lastRunAt) }
                    }
                    emit(IndexProgress(IndexPhase.INDEXING, total, processed, failed))
                }
                from = to
            }
            guarded { writeState(total, total, clock()) }
            completed = true
            emit(IndexProgress(IndexPhase.COMPLETED, total, total, failed))
        } finally {
            // İptal veya hata: ilerlemeyi ve model oturumunu iptalden bağımsız bırak; ikincil hata asıl istisnayı örtmesin.
            withContext(NonCancellable) {
                if (!completed) {
                    try {
                        writeState(total, processed, plan.lastRunAt)
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

        // Güvenli taraf: galeri boş görünüp Room doluysa (kısmi erişim/geçici hata) hiçbir şey silme ve işleme.
        if (mediaIds.isEmpty() && dbIds.isNotEmpty()) {
            return Plan(LongArray(0), maxOf(photoDao.maxIndexVersion() ?: 0, PIPELINE_VERSION), stateDao.get()?.lastRunAt)
        }

        // Galeride artık olmayanlar: toplu sil (CASCADE embedding'i siler).
        val stale = IdSets.difference(dbIds, mediaIds)
        var i = 0
        while (i < stale.size) {
            val end = minOf(i + DELETE_CHUNK, stale.size)
            photoDao.deleteByIds(stale.asList().subList(i, end))
            i = end
        }

        // Silmeler sonrası: silinen kayıtların sürümü tabanı yükseltmesin.
        val max = maxOf(photoDao.maxIndexVersion() ?: 0, PIPELINE_VERSION)
        val missing = IdSets.difference(mediaIds, dbIds)
        val needing = photoDao.idsNeedingIndex(max, embeddingSpec.modelVersion).toLongArray().also { it.sort() }
        val remainder = IdSets.mergeDescending(missing, needing)
        // INCREMENTAL her zaman kalanı işler. FULL: Room'da kalan varsa (yarım FULL) aynı hedefle kalanı sürdürür, yoksa yeni tur.
        // "Kalan" ölçütü yalnızca Room'daki kayıtlardır (yarım FULL); yeni eklenen fotoğraflar tek başına yeni tur açmaz
        // ama kalan işlenirken (ya da INCREMENTAL'da) onlar da işlenir.
        val startNewRound = mode == IndexMode.FULL && needing.isEmpty()
        val target = if (startNewRound) max + 1 else max
        val ids = if (startNewRound) IdSets.mergeDescending(mediaIds, LongArray(0)) else remainder
        return Plan(ids, target, stateDao.get()?.lastRunAt)
    }

    /** Başarılıysa vektör, tek fotoğraf hatasıysa null; model düzeyi hata [IndexException.Unexpected], izin hatası [IndexException.PermissionMissing] olur. */
    private suspend fun encodeOrNull(media: MediaPhoto): FloatArray? =
        try {
            encoder.encode(media.uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            throw IndexException.PermissionMissing()
        } catch (e: Exception) {
            if (ModelFailures.isModelLevel(e)) throw IndexException.Unexpected(e)
            null
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

    private suspend fun writeState(total: Int, processed: Int, lastRunAt: Long?) {
        stateDao.upsert(IndexState(total = total, processed = processed, lastRunAt = lastRunAt))
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

        /** Bu çalıştırmada hiç başarı yokken bu kadar fotoğraf başarısız olursa akış kesilir (bkz. sınıf KDoc'u). */
        const val MAX_FAILURES_WITHOUT_SUCCESS = 20

        /** IndexState yazım aralığı (fotoğraf sayısı). */
        const val STATE_WRITE_EVERY = 10
    }
}
