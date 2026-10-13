package com.ktu.aigaleri

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * T-024: release küçültme (R8 + kaynak küçültme) yapılandırması ve R8 "seeds" çıktısı.
 * Seeds testi `./gradlew assembleRelease` sonrası anlamlıdır; dosya yoksa atlanır (normal testDebugUnitTest kırılmaz).
 * Çalışma dizini mobile/app.
 */
class ReleaseShrinkConfigTest {
    private val appDir = File(System.getProperty("user.dir")!!)

    @Test
    fun gradle_releaseShrinkingAndLocaleFilters_areEnabled() {
        val gradle = File(appDir, "build.gradle.kts").readText()
        assertTrue(Regex("isMinifyEnabled\\s*=\\s*true").containsMatchIn(gradle))
        assertTrue(Regex("isShrinkResources\\s*=\\s*true").containsMatchIn(gradle))
        assertTrue(Regex("localeFilters\\s*\\+=\\s*listOf\\(\\s*\"tr\"\\s*,\\s*\"en\"\\s*\\)").containsMatchIn(gradle))
        // Model dosyaları sıkıştırılmaz (T-024 öncesi davranış korunur).
        assertTrue(Regex("noCompress\\s*\\+=\\s*\"onnx\"").containsMatchIn(gradle))
    }

    @Test
    fun proguardRules_keepOnnxRuntime() {
        val rules = File(appDir, "proguard-rules.pro").readLines().map { it.trim() }
        assertTrue(rules.contains("-keep class ai.onnxruntime.** { *; }"))
    }

    @Test
    fun releaseSeeds_containKeptClasses() {
        val seeds = File(appDir, "build/outputs/mapping/release/seeds.txt")
        assumeTrue("assembleRelease çalışmadı: seeds.txt yok", seeds.isFile)
        // Sınıf seed'leri çıplak sınıf adı satırıdır ("sınıf: üye" satırları üyedir): tam eşleşme aranır.
        val lines = seeds.readLines().map { it.trim() }.toSet()
        val required = listOf(
            "com.ktu.aigaleri.work.IndexWorker", // WorkManager sınıfı adıyla yansıtma ile oluşturur
            "com.ktu.aigaleri.data.AiGaleriDatabase_Impl", // Room adıyla yansıtma ile oluşturur
            "androidx.work.impl.WorkDatabase_Impl",
            "ai.onnxruntime.OrtSession", // JNI adıyla çağırır
        )
        for (cls in required) assertTrue("seeds.txt içinde yok (R8 sildi/yeniden adlandırdı): $cls", cls in lines)
    }
}
