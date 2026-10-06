package com.ktu.aigaleri.data

import androidx.room.withTransaction

/** Veritabanı işlemi soyutlaması: indeksleyici fotoğraf+vektör yazımını tek işlemde yapar; testte doğrudan çalıştırılır. */
interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

/** Room `withTransaction` ile atomik çalıştırır (hata olursa tümü geri alınır). */
class RoomTransactionRunner(private val db: AiGaleriDatabase) : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = db.withTransaction { block() }
}
