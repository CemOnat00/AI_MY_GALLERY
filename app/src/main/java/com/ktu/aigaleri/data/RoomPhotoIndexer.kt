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
 * - FULL: galerideki tümü, hedef sürüm = (mevcut en yüksek `Photo.indexVersion`, en az [PIPELINE_VERSION]) + 1.
 *   Kayıtlar fotoğraf bazında YERİNE yazılır (önce silinmez); başarısız veya yarıda kalan fotoğrafın eski kaydı
 *   ve vektörü korunur, aranabilir kalır.
 * - INCREMENTAL: hedef sürüm = mevcut en yüksek `indexVersion` (en az [PIPELINE_VERSION]); işlenenler: Room'da
 *   olmayanlar + `indexVersion < hedef` + güncel `modelVersion` ile vektörü olmayanlar ([PhotoDao.idsNeedingIndex]).
 *   Yarım kalmış FULL'un kalanı böylece (bir kısmı hedef sürüme yükseldiği için) devam eder; başarısız fotoğraflar
 *   kalıcı işaretlenmedikleri için her çalıştırmada yeniden denenir. Aynı model/sürümde yazılmış kayıt yeniden
 *   işlenmez.
 * Şema değişmez: "FULL hedefi" ayrı bir sütun/tablo değil, `Photo.indexVersion`'ın azami değeridir.
 * Sınır: iptal edilen FULL hiçbir fotoğraf yazmadan kesilirse hedef kaydedilmemiştir (kalan iş yoktur, eski kayıtlar
 * aynen durur); yeniden FULL istenmelidir.
 *
 * Bellek: yalnızca kimlikler tutulur. 100 bin fotoğrafta MediaStore kimlikleri ~0.8 MB (LongArray), Room kimlikleri
 * ~3 MB (List<Long>), işlenecek liste ~0.8 MB; tepe ~8-10 MB. URI/tarih dizileri [BATCH_SIZE] fotoğraflık parçalarla
 * ([MediaPhotoSource.loadByIds]) okunur. Görüntü başına tek çözme yapılır (bkz. [ImageEncoder]).
 *
 * Hata politikası: tek fotoğraf kodlanamazsa (çözme, ONNX çıkarımı) atlanır, `failed` artar, devam edilir; ham istisna,
 * yol veya içerik log'lanmaz (hiç log yazılmaz). Model düzeyi hatalar ([ModelFailures.isModelLevel]), veritabanı
 * ve tarama hataları akışı [IndexException.Unexpected] ile, izin hatası [IndexException.PermissionMissing] ile sonlandırır.
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
                        if (vector == null) failed++ else guarded { write(media, vector, plan.targetVersion) }
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

        // Galeride artık olmayanlar: toplu sil (CASCADE embedding'i siler).
        val stale = IdSets.difference(dbIds, mediaIds)
        var i = 0
        while (i < stale.size) {
            val end = minOf(i + DELETE_CHUNK, stale.size)
            photoDao.deleteByIds(stale.asList().subList(i, end))
            i = end
        }

        // Silmeler sonrası: silinen kayıtların sürümü tabanı yükseltmesin.
        val base = maxOf(photoDao.maxIndexVersion() ?: 0, PIPELINE_VERSION)
        val target = if (mode == IndexMode.FULL) base + 1 else base
        val ids = when (mode) {
            IndexMode.FULL -> IdSets.mergeDescending(mediaIds, LongArray(0))
            IndexMode.INCREMENTAL -> {
                val missing = IdSets.difference(mediaIds, dbIds)
                val needing = photoDao.idsNeedingIndex(target, embeddingSpec.modelVersion).toLongArray().also { it.sort() }
                IdSets.mergeDescending(missing, needing)
            }
        }
        return Plan(ids, target, stateDao.get()?.lastRunAt)
    }

    /** Başarılıysa vektör, tek fotoğraf hatasıysa null; model düzeyi hata [IndexException.Unexpected] olur. */
    private suspend fun encodeOrNull(media: MediaPhoto): FloatArray? =
        try {
            encoder.encode(media.uri)
        } catch (e: CancellationException) {
            throw e
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

        /** IndexState yazım aralığı (fotoğraf sayısı). */
        const val STATE_WRITE_EVERY = 10
    }
}
