package com.ktu.aigaleri.data

import com.ktu.aigaleri.domain.SearchResult
import java.util.PriorityQueue

/**
 * En iyi [limit] adayı sınırlı boyutlu min-heap ile tutar (kök = en kötü aday). Sıralama toplam düzendir:
 * skor büyük olan iyi, eşit skorda `photoId` küçük olan iyi (SearchRepository sözleşmesi); `photoId` benzersiz
 * olduğundan sonuç, adayların gelme sırasından (sayfa sınırları dahil) bağımsız ve deterministiktir.
 *
 * Bellek: en fazla `min(limit, aday sayısı)` giriş (vektör tutulmaz; giriş başına ~64 bayt + uri). Aday heap'e
 * girmeyecekse hiç nesne ayrılmaz. Tek thread için; eşzamanlı kullanım güvenli değildir.
 */
internal class TopKCollector(private val limit: Int) {
    private class Entry(val photoId: Long, val uri: String, val score: Float, val dateTaken: Long?)

    init {
        require(limit > 0) { "limit > 0 olmalı" }
    }

    // Kök en kötü giriş: düşük skor önce, eşit skorda büyük photoId önce.
    private val heap = PriorityQueue<Entry>(minOf(limit, INITIAL_CAPACITY_CAP)) { a, b ->
        val c = a.score.compareTo(b.score)
        if (c != 0) c else b.photoId.compareTo(a.photoId)
    }

    val size: Int get() = heap.size

    /** [score] NaN olmamalıdır (çağıran süzer). -0.0 ile 0.0 aynı kabul edilir. */
    fun offer(photoId: Long, score: Float, uri: String, dateTaken: Long?) {
        val s = score + 0f // -0.0f -> 0.0f
        if (heap.size >= limit) {
            val worst = heap.peek()!!
            val better = s > worst.score || (s == worst.score && photoId < worst.photoId)
            if (!better) return
            heap.poll()
        }
        heap.add(Entry(photoId, uri, s, dateTaken))
    }

    /** En iyiden en kötüye sıralı sonuç; toplayıcıyı boşaltır. */
    fun drainSorted(): List<SearchResult> {
        val out = arrayOfNulls<SearchResult>(heap.size)
        var i = out.size - 1
        while (i >= 0) { // poll() en kötüyü verir: sondan doldur
            val e = heap.poll()!!
            out[i--] = SearchResult(e.photoId, e.uri, e.score, e.dateTaken)
        }
        @Suppress("UNCHECKED_CAST")
        return (out as Array<SearchResult>).asList()
    }

    private companion object {
        const val INITIAL_CAPACITY_CAP = 256
    }
}
