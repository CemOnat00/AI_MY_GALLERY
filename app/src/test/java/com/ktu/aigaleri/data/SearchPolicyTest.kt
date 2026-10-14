package com.ktu.aigaleri.data

import com.ktu.aigaleri.domain.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** T-013: [SearchPolicy] kuralları (sabit veri; ikili-tam gösterilebilen skorlarla sınır eşitliği kesin) ve [RoomSearchRepository] entegrasyonu. */
class SearchPolicyTest {
    private fun r(id: Long, score: Float) = SearchResult(id, "content://media/$id", score, null)
    private fun ids(l: List<SearchResult>) = l.map { it.photoId }

    // top1 = 0,5 ; skorlar 2'nin kuvveti toplamları: sınır hesabı (top1 - marj) tam.
    private val list = listOf(r(1, 0.5f), r(2, 0.375f), r(3, 0.25f), r(4, 0.125f), r(5, 0f), r(6, -0.25f))

    @Test
    fun off_returnsSameInstance_forAnyInput() {
        assertSame(list, SearchPolicy.OFF.apply(list))
        val inf = listOf(r(1, Float.POSITIVE_INFINITY))
        assertSame(inf, SearchPolicy.OFF.apply(inf))
        assertTrue(SearchPolicy.OFF.isOff)
        assertFalse(SearchPolicy.DEFAULT.isOff)
        assertEquals(emptyList<SearchResult>(), SearchPolicy.OFF.apply(emptyList()))
    }

    @Test
    fun floor_top1BelowFloor_emptiesList() {
        assertEquals(emptyList<SearchResult>(), SearchPolicy(minTop1Score = 0.5000001f).apply(list))
        assertEquals(emptyList<SearchResult>(), SearchPolicy(minTop1Score = 0.9f).apply(list))
    }

    @Test
    fun floor_top1EqualToFloor_keepsList() {
        assertEquals(list.size, SearchPolicy(minTop1Score = 0.5f).apply(list).size)
        assertEquals(ids(list), ids(SearchPolicy(minTop1Score = 0.25f).apply(list)))
    }

    @Test
    fun floor_onlyChecksTop1_notEachResult() {
        // taban yalnızca top1'e bakar; tek tek sonuçları eleyen kural marjdır.
        assertEquals(ids(list), ids(SearchPolicy(minTop1Score = 0.4f).apply(list)))
    }

    @Test
    fun margin_dropsBelowTop1MinusMargin_keepsBoundary() {
        // top1 0,5 - 0,25 = 0,25 : 0,25 sınırda korunur, 0,125 elenir.
        assertEquals(listOf(1L, 2L, 3L), ids(SearchPolicy(margin = 0.25f).apply(list)))
        assertEquals(listOf(1L, 2L), ids(SearchPolicy(margin = 0.125f).apply(list)))
        assertEquals(listOf(1L), ids(SearchPolicy(margin = 0f).apply(list)))
        assertEquals(ids(list), ids(SearchPolicy(margin = 1f).apply(list)))
    }

    @Test
    fun margin_preservesOrderAndEntries() {
        val out = SearchPolicy(margin = 0.25f).apply(list)
        assertEquals(list.take(3), out)
    }

    @Test
    fun margin_equalScores_neverSplit_andIdOrderKept() {
        val ties = listOf(r(2, 0.5f), r(5, 0.5f), r(9, 0.5f), r(1, 0.25f), r(3, 0.25f))
        assertEquals(listOf(2L, 5L, 9L), ids(SearchPolicy(margin = 0f).apply(ties)))
        assertEquals(listOf(2L, 5L, 9L, 1L, 3L), ids(SearchPolicy(margin = 0.25f).apply(ties)))
    }

    @Test
    fun singleResult_keptAboveFloor_emptiedBelow() {
        val one = listOf(r(7, 0.3f))
        assertEquals(one, SearchPolicy(minTop1Score = 0.2f, margin = 0f).apply(one))
        assertEquals(emptyList<SearchResult>(), SearchPolicy(minTop1Score = 0.31f, margin = 0f).apply(one))
    }

    @Test
    fun emptyInput_staysEmpty() {
        assertEquals(emptyList<SearchResult>(), SearchPolicy.DEFAULT.apply(emptyList()))
    }

    @Test
    fun negativeScores_workWithFloorAndMargin() {
        val neg = listOf(r(1, -0.125f), r(2, -0.25f), r(3, -0.5f))
        assertEquals(emptyList<SearchResult>(), SearchPolicy(minTop1Score = 0f).apply(neg))
        assertEquals(listOf(1L, 2L), ids(SearchPolicy(minTop1Score = -0.25f, margin = 0.125f).apply(neg)))
    }

    @Test
    fun infiniteScores_areDropped_andIgnoredForTop1_whenRuleIsOn() {
        val bad = listOf(r(1, Float.POSITIVE_INFINITY), r(2, Float.POSITIVE_INFINITY), r(3, 0.5f), r(4, 0.375f), r(5, Float.NEGATIVE_INFINITY))
        assertEquals(listOf(3L, 4L), ids(SearchPolicy(margin = 0.125f).apply(bad)))
        assertEquals(listOf(3L, 4L), ids(SearchPolicy(minTop1Score = 0.5f).apply(bad)))
        assertEquals(emptyList<SearchResult>(), SearchPolicy(minTop1Score = 0.51f).apply(bad))
        assertEquals(emptyList<SearchResult>(), SearchPolicy(margin = 0.1f).apply(listOf(r(1, Float.POSITIVE_INFINITY), r(2, Float.NEGATIVE_INFINITY))))
    }

    @Test
    fun inputIsNotMutated() {
        val copy = list.toList()
        SearchPolicy(minTop1Score = 0.2f, margin = 0.125f).apply(list)
        assertEquals(copy, list)
    }

    @Test
    fun invalidParameters_rejected() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY)) {
            try { SearchPolicy(minTop1Score = bad); fail("minTop1Score=$bad") } catch (_: IllegalArgumentException) {}
            try { SearchPolicy(margin = bad); fail("margin=$bad") } catch (_: IllegalArgumentException) {}
        }
        try { SearchPolicy(margin = -0.01f); fail("negatif marj") } catch (_: IllegalArgumentException) {}
        SearchPolicy(minTop1Score = -1f) // negatif taban geçerli
    }

    @Test
    fun default_isConservative_keepsMeasuredRelevantRange() {
        val d = SearchPolicy.DEFAULT
        // T-013 ölçümü: en düşük VAR top1 0,2264; en büyük (top1 - en düşük ilgili) 0,0936. Güvenlik payı > ARM gürültüsü (2e-3).
        assertTrue(d.minTop1Score!! <= 0.2264f - 0.02f)
        assertTrue(d.margin!! >= 0.0936f + 0.02f)
        // Ölçülen en düşük ilgili skoru (top1 0,2264 iken 0,1328) ve en zayıf VAR sorgusu korunur.
        val weakVar = listOf(r(1, 0.2264f), r(2, 0.2248f), r(3, 0.1328f))
        assertEquals(ids(weakVar), ids(d.apply(weakVar)))
    }

    // ---- RoomSearchRepository entegrasyonu ----

    private fun repo(dao: PhotoEmbeddingDao, policy: SearchPolicy?, minScore: Float? = null): RoomSearchRepository =
        if (policy == null) RoomSearchRepository(FixedTextEncoder(e0()), dao, 256, minScore, Dispatchers.Unconfined)
        else RoomSearchRepository(FixedTextEncoder(e0()), dao, 256, minScore, Dispatchers.Unconfined, policy)

    private fun dao() = FakeEmbeddingDao(
        listOf(
            row(5, unitWithFirst(0.5f)),
            row(3, unitWithFirst(0.75f)),
            row(9, unitWithFirst(0.5f)),
            row(1, unitWithFirst(0.25f)),
            row(7, unitWithFirst(-0.5f)),
        ),
    )

    @Test
    fun repository_withoutPolicyArgument_matchesIndependentReference_andExplicitOff() = runBlocking {
        // Bağımsız referans: bilinen skorlar (3: 0,75; 5 ve 9: 0,5; 1: 0,25; 7: -0,5), skor azalan, eşitlikte id artan.
        val reference = listOf(3L to 0.75f, 5L to 0.5f, 9L to 0.5f, 1L to 0.25f, 7L to -0.5f)
        val implicit = repo(dao(), null).search("x")
        assertEquals(reference.map { it.first }, ids(implicit))
        assertEquals(reference.map { it.second }, implicit.map { it.score })
        assertEquals(implicit, repo(dao(), SearchPolicy.OFF).search("x"))
        // limit varsayılan politika dışında da eski davranışla aynı: ilk 2.
        assertEquals(listOf(3L, 5L), ids(repo(dao(), null).search("x", limit = 2)))
    }

    @Test
    fun floorAndMargin_together_nonBinaryExactBoundary() {
        // 0,2264f - 0,12f ikili-tam DEĞİL; sınır Float aritmetiğiyle top - margin olarak hesaplanır: tam sınırdaki kalır,
        // bir ulp altı elenir. Taban (0,20) top1 0,2264 üstünde olduğundan liste boşalmaz.
        val top = 0.2264f
        val cut = top - 0.12f
        val l = listOf(r(1, top), r(2, cut), r(3, Math.nextDown(cut)), r(4, 0.05f))
        assertEquals(listOf(1L, 2L), ids(SearchPolicy.DEFAULT.apply(l)))
        // Taban aynı top1 için sınırda: top1 == 0,2264f ise 0,2264f tabanı korur, bir ulp üstü boşaltır.
        assertEquals(listOf(1L, 2L), ids(SearchPolicy(minTop1Score = top, margin = 0.12f).apply(l)))
        assertEquals(emptyList<SearchResult>(), SearchPolicy(minTop1Score = Math.nextUp(top), margin = 0.12f).apply(l))
        // Taban kuralı marjdan önce: top1 tabanın altındaysa marj ne olursa olsun boş.
        assertEquals(emptyList<SearchResult>(), SearchPolicy.DEFAULT.apply(listOf(r(1, 0.1999f), r(2, 0.1999f))))
    }

    @Test
    fun repository_marginCut_keepsOrder_andTieRule() = runBlocking {
        // top1 0,75 ; marj 0,25 -> eşik 0,5: 0,5 sınırda korunur (5 ve 9, id artan), 0,25 ve -0,5 elenir.
        val out = repo(dao(), SearchPolicy(margin = 0.25f)).search("x")
        assertEquals(listOf(3L, 5L, 9L), ids(out))
    }

    @Test
    fun repository_floor_emptiesWhenTop1BelowFloor() = runBlocking {
        assertEquals(emptyList<SearchResult>(), repo(dao(), SearchPolicy(minTop1Score = 0.76f)).search("x"))
        assertEquals(5, repo(dao(), SearchPolicy(minTop1Score = 0.75f)).search("x").size)
    }

    @Test
    fun repository_limitApplied_thenPolicy() = runBlocking {
        val out = repo(dao(), SearchPolicy(margin = 1f)).search("x", limit = 2)
        assertEquals(listOf(3L, 5L), ids(out))
    }

    @Test
    fun repository_emptyIndex_staysEmpty() = runBlocking {
        assertEquals(emptyList<SearchResult>(), repo(FakeEmbeddingDao(emptyList()), SearchPolicy.DEFAULT).search("x"))
    }

    @Test
    fun repository_minScoreAndPolicyCompose() = runBlocking {
        // minScore 0,3 kayıtları eler (0,25 ve -0,5 kalmaz); politika kalanlara bakar (top1 0,75, marj 0 -> yalnız 3).
        val out = repo(dao(), SearchPolicy(margin = 0f), minScore = 0.3f).search("x")
        assertEquals(listOf(3L), ids(out))
        // Tüm kayıtlar minScore altındaysa mevcut davranış: boş (hata değil).
        assertEquals(emptyList<SearchResult>(), repo(dao(), SearchPolicy.DEFAULT, minScore = 0.9f).search("x"))
    }
}
