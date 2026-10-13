package com.ktu.aigaleri.ui.search

/**
 * Metin oturumu ısınma politikası (T-008). Isınma yalnızca yararlı ve zararsız olduğunda yapılır:
 * - İndekste aranabilir kayıt yoksa ([hasSearchableIndex] false) yapılmaz: model boşuna yüklenmez (arama boş döner).
 * - İndeksleme GERÇEKTEN çalışırken ([isIndexing] true; kuyrukta bekleyen/pil kısıtlı iş sayılmaz, T-016) YAPILMAZ,
 *   ertelenir: tek oturum kuralıyla metin oturumu açmak indeksleyicinin görüntü oturumunu kapatır ve ona yeniden açma maliyeti yükler. Ertelenen ısınma, bir sonraki
 *   tetikte (ilk metin alanı odağı) yeniden denenir; kullanıcı yine de arama yaparsa model normal yoldan açılır.
 * - Zaten ısınmışsa ve oturum bırakılmadıysa ([onSessionReleased] çağrılmadıysa) tekrar çıkarım yapılmaz.
 *
 * İstem veya sonuç içermez, log yazmaz. Eşzamanlı çağrılar güvenlidir: [run] ardışık çağrıları en fazla bir kez
 * ısıtır (aynı anda iki çağrı gelirse ikisi de ısıtabilir; ısıtma idempotenttir ve slot kilidiyle serileşir).
 *
 * @param warm gerçek ısınma işlemi (`OnnxTextEncoder.warmUp`); hatayı yayar, çağıran yutar.
 */
class SearchWarmUp(
    private val hasSearchableIndex: suspend () -> Boolean,
    private val isIndexing: suspend () -> Boolean,
    private val warm: suspend () -> Unit,
) {
    private val lock = Any()
    private var warmed = false

    /**
     * Her bırakmada artar; ısınma sürerken bırakma olduysa `warmed` true yapılmaz (bayat bayrak engeli). Bırakma
     * üç noktadan bildirilir (AppDependencies): trim işleyicisinin `beforeRelease`'i (bırakma BAŞLAMADAN, süren ısınmayı
     * geçersiz kılar), slot `onClosed` geri çağrısı (oturum fiilen kapanırken; indeksleme görüntü oturumunu açıp metin
     * oturumunu çıkarınca da) ve `afterRelease` (hata olsa da). Hepsi idempotenttir.
     */
    private var releaseGeneration = 0L

    /**
     * Ağırlık/oturum bırakıldı (arka plan, bellek baskısı); sonraki [run] yeniden ısıtır. Süren bir ısınma varsa
     * onun sonucu geçersiz sayılır (o ısınma `warmed`'ı true yapmaz).
     */
    fun onSessionReleased() {
        synchronized(lock) {
            releaseGeneration++
            warmed = false
        }
    }

    /** @return true: oturum ısınmış durumda; false: koşullar uygun olmadığı için ertelendi/atlandı ya da ısınma sürerken oturum bırakıldı. */
    suspend fun run(): Boolean {
        val generation = synchronized(lock) { if (warmed) return true; releaseGeneration }
        if (!hasSearchableIndex()) return false
        if (isIndexing()) return false
        warm()
        // Isınma sürerken oturum bırakıldıysa (onSessionReleased) oturum artık açık değil: bayrak true yapılmaz,
        // sonraki çağrı yeniden ısıtır.
        return synchronized(lock) {
            if (releaseGeneration == generation) warmed = true
            warmed
        }
    }
}
