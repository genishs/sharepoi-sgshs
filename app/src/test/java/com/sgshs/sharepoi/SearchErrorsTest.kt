package com.sgshs.sharepoi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class SearchErrorsTest {

    // --- HTTP 응답 분류 ---

    @Test
    fun http401And403AreAuth() {
        assertEquals(FailureKind.AUTH, SearchErrors.classifyHttp(401, """{"errorType":"AccessDeniedError","message":"wrong appKey"}"""))
        assertEquals(
            FailureKind.AUTH,
            SearchErrors.classifyHttp(403, """{"errorType":"AccessDeniedError","message":"App(x) disabled OPEN_MAP_AND_LOCAL service."}""")
        )
        assertEquals(FailureKind.AUTH, SearchErrors.classifyHttp(401, null))
    }

    @Test
    fun http429IsQuotaEvenWithoutBody() {
        assertEquals(FailureKind.QUOTA, SearchErrors.classifyHttp(429, null))
        assertEquals(FailureKind.QUOTA, SearchErrors.classifyHttp(429, "anything"))
    }

    @Test
    fun quotaBodyWinsOverStatusCode() {
        // 카카오 공통 오류 코드 -10(허용된 요청 횟수 초과)은 400 으로 올 수 있다
        assertEquals(FailureKind.QUOTA, SearchErrors.classifyHttp(400, """{"msg":"API limit has been exceeded.","code":-10}"""))
        assertEquals(FailureKind.QUOTA, SearchErrors.classifyHttp(403, """{"errorType":"RequestThrottled","message":"too many requests"}"""))
        assertEquals(FailureKind.QUOTA, SearchErrors.classifyHttp(400, """{"message":"exceeded the daily limit"}"""))
        assertEquals(FailureKind.QUOTA, SearchErrors.classifyHttp(400, """{"message":"Quota exceeded"}"""))
    }

    @Test
    fun invalidArgumentLimitIsNotQuota() {
        // "limit" 이 들어 있어도 잘못된 인자 안내는 한도 초과가 아니다
        assertEquals(FailureKind.SERVICE, SearchErrors.classifyHttp(400, """{"errorType":"InvalidArgument","message":"size is more than max limit"}"""))
        // -100 같은 다른 코드를 -10 으로 읽지 않는다
        assertEquals(FailureKind.SERVICE, SearchErrors.classifyHttp(400, """{"code":-100}"""))
    }

    @Test
    fun otherHttpErrorsAreService() {
        assertEquals(FailureKind.SERVICE, SearchErrors.classifyHttp(500, null))
        assertEquals(FailureKind.SERVICE, SearchErrors.classifyHttp(503, "<html>Service Unavailable</html>"))
        assertEquals(FailureKind.SERVICE, SearchErrors.classifyHttp(404, ""))
    }

    // --- 예외 분류 ---

    @Test
    fun ioExceptionsAreNetwork() {
        assertEquals(FailureKind.NETWORK, SearchErrors.classifyException(UnknownHostException("Unable to resolve host \"dapi.kakao.com\"")))
        assertEquals(FailureKind.NETWORK, SearchErrors.classifyException(ConnectException("failed to connect")))
        assertEquals(FailureKind.NETWORK, SearchErrors.classifyException(SocketTimeoutException("timeout")))
        assertEquals(FailureKind.NETWORK, SearchErrors.classifyException(SSLHandshakeException("handshake")))
        assertEquals(FailureKind.NETWORK, SearchErrors.classifyException(IOException("closed")))
    }

    @Test
    fun nonIoExceptionsAreService() {
        // 응답 JSON 해석 실패(JSONException 은 IOException 이 아니다) 등
        assertEquals(FailureKind.SERVICE, SearchErrors.classifyException(IllegalStateException("bad json")))
        assertEquals(FailureKind.SERVICE, SearchErrors.classifyException(NumberFormatException()))
    }

    // --- 지도 SDK 오류 분류(#15 릴리스 안내) ---

    @Test
    fun mapErrorCodes() {
        for (code in listOf(-2, -3, -4, 499)) assertEquals("code $code", FailureKind.NETWORK, SearchErrors.classifyMapError(code))
        assertEquals(FailureKind.QUOTA, SearchErrors.classifyMapError(429))
        for (code in listOf(401, 403, 1003)) assertEquals("code $code", FailureKind.AUTH, SearchErrors.classifyMapError(code))
        assertEquals(FailureKind.SERVICE, SearchErrors.classifyMapError(-1))
        assertEquals(FailureKind.SERVICE, SearchErrors.classifyMapError(null))
    }

    @Test
    fun releaseMapMessagesCarryNoDiagnostics() {
        for (kind in FailureKind.values()) {
            val msg = SearchErrors.mapFailureMessage(kind)
            for (leak in listOf("keyHash", "키 해시", "signers", "서명", "pkg=", "com.sgshs", "Exception", "\tat ")) {
                assertFalse("$kind: $leak", msg.contains(leak))
            }
            assertTrue(msg.contains("다시 시도"))
        }
    }

    // --- 결론 모으기 ---

    private val net = RequestOutcome.Failed(FailureKind.NETWORK)
    private val auth = RequestOutcome.Failed(FailureKind.AUTH, 401)
    private val quota = RequestOutcome.Failed(FailureKind.QUOTA, 429)

    @Test
    fun allSucceededWithNothingIsNoResults() {
        val summary = SearchErrors.summarize(List(4) { RequestOutcome.Ok(0) }, 0)
        assertEquals(SearchSummary.NoResults, summary)
    }

    @Test
    fun airplaneModeIsFailureNotNoResults() {
        // 비행기 모드: 네 요청이 모두 UnknownHost → 「주변에 없음」이 아니라 네트워크 실패
        val summary = SearchErrors.summarize(List(4) { net }, 0)
        assertTrue(summary is SearchSummary.Failed)
        assertEquals(FailureKind.NETWORK, (summary as SearchSummary.Failed).cause.kind)
    }

    @Test
    fun someFailedAndNothingFoundIsStillFailure() {
        // 일부만 성공(0건)하고 나머지가 실패했으면 결과가 정말 없는지 알 수 없다
        val summary = SearchErrors.summarize(listOf(RequestOutcome.Ok(0), RequestOutcome.Ok(0), net, RequestOutcome.Ok(0)), 0)
        assertTrue(summary is SearchSummary.Failed)
    }

    @Test
    fun foundWithPartialFailureKeepsResultsAndNotes() {
        val summary = SearchErrors.summarize(listOf(RequestOutcome.Ok(3), quota, RequestOutcome.Ok(2), RequestOutcome.Ok(0)), 5)
        assertTrue(summary is SearchSummary.Found)
        summary as SearchSummary.Found
        assertEquals(5, summary.count)
        assertEquals(FailureKind.QUOTA, summary.partialFailure?.kind)
    }

    @Test
    fun foundWithoutFailureHasNoNote() {
        val summary = SearchErrors.summarize(listOf(RequestOutcome.Ok(15), RequestOutcome.Ok(4)), 19) as SearchSummary.Found
        assertNull(summary.partialFailure)
    }

    @Test
    fun noRequestsIsFailure() {
        assertTrue(SearchErrors.summarize(emptyList(), 0) is SearchSummary.Failed)
    }

    @Test
    fun representativeIsMostFrequentThenPriority() {
        val mostlyAuth = SearchErrors.summarize(listOf(net, auth, auth, quota), 0) as SearchSummary.Failed
        assertEquals(FailureKind.AUTH, mostlyAuth.cause.kind)
        assertEquals(401, mostlyAuth.cause.httpCode)
        // 같은 수면 네트워크 > 인증 > 한도 > 서비스 순
        val tie = SearchErrors.summarize(listOf(quota, net), 0) as SearchSummary.Failed
        assertEquals(FailureKind.NETWORK, tie.cause.kind)
    }

    // --- 안내 문구 ---

    @Test
    fun failureMessagesNeverSayNothingNearby() {
        val causes = listOf(net, auth, quota, RequestOutcome.Failed(FailureKind.SERVICE, 503), RequestOutcome.Failed(FailureKind.SERVICE))
        for (cause in causes) {
            val msg = SearchErrors.searchFailureMessage(cause, "화장실")
            assertFalse(msg, msg.contains("정보가 없습니다"))
            assertTrue(msg, msg.contains("다시 시도"))
        }
    }

    @Test
    fun failureMessagesNameTheCause() {
        assertTrue(SearchErrors.searchFailureMessage(net, "카페").contains("인터넷"))
        assertTrue(SearchErrors.searchFailureMessage(auth, "카페").contains("인증"))
        assertTrue(SearchErrors.searchFailureMessage(quota, "카페").contains("요청이 많아"))
        assertTrue(SearchErrors.searchFailureMessage(RequestOutcome.Failed(FailureKind.SERVICE, 503), "카페").contains("HTTP 503"))
        assertFalse(SearchErrors.searchFailureMessage(RequestOutcome.Failed(FailureKind.SERVICE), "카페").contains("HTTP"))
    }
}
