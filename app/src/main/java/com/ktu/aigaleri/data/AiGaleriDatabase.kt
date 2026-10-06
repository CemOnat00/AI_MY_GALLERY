package com.ktu.aigaleri.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Cihaz üstü indeks veritabanı. Şema JSON'ları `app/schemas` altına export edilir. */
@Database(
    entities = [Photo::class, PhotoEmbedding::class, IndexState::class],
    version = 2,
    exportSchema = true,
)
abstract class AiGaleriDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    abstract fun photoEmbeddingDao(): PhotoEmbeddingDao
    abstract fun indexStateDao(): IndexStateDao

    companion object {
        const val DATABASE_NAME = "aigaleri.db"

        /** v1 -> v2: `index_state.fullTargetVersion` (nullable, devam eden FULL turunun hedefi). Mevcut veri korunur. */
        const val MIGRATION_1_2_SQL = "ALTER TABLE `index_state` ADD COLUMN `fullTargetVersion` INTEGER"

        /**
         * Gerçek migration (yıkıcı geri dönüş KULLANILMAZ): indeks ve ilerleme korunur. Sütun nullable
         * olduğundan varsayılan değer gerekmez; mevcut satırlarda null (yarım FULL yok) olur.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(MIGRATION_1_2_SQL)
            }
        }

        /**
         * Application'da tek kez çağır; birden fazla örnek Flow invalidation'larını birbirinden
         * habersiz bırakır.
         */
        fun create(context: Context): AiGaleriDatabase =
            Room.databaseBuilder(context.applicationContext, AiGaleriDatabase::class.java, DATABASE_NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
