package com.ktu.aigaleri

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * T-024: release küçültme (R8 + kaynak küçültme) yapılandırması ve R8 çıktıları (seeds.txt, mapping.txt).
 * R8 çıktı testleri `./gradlew assembleRelease` sonrası anlamlıdır; dosya yoksa atlanır (normal testDebugUnitTest
 * kırılmaz). Çalışma dizini app/ (Gradle proje kökünün altında).
 */
class ReleaseShrinkConfigTest {
    private val appDir = File(System.getProperty("user.dir")!!)

    /** build.gradle.kts, `//` yorumları atılmış hâlde (yorum satırları yanlış eşleşme üretmesin). */
    private fun gradleWithoutComments(): String =
        File(appDir, "build.gradle.kts").readLines().joinToString("\n") { it.substringBefore("//") }

    /** `release {` bloğunun gövdesi (iç içe süslü parantezler sayılır). */
    private fun releaseBlock(gradle: String): String {
        val start = Regex("(?m)^\\s*release\\s*\\{").find(gradle)
        assertTrue("build.gradle.kts içinde release { } bloğu yok", start != null)
        var depth = 0
        for (i in (start!!.range.last)..gradle.lastIndex) {
            when (gradle[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return gradle.substring(start.range.last + 1, i)
            }
        }
        error("release bloğu kapanmıyor")
    }

    @Test
    fun gradle_releaseShrinkingAndLocaleFilters_areEnabled() {
        val gradle = gradleWithoutComments()
        val release = releaseBlock(gradle)
        assertTrue(Regex("(?m)^\\s*isMinifyEnabled\\s*=\\s*true\\b").containsMatchIn(release))
        assertTrue(Regex("(?m)^\\s*isShrinkResources\\s*=\\s*true\\b").containsMatchIn(release))
        assertTrue(Regex("(?m)^\\s*localeFilters\\s*\\+=\\s*listOf\\(\\s*\"tr\"\\s*,\\s*\"en\"\\s*\\)").containsMatchIn(gradle))
        // Model dosyaları sıkıştırılmaz (T-024 öncesi davranış korunur).
        assertTrue(Regex("(?m)^\\s*noCompress\\s*\\+=\\s*\"onnx\"").containsMatchIn(gradle))
    }

    @Test
    fun proguardRules_keepOnnxRuntime() {
        val rules = File(appDir, "proguard-rules.pro").readLines()
            .map { it.substringBefore("#").trim().replace(Regex("\\s+"), " ") }
        assertTrue(rules.contains("-keep class ai.onnxruntime.** { *; }"))
    }

    @Test
    fun releaseSeedsAndMapping_proveKeptClassesSurvived() {
        val seeds = File(appDir, "build/outputs/mapping/release/seeds.txt")
        val mapping = File(appDir, "build/outputs/mapping/release/mapping.txt")
        assumeTrue("assembleRelease çalışmadı: seeds.txt yok", seeds.isFile)
        assumeTrue("assembleRelease çalışmadı: mapping.txt yok", mapping.isFile)
        // Sınıf seed'leri çıplak sınıf adı satırıdır ("sınıf: üye" satırları üyedir): tam eşleşme aranır.
        val lines = seeds.readLines().map { it.trim() }.toSet()
        val required = listOf(
            "com.ktu.aigaleri.work.IndexWorker", // WorkManager sınıfı adıyla yansıtma ile oluşturur
            "com.ktu.aigaleri.data.AiGaleriDatabase_Impl", // Room adıyla yansıtma ile oluşturur
            "androidx.work.impl.WorkDatabase_Impl",
            "ai.onnxruntime.OrtSession", // JNI adıyla çağırır
        )
        for (cls in required) assertTrue("seeds.txt içinde yok (R8 sildi/yeniden adlandırdı): $cls", cls in lines)
        // WorkManager kurucuyu (Context, WorkerParameters) yansıtma ile çağırır: kurucu da korunmalı.
        assertTrue(
            "seeds.txt: IndexWorker kurucusu yok",
            "com.ktu.aigaleri.work.IndexWorker: IndexWorker(android.content.Context,androidx.work.WorkerParameters)" in lines,
        )
        // Sınıf mapping'te hayatta ve adı değişmemiş (silinmiş/yeniden adlandırılmış olsaydı satır farklı olurdu).
        val mapped = mapping.useLines { seq -> seq.any { it == "com.ktu.aigaleri.work.IndexWorker -> com.ktu.aigaleri.work.IndexWorker:" } }
        assertTrue("mapping.txt: IndexWorker adı değişmiş veya sınıf yok", mapped)
    }
}
