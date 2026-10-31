package com.sgshs.sharepoi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchCatalogTest {

    // --- 화장실 검색 출처별 아이콘(#18: PO3·OL7 이 🚽 로 보이던 문제) ---

    @Test
    fun publicOfficeAndGasStationAreNotRestroomIcon() {
        val byCode = SearchCatalog.restroomSources.filter { it.categoryCode != null }.associateBy { it.categoryCode }
        assertEquals(SearchCatalog.PUBLIC_OFFICE_ICON, byCode.getValue("PO3").icon)
        assertEquals(SearchCatalog.GAS_STATION_ICON, byCode.getValue("OL7").icon)
        for (source in byCode.values) {
            assertNotEquals(SearchCatalog.RESTROOM_ICON, source.icon)
            assertTrue("공공기관·주유소에는 현장 확인 안내가 붙는다", !source.note.isNullOrBlank())
        }
    }

    @Test
    fun restroomKeywordsComeFirstAndKeepRestroomIcon() {
        // 중복 제거는 먼저 들어온 출처가 이긴다 — 화장실 키워드가 앞이어야 같은 장소가 🚽 로 남는다.
        val sources = SearchCatalog.restroomSources
        assertEquals(listOf("공중화장실", "개방화장실"), sources.take(2).map { it.keyword })
        assertTrue(sources.take(2).all { it.icon == SearchCatalog.RESTROOM_ICON && it.note == null })
        assertEquals(listOf("PO3", "OL7"), sources.drop(2).map { it.categoryCode })
    }

    // --- 다시 시도용 요청 키 ---

    @Test
    fun requestKeyRoundTrip() {
        assertEquals(SearchCatalog.Request.Restroom, SearchCatalog.Request.fromKey(SearchCatalog.Request.Restroom.key))
        for (category in SearchCatalog.placeCategories) {
            val request = SearchCatalog.Request.Category(category)
            assertEquals(request, SearchCatalog.Request.fromKey(request.key))
            assertEquals(category.label, request.label)
        }
        assertEquals("화장실", SearchCatalog.Request.Restroom.label)
    }

    @Test
    fun unknownRequestKeyIsNull() {
        assertNull(SearchCatalog.Request.fromKey(null))
        assertNull(SearchCatalog.Request.fromKey(""))
        assertNull(SearchCatalog.Request.fromKey("category:XX9"))
        assertNull(SearchCatalog.Request.fromKey("restroom2"))
    }

    @Test
    fun categoryIconsAreDistinct() {
        val icons = SearchCatalog.placeCategories.map { it.icon }
        assertEquals(icons.size, icons.toSet().size)
        assertEquals(8, SearchCatalog.placeCategories.size)
    }

    // --- 핀 크기(dp 기준) ---

    @Test
    fun markerSizeFollowsDensity() {
        assertEquals(34, SearchCatalog.dpToPx(SearchCatalog.MARKER_DP, 1f))
        assertEquals(102, SearchCatalog.dpToPx(SearchCatalog.MARKER_DP, 3f))
        assertEquals(119, SearchCatalog.dpToPx(SearchCatalog.MARKER_DP, 3.5f))
        assertEquals(1, SearchCatalog.dpToPx(0, 2f))
    }

    // --- 공유 결과 나이 제한 ---

    @Test
    fun shareResultFreshWithinLimit() {
        assertTrue(SearchCatalog.isShareResultFresh(1_000L, 1_000L))
        assertTrue(SearchCatalog.isShareResultFresh(1_000L, 1_000L + SearchCatalog.SHARE_RESULT_MAX_AGE_MS))
    }

    @Test
    fun shareResultStaleAfterLimit() {
        assertFalse(SearchCatalog.isShareResultFresh(1_000L, 1_001L + SearchCatalog.SHARE_RESULT_MAX_AGE_MS))
        assertFalse(SearchCatalog.isShareResultFresh(1_000L, 10 * 60_000L))
    }

    @Test
    fun shareResultFromTheFutureIsStale() {
        // 재부팅 등으로 시계가 뒤로 간 경우: 믿지 않는다.
        assertFalse(SearchCatalog.isShareResultFresh(5_000L, 1_000L))
    }
}
