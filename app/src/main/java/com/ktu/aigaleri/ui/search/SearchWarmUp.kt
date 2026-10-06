package com.ktu.aigaleri.ui.search

/**
 * Metin oturumu ısınma politikası (T-008). Isınma yalnızca yararlı ve zararsız olduğunda yapılır:
 * - İndekste aranabilir kayıt yoksa ([hasSearchableIndex] false) yapılmaz: model boşuna yüklenmez (arama boş döner).
 * - İndeksleme çalışırken ([isIndexing] true) YAPILMAZ, ertelenir: tek oturum kuralıyla metin oturumu açmak
 *   indeksleyicinin görüntü oturumunu kapatır ve ona yeniden açma maliyeti yükler. Ertelenen ısınma, bir sonraki
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
    @Volatile private var warmed = false

    /** Ağırlık/oturum bırakıldı (arka plan, bellek baskısı); sonraki [run] yeniden ısıtır. */
    fun onSessionReleased() {
        warmed = false
    }

    /** @return true: oturum ısınmış durumda; false: koşullar uygun olmadığı için ertelendi/atlandı. */
    suspend fun run(): Boolean {
        if (warmed) return true
        if (!hasSearchableIndex()) return false
        if (isIndexing()) return false
        warm()
        warmed = true
        return true
    }
}
