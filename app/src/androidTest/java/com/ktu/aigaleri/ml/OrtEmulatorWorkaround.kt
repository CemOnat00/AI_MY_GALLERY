package com.ktu.aigaleri.ml

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Build
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * T-012 emülatör geçici çözümü (YALNIZCA test tarafı; üretim kodu değişmedi).
 *
 * Apple M-serisi ana makinedeki Android emülatörü gece SME/SME2/SVE2 bayraklarını konuk çekirdeğe gösterir
 * ("Features: ... sme sme2 ..."); ORT 1.30 KleidiAI SME çekirdekleri nicemlenmiş metin modelinin oturum açılışında
 * SIGILL verir (bkz. [ZzSmeDiagTest] / OrtSmeEmulatorDiagTest). `mlas.disable_kleidiai=1` ile açılan oturum çalışır.
 * Üretim `OnnxTextEncoder`/`OnnxImageEncoder` oturum seçeneklerini değiştirmeden, bu yardımcı [SingleSessionSlot]'a
 * ("text"/"vision" anahtarıyla) KleidiAI kapalı bir oturum yerleştirir; kodlayıcı slot'ta açık oturumu kullanır.
 * Gerçek telefonda ([needed] false) hiçbir şey yapmaz.
 *
 * Sınır: oturum slot'tan çıkarılırsa (release/eviction) üretim kodu varsayılan seçenekle yeniden açar ve emülatörde
 * çöker; bu yüzden yeniden-açma sınayan testler [needed] iken atlanır.
 */
object OrtEmulatorWorkaround {
    private const val TEXT_KEY = "text"
    private const val VISION_KEY = "vision"

    /** Emülatör VE konuk CPU "sme" özelliğini bildiriyor. */
    val needed: Boolean by lazy {
        val emulator = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone") ||
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"
        emulator && cpuFeatures().any { it == "sme" || it == "sme2" }
    }

    private fun cpuFeatures(): Set<String> = try {
        File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") }
            ?.substringAfter(':')?.trim()?.split(' ')?.toSet() ?: emptySet()
    } catch (_: Exception) {
        emptySet()
    }

    /** Üretimle aynı seçenekler + KleidiAI kapalı. */
    fun openSession(path: String): OrtSession {
        val env = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { o ->
            o.setIntraOpNumThreads(2)
            o.setMemoryPatternOptimization(false)
            o.setCPUArenaAllocator(false)
            o.addConfigEntry("mlas.disable_kleidiai", "1")
            return env.createSession(path, o)
        }
    }

    fun seedText(store: ModelStore, slot: SingleSessionSlot = SingleSessionSlot.shared) {
        if (!needed) return
        val path = store.ensure(ModelManifest.TEXT_ONNX).absolutePath
        runBlocking { slot.withSession(TEXT_KEY, open = { openSession(path) }) { } }
    }

    fun seedVision(store: ModelStore, slot: SingleSessionSlot = SingleSessionSlot.shared) {
        if (!needed) return
        val path = store.ensure(ModelManifest.VISION_ONNX).absolutePath
        runBlocking { slot.withSession(VISION_KEY, open = { openSession(path) }) { } }
    }
}
