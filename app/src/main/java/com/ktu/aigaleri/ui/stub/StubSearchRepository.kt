package com.ktu.aigaleri.ui.stub

import com.ktu.aigaleri.domain.SearchRepository
import com.ktu.aigaleri.domain.SearchResult

/**
 * GEÇİCİ stub: gerçek SearchRepository (T-008) gelene kadar her zaman boş liste döner.
 * T-008'de [com.ktu.aigaleri.ui.AppDependencies] içinde gerçek implementasyonla değiştirilir.
 */
class StubSearchRepository : SearchRepository {
    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        require(limit > 0) { "limit > 0 olmalı" }
        return emptyList()
    }
}
