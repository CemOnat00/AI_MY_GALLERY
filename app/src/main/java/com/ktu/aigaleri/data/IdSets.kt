package com.ktu.aigaleri.data

/** Sıralı kimlik dizileri üzerinde kümesel işlemler; boxing ve HashSet'siz (100 bin kimlik için ~800 KB). */
internal object IdSets {
    /** [a] içinde olup [b]'de olmayanlar. İki dizi de artan sıralı ve tekrarsız olmalı; sonuç artan sıralı. */
    fun difference(a: LongArray, b: LongArray): LongArray {
        val out = LongArray(a.size)
        var n = 0
        var j = 0
        for (x in a) {
            while (j < b.size && b[j] < x) j++
            if (j < b.size && b[j] == x) continue
            out[n++] = x
        }
        return out.copyOf(n)
    }

    /** Artan sıralı iki ayrık diziyi birleştirip azalan sıralı döner (indeksleme sırası: yeni kimlikler önce). */
    fun mergeDescending(a: LongArray, b: LongArray): LongArray {
        val out = LongArray(a.size + b.size)
        var i = a.size - 1
        var j = b.size - 1
        var n = 0
        while (i >= 0 || j >= 0) {
            out[n++] = when {
                j < 0 -> a[i--]
                i < 0 -> b[j--]
                a[i] >= b[j] -> a[i--]
                else -> b[j--]
            }
        }
        return out
    }
}
