package com.ktu.aigaleri.data

/**
 * Galeri grid'i için hafif fotoğraf zaman çizelgesi: yalnızca kimlik ve etkin tarih, iki düz [LongArray] (fotoğraf
 * başına 16 bayt; 50 bin fotoğraf ~800 KB). URI ve küçük resim hücre çizilirken üretilir. Sıra en yeniden eskiye
 * (tarih azalan, eşitte kimlik azalan); tarihi bilinmeyenler ([UNKNOWN_DATE]) sonda. Dizileri çağıran değiştirmemelidir.
 */
class PhotoTimeline(private val ids: LongArray, private val dates: LongArray) {
    init {
        require(ids.size == dates.size) { "ids ve dates aynı boyutta olmalı" }
    }

    val size: Int get() = ids.size

    fun idAt(index: Int): Long = ids[index]

    /** Çekim tarihi (epoch ms; çekim yoksa eklenme zamanı); bilinmiyorsa null. */
    fun dateAt(index: Int): Long? = dates[index].takeIf { it >= 0 }

    override fun equals(other: Any?): Boolean =
        other is PhotoTimeline && ids.contentEquals(other.ids) && dates.contentEquals(other.dates)

    override fun hashCode(): Int = 31 * ids.contentHashCode() + dates.contentHashCode()

    companion object {
        /** Bilinmeyen tarih işareti; geçerli tarihlerden küçük olduğundan azalan sırada doğal olarak sona düşer. */
        const val UNKNOWN_DATE = -1L

        /**
         * Verilen dizilerden (sahipliği devralır, aynı boyutta) en yeniden eskiye sıralı çizelge üretir. Girdi zaten
         * sıralıysa ikinci bir sıralama yapılmaz (doğrusal kontrol). Sıralama dizilerde yerinde ve kutulamasızdır.
         * Negatif tarih [UNKNOWN_DATE] sayılır.
         */
        fun ofUnsorted(ids: LongArray, dates: LongArray): PhotoTimeline {
            require(ids.size == dates.size) { "ids ve dates aynı boyutta olmalı" }
            for (i in dates.indices) if (dates[i] < 0) dates[i] = UNKNOWN_DATE
            if (!isSortedNewestFirst(ids, dates)) mergeSortNewestFirst(ids, dates)
            return PhotoTimeline(ids, dates)
        }

        /** Hafif sürüm: [MediaPhoto] listesinden (ör. varsayılan [MediaPhotoSource.loadTimeline]). */
        fun fromPhotos(photos: List<MediaPhoto>): PhotoTimeline =
            ofUnsorted(
                LongArray(photos.size) { photos[it].id },
                LongArray(photos.size) { photos[it].dateTaken ?: UNKNOWN_DATE },
            )

        /** (dateA, idA) kaydı (dateB, idB)'den önce mi gelmeli (tarih azalan, eşitte kimlik azalan)? */
        private fun before(dateA: Long, idA: Long, dateB: Long, idB: Long): Boolean =
            dateA > dateB || (dateA == dateB && idA > idB)

        private fun isSortedNewestFirst(ids: LongArray, dates: LongArray): Boolean {
            for (i in 1 until ids.size) {
                if (before(dates[i], ids[i], dates[i - 1], ids[i - 1])) return false
            }
            return true
        }

        /** Alttan yukarı birleştirme sıralaması; yardımcı diziler tek seferlik, nesne/kutulama yok. */
        private fun mergeSortNewestFirst(ids: LongArray, dates: LongArray) {
            val n = ids.size
            var srcI = ids
            var srcD = dates
            var dstI = LongArray(n)
            var dstD = LongArray(n)
            var width = 1
            while (width < n) {
                var lo = 0
                while (lo < n) {
                    val mid = minOf(lo + width, n)
                    val hi = minOf(lo + 2 * width, n)
                    var a = lo
                    var b = mid
                    var k = lo
                    while (a < mid && b < hi) {
                        if (before(srcD[b], srcI[b], srcD[a], srcI[a])) {
                            dstI[k] = srcI[b]; dstD[k++] = srcD[b++]
                        } else {
                            dstI[k] = srcI[a]; dstD[k++] = srcD[a++]
                        }
                    }
                    while (a < mid) { dstI[k] = srcI[a]; dstD[k++] = srcD[a++] }
                    while (b < hi) { dstI[k] = srcI[b]; dstD[k++] = srcD[b++] }
                    lo = hi
                }
                val ti = srcI; srcI = dstI; dstI = ti
                val td = srcD; srcD = dstD; dstD = td
                width *= 2
            }
            if (srcI !== ids) {
                System.arraycopy(srcI, 0, ids, 0, n)
                System.arraycopy(srcD, 0, dates, 0, n)
            }
        }
    }
}
