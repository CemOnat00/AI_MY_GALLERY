package com.ktu.aigaleri.ml

import com.ktu.aigaleri.domain.InvalidQueryException
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OnnxTextEncoderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val forbiddenAssets = AssetOpener { error("geçersiz sorguda assets'e dokunulmamalı") }

    @Test
    fun spec_is512DimWithModelVersionLabel() {
        val enc = OnnxTextEncoder(ModelStore(tmp.root, forbiddenAssets))
        assertEquals(512, enc.embeddingSpec.dimension)
        assertTrue(enc.embeddingSpec.modelVersion.isNotBlank())
        assertEquals(ModelManifest.EMBEDDING_SPEC, enc.embeddingSpec)
    }

    @Test
    fun missingModelFiles_surfaceAsModelException_notRawIoError() = runTest {
        val missing = AssetOpener { throw java.io.FileNotFoundException(it) }
        val enc = OnnxTextEncoder(ModelStore(tmp.root, missing), dispatcher = kotlinx.coroutines.Dispatchers.IO)
        try {
            enc.encode("gizli istem metni")
            fail()
        } catch (e: ModelException.Missing) {
            assertTrue(!e.message!!.contains("gizli"))
        }
    }

    @Test
    fun invalidQuery_isRejectedBeforeAnyModelWork() = runTest {
        val enc = OnnxTextEncoder(ModelStore(tmp.root, forbiddenAssets))
        for (bad in listOf("", "   ", "\u0000\u200b", "x".repeat(QueryPreprocessor.MAX_QUERY_LENGTH + 1))) {
            try {
                enc.encode(bad)
                fail("reddedilmeliydi")
            } catch (_: InvalidQueryException) {
            }
        }
        assertEquals(0, File(tmp.root, "models").list()?.size ?: 0)
    }
}
