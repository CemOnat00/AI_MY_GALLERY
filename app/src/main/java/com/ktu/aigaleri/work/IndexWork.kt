package com.ktu.aigaleri.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.ktu.aigaleri.domain.IndexException
import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.domain.PhotoIndexer
import com.ktu.aigaleri.ml.ModelException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Worker sonucu ([IndexWork.execute]); WorkManager `Result`'ına [IndexWorker] eşler. */
enum class IndexOutcome { SUCCESS, RETRY, FAILURE }

/**
 * İndeksleme işinin WorkManager politikası ve saf (Android'siz, JVM'de test edilebilir) mantığı.
 *
 * Tek benzersiz kuyruk ([UNIQUE_NAME]); hem INCREMENTAL hem FULL aynı kuyruğa girer, böylece iki indeksleme
 * aynı anda çalışmaz.
 * - INCREMENTAL: [ExistingWorkPolicy.KEEP]. Çalışan/bekleyen iş varsa (FULL dahil) yenisi eklenmez: çalışan iş zaten
 *   kalan her şeyi işler ve kaldığı yerden devam eder; tekrarlanan tetiklemeler (ör. her açılış) biriktirmez.
 * - FULL: [ExistingWorkPolicy.REPLACE]. Çalışan/bekleyen iş (INCREMENTAL dahil) iptal edilip FULL başlar; kayıtlar
 *   korunur, FULL yerine yazar. Gerçek durum: arayüz ([com.ktu.aigaleri.ui.index.IndexStatusViewModel]) iş kuyrukta
 *   veya çalışırken ([com.ktu.aigaleri.ui.IndexLauncher.isRunning]) HİÇBİR "yeniden indeksle" isteğini kabul etmez;
 *   yani çalışan bir INCREMENTAL, FULL'u arayüzden engeller ve REPLACE yalnızca arayüz dışı çağrılarda (ör. ileride
 *   başka bir tetikleyici) ulaşılabilir. Bu bilinçli bırakıldı (kod davranışı değiştirilmedi).
 *
 * Yeniden başlatma/devamlılık: sistem işi durdurursa (kısıt bozuldu, süre sınırı, süreç öldü, yeniden başlatma)
 * WorkManager işi aynı moddaki girdiyle yeniden çalıştırır. Mod değişmez: FULL kendi başına yeniden başlatılabilirdir
 * (kalıcı tur durumu `IndexState.fullTargetVersion`, bkz. [com.ktu.aigaleri.data.RoomPhotoIndexer]); tamamlanana dek
 * aynı hedefle kalanı işler. INCREMENTAL zaten kalanı işler.
 *
 * WorkManager sınırı: foreground servis/bildirim yoktur (kapsam dışı; izin ve servis manifestten çıkarıldı). İş
 * sıradan bir arka plan işidir; sistem (Doze, uygulama bekleme kovası, pil kısıtı, JobScheduler süre sınırı) işi her
 * an durdurabilir ve çalışma sıklığını/süresini garanti etmez. Bu yüzden iş kesintiye dayanıklı (resumable)
 * yazılmıştır; büyük galeri bir çalıştırmada bitmeyebilir. Kısıtlar: pil düşük değil; ağ kısıtı YOK (ağ kullanılmaz).
 */
object IndexWork {
    const val UNIQUE_NAME = "ai-galeri-index"
    const val KEY_MODE = "mode"

    /** Beklenmeyen hatada toplam deneme sayısı (ilk çalıştırma dahil); sonra [IndexOutcome.FAILURE]. */
    const val MAX_ATTEMPTS = 3

    private const val BACKOFF_SECONDS = 30L

    fun policyFor(mode: IndexMode): ExistingWorkPolicy = when (mode) {
        IndexMode.INCREMENTAL -> ExistingWorkPolicy.KEEP
        IndexMode.FULL -> ExistingWorkPolicy.REPLACE
    }

    /** Bilinmeyen/eksik değer INCREMENTAL (güvenli varsayılan; yalnızca eksikleri işler). */
    fun parseMode(value: String?): IndexMode = IndexMode.entries.firstOrNull { it.name == value } ?: IndexMode.INCREMENTAL

    /** Kuyrukta bekleyen veya çalışan iş var mı (arayüzdeki "yeniden indeksleme sürüyor" durumu). */
    fun isActive(states: Collection<WorkInfo.State>): Boolean =
        states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.RUNNING || it == WorkInfo.State.BLOCKED }

    fun constraints(): Constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()

    fun request(mode: IndexMode): OneTimeWorkRequest =
        OneTimeWorkRequest.Builder(IndexWorker::class.java)
            .setConstraints(constraints())
            .setInputData(workDataOf(KEY_MODE to mode.name))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()

    /**
     * [indexer] akışını sonuna kadar toplar (ilerleme arayüze Room `IndexState` ile gider; burada tüketilmez).
     * İptal ([CancellationException]) aynen yayılır: yazılan kayıtlar korunur, iş yeniden başlayınca kalan devam eder.
     * [runAttemptCount] yalnızca yeniden deneme sayısını sınırlar; mod değişmez.
     * Sonuç: izin yok -> FAILURE (kullanıcı eylemi gerekir); model dosyası yok/bozuk -> FAILURE (yeniden denemek
     * düzeltmez); diğer beklenmeyen hata -> [MAX_ATTEMPTS]'a kadar RETRY. Tek fotoğraf hataları başarıyı bozmaz.
     */
    suspend fun execute(indexer: PhotoIndexer, requested: IndexMode, runAttemptCount: Int): IndexOutcome =
        try {
            indexer.index(requested).collect { }
            IndexOutcome.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (e: IndexException.PermissionMissing) {
            IndexOutcome.FAILURE
        } catch (e: IndexException.Unexpected) {
            val giveUp = isPermanent(e.cause) || runAttemptCount + 1 >= MAX_ATTEMPTS
            if (giveUp) IndexOutcome.FAILURE else IndexOutcome.RETRY
        }

    /**
     * Neden zincirinde (en çok 8 halka; coroutine yığın izi kurtarma istisnayı sararak zincire halka ekleyebilir)
     * kalıcı bir model hatası var mı.
     */
    private fun isPermanent(cause: Throwable?): Boolean =
        generateSequence(cause) { it.cause }.take(8)
            .any { it is ModelException.Missing || it is ModelException.IntegrityFailure }
}
