package com.sgshs.sharepoi

/**
 * 주변 검색의 종류·출처·핀 모양을 한곳에 둔다(#18). 안드로이드 API 에 기대지 않아 단위 시험으로 확인한다.
 */
object SearchCatalog {

    /** 화장실 검색 반경·편의시설 검색 반경(m) */
    const val RADIUS_M = 2000

    /** 편의시설 카테고리(카카오 category_group_code) */
    data class PlaceCategory(val label: String, val code: String, val icon: String)

    val placeCategories = listOf(
        PlaceCategory("편의점", "CS2", "🏪"),
        PlaceCategory("카페", "CE7", "☕"),
        PlaceCategory("음식점", "FD6", "🍚"),
        PlaceCategory("주차장", "PK6", "🅿️"),
        PlaceCategory("약국", "PM9", "💊"),
        PlaceCategory("은행", "BK9", "🏦"),
        PlaceCategory("지하철역", "SW8", "🚇"),
        PlaceCategory("병원", "HP8", "🏥")
    )

    fun placeCategory(code: String): PlaceCategory? = placeCategories.firstOrNull { it.code == code }

    /**
     * 화장실 통합 검색의 한 출처. [icon] 은 핀·상세 제목에 쓰고, [note] 는 상세에 덧붙인다.
     * 공공기관(PO3)·주유소(OL7)는 화장실 자체가 아니라 「화장실이 있을 수 있는 곳」이라 🚽 로 보이지 않게 한다.
     */
    data class RestroomSource(val keyword: String?, val categoryCode: String?, val icon: String, val note: String?)

    const val RESTROOM_ICON = "🚽"
    const val PUBLIC_OFFICE_ICON = "🏛️"
    const val GAS_STATION_ICON = "⛽"

    /** 출처 순서가 곧 중복 제거 우선순위다(먼저 나온 출처의 아이콘이 남는다). 화장실 키워드가 앞이다. */
    val restroomSources = listOf(
        RestroomSource("공중화장실", null, RESTROOM_ICON, null),
        RestroomSource("개방화장실", null, RESTROOM_ICON, null),
        RestroomSource(null, "PO3", PUBLIC_OFFICE_ICON, "공공기관입니다. 화장실을 쓸 수 있는지는 현장에서 확인해 주세요."),
        RestroomSource(null, "OL7", GAS_STATION_ICON, "주유소·충전소입니다. 화장실을 쓸 수 있는지는 현장에서 확인해 주세요.")
    )

    /** 검색 요청. 다시 시도(대화상자 결과)로 넘길 수 있게 문자열 키로 바꾸고 되돌린다. */
    sealed class Request {
        object Restroom : Request()
        data class Category(val category: PlaceCategory) : Request()

        val key: String
            get() = when (this) {
                Restroom -> "restroom"
                is Category -> "category:${category.code}"
            }

        /** 안내 문구에 쓰는 이름 */
        val label: String
            get() = when (this) {
                Restroom -> "화장실"
                is Category -> category.label
            }

        companion object {
            fun fromKey(key: String?): Request? = when {
                key == "restroom" -> Restroom
                key != null && key.startsWith("category:") ->
                    placeCategory(key.removePrefix("category:"))?.let { Category(it) }
                else -> null
            }
        }
    }

    /** 핀 바탕색: 화장실 검색은 초록, 편의시설은 파랑(검색 버튼 색과 같다). */
    fun markerColor(request: Request): Int = when (request) {
        Request.Restroom -> 0xFF2E7D32.toInt()
        is Request.Category -> 0xFF1565C0.toInt()
    }

    /** 핀 지름(dp). 화면 밀도에 맞춰 px 로 바꿔 그린다(예전에는 48px 고정이라 고해상도에서 작았다). */
    const val MARKER_DP = 34

    fun dpToPx(dp: Int, density: Float): Int = maxOf(1, Math.round(dp * density))

    /**
     * 공유 결과가 아직 쓸 만한지(#18). 공유를 누르고 홈으로 나갔다가 한참 뒤 돌아왔을 때
     * 예전에 확인한 위치로 공유 시트가 뜨지 않게 한다. 시계는 elapsedRealtime(ms) 기준.
     */
    const val SHARE_RESULT_MAX_AGE_MS = 30_000L

    fun isShareResultFresh(createdAtMs: Long, nowMs: Long): Boolean {
        val age = nowMs - createdAtMs
        return age in 0..SHARE_RESULT_MAX_AGE_MS
    }
}
