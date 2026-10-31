package com.sgshs.sharepoi

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 카카오 Local API 로 주변 장소를 찾는다. 화면(Activity)을 붙잡지 않도록 화면 밖에 둔다(#18).
 * [search] 는 막히는(blocking) 호출이라 백그라운드(Dispatchers.IO)에서 부른다.
 *
 * @param appKey 네이티브 앱 키(KakaoAK). KA 헤더의 키 해시·패키지명과 함께 인증된다(#19).
 * @param kaHeader 「sdk/… os/… origin/<키 해시> android_pkg/…」
 */
class LocalSearchClient(private val appKey: String, private val kaHeader: String) {

    /** 검색 한 번의 결과: 화면에 보일 결론과 장소 목록(중복 제거, display_icon·display_note 를 붙임) */
    class Result(val summary: SearchSummary, val documents: List<JSONObject>)

    fun search(request: SearchCatalog.Request, lat: Double, lng: Double): Result {
        val documents = ArrayList<JSONObject>()
        val outcomes = ArrayList<RequestOutcome>()
        try {
            val seenIds = HashSet<String>()
            when (request) {
                SearchCatalog.Request.Restroom -> {
                    // 화장실 키워드 2종 + 공공기관(PO3)·주유소(OL7) 카테고리. 출처마다 아이콘을 따로 붙인다.
                    for (source in SearchCatalog.restroomSources) {
                        val url = if (source.keyword != null) {
                            "$BASE/keyword.json?query=${URLEncoder.encode(source.keyword, "UTF-8")}" +
                                "&x=$lng&y=$lat&radius=${SearchCatalog.RADIUS_M}&sort=distance"
                        } else {
                            "$BASE/category.json?category_group_code=${source.categoryCode}" +
                                "&x=$lng&y=$lat&radius=${SearchCatalog.RADIUS_M}&sort=distance"
                        }
                        when (val response = requestLocal(url)) {
                            is Response.Page -> {
                                merge(response.documents, seenIds, documents, source.icon, source.note)
                                outcomes.add(RequestOutcome.Ok(response.documents.size))
                            }
                            is Response.Error -> outcomes.add(response.failure)
                        }
                    }
                }
                is SearchCatalog.Request.Category -> {
                    // category.json 은 페이지당 최대 15개라 최대 3페이지(45개)까지 모은다.
                    val category = request.category
                    for (page in 1..3) {
                        val url = "$BASE/category.json?category_group_code=${category.code}" +
                            "&x=$lng&y=$lat&radius=${SearchCatalog.RADIUS_M}&sort=distance&page=$page&size=15"
                        when (val response = requestLocal(url)) {
                            is Response.Page -> {
                                merge(response.documents, seenIds, documents, category.icon, null)
                                outcomes.add(RequestOutcome.Ok(response.documents.size))
                                if (response.isEnd) break
                            }
                            is Response.Error -> {
                                // 다음 페이지는 시도하지 않는다. 앞 페이지 결과가 있으면 그것만 보이고 일부 누락을 알린다.
                                outcomes.add(response.failure)
                                break
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 요청 밖에서 난 예상 못 한 오류도 「결과 없음」이 아니라 실패로 알린다.
            if (BuildConfig.DEBUG) Log.w(TAG, "Search failed", e)
            outcomes.add(RequestOutcome.Failed(SearchErrors.classifyException(e)))
        }
        return Result(SearchErrors.summarize(outcomes, documents.size), documents)
    }

    /** 카카오 Local API 응답 한 번의 결과. */
    private sealed class Response {
        class Page(val documents: List<JSONObject>, val isEnd: Boolean) : Response()
        class Error(val failure: RequestOutcome.Failed) : Response()
    }

    /**
     * GET 을 한 번 보낸다. 200 이면 장소 목록을, 그 밖의 응답·예외는 원인을 분류해 돌려준다.
     * 연결·읽기 시간 제한을 두고, 연결은 항상 닫는다.
     */
    private fun requestLocal(urlString: String): Response {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                setRequestProperty("Authorization", "KakaoAK $appKey")
                setRequestProperty("KA", kaHeader)
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val docs = json.getJSONArray("documents")
                val items = (0 until docs.length()).map { docs.getJSONObject(it) }
                val meta = json.optJSONObject("meta")
                Response.Page(items, meta == null || meta.optBoolean("is_end", true))
            } else {
                val kind = SearchErrors.classifyHttp(code, readErrorBody(conn))
                if (BuildConfig.DEBUG) Log.w(TAG, "Local API HTTP $code -> $kind")
                Response.Error(RequestOutcome.Failed(kind, code))
            }
        } catch (e: Exception) {
            val kind = SearchErrors.classifyException(e)
            if (BuildConfig.DEBUG) Log.w(TAG, "Local API request failed -> $kind", e)
            Response.Error(RequestOutcome.Failed(kind))
        } finally {
            conn?.disconnect()
        }
    }

    /** 오류 응답 본문 앞부분(한도 초과 판별용). 읽지 못하면 null. */
    private fun readErrorBody(conn: HttpURLConnection): String? = try {
        conn.errorStream?.bufferedReader()?.use { reader ->
            val sb = StringBuilder()
            val buf = CharArray(512)
            while (sb.length < ERROR_BODY_LIMIT) {
                val n = reader.read(buf)
                if (n < 0) break
                sb.append(buf, 0, n)
            }
            sb.toString()
        }
    } catch (e: IOException) {
        null
    }

    /** id(없으면 이름)로 중복을 걸러 [into] 에 더한다. 먼저 들어온 출처의 아이콘·메모가 남는다. */
    private fun merge(docs: List<JSONObject>, seenIds: MutableSet<String>, into: MutableList<JSONObject>, icon: String, note: String?) {
        for (item in docs) {
            val id = item.optString("id", "")
            val key = if (id.isNotEmpty()) id else item.optString("place_name", "")
            if (seenIds.add(key)) {
                item.put(KEY_ICON, icon)
                if (note != null) item.put(KEY_NOTE, note)
                into.add(item)
            }
        }
    }

    companion object {
        private const val TAG = "LocalSearch"
        private const val BASE = "https://dapi.kakao.com/v2/local/search"

        /** 연결·읽기 시간 제한(각각) */
        private const val HTTP_TIMEOUT_MS = 10_000

        /** 한도 초과 판별용으로 읽는 오류 응답 본문 최대 길이 */
        private const val ERROR_BODY_LIMIT = 4_096

        const val KEY_ICON = "display_icon"
        const val KEY_NOTE = "display_note"
    }
}
