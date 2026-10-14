package com.ktu.aigaleri.data

import com.ktu.aigaleri.domain.SearchResult

/**
 * Arama sonuç ELEME politikası (T-013, Cem kararı K-7: göreli eşik; hiç eşleşme yoksa "bulunamadı"). Tüm eşikler burada,
 * tek yerde; [RoomSearchRepository] yapıcı parametresiyle (`policy`) ve `AppDependencies` ile bağlanır. Politika taramanın
 * ÜSTÜNDE, yalnızca zaten skor sırasına dizilmiş (azalan skor, eşitlikte `photoId` artan) `limit` sonuçlu listeye uygulanır;
 * model, indeks, sıralama ve sorgu kodlayıcıya dokunmaz.
 *
 * İki kural (ikisi de `null` = kapalı):
 * 1. Mutlak TABAN [minTop1Score]: en iyi sonucun skoru tabandan KÜÇÜKSE (`top1 < taban`, taban dahil değil) liste BOŞ döner
 *    ("hiç eşleşme yok"; UI `SearchStatus.Empty`).
 * 2. GÖRELİ KESİM [margin]: skoru `top1 - margin` değerinden KÜÇÜK olan sonuç elenir (`skor >= top1 - margin` kalır; sınırdaki
 *    sonuç korunur, Float aritmetiğiyle hesaplanır). En iyi sonuç ve ona eşit skorlar hiçbir zaman elenmez.
 *
 * Davranış garantileri:
 * - Sonuç, girdinin SIRASI KORUNMUŞ bir alt kümesidir (sıralama/eşitlikte `photoId` kuralı bozulmaz, yeni öğe eklenmez,
 *   `limit` aşılamaz). İkisi de `null` ise ([OFF]) girdi AYNI liste örneği olarak döner (eskisiyle bit-bit aynı).
 * - Boş girdi -> boş. Tek sonuç: taban üstündeyse korunur, altındaysa boş.
 * - Negatif skor geçerlidir (kosinüs [-1, 1]); taban ve marj aynı şekilde işler. NaN skor [SearchResult]'ta zaten kurulamaz;
 *   taramanın kendisi sonlu olmayan skoru atlar. Politika yine de savunmacıdır: +-Inf skorlu öğe, kural açıkken, anlamlı
 *   kıyaslanamayacağı için elenir ve `top1` yalnızca sonlu skorlardan alınır; hiç sonlu skor yoksa liste boştur.
 * - Seçilen değerler gerçek galeride DOĞRULANMADI: yalnızca emülatörde 60 fotoğraf, 10 sınıfla ölçüldü
 *   (`docs/search-threshold.md`). Cihazdaki gerçek galeriyle yeniden kalibre edilmelidir.
 *
 * @property minTop1Score en iyi sonucun asgari skoru; altındaysa liste boş. `null`: kapalı. Sonlu olmalı.
 * @property margin en iyi skordan en fazla uzaklık; `null`: kapalı. Sonlu ve `>= 0` olmalı.
 */
data class SearchPolicy(
    val minTop1Score: Float? = null,
    val margin: Float? = null,
) {
    init {
        require(minTop1Score == null || minTop1Score.isFinite()) { "minTop1Score sonlu olmalı" }
        require(margin == null || (margin.isFinite() && margin >= 0f)) { "margin sonlu ve >= 0 olmalı" }
    }

    /** İki kural da kapalı: [apply] girdiyi olduğu gibi döndürür. */
    val isOff: Boolean get() = minTop1Score == null && margin == null

    /**
     * [sorted] (iyiden kötüye; [SearchRepository][com.ktu.aigaleri.domain.SearchRepository] sözleşmesi) listesine kuralları
     * uygular; ayrıntı için sınıf KDoc'u. Girdiyi değiştirmez. Skor, istem ve yol log'a yazılmaz.
     */
    fun apply(sorted: List<SearchResult>): List<SearchResult> {
        if (isOff || sorted.isEmpty()) return sorted
        var top = Float.NEGATIVE_INFINITY
        var anyFinite = false
        for (r in sorted) {
            if (r.score.isFinite()) {
                anyFinite = true
                if (r.score > top) top = r.score
            }
        }
        if (!anyFinite) return emptyList()
        if (minTop1Score != null && top < minTop1Score) return emptyList()
        val cut = if (margin != null) top - margin else Float.NEGATIVE_INFINITY
        return sorted.filter { it.score.isFinite() && it.score >= cut }
    }

    companion object {
        /** Eşikler kapalı: T-008 davranışı (her zaman top-K). Sınıf varsayılanı ve eşdeğerlik testleri bunu kullanır. */
        val OFF = SearchPolicy()

        /**
         * KONSERVATİF aday değerler (T-013 ölçümü, `docs/search-threshold.md`; emülatör, 60 foto). Uygulamada şu an
         * BAĞLI DEĞİL: `AppDependencies` [OFF] kullanır (PO kararı); Cem'in telefonunda yeniden ölçümden sonra açılır.
         * - taban 0,20: ölçülen en düşük VAR-sorgu top1'inin (0,2264) ~0,026 altı, ARM gürültüsünün (~2e-3) ~13 katı. Mutlak
         *   taban VAR ile YOK/anlamsız sorguyu AYIRMADI (ikisi 0,22-0,29 aralığında örtüşüyor); yalnızca belirgin çöp
         *   (hiçbir şeye benzemeyen, top1 < 0,20) için emniyet ağıdır.
         * - marj 0,12: ölçülen en büyük "top1 - en düşük ilgili skor" (0,0936) üstüne ~0,026 güvenlik payı. Bu YALNIZCA 60
         *   fotoğraflık ölçüm kümesi için geçerlidir (orada ilgili sonuç elenmedi, hit@5/MRR değişmedi; 50'lik listede
         *   VAR çok sözcüklü sorgularda ortalama 50 -> 43,6 sonuç kaldı). Büyük galeride kötü çekilmiş ilgili bir fotoğraf
         *   top1'den 0,12'den fazla geride kalabilir ve elenebilir; garanti DEĞİLDİR.
         */
        val DEFAULT = SearchPolicy(minTop1Score = 0.20f, margin = 0.12f)
    }
}
