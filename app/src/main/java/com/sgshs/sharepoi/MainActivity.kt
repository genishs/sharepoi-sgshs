package com.sgshs.sharepoi

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.KakaoMapSdk
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.Label
import com.kakao.vectormap.label.LabelLayerOptions
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import com.kakao.vectormap.LatLng
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private var kakaoMap: KakaoMap? = null
    private var currentLocationLabel: Label? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // Fallback location when GPS is unavailable: Magoknaru Station (37.5667, 126.8273)
    private val DEFAULT_LAT = 37.5667
    private val DEFAULT_LNG = 126.8273

    // Last real device location obtained from FusedLocationProvider (null until first fix)
    private var lastKnownLocation: Location? = null

    private lateinit var btnShare: Button
    private lateinit var btnRoadview: Button
    private lateinit var btnRestroom: Button
    private lateinit var btnPlaces: Button
    private lateinit var btnZoomIn: Button
    private lateinit var btnZoomOut: Button
    private lateinit var btnMyLocation: Button

    private val PERMISSION_REQUEST_CODE = 1000
    private val SHARE_FIX_TIMEOUT_MS = 6000L
    private val CACHE_MAX_AGE_MS = 120_000L

    private companion object {
        const val TAG = "MainActivity"

        /** 카카오 Local API 연결·읽기 시간 제한(각각) */
        const val HTTP_TIMEOUT_MS = 10_000

        /** 한도 초과 판별용으로 읽는 오류 응답 본문 최대 길이 */
        const val ERROR_BODY_LIMIT = 4_096
    }

    // 주변 편의시설 카테고리 (카카오 category_group_code): (라벨, 코드, 이모지)
    private val placeCategories = listOf(
        Triple("편의점", "CS2", "🏪"),
        Triple("카페", "CE7", "☕"),
        Triple("음식점", "FD6", "🍚"),
        Triple("주차장", "PK6", "🅿️"),
        Triple("약국", "PM9", "💊"),
        Triple("은행", "BK9", "🏦"),
        Triple("지하철역", "SW8", "🚇"),
        Triple("병원", "HP8", "🏥")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize Kakao SDK before setContentView
        KakaoMapSdk.init(this, BuildConfig.KAKAO_MAP_API_KEY)

        val signingKeyHash = computeKeyHash()
        Log.i("MainActivity", "signing key hash=$signingKeyHash pkg=$packageName")

        setContentView(R.layout.activity_main)

        // targetSdk 36: Android 15+ 에서 edge-to-edge 강제. 상단(상태바)은 AppCompat 액션바가 처리하므로
        // 하단 컨트롤 영역에만 좌/우/하단(내비게이션 바·컷아웃·키보드) 인셋을 패딩으로 더한다.
        // enableEdgeToEdge() 는 지원 중단 API(setStatusBarColor 등)를 끌어들여 쓰지 않는다.
        val bottomArea = findViewById<android.view.View>(R.id.bottomArea)
        val baseL = bottomArea.paddingLeft
        val baseT = bottomArea.paddingTop
        val baseR = bottomArea.paddingRight
        val baseB = bottomArea.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<android.view.View>(android.R.id.content)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            bottomArea.setPadding(baseL + bars.left, baseT, baseR + bars.right, baseB + maxOf(bars.bottom, ime.bottom))
            insets
        }

        btnShare = findViewById(R.id.btnShare)
        btnRoadview = findViewById(R.id.btnRoadview)
        btnRestroom = findViewById(R.id.btnRestroom)
        btnPlaces = findViewById(R.id.btnPlaces)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnZoomOut = findViewById(R.id.btnZoomOut)
        btnMyLocation = findViewById(R.id.btnMyLocation)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        mapView = findViewById(R.id.mapView)
        mapView.start(object : MapLifeCycleCallback() {
            override fun onMapDestroy() {}

            override fun onMapError(error: Exception?) {
                val diagnosticText = buildAuthDiagnosticText(error)
                Log.e("MainActivity", "Kakao map error diagnostic:\n$diagnosticText", error)
                showAuthDiagnosticDialog(diagnosticText)
            }
        }, object : KakaoMapReadyCallback() {
            override fun onMapReady(map: KakaoMap) {
                kakaoMap = map
                moveToCurrentLocation()

                // Label Click Listener for Restroom Markers
                map.setOnLabelClickListener { _, _, label ->
                    val item = label.tag as? JSONObject
                    if (item != null) {
                        val icon = item.optString("display_icon", "📍")
                        val name = item.optString("place_name", "장소")
                        val roadAddress = item.optString("road_address_name", "")
                        val address = if (roadAddress.isNotEmpty()) roadAddress else item.optString("address_name", "주소 정보 없음")
                        val phone = item.optString("phone", "")
                        val phoneDisplay = if (phone.isEmpty()) "전화번호 정보 없음" else phone
                        val lat = item.optString("y", "37.5667").toDouble()
                        val lng = item.optString("x", "126.8273").toDouble()

                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("$icon $name")
                            .setMessage("📍 주소: $address\n📞 전화: $phoneDisplay")
                            .setPositiveButton("📷 로드뷰 보기") { _, _ ->
                                val roadviewUrl = "https://map.kakao.com/link/roadview/$lat,$lng"
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(roadviewUrl))
                                startActivity(intent)
                            }
                            .setNegativeButton("닫기", null)
                            .show()

                        true
                    } else {
                        false
                    }
                }
            }
        })

        checkAndRequestPermissions()

        // Zoom Control Listeners
        btnZoomIn.setOnClickListener {
            kakaoMap?.moveCamera(CameraUpdateFactory.zoomIn())
        }

        btnZoomOut.setOnClickListener {
            kakaoMap?.moveCamera(CameraUpdateFactory.zoomOut())
        }

        btnMyLocation.setOnClickListener {
            moveToCurrentLocation()
        }

        // Restroom Search Listener (4-in-1 multi-source search)
        btnRestroom.setOnClickListener {
            fetchNearbyRestroomsMultiSource()
        }

        // Nearby places (편의시설) search: pick a category, then search
        btnPlaces.setOnClickListener {
            showPlaceCategoryDialog()
        }

        // Roadview Listener with Magoknaru fallback
        btnRoadview.setOnClickListener {
            val cameraPos = kakaoMap?.cameraPosition?.position
            val lat = cameraPos?.latitude ?: DEFAULT_LAT
            val lng = cameraPos?.longitude ?: DEFAULT_LNG

            val roadviewUrl = "https://map.kakao.com/link/roadview/$lat,$lng"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(roadviewUrl))
            startActivity(intent)
        }

        btnShare.setOnClickListener {
            shareLocation()
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.resume()
    }

    override fun onPause() {
        super.onPause()
        mapView.pause()
    }

    /**
     * Resolve the device's real location via FusedLocationProvider and center the map on it.
     * Falls back to Magoknaru Station only when permission is missing or no fix is available.
     */
    private fun moveToCurrentLocation() {
        val hasFine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            showLocationOnMap(DEFAULT_LAT, DEFAULT_LNG)
            return
        }

        val cts = CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { location ->
                if (location != null) {
                    lastKnownLocation = location
                    showLocationOnMap(location.latitude, location.longitude)
                } else {
                    // No fresh fix (e.g. GPS off) — try the cached one before falling back
                    fusedLocationClient.lastLocation.addOnSuccessListener { cached ->
                        if (cached != null) {
                            lastKnownLocation = cached
                            showLocationOnMap(cached.latitude, cached.longitude)
                        } else {
                            Toast.makeText(this, "현재 위치를 가져올 수 없어 기본 위치(마곡나루역)를 표시합니다.", Toast.LENGTH_SHORT).show()
                            showLocationOnMap(DEFAULT_LAT, DEFAULT_LNG)
                        }
                    }
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "위치 조회 실패: ${it.message}", Toast.LENGTH_SHORT).show()
                showLocationOnMap(DEFAULT_LAT, DEFAULT_LNG)
            }
    }

    private fun showLocationOnMap(latitude: Double, longitude: Double) {
        val map = kakaoMap ?: return
        val position = LatLng.from(latitude, longitude)

        map.moveCamera(CameraUpdateFactory.newCenterPosition(position, 16))

        val labelManager = map.labelManager ?: return
        val layer = labelManager.getLayer("currentLocLayer")
            ?: labelManager.addLayer(LabelLayerOptions.from("currentLocLayer").setZOrder(10001))

        val label = currentLocationLabel
        if (label == null) {
            val bitmap = getBitmapFromVector(this, R.drawable.current_location_marker)
            val styles = labelManager.addLabelStyles(
                LabelStyles.from(LabelStyle.from(bitmap).setAnchorPoint(0.5f, 0.5f))
            )
            val options = LabelOptions.from("current_loc_label", position).setStyles(styles)
            currentLocationLabel = layer?.addLabel(options)
        } else {
            label.moveTo(position)
        }
    }

    /**
     * 설치된 앱의 실제 서명 인증서로부터 SHA-1 키 해시(Base64, NO_WRAP)를 계산한다.
     * Play 앱 서명(App Signing)으로 재서명되면 디버그 빌드의 키 해시와 실제 배포 키 해시가
     * 달라지므로, 하드코딩된 값 대신 런타임에 직접 계산해서 사용한다.
     */
    private fun computeKeyHash(): String {
        val pkgName = packageName

        return try {
            val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = packageManager.getPackageInfo(
                    pkgName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                ).signingInfo

                if (signingInfo != null && signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners[0]
                } else {
                    signingInfo?.signingCertificateHistory?.get(0)
                }
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = packageManager.getPackageInfo(
                    pkgName,
                    PackageManager.GET_SIGNATURES
                )
                @Suppress("DEPRECATION")
                packageInfo.signatures?.get(0)
            }

            if (signature != null) {
                val keyHashBytes = MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
                Base64.encodeToString(keyHashBytes, Base64.NO_WRAP)
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to compute signing key hash for KA header", e)
            ""
        }
    }

    /**
     * 설치된 앱의 서명 인증서 전부(멀티 서명 포함)에 대한 SHA-1 키 해시(Base64, NO_WRAP) 목록을 계산한다.
     * API28+ 에서는 signingInfo.apkContentsSigners 전부를, 그 이하에서는 signatures 전부를 사용한다.
     */
    private fun computeAllSignerHashes(): List<String> {
        val pkgName = packageName

        return try {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = packageManager.getPackageInfo(
                    pkgName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                ).signingInfo
                signingInfo?.apkContentsSigners ?: emptyArray()
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = packageManager.getPackageInfo(
                    pkgName,
                    PackageManager.GET_SIGNATURES
                )
                @Suppress("DEPRECATION")
                packageInfo.signatures ?: emptyArray()
            }

            signatures.map { signature ->
                val keyHashBytes = MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
                Base64.encodeToString(keyHashBytes, Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to compute signer hashes", e)
            emptyList()
        }
    }

    /**
     * 지도 인증 오류(onMapError) 진단 정보를 사람이 읽을 수 있는 텍스트로 조립한다.
     */
    private fun buildAuthDiagnosticText(error: Exception?): String {
        val signers = computeAllSignerHashes().joinToString(", ")
        val stackLines = error?.stackTrace
            ?.take(3)
            ?.joinToString("\n") { "\tat $it" }
            ?: ""

        return buildString {
            append("pkg=$packageName\n")
            append("keyHash=${computeKeyHash()}\n")
            append("signers=$signers\n")
            append("sdkInt=${Build.VERSION.SDK_INT}\n")
            append("error=${error?.javaClass?.name}: ${error?.message}\n")
            append("cause=${error?.cause?.message}")
            if (stackLines.isNotEmpty()) {
                append("\n")
                append(stackLines)
            }
        }
    }

    /**
     * 지도 인증 오류 진단 다이얼로그. 본문은 선택/복사 가능하며, "복사" 버튼으로 클립보드에 전체 복사한다.
     */
    private fun showAuthDiagnosticDialog(diagnosticText: String) {
        val messageView = TextView(this@MainActivity).apply {
            text = diagnosticText
            setTextIsSelectable(true)
            val paddingPx = (16 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }

        AlertDialog.Builder(this@MainActivity)
            .setTitle("지도 인증 오류")
            .setView(messageView)
            .setPositiveButton("복사") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("지도 인증 오류", diagnosticText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@MainActivity, "복사됨", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    /**
     * computeKeyHash()로 얻은 서명 키 해시를 이용해 Kakao KA 헤더를 조립한다.
     */
    private fun buildKaHeader(): String {
        val sdkVersion = "2.14.1"
        val osVersion = "android-${Build.VERSION.SDK_INT}"
        val pkgName = packageName
        val origin = computeKeyHash()

        return "sdk/$sdkVersion os/$osVersion origin/$origin android_pkg/$pkgName"
    }

    private fun fetchNearbyRestroomsMultiSource() {
        val cameraPos = kakaoMap?.cameraPosition?.position
        val lat = cameraPos?.latitude ?: DEFAULT_LAT
        val lng = cameraPos?.longitude ?: DEFAULT_LNG

        Toast.makeText(this, "지도 중심 주변 4가지 소스(공중/개방/관공서/주유소) 통합 검색 중...", Toast.LENGTH_SHORT).show()

        Thread {
            val allDocuments = ArrayList<JSONObject>()
            val outcomes = ArrayList<RequestOutcome>()
            try {
                val seenIds = HashSet<String>()

                val searchUrls = listOf(
                    "https://dapi.kakao.com/v2/local/search/keyword.json?query=${URLEncoder.encode("공중화장실", "UTF-8")}&x=$lng&y=$lat&radius=2000&sort=distance",
                    "https://dapi.kakao.com/v2/local/search/keyword.json?query=${URLEncoder.encode("개방화장실", "UTF-8")}&x=$lng&y=$lat&radius=2000&sort=distance",
                    "https://dapi.kakao.com/v2/local/search/category.json?category_group_code=PO3&x=$lng&y=$lat&radius=2000&sort=distance",
                    "https://dapi.kakao.com/v2/local/search/category.json?category_group_code=OL7&x=$lng&y=$lat&radius=2000&sort=distance"
                )

                val kaHeader = buildKaHeader()

                for (urlString in searchUrls) {
                    when (val response = requestLocal(urlString, kaHeader)) {
                        is LocalResponse.Page -> {
                            mergeDocuments(response.documents, seenIds, allDocuments, "🚽")
                            outcomes.add(RequestOutcome.Ok(response.documents.size))
                        }
                        is LocalResponse.Error -> outcomes.add(response.failure)
                    }
                }
            } catch (e: Exception) {
                // 요청 밖에서 난 예상 못 한 오류도 「결과 없음」이 아니라 실패로 알린다.
                if (BuildConfig.DEBUG) Log.w(TAG, "Restroom search failed", e)
                outcomes.add(RequestOutcome.Failed(SearchErrors.classifyException(e)))
            }

            val summary = SearchErrors.summarize(outcomes, allDocuments.size)
            runOnUiThread {
                showSearchResult(summary, allDocuments, R.drawable.restroom_marker, "restroomLayer", "화장실") {
                    fetchNearbyRestroomsMultiSource()
                }
            }
        }.start()
    }

    /** 카카오 Local API 응답 한 번의 결과. */
    private sealed class LocalResponse {
        class Page(val documents: List<JSONObject>, val isEnd: Boolean) : LocalResponse()
        class Error(val failure: RequestOutcome.Failed) : LocalResponse()
    }

    /**
     * 카카오 Local API 에 GET 을 한 번 보낸다. 200 이면 장소 목록을, 그 밖의 응답·예외는 원인을 분류해 돌려준다.
     * 연결·읽기 시간 제한을 두고, 연결은 항상 닫는다. (백그라운드 스레드에서 부른다)
     */
    private fun requestLocal(urlString: String, kaHeader: String): LocalResponse {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                setRequestProperty("Authorization", "KakaoAK ${BuildConfig.KAKAO_REST_API_KEY}")
                setRequestProperty("KA", kaHeader)
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val docs = json.getJSONArray("documents")
                val items = (0 until docs.length()).map { docs.getJSONObject(it) }
                val meta = json.optJSONObject("meta")
                LocalResponse.Page(items, meta == null || meta.optBoolean("is_end", true))
            } else {
                val kind = SearchErrors.classifyHttp(code, readErrorBody(conn))
                if (BuildConfig.DEBUG) Log.w(TAG, "Local API HTTP $code -> $kind")
                LocalResponse.Error(RequestOutcome.Failed(kind, code))
            }
        } catch (e: Exception) {
            val kind = SearchErrors.classifyException(e)
            if (BuildConfig.DEBUG) Log.w(TAG, "Local API request failed -> $kind", e)
            LocalResponse.Error(RequestOutcome.Failed(kind))
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

    /** id(없으면 이름)로 중복을 걸러 [into] 에 더한다. */
    private fun mergeDocuments(docs: List<JSONObject>, seenIds: MutableSet<String>, into: MutableList<JSONObject>, icon: String) {
        for (item in docs) {
            val id = item.optString("id", "")
            val key = if (id.isNotEmpty()) id else item.optString("place_name", "")
            if (seenIds.add(key)) {
                item.put("display_icon", icon)
                into.add(item)
            }
        }
    }

    /**
     * 검색 결론을 화면에 보인다. 모든 요청이 성공했는데 결과가 없을 때만 「주변에 없음」으로 안내하고,
     * 실패(네트워크·인증·한도·서비스 오류)는 원인별 안내와 다시 시도를 보인다.
     */
    private fun showSearchResult(
        summary: SearchSummary,
        documents: List<JSONObject>,
        markerDrawableId: Int,
        layerId: String,
        categoryLabel: String,
        retry: () -> Unit
    ) {
        if (isFinishing || isDestroyed) return
        when (summary) {
            is SearchSummary.Failed ->
                showSearchFailureDialog(SearchErrors.searchFailureMessage(summary.cause, categoryLabel), retry)
            SearchSummary.NoResults ->
                Toast.makeText(this, "주변 2km 이내에 검색된 ${categoryLabel} 정보가 없습니다.", Toast.LENGTH_SHORT).show()
            is SearchSummary.Found -> {
                val shown = displayMarkers(documents, markerDrawableId, layerId)
                if (shown == 0) {
                    Toast.makeText(this, "지도를 불러오지 못해 검색 결과를 표시할 수 없습니다.", Toast.LENGTH_LONG).show()
                    return
                }
                val note = summary.partialFailure?.let { "\n" + SearchErrors.partialFailureNote(it) } ?: ""
                Toast.makeText(this, "검색 완료! 총 ${shown}개의 ${categoryLabel}을(를) 표시했습니다. 핀을 터치해 상세 정보를 확인하세요!$note", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showSearchFailureDialog(message: String, retry: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("검색하지 못했습니다")
            .setMessage(message)
            .setPositiveButton("다시 시도") { _, _ -> retry() }
            .setNegativeButton("닫기", null)
            .show()
    }

    /** 장소 핀을 지도에 올린다. 올린 핀 수를 돌려준다(지도가 없으면 0). */
    private fun displayMarkers(documents: List<JSONObject>, markerDrawableId: Int, layerId: String): Int {
        val map = kakaoMap ?: return 0
        val labelManager = map.labelManager ?: return 0

        val layer = labelManager.getLayer(layerId)
            ?: labelManager.addLayer(LabelLayerOptions.from(layerId).setZOrder(10000))
            ?: return 0

        val bitmap = getBitmapFromVector(this, markerDrawableId)
        val styles = labelManager.addLabelStyles(
            LabelStyles.from(LabelStyle.from(bitmap).setAnchorPoint(0.5f, 0.5f))
        )

        var shown = 0
        documents.forEachIndexed { i, item ->
            val itemLat = item.optString("y").toDoubleOrNull()
            val itemLng = item.optString("x").toDoubleOrNull()
            if (itemLat == null || itemLng == null) return@forEachIndexed
            val id = item.optString("id", "${layerId}_$i")

            val position = LatLng.from(itemLat, itemLng)
            // Attach item JSONObject as Tag
            val options = LabelOptions.from("${layerId}_label_$id", position)
                .setStyles(styles)
                .setTag(item)

            layer.addLabel(options)
            shown++
        }
        return shown
    }

    /**
     * 주변 편의시설 카테고리 선택 다이얼로그. 선택하면 해당 카테고리를 검색한다.
     */
    private fun showPlaceCategoryDialog() {
        val labels = placeCategories.map { "${it.third} ${it.first}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("주변에서 찾을 편의시설을 선택하세요")
            .setItems(labels) { _, which ->
                val (label, code, icon) = placeCategories[which]
                fetchNearbyByCategory(code, icon, label)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /**
     * 지도 중심 주변 2km 내에서 지정한 카카오 카테고리를 검색해 파란 핀으로 표시한다.
     * category.json 은 페이지당 최대 15개라 최대 3페이지(45개)까지 누적한다.
     */
    private fun fetchNearbyByCategory(categoryCode: String, icon: String, categoryLabel: String) {
        val cameraPos = kakaoMap?.cameraPosition?.position
        val lat = cameraPos?.latitude ?: DEFAULT_LAT
        val lng = cameraPos?.longitude ?: DEFAULT_LNG

        Toast.makeText(this, "지도 중심 주변 ${categoryLabel} 검색 중...", Toast.LENGTH_SHORT).show()

        Thread {
            val allDocuments = ArrayList<JSONObject>()
            val outcomes = ArrayList<RequestOutcome>()
            try {
                val seenIds = HashSet<String>()
                val kaHeader = buildKaHeader()

                for (page in 1..3) {
                    val urlString = "https://dapi.kakao.com/v2/local/search/category.json?category_group_code=$categoryCode&x=$lng&y=$lat&radius=2000&sort=distance&page=$page&size=15"
                    when (val response = requestLocal(urlString, kaHeader)) {
                        is LocalResponse.Page -> {
                            mergeDocuments(response.documents, seenIds, allDocuments, icon)
                            outcomes.add(RequestOutcome.Ok(response.documents.size))
                            if (response.isEnd) break
                        }
                        is LocalResponse.Error -> {
                            // 다음 페이지는 시도하지 않는다. 앞 페이지 결과가 있으면 그것만 보이고 일부 누락을 알린다.
                            outcomes.add(response.failure)
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Category search failed", e)
                outcomes.add(RequestOutcome.Failed(SearchErrors.classifyException(e)))
            }

            val summary = SearchErrors.summarize(outcomes, allDocuments.size)
            runOnUiThread {
                showSearchResult(summary, allDocuments, R.drawable.place_marker, "placeLayer", categoryLabel) {
                    fetchNearbyByCategory(categoryCode, icon, categoryLabel)
                }
            }
        }.start()
    }

    private fun getBitmapFromVector(context: Context, drawableId: Int): Bitmap {
        val drawable = ContextCompat.getDrawable(context, drawableId)
            ?: return Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private var sharing = false

    /**
     * 위치 공유: 공유 시점에 위치를 다시 요청한다(최대 SHARE_FIX_TIMEOUT_MS).
     * 새 위치를 얻지 못하면 지도 중심이나 기본 좌표를 "현재 위치"로 보내지 않고,
     * 안내 대화상자에서 "지도에서 고른 위치"로 보낼지 묻는다.
     */
    private fun shareLocation() {
        if (sharing) return
        val hasPermission =
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            showNoFixDialog("위치 권한이 없어 현재 위치를 확인할 수 없습니다.")
            return
        }

        sharing = true
        btnShare.isEnabled = false
        Toast.makeText(this, "현재 위치를 확인하는 중입니다…", Toast.LENGTH_SHORT).show()

        val cts = CancellationTokenSource()
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        val finish: (Location?) -> Unit = finish@{ location ->
            if (finished) return@finish
            finished = true
            handler.removeCallbacksAndMessages(null)
            sharing = false
            btnShare.isEnabled = true
            if (isFinishing || isDestroyed) return@finish
            if (location != null) {
                lastKnownLocation = location
                startShareChooser("현재 제 위치입니다:", location.latitude, location.longitude)
            } else {
                showNoFixDialog("현재 위치를 확인하지 못했습니다. 위치(GPS)가 켜져 있는지 확인해 주세요.")
            }
        }
        handler.postDelayed({ cts.cancel(); finish(freshCachedLocation()) }, SHARE_FIX_TIMEOUT_MS)
        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { location -> finish(location ?: freshCachedLocation()) }
                .addOnFailureListener { finish(freshCachedLocation()) }
        } catch (e: SecurityException) {
            finish(null)
        }
    }

    /** 최근(CACHE_MAX_AGE_MS 이내)에 얻은 실제 위치만 인정한다. 오래된 캐시는 쓰지 않는다. */
    private fun freshCachedLocation(): Location? {
        val known = lastKnownLocation ?: return null
        val ageMs = (SystemClock.elapsedRealtimeNanos() - known.elapsedRealtimeNanos) / 1_000_000
        return if (ageMs in 0..CACHE_MAX_AGE_MS) known else null
    }

    private fun showNoFixDialog(reason: String) {
        val center = kakaoMap?.cameraPosition?.position
        val builder = AlertDialog.Builder(this)
            .setTitle("현재 위치를 보낼 수 없습니다")
            .setNegativeButton("취소", null)
        if (center == null) {
            builder.setMessage(reason).show()
            return
        }
        builder.setMessage("$reason\n\n대신 지도에서 고른 위치(화면 중앙)를 보낼 수 있습니다. 이 경우 \"현재 위치\"가 아니라 \"지도에서 고른 위치\"로 표시됩니다.")
            .setPositiveButton("지도에서 고른 위치 공유") { _, _ ->
                startShareChooser("지도에서 고른 위치입니다:", center.latitude, center.longitude)
            }
            .show()
    }

    private fun startShareChooser(intro: String, lat: Double, lng: Double) {
        val mapLink = "https://map.kakao.com/link/map/" + String.format(java.util.Locale.US, "%.6f,%.6f", lat, lng)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "$intro\n$mapLink")
        }
        try {
            startActivity(Intent.createChooser(send, "위치 공유"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "위치를 공유할 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val listPermissionsNeeded = ArrayList<String>()
        for (permission in permissions) {
            if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(permission)
            }
        }

        if (listPermissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                listPermissionsNeeded.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            var allGranted = true
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false
                }
            }
            if (allGranted) {
                moveToCurrentLocation()
            } else {
                Toast.makeText(this, "필요한 권한이 거부되어 일부 기능이 제한됩니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
