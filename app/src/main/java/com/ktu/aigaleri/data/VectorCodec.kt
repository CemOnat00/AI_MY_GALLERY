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

    fun decode(blob: ByteArray): FloatArray {
        require(blob.size % Float.SIZE_BYTES == 0) { "BLOB uzunluğu 4'ün katı olmalı" }
        val out = FloatArray(blob.size / Float.SIZE_BYTES)
        ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }
}
