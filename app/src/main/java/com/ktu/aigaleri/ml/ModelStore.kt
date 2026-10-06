package com.ktu.aigaleri.ml

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** assets erişimi soyutlaması (testte sahte, uygulamada AssetManager). */
fun interface AssetOpener {
    /** @throws FileNotFoundException varlık yoksa. */
    fun open(assetPath: String): InputStream
}

/** Model dosyası yönetimi hataları. Mesajlar yalnızca dosya adı içerir. */
sealed class ModelException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Dosya APK assets'inde yok (tools/fetch_models.sh çalıştırılmamış olabilir; docs/model-setup.md). */
    class Missing(val assetPath: String) : ModelException("Model dosyası assets'te yok: $assetPath")

    /** Kopya veya mevcut dosya beklenen boyut/SHA-256 ile uyuşmuyor. */
    class IntegrityFailure(val fileName: String, detail: String) :
        ModelException("Model dosyası bütünlük doğrulaması başarısız: $fileName ($detail)")

    class InsufficientStorage(val fileName: String, val requiredBytes: Long) :
        ModelException("Yetersiz depolama alanı: $fileName için $requiredBytes bayt gerekir")

    class Io(val fileName: String, cause: Throwable) :
        ModelException("Model dosyası kopyalanamadı: $fileName", cause)

    /**
     * ONNX Runtime oturumu açılamadı veya çıkarım başarısız (OrtException, yerel kütüphane yüklenemedi).
     * Ham ORT istisnası arayüze sızmaz; mesaj istemi/girdiyi içermez, ORT ayrıntısı yalnızca [cause]'tadır.
     */
    class Inference(val stage: String, cause: Throwable) :
        ModelException("ONNX Runtime hatası ($stage)", cause)
}

/**
 * Model dosyalarını assets'ten `filesDir/models/` altına kopyalar (model-research.md 5.1): ORT dosya
 * yolundan açar, böylece yüzlerce MB Java yığınına girmez. Ağ yok.
 *
 * Kopya güvenliği: (1) 64 KB tamponlu akış kopyası `<ad>.part`'a, aynı geçişte SHA-256 ve boyut; (2) fsync;
 * (3) doğrulama geçerse atomik yeniden adlandırma, geçmezse `.part` silinir ve [ModelException.IntegrityFailure];
 * (4) başarıdan sonra `<ad>.ok` işaretçisi (boyut + SHA) yazılır. Sonraki açılışta işaretçi ve boyut tutuyorsa
 * dosya yeniden hash'lenmez. Yarım kalmış `.part` her çağrıda temizlenir; işaretçisiz ama hedefi olan dosya
 * yeniden hash'lenir (kopya ile işaretçi arasında süreç öldüyse), tutmazsa silinip yeniden kopyalanır.
 *
 * Bloklayıcıdır; ana thread dışında çağrılmalıdır. Eşzamanlı çağrılar kilitle serileştirilir.
 * İptal: `checkCancelled` her 64 KB'ta çağrılır; fırlatırsa `.part` silinir ve istisna aynen yayılır.
 */
class ModelStore(
    filesDir: File,
    private val assets: AssetOpener,
) {
    private val dir = File(filesDir, "models")
    private val lock = Any()

    /** Dosyayı hazırlar ve doğrulanmış yerel yolunu döner. */
    fun ensure(model: ModelFile, checkCancelled: () -> Unit = {}): File = synchronized(lock) {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw ModelException.Io(model.fileName, IOException("klasör oluşturulamadı"))
        }
        val target = File(dir, model.fileName)
        val part = File(dir, model.fileName + ".part")
        val marker = File(dir, model.fileName + ".ok")
        part.delete()

        if (target.isFile) {
            if (target.length() == model.sizeBytes) {
                if (readMarker(marker) == markerText(model)) return target
                if (sha256Of(target) == model.sha256) {
                    writeMarker(marker, model)
                    return target
                }
            }
            marker.delete()
            target.delete()
        } else {
            marker.delete()
        }

        if (dir.usableSpace < model.sizeBytes + SPACE_MARGIN) {
            throw ModelException.InsufficientStorage(model.fileName, model.sizeBytes + SPACE_MARGIN)
        }
        copyVerified(model, part, checkCancelled)
        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            part.delete()
            throw ModelException.Io(model.fileName, e)
        }
        writeMarker(marker, model)
        target
    }

    private fun copyVerified(model: ModelFile, part: File, checkCancelled: () -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            assets.open(model.assetPath).use { input ->
                java.io.FileOutputStream(part).use { out ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        checkCancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        total += n
                    }
                    out.fd.sync()
                }
            }
        } catch (e: FileNotFoundException) {
            part.delete()
            throw ModelException.Missing(model.assetPath)
        } catch (e: IOException) {
            part.delete()
            throw ModelException.Io(model.fileName, e)
        } catch (e: Throwable) {
            // İptal (checkCancelled) veya beklenmeyen hata: yarım kopya kalmasın.
            part.delete()
            throw e
        }
        if (total != model.sizeBytes) {
            part.delete()
            throw ModelException.IntegrityFailure(model.fileName, "boyut $total != ${model.sizeBytes}")
        }
        if (hex(digest.digest()) != model.sha256) {
            part.delete()
            throw ModelException.IntegrityFailure(model.fileName, "SHA-256 uyuşmuyor")
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            file.inputStream().use { input ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
        } catch (e: IOException) {
            throw ModelException.Io(file.name, e)
        }
        return hex(digest.digest())
    }

    private fun markerText(model: ModelFile) = "${model.sizeBytes}:${model.sha256}"

    private fun readMarker(marker: File): String? =
        try {
            if (marker.isFile) marker.readText() else null
        } catch (e: IOException) {
            null
        }

    private fun writeMarker(marker: File, model: ModelFile) {
        val tmp = File(marker.path + ".tmp")
        try {
            tmp.writeText(markerText(model))
            Files.move(tmp.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            tmp.delete()
            // İşaretçi yazılamazsa sonraki açılışta dosya yeniden hash'lenir; kritik değil.
        }
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        return sb.toString()
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val SPACE_MARGIN = 16L * 1024 * 1024
        const val HEX = "0123456789abcdef"
    }
}
