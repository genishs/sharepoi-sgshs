package com.sgshs.sharepoi

import java.util.Locale

/** 공유 문구의 종류. 받는 사람이 위치의 성격(정확·대략·지도에서 고름)을 알 수 있어야 한다. */
enum class ShareKind {
    /** 정확한 위치 권한으로 얻은 현재 위치 */
    CURRENT_PRECISE,

    /** 대략적인 위치 권한만 있어 오차가 클 수 있는 현재 위치 */
    CURRENT_APPROXIMATE,

    /** 현재 위치를 얻지 못해 이용자가 지도에서 고른 위치(화면 중앙) */
    MAP_PICKED
}

/**
 * 화면·공유에 쓰는 문자열 조합. 안드로이드 API 에 기대지 않아 단위 시험으로 확인한다.
 */
object PlaceText {

    const val NO_ADDRESS = "주소 정보 없음"
    const val NO_PHONE = "전화번호 정보 없음"

    /**
     * 장소 상세의 주소 부분(#8). 도로명 주소를 첫 줄에, 지번 주소를 둘째 줄에 「(지번) …」으로 적는다.
     * 한쪽이 비어 있으면 그 줄은 뺀다(도로명이 없으면 「(지번) …」 한 줄). 둘 다 비면 [NO_ADDRESS].
     * 카카오 응답의 JSON null 은 org.json optString 에서 문자열 "null" 로 오므로 빈 값으로 본다.
     */
    fun addressLines(roadAddress: String?, jibunAddress: String?): String {
        val road = clean(roadAddress)
        val jibun = clean(jibunAddress)
        val lines = buildList {
            if (road.isNotEmpty()) add(road)
            if (jibun.isNotEmpty() && jibun != road) add("(지번) $jibun")
        }
        return if (lines.isEmpty()) NO_ADDRESS else lines.joinToString("\n")
    }

    /** 장소 상세 대화상자 본문: 주소(도로명·지번)와 전화번호. */
    fun placeDetail(roadAddress: String?, jibunAddress: String?, phone: String?): String {
        val phoneText = clean(phone).ifEmpty { NO_PHONE }
        return "📍 주소: ${addressLines(roadAddress, jibunAddress)}\n📞 전화: $phoneText"
    }

    private fun clean(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return if (trimmed.equals("null", ignoreCase = true)) "" else trimmed
    }

    /** 공유 링크. 좌표는 소수 6자리로 고정한다(로케일과 무관하게 점 소수). */
    fun mapLink(lat: Double, lng: Double): String =
        "https://map.kakao.com/link/map/" + String.format(Locale.US, "%.6f,%.6f", lat, lng)

    /** 공유 문구 첫 줄. */
    fun shareIntro(kind: ShareKind): String = when (kind) {
        ShareKind.CURRENT_PRECISE -> "현재 제 위치입니다:"
        ShareKind.CURRENT_APPROXIMATE -> "대략적인 현재 위치입니다(오차가 클 수 있어요):"
        ShareKind.MAP_PICKED -> "지도에서 고른 위치입니다:"
    }

    /** 공유 시트로 보낼 전체 문구(첫 줄 + 지도 링크). */
    fun shareText(kind: ShareKind, lat: Double, lng: Double): String =
        "${shareIntro(kind)}\n${mapLink(lat, lng)}"
}
