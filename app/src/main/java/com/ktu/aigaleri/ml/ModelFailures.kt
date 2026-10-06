package com.ktu.aigaleri.ml

/** [ModelException] hatalarının indeksleme açısından sınıflandırması. */
object ModelFailures {
    /** Oturum açılamadı/yerel kütüphane yüklenemedi: tüm fotoğrafları etkiler. */
    private val SESSION_STAGES = setOf("open", "native-load")

    /**
     * Hata model düzeyindeyse (dosya yok/bozuk, depolama, oturum açılamadı) true: aynı hata her fotoğrafta
     * tekrarlanır, bu yüzden indeksleme fotoğraf atlamak yerine durmalıdır. Tek çıkarıma özgü hatalar
     * ([ModelException.Inference] "run"/"output-*") false döner: fotoğraf atlanır.
     */
    fun isModelLevel(e: Throwable): Boolean = when (e) {
        is ModelException.Inference -> e.stage in SESSION_STAGES
        is ModelException -> true
        else -> false
    }
}
