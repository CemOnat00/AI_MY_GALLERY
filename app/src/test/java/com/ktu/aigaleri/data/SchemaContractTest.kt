package com.ktu.aigaleri.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-002 QA: export edilen şema JSON'u ve kaynak dosyalar architecture.md Bölüm 5 ve 8 ile uyumlu mu.
 * Çalışma dizini mobile/app.
 */
class SchemaContractTest {
    private val appDir = File(System.getProperty("user.dir")!!)
    private val schema = File(appDir, "schemas/com.ktu.aigaleri.data.AiGaleriDatabase/1.json").readText()
    private fun src(name: String) = File(appDir, "src/main/java/com/ktu/aigaleri/data/$name").readText()

    @Test
    fun schema_photoTable_hasMediaStoreIdPrimaryKey() {
        assertTrue(schema.contains("`mediaStoreId` INTEGER NOT NULL, `uri` TEXT NOT NULL, `dateTaken` INTEGER, `indexedAt` INTEGER NOT NULL, `indexVersion` INTEGER NOT NULL, PRIMARY KEY(`mediaStoreId`)"))
    }

    @Test
    fun schema_embedding_blobVector_modelVersion_cascadeFk_singlePerPhoto() {
        assertTrue(schema.contains("`photoId` INTEGER NOT NULL, `vector` BLOB NOT NULL, `modelVersion` TEXT NOT NULL, PRIMARY KEY(`photoId`)"))
        assertTrue(schema.contains("FOREIGN KEY(`photoId`) REFERENCES `photo`(`mediaStoreId`) ON UPDATE NO ACTION ON DELETE CASCADE"))
    }

    @Test
    fun schema_indexState_columns() {
        assertTrue(schema.contains("`id` INTEGER NOT NULL, `total` INTEGER NOT NULL, `processed` INTEGER NOT NULL, `lastRunAt` INTEGER, PRIMARY KEY(`id`)"))
    }

    @Test
    fun database_versionIsOne_andExportsSchema() {
        val db = src("AiGaleriDatabase.kt")
        assertTrue(db.contains("version = 1"))
        assertTrue(db.contains("exportSchema = true"))
    }

    @Test
    fun daos_useUpsert_neverReplace_andNoStringInterpolationInQueries() {
        val daos = src("Daos.kt")
        assertFalse(daos.contains("OnConflictStrategy.REPLACE"))
        assertFalse(daos.contains("@Insert"))
        assertTrue(Regex("@Upsert").findAll(daos).count() >= 3)
        // @Query literalleri parametreli (:name) olmalı; Kotlin şablonu ($) ile birleştirme olmamalı.
        val queries = Regex("@Query\\((.*?)\\)\\s*(suspend )?fun", RegexOption.DOT_MATCHES_ALL).findAll(daos).map { it.groupValues[1] }.toList()
        assertTrue(queries.isNotEmpty())
        queries.forEach { assertFalse("interpolasyon: $it", it.contains("$")) }
    }

    @Test
    fun manifest_hasNoInternetPermission_andBackupDisabled() {
        val m = File(appDir, "src/main/AndroidManifest.xml").readText()
        // tools:node="remove" girdisi (ORT AAR izni kaldırma, T-007) izin değildir.
        val internet = Regex("<uses-permission[^>]*android.permission.INTERNET[^>]*>").findAll(m).map { it.value }
            .filterNot { it.contains("tools:node=\"remove\"") }.toList()
        assertTrue(internet.toString(), internet.isEmpty())
        assertTrue(m.contains("android:allowBackup=\"false\""))
    }
}
