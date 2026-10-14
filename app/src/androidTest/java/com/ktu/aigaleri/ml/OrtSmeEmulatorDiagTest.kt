package com.ktu.aigaleri.ml

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeFalse
import org.junit.Test

/**
 * T-012 tanı: ORT oturum açılışı SME/KleidiAI ile uyumlu mu? Apple M4 ana makinede Pixel_8 API 35 emülatöründe
 * (konuk CPU "sme sme2 sve2" bildirir) varsayılan seçenekle (a) ve NO_OPT ile (c) metin modeli oturum açılışı
 * `SIGILL` ile süreci çökertir (libonnxruntime.so, nicemlenmiş MatMul/KleidiAI SME çekirdeği); `mlas.disable_kleidiai=1`
 * (b, d) ile açılır. Bu nedenle a ve c, SME bildiren emülatörde ATLANIR (süreci öldürüp koşuyu keserdi); gerçek telefonda
 * çalışır ve varsayılan seçenekle oturumun açıldığını doğrular (SME/KleidiAI regresyon bekçisi).
 */
class OrtSmeEmulatorDiagTest {
    private fun model(): String {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return ModelStore(ctx.filesDir, AssetManagerOpener(ctx.assets)).ensure(ModelManifest.TEXT_ONNX).absolutePath
    }

    private fun open(cfg: (OrtSession.SessionOptions) -> Unit) {
        val env = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { o ->
            o.setIntraOpNumThreads(2)
            o.setMemoryPatternOptimization(false)
            o.setCPUArenaAllocator(false)
            cfg(o)
            env.createSession(model(), o).close()
        }
    }

    @Test fun a_baseline() {
        assumeFalse("SME bildiren emülatörde SIGILL", OrtEmulatorWorkaround.needed)
        open { }
    }

    @Test fun b_noKleidi() = open { it.addConfigEntry("mlas.disable_kleidiai", "1") }

    @Test fun c_noOpt() {
        assumeFalse("SME bildiren emülatörde SIGILL", OrtEmulatorWorkaround.needed)
        open { it.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT) }
    }

    @Test fun d_both() = open {
        it.addConfigEntry("mlas.disable_kleidiai", "1")
        it.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
    }
}
