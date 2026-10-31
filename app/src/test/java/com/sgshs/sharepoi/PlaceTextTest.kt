package com.sgshs.sharepoi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PlaceTextTest {

    // --- 주소 병기(#8) ---

    @Test
    fun roadThenJibunOnTwoLines() {
        assertEquals(
            "서울 강서구 마곡중앙로 161-8\n(지번) 서울 강서구 마곡동 757",
            PlaceText.addressLines("서울 강서구 마곡중앙로 161-8", "서울 강서구 마곡동 757")
        )
    }

    @Test
    fun emptyRoadHidesRoadLine() {
        assertEquals("(지번) 서울 강서구 마곡동 757", PlaceText.addressLines("", "서울 강서구 마곡동 757"))
        assertEquals("(지번) 서울 강서구 마곡동 757", PlaceText.addressLines(null, "서울 강서구 마곡동 757"))
    }

    @Test
    fun emptyJibunHidesJibunLine() {
        assertEquals("서울 강서구 마곡중앙로 161-8", PlaceText.addressLines("서울 강서구 마곡중앙로 161-8", ""))
        assertEquals("서울 강서구 마곡중앙로 161-8", PlaceText.addressLines("서울 강서구 마곡중앙로 161-8", "  "))
    }

    @Test
    fun bothEmptyShowsNoAddress() {
        assertEquals(PlaceText.NO_ADDRESS, PlaceText.addressLines("", ""))
        assertEquals(PlaceText.NO_ADDRESS, PlaceText.addressLines(null, null))
    }

    @Test
    fun jsonNullStringIsTreatedAsEmpty() {
        // org.json optString 은 JSON null 을 "null" 로 돌려준다
        assertEquals("(지번) 부산 중구 중앙동4가 1", PlaceText.addressLines("null", "부산 중구 중앙동4가 1"))
        assertEquals(PlaceText.NO_ADDRESS, PlaceText.addressLines("null", "null"))
    }

    @Test
    fun sameValueIsNotRepeated() {
        assertEquals("서울 중구 세종대로 110", PlaceText.addressLines("서울 중구 세종대로 110", "서울 중구 세종대로 110"))
    }

    @Test
    fun valuesAreTrimmed() {
        assertEquals("도로명 1\n(지번) 지번 2", PlaceText.addressLines("  도로명 1 ", " 지번 2  "))
    }

    @Test
    fun placeDetailCombinesAddressAndPhone() {
        assertEquals(
            "📍 주소: 서울 강서구 마곡중앙로 161-8\n(지번) 서울 강서구 마곡동 757\n📞 전화: 02-123-4567",
            PlaceText.placeDetail("서울 강서구 마곡중앙로 161-8", "서울 강서구 마곡동 757", "02-123-4567")
        )
        assertEquals(
            "📍 주소: (지번) 서울 강서구 마곡동 757\n📞 전화: ${PlaceText.NO_PHONE}",
            PlaceText.placeDetail("", "서울 강서구 마곡동 757", "")
        )
    }

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

    // --- 공공기관·주유소 안내 줄·전화번호(#18 #20) ---

    @Test
    fun detailAppendsNoteLineWhenGiven() {
        val text = PlaceText.placeDetail("도로 1", "", "02-1234-5678", "공공기관입니다. 화장실을 쓸 수 있는지는 현장에서 확인해 주세요.")
        assertEquals("📍 주소: 도로 1\n📞 전화: 02-1234-5678\nℹ️ 공공기관입니다. 화장실을 쓸 수 있는지는 현장에서 확인해 주세요.", text)
    }

    @Test
    fun detailWithoutNoteIsUnchanged() {
        val expected = "📍 주소: 도로 1\n📞 전화: ${PlaceText.NO_PHONE}"
        assertEquals(expected, PlaceText.placeDetail("도로 1", "", ""))
        assertEquals(expected, PlaceText.placeDetail("도로 1", "", "", ""))
        assertEquals(expected, PlaceText.placeDetail("도로 1", "", "null", "null"))
    }

    @Test
    fun cleanPhoneDropsNullAndBlank() {
        assertEquals("", PlaceText.cleanPhone(null))
        assertEquals("", PlaceText.cleanPhone("  "))
        assertEquals("", PlaceText.cleanPhone("null"))
        assertEquals("02-1234-5678", PlaceText.cleanPhone(" 02-1234-5678 "))
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
