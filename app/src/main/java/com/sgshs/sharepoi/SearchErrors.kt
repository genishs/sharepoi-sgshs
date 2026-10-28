package com.sgshs.sharepoi

import java.io.IOException

/**
 * 장소 검색(카카오 Local API)이 실패한 원인. 어떤 안내 문구를 보일지 고르는 데 쓴다.
 * 안드로이드 API 에 기대지 않아 단위 시험으로 확인한다.
 */
enum class FailureKind {
    /** 인터넷 연결 없음·연결 실패·응답 시간 초과 */
    NETWORK,

    /** 키·앱 인증 실패(HTTP 401·403 등) */
    AUTH,

    /** 요청 한도 초과(HTTP 429 또는 한도 초과 응답) */
    QUOTA,

    /** 그 밖의 서버 오류(5xx·예상 못 한 HTTP 코드·응답 해석 실패) */
    SERVICE
}

/** 검색 요청 한 번의 결과. */
sealed class RequestOutcome {
    /** 정상 응답(HTTP 200)을 받아 해석까지 끝났다. [count] 는 그 응답에 든 장소 수. */
    data class Ok(val count: Int) : RequestOutcome()

    /** 실패. [httpCode] 는 HTTP 응답을 받았을 때만 채운다. */
    data class Failed(val kind: FailureKind, val httpCode: Int? = null) : RequestOutcome()
}

/** 여러 요청의 결과를 모아 화면에 보일 결론. */
sealed class SearchSummary {
    /** 장소를 하나 이상 찾았다. 일부 요청이 실패했다면 [partialFailure] 에 대표 원인을 둔다. */
    data class Found(val count: Int, val partialFailure: RequestOutcome.Failed?) : SearchSummary()

    /** 모든 요청이 성공했고 결과가 정말 없다. 이때만 「주변에 없음」으로 안내한다. */
    object NoResults : SearchSummary()

    /** 보여 줄 결과가 없고 실패한 요청이 있다. 「주변에 없음」이 아니라 실패로 안내한다. */
    data class Failed(val cause: RequestOutcome.Failed) : SearchSummary()
}

object SearchErrors {

    /** 실패 원인이 섞였을 때 대표로 고를 순서. 이용자가 할 수 있는 조치가 분명한 쪽을 앞에 둔다. */
    private val PRIORITY = listOf(FailureKind.NETWORK, FailureKind.AUTH, FailureKind.QUOTA, FailureKind.SERVICE)

    // 카카오 REST API 공통 오류 코드 -10(허용된 요청 횟수 초과)이나 한도 초과를 뜻하는 문구.
    // "size limit" 같은 잘못된 인자 안내를 한도 초과로 잘못 읽지 않도록 "limit" 단독으로는 보지 않는다.
    private val QUOTA_BODY = Regex(
        "\"code\"\\s*:\\s*-10(?!\\d)" +
            "|quota" +
            "|throttl" +
            "|too\\s*many\\s*request" +
            "|limit\\w*\\s+(has\\s+been\\s+|is\\s+|was\\s+)?exceed" +
            "|exceed\\w*\\s+(the\\s+)?(api\\s+|request\\s+|daily\\s+)?limit",
        RegexOption.IGNORE_CASE
    )

    /** HTTP 200 이 아닌 응답을 분류한다. [errorBody] 는 오류 응답 본문(없으면 null). */
    fun classifyHttp(code: Int, errorBody: String?): FailureKind {
        if (code == 429) return FailureKind.QUOTA
        if (errorBody != null && QUOTA_BODY.containsMatchIn(errorBody)) return FailureKind.QUOTA
        return when (code) {
            401, 403 -> FailureKind.AUTH
            else -> FailureKind.SERVICE
        }
    }

    /**
     * 요청 중 난 예외를 분류한다. 입출력 예외(UnknownHost·Connect·SocketTimeout·SSL 등)는 연결 문제로,
     * 그 밖(응답 JSON 해석 실패 등)은 서비스 오류로 본다.
     */
    fun classifyException(e: Throwable): FailureKind =
        if (e is IOException) FailureKind.NETWORK else FailureKind.SERVICE

    /** 여러 요청 결과와 (중복을 걸러 낸) 장소 수로 결론을 낸다. */
    fun summarize(outcomes: List<RequestOutcome>, totalFound: Int): SearchSummary {
        val failures = outcomes.filterIsInstance<RequestOutcome.Failed>()
        val representative = pickRepresentative(failures)
        return when {
            totalFound > 0 -> SearchSummary.Found(totalFound, representative)
            representative != null -> SearchSummary.Failed(representative)
            outcomes.isEmpty() -> SearchSummary.Failed(RequestOutcome.Failed(FailureKind.SERVICE))
            else -> SearchSummary.NoResults
        }
    }

    private fun pickRepresentative(failures: List<RequestOutcome.Failed>): RequestOutcome.Failed? {
        if (failures.isEmpty()) return null
        val kind = failures.groupingBy { it.kind }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<FailureKind, Int>> { it.value }.thenBy { PRIORITY.indexOf(it.key) })
            .first().key
        return failures.first { it.kind == kind }
    }

    /** 검색에 실패해 결과를 보여 줄 수 없을 때의 안내 문구. [label] 은 「화장실」「카페」처럼 찾던 대상. */
    fun searchFailureMessage(cause: RequestOutcome.Failed, label: String): String = when (cause.kind) {
        FailureKind.NETWORK ->
            "인터넷에 연결되어 있지 않거나 연결이 불안정해 $label 검색을 하지 못했습니다.\n네트워크 상태를 확인한 뒤 다시 시도해 주세요."
        FailureKind.AUTH ->
            "검색 서비스 인증에 실패해 $label 검색을 하지 못했습니다.\n잠시 후 다시 시도해 주세요. 계속되면 앱을 최신 버전으로 업데이트해 주세요."
        FailureKind.QUOTA ->
            "검색 요청이 많아 검색 서비스 이용이 잠시 제한되었습니다.\n잠시 후 다시 시도해 주세요."
        FailureKind.SERVICE ->
            "검색 서비스에 문제가 있어 $label 검색을 하지 못했습니다${httpSuffix(cause.httpCode)}.\n잠시 후 다시 시도해 주세요."
    }

    /** 결과는 찾았지만 일부 요청이 실패했을 때 덧붙이는 짧은 안내. */
    fun partialFailureNote(cause: RequestOutcome.Failed): String = when (cause.kind) {
        FailureKind.NETWORK -> "연결이 불안정해 일부 결과가 빠졌을 수 있습니다."
        FailureKind.AUTH -> "검색 서비스 인증 오류로 일부 결과가 빠졌을 수 있습니다."
        FailureKind.QUOTA -> "검색 요청 한도에 걸려 일부 결과가 빠졌을 수 있습니다."
        FailureKind.SERVICE -> "검색 서비스 오류${httpSuffix(cause.httpCode)}로 일부 결과가 빠졌을 수 있습니다."
    }

    private fun httpSuffix(code: Int?): String = if (code != null) "(HTTP $code)" else ""
}
