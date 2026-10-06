package com.ktu.aigaleri.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-006: Room v1 -> v2 geçişinin JVM'de doğrulanabilen kısmı (SQLite çalıştırılmaz; gerçek geçiş
 * `AiGaleriMigrationTest` androidTest'indedir, çalıştırılmadı). Migration SQL'i, v1 ve v2 şema JSON'ları ve kaynak
 * bağlantısı birbiriyle tutarlı mı. Çalışma dizini mobile/app.
 */
class MigrationContractTest {
    private val appDir = File(System.getProperty("user.dir")!!)
    private val schemaDir = File(appDir, "schemas/com.ktu.aigaleri.data.AiGaleriDatabase")
    private val v1 = File(schemaDir, "1.json").readText()
    private val v2 = File(schemaDir, "2.json").readText()
    private val dbSource = File(appDir, "src/main/java/com/ktu/aigaleri/data/AiGaleriDatabase.kt").readText()

    private fun createSql(schema: String, table: String): String =
        Regex("\"tableName\": \"$table\",\\s*\"createSql\": \"(.*?)\",").find(schema)!!.groupValues[1]

    @Test
    fun migrationSql_isNullableAddColumn_onIndexState() {
        val sql = AiGaleriDatabase.MIGRATION_1_2_SQL
        assertEquals("ALTER TABLE `index_state` ADD COLUMN `fullTargetVersion` INTEGER", sql)
        assertFalse(sql.contains("NOT NULL")) // mevcut satırlar için varsayılan gerekmez
    }

    @Test
    fun v2IndexStateSchema_isV1PlusTheMigratedColumn() {
        val column = Regex("ADD COLUMN (`\\w+` INTEGER)$").find(AiGaleriDatabase.MIGRATION_1_2_SQL)!!.groupValues[1]
        val expected = createSql(v1, "index_state").replace(", PRIMARY KEY", ", $column, PRIMARY KEY")
        assertEquals(expected, createSql(v2, "index_state"))
    }

    @Test
    fun otherTablesAreUnchanged_betweenV1AndV2() {
        for (table in listOf("photo", "photo_embedding")) assertEquals(table, createSql(v1, table), createSql(v2, table))
        assertTrue(v1.contains("\"version\": 1,"))
        assertTrue(v2.contains("\"version\": 2,"))
        assertNotEquals(
            Regex("\"identityHash\": \"(\\w+)\"").find(v1)!!.groupValues[1],
            Regex("\"identityHash\": \"(\\w+)\"").find(v2)!!.groupValues[1],
        )
    }

    @Test
    fun builder_usesRealMigration_neverDestructiveFallback() {
        assertTrue(dbSource.contains(".addMigrations(MIGRATION_1_2)"))
        assertTrue(dbSource.contains("Migration(1, 2)"))
        assertFalse(dbSource.contains("fallbackToDestructiveMigration"))
    }

    @Test
    fun indexStateEntity_hasNullableFullTargetVersion_defaultNull() {
        val state = IndexState(total = 1, processed = 0, lastRunAt = null)
        assertEquals(null, state.fullTargetVersion)
        assertEquals(3, state.copy(fullTargetVersion = 3).fullTargetVersion)
    }
}
