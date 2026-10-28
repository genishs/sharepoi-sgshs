package com.sgshs.sharepoi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PlaceTextTest {

    // --- 공유 문구(#11) ---

    @Test
    fun preciseShareSaysCurrentLocation() {
        assertEquals(
            "현재 제 위치입니다:\nhttps://map.kakao.com/link/map/37.566700,126.827300",
            PlaceText.shareText(ShareKind.CURRENT_PRECISE, 37.5667, 126.8273)
        )
    }

    @Test
    fun approximateShareSaysItIsApproximate() {
        val text = PlaceText.shareText(ShareKind.CURRENT_APPROXIMATE, 37.5, 127.0)
        assertTrue(text, text.startsWith("대략적인 현재 위치입니다(오차가 클 수 있어요):\n"))
        // 대략적 위치를 「현재 제 위치」로 보내지 않는다
        assertFalse(text, text.contains("현재 제 위치"))
        assertTrue(text.endsWith("https://map.kakao.com/link/map/37.500000,127.000000"))
    }

    @Test
    fun mapPickedShareSaysPickedOnMap() {
        val text = PlaceText.shareText(ShareKind.MAP_PICKED, 35.1, 129.04)
        assertTrue(text.startsWith("지도에서 고른 위치입니다:\n"))
        assertFalse(text.contains("현재"))
    }

    @Test
    fun linkUsesDotDecimalRegardlessOfLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // 쉼표 소수 로케일
            assertEquals("https://map.kakao.com/link/map/37.123457,126.000001", PlaceText.mapLink(37.1234567, 126.0000009))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
