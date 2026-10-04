package com.ktu.aigaleri.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Cihaz üstü indeks veritabanı. Şema JSON'ları `app/schemas` altına export edilir. */
@Database(
    entities = [Photo::class, PhotoEmbedding::class, IndexState::class],
    version = 1,
    exportSchema = true,
)
abstract class AiGaleriDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    abstract fun photoEmbeddingDao(): PhotoEmbeddingDao
    abstract fun indexStateDao(): IndexStateDao

    companion object {
        const val DATABASE_NAME = "aigaleri.db"

        fun create(context: Context): AiGaleriDatabase =
            Room.databaseBuilder(context.applicationContext, AiGaleriDatabase::class.java, DATABASE_NAME)
                .build()
    }
}
