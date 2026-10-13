package com.ktu.aigaleri.data

import com.ktu.aigaleri.domain.EmbeddingSpec
import com.ktu.aigaleri.domain.InvalidQueryException
import com.ktu.aigaleri.domain.TextEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * T-025 QA: BAĞIMSIZ doğrulama. Mevcut eşdeğerlik testleri ([SearchScanEquivalenceTest]) eski taramayı üretimle paylaşılan
 * [TopKCollector] ve `RoomSearchRepository.dot` ile yeniden yazar; burada referans bunlardan ve [SyntheticIndex]'ten
 * BAĞIMSIZDIR: kendi nokta çarpımı, kendi bayt çözmesi, kendi sahte DAO'su, tam sıralama (skor azalan, eşitte photoId
 * artan) + ilk `limit`, eski INNER JOIN semantiği (photo satırı olmayan embedding görünmez; keyset `photoId > after`
 * olduğundan `Long.MIN_VALUE` kimlikli kayıt hiç taranmaz, eski sorguyla aynı) ve eski hata kuralı (görünür kayıtların
 * TAMAMI atlandıysa IllegalStateException). Sabit tohumlu çok sayıda rastgele senaryoda yeni uygulamayla BİT-BİT
 * karşılaştırılır (kimlik, uri, skor ham bitleri, dateTaken, sıra, sayı, hata türü).
 */
class SearchScanQaReferenceTest {
    private companion object {
        const val MODEL = "kıyas-İI-ı-v2" // Türkçe karakterli model sürümü (tam eşleşme gerekir)
        val LOOKALIKE_MODELS = listOf(
            "kıyas-iI-ı-v2", "KIYAS-Iİ-I-V2", "kıyas-İI-ı-v2 ", " kıyas-İI-ı-v2", "kıyas-İI-ı-v2", "other-v1", "", "kıyas-İI-ı-v3",
        )
        val URIS = listOf(
            "content://media/external/images/media/%d",
            "content://media/external/images/media/çğıöşü-İ-%d",
            "file:///sdcard/Pictures/Şişli İstanbul %d.jpg",
            "content://x/​%d", // sıfır genişlikli boşluk: isBlank DEĞİL
        )
        val BLANK_URIS = listOf("", " ", "   ", "\t", "\n", " \t\r\n ", " ", " ")
    }

    // ---------------------------------------------------------------- bağımsız referans

    private class Rec(val id: Long, val model: String, val hasPhoto: Boolean, val uri: String, val date: Long?, val blob: ByteArray)

    private data class Hit(val id: Long, val uri: String, val scoreBits: Int, val date: Long?)

    private sealed interface Outcome {
        data class Ok(val hits: List<Hit>) : Outcome
        data object IllegalState : Outcome
    }

    private fun leBytes(bits: IntArray): ByteArray {
        val b = ByteArray(bits.size * 4)
        for (i in bits.indices) for (k in 0 until 4) b[i * 4 + k] = (bits[i] ushr (8 * k)).toByte()
        return b
    }

    private fun leBytes(v: FloatArray) = leBytes(IntArray(v.size) { v[it].toRawBits() })

    private fun refDecode(blob: ByteArray): FloatArray = FloatArray(blob.size / 4) { i ->
        var bits = 0L
        for (k in 0 until 4) bits = bits or ((blob[i * 4 + k].toLong() and 0xFF) shl (8 * k))
        Float.fromBits(bits.toInt())
    }

    private fun refDot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** Eski davranış: tam sıralama + ilk limit. [TopKCollector] KULLANILMAZ. */
    private fun reference(recs: List<Rec>, model: String, q: FloatArray, limit: Int, minScore: Float?): Outcome {
        val visible = recs.filter { it.model == model && it.hasPhoto && it.id != Long.MIN_VALUE }
        if (visible.isEmpty()) return Outcome.Ok(emptyList())
        var skipped = 0
        val valid = ArrayList<Pair<Rec, Float>>()
        for (r in visible) {
            if (r.blob.size != q.size * 4 || r.uri.isBlank()) { skipped++; continue }
            val s = refDot(q, refDecode(r.blob))
            if (!s.isFinite()) { skipped++; continue }
            if (minScore != null && s < minScore) continue
            valid += r to (s + 0f)
        }
        if (skipped == visible.size) return Outcome.IllegalState
        valid.sortWith { x, y ->
            val c = y.second.compareTo(x.second)
            if (c != 0) c else x.first.id.compareTo(y.first.id)
        }
        return Outcome.Ok(valid.take(limit).map { (r, s) -> Hit(r.id, r.uri, s.toRawBits(), r.date) })
    }

    // ---------------------------------------------------------------- sahte DAO (gerçek SQL'i taklit eder)

    private class QaDao(recs: List<Rec>) : PhotoEmbeddingDao {
        private val sorted = recs.sortedBy { it.id }
        private val photos = recs.filter { it.hasPhoto }.associateBy { it.id }
        val pageCalls = mutableListOf<Triple<String, Long, Int>>()
        val refCalls = mutableListOf<List<Long>>()
        var onPage: (suspend (Int) -> Unit)? = null
        var onRefs: (suspend (Int) -> Unit)? = null

        val passes get() = pageCalls.count { it.second == Long.MIN_VALUE }

        override suspend fun getPageForModel(modelVersion: String, afterId: Long, limit: Int): List<EmbeddingRow> {
            pageCalls += Triple(modelVersion, afterId, limit)
            onPage?.invoke(pageCalls.size)
            require(limit >= 1)
            var lo = 0
            var hi = sorted.size
            while (lo < hi) { // ilk id > afterId
                val mid = (lo + hi) ushr 1
                if (sorted[mid].id > afterId) hi = mid else lo = mid + 1
            }
            val out = ArrayList<EmbeddingRow>(minOf(limit, 512))
            var i = lo
            while (i < sorted.size && out.size < limit) {
                val r = sorted[i++]
                if (r.model == modelVersion) out += EmbeddingRow(r.id, r.blob)
            }
            return out
        }

        override suspend fun getPhotoRefsByIds(ids: List<Long>): List<PhotoRef> {
            refCalls += ids.toList()
            onRefs?.invoke(refCalls.size)
            require(ids.isNotEmpty()) { "boş IN listesi" }
            require(ids.size <= RoomSearchRepository.REF_CHUNK) { "SQLite değişken sınırı: ${ids.size}" }
            return ids.mapNotNull { id -> photos[id]?.let { PhotoRef(id, it.uri, it.date) } }.shuffled(Random(ids.size.toLong())) // sırasız
        }

        override suspend fun getAllForModel(modelVersion: String): List<IndexedVector> = throw AssertionError("tümünü okuma")
        override suspend fun upsert(embedding: PhotoEmbedding) = throw UnsupportedOperationException()
        override suspend fun getByPhotoId(photoId: Long): PhotoEmbedding? = throw UnsupportedOperationException()
        override suspend fun count(): Int = throw UnsupportedOperationException()
        override suspend fun deleteNotMatching(modelVersion: String) = throw UnsupportedOperationException()
    }

    private fun search(
        recs: List<Rec>, q: FloatArray, limit: Int, pageSize: Int, minScore: Float? = null, dao: QaDao = QaDao(recs),
        model: String = MODEL,
    ): Outcome = runBlocking {
        val repo = RoomSearchRepository(FixedTextEncoder(q, EmbeddingSpec(q.size, model)), dao, pageSize, minScore, Dispatchers.Unconfined)
        try {
            Outcome.Ok(repo.search("x", limit).map { Hit(it.photoId, it.uri, it.score.toRawBits(), it.dateTaken) })
        } catch (e: IllegalStateException) {
            Outcome.IllegalState
        }
    }

    // ---------------------------------------------------------------- senaryo üretimi

    private fun <T> Random.pick(vararg v: T): T = v[nextInt(v.size)]

    private fun distinctIds(rnd: Random, n: Int, mode: Int): List<Long> {
        val s = LinkedHashSet<Long>()
        when (mode) {
            0 -> for (i in 1..n) s += i.toLong()
            1 -> { var id = 0L; repeat(n) { id += 1 + rnd.nextInt(9); s += id } }
            2 -> for (i in 0 until n) s += (i - n / 2).toLong() // negatifler, 0
            3 -> for (i in 0 until n) s += Long.MAX_VALUE - i
            4 -> for (i in 1..n) s += Long.MIN_VALUE + i // MIN'e bitişik (MIN'in kendisi hariç)
            5 -> while (s.size < n) s += rnd.nextLong()
            6 -> { // uçlar karışık: MIN, MIN+1, -1, 0, 1, MAX-1, MAX + rastgele
                val edge = listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L, 0L, 1L, Long.MAX_VALUE - 1, Long.MAX_VALUE)
                for (e in edge) if (s.size < n) s += e
                while (s.size < n) s += rnd.nextLong() shr rnd.nextInt(63)
            }
        }
        return s.toList().shuffled(rnd)
    }

    private fun badBlob(rnd: Random, dim: Int): ByteArray = when (rnd.nextInt(14)) {
        0 -> ByteArray(0)
        1 -> ByteArray(1)
        2 -> ByteArray(dim * 4 - 1)
        3 -> ByteArray(dim * 4 + 1)
        4 -> leBytes(FloatArray(dim * 2) { 0.5f }) // dim*8 bayt
        5 -> ByteArray(dim * 4 + 3)
        6 -> leBytes(FloatArray(dim) { Float.NaN })
        7 -> leBytes(FloatArray(dim) { Float.POSITIVE_INFINITY })
        8 -> leBytes(FloatArray(dim) { Float.NEGATIVE_INFINITY })
        9 -> leBytes(IntArray(dim) { if (it % 2 == 0) 0x7FA00000 else 0xFFFFFFFF.toInt() }) // NaN yükleri
        10 -> leBytes(FloatArray(dim) { Float.MAX_VALUE }) // skor taşabilir (sonlu vektör, sonlu olmayan skor)
        11 -> leBytes(IntArray(dim) { 0x7F800000 }) // +Inf bitleri
        12 -> leBytes(FloatArray(dim) { if (it == 0) Float.NaN else 1f }) // tek bileşen NaN
        else -> ByteArray(dim * 4 - 3)
    }

    private fun oddValidBlob(rnd: Random, dim: Int): ByteArray = when (rnd.nextInt(3)) {
        0 -> leBytes(IntArray(dim) { 0x80000000.toInt() }) // -0.0
        1 -> leBytes(IntArray(dim) { 1 + rnd.nextInt(0x7FFFFF) }) // alt-normal
        else -> leBytes(IntArray(dim) { (0x80000001.toInt()) + rnd.nextInt(0x7FFFFF) }) // negatif alt-normal
    }

    private class Scenario(
        val recs: List<Rec>, val q: FloatArray, val limit: Int, val pageSize: Int, val minScore: Float?, val desc: String,
    )

    private fun scenario(rnd: Random, it: Int): Scenario {
        val dim = rnd.pick(1, 2, 3, 4, 8, 16)
        val pageSize = rnd.pick(1, 2, 3, 5, 7, 64, 255, 256, 257, 499, 500)
        val baseLimit = rnd.pick(1, 2, 3, 31, 32, 33, 50, 51, 64, 100, 257)
        val n = when (rnd.nextInt(12)) {
            0 -> 0
            1 -> 1
            2 -> baseLimit
            3 -> baseLimit + 1
            4 -> baseLimit + 32
            5 -> baseLimit + 33
            6 -> pageSize - 1
            7 -> pageSize
            8 -> pageSize + 1
            9 -> 2 * pageSize + rnd.nextInt(3) - 1
            else -> rnd.nextInt(700)
        }.coerceIn(0, 1100)
        val limit = when (rnd.nextInt(9)) {
            0 -> 1
            1 -> 50
            2 -> maxOf(1, n)
            3 -> n + 1
            4 -> maxOf(1, n - 1)
            5 -> Int.MAX_VALUE
            6 -> Int.MAX_VALUE - 31
            else -> baseLimit
        }
        val quant = rnd.nextBoolean()
        val sameAll = rnd.nextInt(8) == 0
        val bad = rnd.pick(0.0, 0.0, 0.02, 0.3, 1.0)
        val blank = rnd.pick(0.0, 0.0, 0.02, 0.3, 1.0)
        val orphan = rnd.pick(0.0, 0.0, 0.1, 0.5, 0.9, 1.0)
        val other = rnd.pick(0.0, 0.0, 0.1, 0.5, 1.0)
        val odd = rnd.pick(0.0, 0.0, 0.05)
        val minScore = rnd.pick(null, null, null, null, -1f, 0f, -0.0f, 0.3f, 0.99f, 2f)
        val ids = distinctIds(rnd, n, rnd.nextInt(7))

        val q = if (quant) {
            FloatArray(dim).also { it[rnd.nextInt(dim)] = if (rnd.nextBoolean()) 1f else -1f } // eksen: tam birim
        } else randomUnit(rnd, dim)
        val fixed = FloatArray(dim) { (rnd.nextInt(5) - 2) * 0.5f }
        val pool = Array(3) { FloatArray(dim) { (rnd.nextInt(5) - 2) * 0.5f } }
        val recs = ids.map { id ->
            val vec: ByteArray = when {
                rnd.nextDouble() < bad -> badBlob(rnd, dim)
                rnd.nextDouble() < odd -> oddValidBlob(rnd, dim)
                sameAll -> leBytes(fixed)
                quant -> leBytes(FloatArray(dim) { (rnd.nextInt(5) - 2) * 0.5f })
                rnd.nextInt(4) == 0 -> leBytes(pool[rnd.nextInt(pool.size)])
                else -> leBytes(FloatArray(dim) { rnd.nextGaussian().toFloat() })
            }
            val uri = if (rnd.nextDouble() < blank) BLANK_URIS[rnd.nextInt(BLANK_URIS.size)] else URIS[rnd.nextInt(URIS.size)].format(id)
            Rec(
                id = id,
                model = if (rnd.nextDouble() < other) LOOKALIKE_MODELS[rnd.nextInt(LOOKALIKE_MODELS.size)] else MODEL,
                hasPhoto = rnd.nextDouble() >= orphan,
                uri = uri,
                date = if (id % 3 == 0L) null else id / 2,
                blob = vec,
            )
        }
        return Scenario(
            recs, q, limit, pageSize, minScore,
            "#$it dim=$dim n=$n limit=$limit page=$pageSize min=$minScore quant=$quant sameAll=$sameAll bad=$bad blank=$blank orphan=$orphan other=$other odd=$odd",
        )
    }

    // ---------------------------------------------------------------- testler

    @Test(timeout = 240_000)
    fun randomScenarios_bitIdenticalToIndependentSortReference() {
        val rnd = Random(0x25_2026_1013L)
        var failures = 0
        var secondPass = 0
        var illegalState = 0
        var shortOfLimit = 0
        var full = 0
        var empty = 0
        var withTies = 0
        val iterations = 20_000
        for (it in 0 until iterations) {
            val sc = scenario(rnd, it)
            val snapshot = sc.recs.map { r -> r.blob.copyOf() }
            val dao = QaDao(sc.recs)
            val expected = reference(sc.recs, MODEL, sc.q, sc.limit, sc.minScore)
            val actual = search(sc.recs, sc.q, sc.limit, sc.pageSize, sc.minScore, dao)
            if (expected != actual) {
                failures++
                fail("${sc.desc}\nbeklenen=$expected\ngerçek=$actual")
            }
            // yan invariantlar
            for ((i, r) in sc.recs.withIndex()) assertTrue("${sc.desc}: BLOB değişti", snapshot[i].contentEquals(r.blob))
            assertTrue("${sc.desc}: tarama sayfa boyutu", dao.pageCalls.all { c -> c.third == sc.pageSize && c.first == MODEL })
            assertTrue("${sc.desc}: parça <= 500", dao.refCalls.all { c -> c.size in 1..500 })
            // her geçişte keyset ilerler: afterId == önceki sayfanın son kimliği (tekrar yok)
            var prev = Long.MIN_VALUE
            for (c in dao.pageCalls) {
                if (c.second == Long.MIN_VALUE) prev = Long.MIN_VALUE else assertTrue("${sc.desc}: keyset ilerlemedi", c.second > prev)
                prev = c.second
            }
            when (actual) {
                is Outcome.IllegalState -> illegalState++
                is Outcome.Ok -> {
                    val ids = actual.hits.map { h -> h.id }
                    assertEquals("${sc.desc}: kimlik tekrarı", ids.size, ids.toSet().size)
                    assertTrue("${sc.desc}: limit aşıldı", actual.hits.size <= sc.limit)
                    if (actual.hits.isEmpty()) empty++ else if (actual.hits.size == sc.limit) full++ else shortOfLimit++
                    if (actual.hits.zipWithNext().any { (a, b) -> a.scoreBits == b.scoreBits }) withTies++
                }
            }
            if (dao.passes > 1) secondPass++
        }
        assertEquals(0, failures)
        println("QA random: iterations=$iterations secondPass=$secondPass illegalState=$illegalState full=$full short=$shortOfLimit empty=$empty ties=$withTies")
        // anlamsız (vakumlu) geçmemek için kapsama eşikleri
        assertTrue("ikinci geçiş görülmedi: $secondPass", secondPass >= 20)
        assertTrue("IllegalStateException senaryosu az: $illegalState", illegalState >= 50)
        assertTrue("limit dolu senaryo az: $full", full >= 200)
        assertTrue("limit'ten az (gerçekten az geçerli kayıt) az: $shortOfLimit", shortOfLimit >= 200)
        assertTrue("boş sonuç az: $empty", empty >= 100)
        assertTrue("eşit skor az: $withTies", withTies >= 300)
    }

    /** Aynı sonuç, kayıtların eklenme sırasından ve DAO'nun satır sıralamasından bağımsız (kimlik uçları dahil). */
    @Test
    fun extremeIds_sorting_andLongMinInvisibleLikeLegacyKeysetQuery() {
        val q = floatArrayOf(1f, 0f, 0f, 0f)
        fun r(id: Long, s: Float) = Rec(id, MODEL, true, "content://m/${id}", null, leBytes(floatArrayOf(s, 0f, 0f, 0f)))
        // hepsi eşit skor: sıra yalnızca photoId artan (negatifler önce, MIN+1 en başta)
        val ids = listOf(Long.MAX_VALUE, 0L, -1L, 1L, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, -5L, 7L)
        val recs = ids.map { r(it, 0.5f) } + r(Long.MIN_VALUE, 0.9f) // MIN en iyi skorlu ama taranmaz
        for (pageSize in listOf(1, 2, 3, 500)) {
            val out = search(recs, q, 100, pageSize) as Outcome.Ok
            assertEquals(ids.sorted(), out.hits.map { it.id })
            assertEquals(reference(recs, MODEL, q, 100, null), out)
            // limit kesimi eşit skorlarda en küçük kimlikler kalır
            val top3 = search(recs, q, 3, pageSize) as Outcome.Ok
            assertEquals(ids.sorted().take(3), top3.hits.map { it.id })
        }
        // yalnızca MIN kimlikli kayıt: taranmaz -> boş liste (eski sorguyla aynı), hata değil
        assertEquals(Outcome.Ok(emptyList<Hit>()), search(listOf(r(Long.MIN_VALUE, 1f)), q, 5, 10))
    }

    @Test
    fun allIdenticalVectors_manyRecords_limitCutsBySmallestIds() {
        val q = floatArrayOf(0.6f, 0.8f)
        val recs = (1..1000).map { id -> Rec(id * 3L, MODEL, true, "content://m/$id", null, leBytes(floatArrayOf(0.25f, -0.5f))) }.shuffled(Random(3))
        for (limit in listOf(1, 49, 50, 51, 999, 1000, 1001, Int.MAX_VALUE)) {
            for (pageSize in listOf(1, 255, 256, 257, 500)) {
                val out = search(recs, q, limit, pageSize) as Outcome.Ok
                val want = (1..minOf(limit.toLong(), 1000L).toInt()).map { it * 3L }
                assertEquals("limit=$limit page=$pageSize", want, out.hits.map { it.id })
                assertEquals(1, out.hits.map { it.scoreBits }.toSet().size)
            }
        }
    }

    /**
     * Aday kapasitesi sınırı: en iyi m kayıt geçersiz (boş uri / yetim karışık); kapasite limit+32. m <= 32 iken tek geçiş,
     * m = 33'te ikinci geçiş; her durumda sonuç referansla aynı ve (yeterince geçerli kayıt varsa) limit dolu.
     */
    @Test
    fun candidateCapacityBoundary_slackPlusMinusOne() {
        val dim = 4
        val q = FloatArray(dim).also { it[0] = 1f }
        val n = 300
        val perm = (0 until n).shuffled(Random(11))
        for (limit in listOf(1, 5, 50)) {
            for (m in listOf(0, 1, 31, 32, 33, 34, 65, 120, 299)) {
                val recs = (0 until n).map { rank ->
                    val s = 1f - rank * 0.001f // rank 0 en iyi
                    val invalid = rank < m
                    Rec(
                        1000L + perm[rank], MODEL,
                        hasPhoto = !(invalid && rank % 2 == 1),
                        uri = if (invalid && rank % 2 == 0) (if (rank % 4 == 0) "" else "   ") else "content://m/$rank",
                        date = rank.toLong(), blob = leBytes(floatArrayOf(s, 0f, 0f, 0f)),
                    )
                }
                val dao = QaDao(recs)
                val out = search(recs, q, limit, 64, null, dao)
                assertEquals("limit=$limit m=$m", reference(recs, MODEL, q, limit, null), out)
                val hits = (out as Outcome.Ok).hits
                assertEquals(minOf(limit, n - m), hits.size)
                if (n - m >= limit) {
                    if (m <= RoomSearchRepository.CANDIDATE_SLACK) {
                        assertEquals("limit=$limit m=$m tek geçiş", 1, dao.passes)
                        assertEquals(limit + RoomSearchRepository.CANDIDATE_SLACK, dao.refCalls.single().size)
                    } else {
                        assertTrue("limit=$limit m=$m ikinci geçiş beklenirdi", dao.passes >= 2)
                    }
                }
                assertTrue("limit=$limit m=$m geçiş sayısı sınırlı: ${dao.passes}", dao.passes <= 6)
                // beklenen kimlikler: geçerli kayıtların en iyi `limit` tanesi
                assertEquals((m until minOf(n, m + limit)).map { 1000L + perm[it] }, hits.map { it.id })
            }
        }
    }

    /** Eşik: minScore altı kayıtlar adaya girmez; üst sıradaki geçersizler (yetim) yüzünden eksik kalınca da doğru sayı. */
    @Test
    fun minScoreWithInvalidTopRecords_stillFillsFromRemainingEligible() {
        val q = floatArrayOf(1f, 0f, 0f, 0f)
        val recs = (0 until 400).map { rank ->
            Rec(
                rank + 1L, MODEL, hasPhoto = rank >= 100 || rank % 3 == 0, uri = if (rank in 100..139) " " else "content://m/$rank",
                date = null, blob = leBytes(floatArrayOf(1f - rank * 0.002f, 0f, 0f, 0f)),
            )
        }
        for (min in listOf(null, 0.5f, 0.2f, 0.999f, 1f, 1.5f)) {
            for (limit in listOf(1, 10, 80, 250)) {
                assertEquals("min=$min limit=$limit", reference(recs, MODEL, q, limit, min), search(recs, q, limit, 37, min))
            }
        }
    }

    @Test
    fun badBlobLengths_eachSkipped_andAllBadFailsOnlyWhenNoGoodVisible() {
        val dim = 6
        val q = FloatArray(dim).also { it[1] = 1f }
        val good = leBytes(FloatArray(dim) { if (it == 1) 0.5f else 0f })
        val lengths = listOf(0, 1, 3, dim * 4 - 1, dim * 4 + 1, dim * 4 + 4, dim * 8, dim * 2, 4)
        val badRecs = lengths.mapIndexed { i, len -> Rec(10L + i, MODEL, true, "content://m/$i", null, ByteArray(len)) }
        // yalnızca bozuklar -> IllegalStateException (eski ve yeni)
        assertEquals(Outcome.IllegalState, search(badRecs, q, 5, 4))
        assertEquals(reference(badRecs, MODEL, q, 5, null), search(badRecs, q, 5, 4))
        // bir iyi kayıt -> bozuklar atlanır
        val withGood = badRecs + Rec(99, MODEL, true, "content://m/ok", 5L, good)
        val out = search(withGood, q, 5, 4) as Outcome.Ok
        assertEquals(listOf(99L), out.hits.map { it.id })
        // bozuklar + yetimler: görünür kayıt (bozuk) var -> hata; bozuklar da yetimse -> boş
        val oneVisibleBad = listOf(Rec(1, MODEL, false, "u", null, good), Rec(2, MODEL, true, "u", null, ByteArray(7)))
        assertEquals(Outcome.IllegalState, search(oneVisibleBad, q, 3, 5))
        val noneVisible = listOf(Rec(1, MODEL, false, "u", null, good), Rec(2, MODEL, false, "u", null, ByteArray(7)))
        assertEquals(Outcome.Ok(emptyList<Hit>()), search(noneVisible, q, 3, 5))
    }

    @Test
    fun nanInfinityOverflowVectors_skipped_notRanked() {
        val dim = 3
        val q = floatArrayOf(0.6f, 0.0f, 0.8f)
        fun r(id: Long, v: FloatArray) = Rec(id, MODEL, true, "content://m/$id", null, leBytes(v))
        val recs = listOf(
            r(1, floatArrayOf(Float.NaN, 0f, 0f)),
            r(2, floatArrayOf(Float.POSITIVE_INFINITY, 0f, 0f)),
            r(3, floatArrayOf(Float.NEGATIVE_INFINITY, 0f, 1f)),
            r(4, floatArrayOf(Float.MAX_VALUE, 0f, Float.MAX_VALUE)), // taşar: +Inf skor
            r(5, floatArrayOf(Float.MAX_VALUE, 0f, -Float.MAX_VALUE)), // sonlu vektör, skor ~0 sonlu
            r(6, floatArrayOf(0.1f, 0f, 0.1f)),
        )
        val out = search(recs, q, 10, 2) as Outcome.Ok
        assertEquals(listOf(6L, 5L).sortedBy { it }.toSet(), out.hits.map { it.id }.toSet())
        assertEquals(reference(recs, MODEL, q, 10, null), out)
        assertTrue(out.hits.none { it.id in 1L..4L })
        assertEquals(dim, q.size)
    }

    @Test
    fun orphanPercentages_neverShrinkResult_whileValidRecordsRemain() {
        val q = floatArrayOf(1f, 0f)
        for (pct in listOf(0, 10, 50, 90, 99, 100)) {
            val rnd = Random(pct.toLong())
            val recs = (1..600).map { id ->
                Rec(id.toLong(), MODEL, hasPhoto = rnd.nextInt(100) >= pct, uri = "content://m/$id", date = null, blob = leBytes(floatArrayOf(rnd.nextFloat(), 0f)))
            }
            val visible = recs.count { it.hasPhoto }
            for (limit in listOf(1, 10, 50, 300, 600)) {
                val out = search(recs, q, limit, 64)
                assertEquals("orphan%=$pct limit=$limit", reference(recs, MODEL, q, limit, null), out)
                assertEquals(minOf(limit, visible), (out as Outcome.Ok).hits.size)
            }
        }
    }

    @Test
    fun emptyGallery_orNoPermission_isEmptyList_oneEmptyPageScan_noRefQueries() = runBlocking {
        val dao = QaDao(emptyList())
        val repo = RoomSearchRepository(FixedTextEncoder(floatArrayOf(1f), EmbeddingSpec(1, MODEL)), dao, dispatcher = Dispatchers.Unconfined)
        assertEquals(emptyList<Any>(), repo.search("anahtar", 50))
        assertEquals(1, dao.pageCalls.size)
        assertEquals(0, dao.refCalls.size)
        // yalnızca başka model sürümü: aynı (boş, hata değil)
        val other = QaDao(listOf(Rec(1, "other-v1", true, "u", null, leBytes(floatArrayOf(1f)))))
        assertEquals(emptyList<Any>(), RoomSearchRepository(FixedTextEncoder(floatArrayOf(1f), EmbeddingSpec(1, MODEL)), other, dispatcher = Dispatchers.Unconfined).search("x"))
        assertEquals(0, other.refCalls.size)
    }

    @Test(timeout = 60_000)
    fun hugeLimit_intMax_neverAllocatesByLimit_andAllInvalidTerminates() {
        val dim = 8
        val q = randomUnit(Random(5), dim)
        val rnd = Random(6)
        val n = 6000
        // geçerli kayıtlar + çok sayıda boş uri/yetim
        val recs = (1..n).map { id ->
            Rec(id.toLong(), MODEL, hasPhoto = id % 5 != 0, uri = if (id % 7 == 0) "  " else "content://m/$id", date = null, blob = leBytes(randomUnit(rnd, dim)))
        }
        val rt = Runtime.getRuntime()
        for (limit in listOf(Int.MAX_VALUE, Int.MAX_VALUE - 1, Int.MAX_VALUE - 32, Int.MAX_VALUE - 33)) {
            val before = rt.totalMemory() - rt.freeMemory()
            val out = search(recs, q, limit, 256)
            val after = rt.totalMemory() - rt.freeMemory()
            assertEquals("limit=$limit", reference(recs, MODEL, q, limit, null), out)
            assertTrue("bellek patlaması: ${(after - before) shr 20} MB", after - before < 200L * 1024 * 1024)
        }
        // hepsi boş uri: ISE, geçiş sayısı sınırlı
        val allBlank = recs.map { Rec(it.id, MODEL, true, " ", null, it.blob) }
        val dao = QaDao(allBlank)
        assertEquals(Outcome.IllegalState, search(allBlank, q, Int.MAX_VALUE, 256, null, dao))
        assertTrue("geçiş: ${dao.passes}", dao.passes <= 6)
        val dao2 = QaDao(allBlank)
        assertEquals(Outcome.IllegalState, search(allBlank, q, 1, 256, null, dao2))
        assertTrue("geçiş: ${dao2.passes}", dao2.passes <= 6)
    }

    // ---------------------------------------------------------------- sözleşme: hata ve iptal

    @Test
    fun invalidLimit_throwsIllegalArgument_beforeEncoderAndDao() = runBlocking {
        for (limit in listOf(0, -1, -50, Int.MIN_VALUE)) {
            val enc = FixedTextEncoder(e0())
            val dao = QaDao(emptyList())
            val repo = RoomSearchRepository(enc, dao, dispatcher = Dispatchers.Unconfined)
            try {
                repo.search("kedi", limit)
                fail("limit=$limit")
            } catch (e: IllegalArgumentException) {
                assertFalse(e is InvalidQueryException)
            }
            assertEquals(0, enc.calls)
            assertEquals(0, dao.pageCalls.size)
        }
    }

    @Test
    fun encoderFailures_passThroughUnchanged_andDaoUntouched() = runBlocking {
        for (failure in listOf<Throwable>(InvalidQueryException(InvalidQueryException.Reason.EMPTY), CancellationException("iptal"), IllegalStateException("oturum"))) {
            val dao = QaDao(listOf(Rec(1, "x", true, "u", null, leBytes(e0()))))
            val repo = RoomSearchRepository(FixedTextEncoder(e0(), failure = failure), dao, dispatcher = Dispatchers.Unconfined)
            try {
                repo.search("", 5)
                fail()
            } catch (e: Throwable) {
                assertSame(failure, e)
            }
            assertEquals(0, dao.pageCalls.size)
        }
    }

    @Test
    fun turkishAndExtremeQueryStrings_reachEncoderVerbatim_noRepositoryJudgement() = runBlocking {
        val seen = mutableListOf<String>()
        val enc = object : TextEncoder {
            override val embeddingSpec = EmbeddingSpec(1, MODEL)
            override suspend fun encode(query: String): FloatArray { seen += query; return floatArrayOf(1f) }
        }
        val dao = QaDao(listOf(Rec(1, MODEL, true, "content://m/1", null, leBytes(floatArrayOf(1f)))))
        val queries = listOf("", " ", "çğıöşü ÇĞIÖŞÜ İıIi", "İSTANBUL ıspanak", "İ", "a".repeat(1_000_000), "😀🏞️ kedi\u0000\n\t")
        for (qs in queries) assertEquals(listOf(1L), RoomSearchRepository(enc, dao, dispatcher = Dispatchers.Unconfined).search(qs, 3).map { it.photoId })
        assertEquals(queries, seen)
    }

    @Test
    fun cancellation_atEveryPage_stopsScan_andNeverBecomesOtherException() = runBlocking {
        val dim = 4
        val q = FloatArray(dim).also { it[0] = 1f }
        val recs = (1..60).map { id -> Rec(id.toLong(), MODEL, true, "content://m/$id", null, leBytes(floatArrayOf(id / 100f, 0f, 0f, 0f))) }
        val pages = 60 / 7 + 1 // 9 dolu/kısmi sayfa; son sayfa kısa
        for (k in 1..pages) {
            val dao = QaDao(recs)
            dao.onPage = { n -> if (n == k) currentCoroutineContext().job.cancel() }
            val d = async(Dispatchers.Default) {
                RoomSearchRepository(FixedTextEncoder(q, EmbeddingSpec(dim, MODEL)), dao, 7, null, Dispatchers.Unconfined).search("x", 5)
            }
            try {
                d.await()
                fail("k=$k iptal beklenirdi")
            } catch (_: CancellationException) {
            }
            assertEquals("k=$k", k, dao.pageCalls.size)
            assertEquals("k=$k uri sorgusu yapılmamalı", 0, dao.refCalls.size)
        }
    }

    @Test
    fun cancellation_duringUriLookup_andDuringFailurePath_isCancellation_notIllegalState() = runBlocking {
        val dim = 4
        val q = FloatArray(dim).also { it[0] = 1f }
        // (a) iyi yol: aday uri sorgusu sırasında iptal
        val good = (1..40).map { id -> Rec(id.toLong(), MODEL, true, "content://m/$id", null, leBytes(floatArrayOf(id / 100f, 0f, 0f, 0f))) }
        val daoA = QaDao(good)
        daoA.onRefs = { currentCoroutineContext().job.cancel() }
        val a = async(Dispatchers.Default) { RoomSearchRepository(FixedTextEncoder(q, EmbeddingSpec(dim, MODEL)), daoA, 7, null, Dispatchers.Unconfined).search("x", 3) }
        try { a.await(); fail("a") } catch (_: CancellationException) {}
        // (b) hata yolu: hepsi bozuk + 1200 eşik altı/bozuk kimlik parçalı sorgulanırken iptal; ISE'ye dönüşmemeli
        val bad = (1..1200).map { id -> Rec(id.toLong(), MODEL, false, "content://m/$id", null, ByteArray(3)) }
        val daoB = QaDao(bad)
        daoB.onRefs = { n -> if (n == 2) currentCoroutineContext().job.cancel() }
        val b = async(Dispatchers.Default) { RoomSearchRepository(FixedTextEncoder(q, EmbeddingSpec(dim, MODEL)), daoB, 500, null, Dispatchers.Unconfined).search("x", 3) }
        try { b.await(); fail("b") } catch (_: CancellationException) {}
        assertEquals(2, daoB.refCalls.size)
    }

    @Test
    fun minScoreMinusZero_andBoundaryEquality_behaveLikeReference() {
        val q = floatArrayOf(1f, 0f)
        val recs = listOf(
            Rec(1, MODEL, true, "u1", null, leBytes(floatArrayOf(0f, 1f))), // skor 0.0
            Rec(2, MODEL, true, "u2", null, leBytes(floatArrayOf(-0f, 1f))), // skor 0.0 (-0 değil)
            Rec(3, MODEL, true, "u3", null, leBytes(floatArrayOf(0.5f, 1f))),
            Rec(4, MODEL, true, "u4", null, leBytes(floatArrayOf(-0.5f, 1f))),
        )
        for (min in listOf(0f, -0f, 0.5f, 0.50000006f, 0.49999997f, -0.5f)) {
            val out = search(recs, q, 10, 2, min)
            assertEquals("min=$min", reference(recs, MODEL, q, 10, min), out)
            (out as Outcome.Ok).hits.forEach { assertTrue("skor -0.0 olmamalı", it.scoreBits != 0x80000000.toInt()) }
        }
    }

    @Test
    fun bufferHelpersSanity_referenceDecodeMatchesJdkLittleEndian() {
        // Referansın kendi çözmesi doğru mu (testin testi)
        val bits = intArrayOf(0x3F800000, 0xBF800000.toInt(), 0x7FC00000, 0x00000001, 0x80000000.toInt())
        val viaJdk = ByteBuffer.wrap(leBytes(bits)).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        // asIntBuffer little-endian bayt dizisini int'e çevirir
        for (i in bits.indices) assertEquals(bits[i], viaJdk.get(i))
        assertEquals(1f, refDecode(leBytes(bits))[0], 0f)
    }
}
