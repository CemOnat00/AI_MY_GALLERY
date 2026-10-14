package com.ktu.aigaleri

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * T-038: test kaynaklarının yerleşimden bağımsız yol çözümü.
 *
 * Proje kökü = Gradle kökü: `user.dir`'den (Gradle test çalışma dizini app/) yukarı çıkıp `settings.gradle.kts`
 * içeren ilk dizin. Git kökü proje kökünden farklı olabilir (eski yerleşimde proje kökü git deposunun `mobile/`
 * alt dizinidir, yeni yerleşimde ikisi aynıdır); testler yolları bu iki kökten türetir, `mobile/` varsaymaz.
 */
object ProjectLayout {
    /** Gradle proje kökü (settings.gradle.kts'in bulunduğu dizin). */
    val projectRoot: File = run {
        val start = File(System.getProperty("user.dir")!!).absoluteFile
        generateSequence(start) { it.parentFile }.firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("settings.gradle.kts bulunamadı: ${start.path} ve üstü")
    }

    /** Git işlemi çalıştırır (git yoksa/zaman aşımında null). Çıktı: çıkış kodu ve birleşik stdout+stderr. */
    fun git(dir: File, vararg args: String): Pair<Int, String>? {
        return try {
            val p = ProcessBuilder(listOf("git", "-C", dir.path) + args).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                null
            } else {
                p.exitValue() to out
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Git deposunun kökü; proje git deposu içinde değilse veya git yoksa null. */
    val gitRoot: File? by lazy {
        val r = git(projectRoot, "rev-parse", "--show-toplevel")
        if (r != null && r.first == 0) File(r.second.trim()).canonicalFile else null
    }

    /**
     * Proje kökünün git kökünden göreli öneki, `/` ile bitecek şekilde: eski yerleşimde "mobile/", yeni yerleşimde "".
     * Git yoksa null. `git ls-files` ve `git diff` yolları (git köküne göreli) bu önekle proje köküne göre normalize edilir.
     */
    val gitPrefix: String? by lazy {
        val root = gitRoot ?: return@lazy null
        val rel = projectRoot.canonicalFile.relativeTo(root).path.replace(File.separatorChar, '/')
        if (rel.isEmpty()) "" else "$rel/"
    }
}
