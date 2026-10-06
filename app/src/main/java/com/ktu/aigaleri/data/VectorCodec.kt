package com.ktu.aigaleri.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Float vektör <-> BLOB dönüşümü (little-endian, vektör başına 4 bayt/eleman). Boyut sabit değildir;
 * BLOB uzunluğundan çıkarılır. Vektörün değerini hesaplamak ml katmanının işidir.
 */
object VectorCodec {
    fun encode(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(vector)
        return buffer.array()
    }

    /**
     * [blob]'u yeni dizi ayırmadan [out]'a çözer (arama taramasında satır başına tahsisi önler; [out] yeniden
     * kullanılır). [blob] uzunluğu tam `out.size * 4` olmalıdır, aksi halde [IllegalArgumentException].
     */
    fun decodeInto(blob: ByteArray, out: FloatArray) {
        require(blob.size == out.size * Float.SIZE_BYTES) { "BLOB uzunluğu out.size*4 olmalı" }
        var j = 0
        for (i in out.indices) {
            val bits = (blob[j].toInt() and 0xFF) or
                ((blob[j + 1].toInt() and 0xFF) shl 8) or
                ((blob[j + 2].toInt() and 0xFF) shl 16) or
                ((blob[j + 3].toInt() and 0xFF) shl 24)
            out[i] = Float.fromBits(bits)
            j += Float.SIZE_BYTES
        }
    }

    fun decode(blob: ByteArray): FloatArray {
        require(blob.size % Float.SIZE_BYTES == 0) { "BLOB uzunluğu 4'ün katı olmalı" }
        val out = FloatArray(blob.size / Float.SIZE_BYTES)
        ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }
}
