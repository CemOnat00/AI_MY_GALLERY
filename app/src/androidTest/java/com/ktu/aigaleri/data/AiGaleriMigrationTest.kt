package com.ktu.aigaleri.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room v1 -> v2 geçişi (`index_state.fullTargetVersion`): mevcut indeks, vektör ve ilerleme korunur, yeni sütun null olur,
 * şema `schemas/2.json` ile doğrulanır (androidTest assets'ine schemas eklenir, bkz. build.gradle.kts).
 *
 * DURUM: YAZILDI, DERLENDİ, ÇALIŞTIRILMADI (cihaz/emülatör yok).
 */
@RunWith(AndroidJUnit4::class)
class AiGaleriMigrationTest {
    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AiGaleriDatabase::class.java)

    @Test
    fun migrate1To2_keepsData_andAddsNullableColumn() {
        helper.createDatabase(dbName, 1).apply {
            execSQL("INSERT INTO photo (mediaStoreId, uri, dateTaken, indexedAt, indexVersion) VALUES (7, 'content://x/7', 5, 6, 1)")
            execSQL("INSERT INTO photo_embedding (photoId, vector, modelVersion) VALUES (7, x'0000803F', 'm1')")
            execSQL("INSERT INTO index_state (id, total, processed, lastRunAt) VALUES (1, 10, 4, 99)")
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 2, true, AiGaleriDatabase.MIGRATION_1_2)

        db.query("SELECT total, processed, lastRunAt, fullTargetVersion FROM index_state WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(10, c.getInt(0))
            assertEquals(4, c.getInt(1))
            assertEquals(99L, c.getLong(2))
            assertTrue(c.isNull(3))
        }
        db.query("SELECT COUNT(*) FROM photo").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM photo_embedding").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.execSQL("UPDATE index_state SET fullTargetVersion = 3 WHERE id = 1")
        db.query("SELECT fullTargetVersion FROM index_state").use { c -> c.moveToFirst(); assertEquals(3, c.getInt(0)) }
    }

    @Test
    fun migrate1To2_withoutIndexStateRow_works() {
        helper.createDatabase(dbName, 1).close()
        helper.runMigrationsAndValidate(dbName, 2, true, AiGaleriDatabase.MIGRATION_1_2).close()
    }
}
