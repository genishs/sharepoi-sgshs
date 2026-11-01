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
import android.graphics.Paint
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.KakaoMapSdk
import com.kakao.vectormap.MapLogger
import com.kakao.vectormap.MapAuthException
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.Label
import com.kakao.vectormap.label.LabelLayer
import com.kakao.vectormap.label.LabelLayerOptions
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import com.kakao.vectormap.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private var kakaoMap: KakaoMap? = null
    private var currentLocationLabel: Label? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // Fallback location when GPS is unavailable: Magoknaru Station (37.5667, 126.8273)
    private val DEFAULT_LAT = 37.5667
    private val DEFAULT_LNG = 126.8273

    // 공유 위치 확인·마지막 실제 위치를 화면 재생성(회전) 너머로 이어 간다(#29)
    private lateinit var shareViewModel: LocationShareViewModel

    /** 설치된 앱의 서명 키 해시. KA 헤더에 쓴다(검색 스레드에서도 읽으므로 lazy 는 동기화 기본값). */
    private val keyHash: String by lazy { computeKeyHash() }

    /** 지도 SDK 가 오류를 냈는지. 이때는 「지도에서 고른 위치」를 권하지 않는다. */
    private var mapFailed = false

    /** 진행 중인 주변 검색. 끝나기 전에 다시 누르면 새 요청을 보내지 않는다(#18). */
    private var searchJob: Job? = null

    /** 검색 핀 모양(아이콘·색별). 지도마다 한 번만 등록한다. */
    private val markerStyles = HashMap<String, LabelStyles>()

    /** 🎯 에서 「위치 켜기」 시스템 창(#18). 켜면 바로 현재 위치를 다시 찾는다. */
    private val enableLocationLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) moveToCurrentLocation(LocateTrigger.USER)
        }

    /** 권한·위치 설정 화면을 열었으면 돌아왔을 때 현재 위치를 다시 찾는다(#30). */
    private var locateOnResume = false

    private lateinit var btnShare: Button
    private lateinit var btnRoadview: Button
    private lateinit var btnRestroom: Button
    private lateinit var btnPlaces: Button
    private lateinit var btnZoomIn: Button
    private lateinit var btnZoomOut: Button
    private lateinit var btnMyLocation: Button

    private val PERMISSION_REQUEST_CODE = 1000

    private companion object {
        const val TAG = "MainActivity"

        /** 검색 핀을 올리는 레이어. 새 검색 결과가 오면 비우고 다시 채운다(#18). */
        const val SEARCH_LAYER = "searchLayer"

        // 대화상자 태그(같은 태그는 하나만 뜬다)
        const val DLG_PLACE = "place_detail"
        const val DLG_LOCATION = "location_guide"
        const val DLG_MAP_ERROR = "map_error"
        const val DLG_SEARCH_FAILED = "search_failed"
        const val DLG_CATEGORY = "category_picker"
        const val DLG_NO_FIX = "no_fix"

        // 대화상자 결과에 실어 보내는 값
        const val P_LAT = "lat"
        const val P_LNG = "lng"
        const val P_PHONE = "phone"
        const val P_TEXT = "text"
        const val P_SEARCH = "search"

        /** 「정확한 위치를 켜면 더 정확해요」 안내를 이미 보였다는 표시 파일(noBackupFilesDir) */
        const val PRECISE_HINT_MARKER = "precise_location_hint_shown"
    }

    /**
     * The Kakao Maps SDK logs from native code (tag K3fAApi: app key header, API URLs, map
     * coordinates) even in release builds, which R8 cannot strip (#35). Its own switch turns
     * that off; the library is loaded by [KakaoMapSdk.init], so call this right after it.
     */
    private fun silenceSdkLogsInRelease() {
        if (BuildConfig.DEBUG) return
        try {
            MapLogger.setLogEnable(false)
            MapLogger.setCommonLogEnable(false)
            MapLogger.setLabelLogEnable(false)
            MapLogger.setRouteLogEnable(false)
            MapLogger.setShapeLogEnable(false)
            MapLogger.setMapWidgetLogEnable(false)
            MapLogger.setCameraLogEnable(false)
        } catch (e: Throwable) {
            // Not fatal: the SDK just keeps logging.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize Kakao SDK before setContentView
        KakaoMapSdk.init(this, BuildConfig.KAKAO_MAP_API_KEY)
        silenceSdkLogsInRelease()

        // 키 해시는 카카오 KA 헤더(buildKaHeader)에 쓰는 기능이라 계산은 그대로 하되, 로그는 디버그 빌드에서만 남긴다.
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "signing key hash=$keyHash pkg=$packageName")
        }

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

        shareViewModel = ViewModelProvider(this)[LocationShareViewModel::class.java]
        shareViewModel.sharing.observe(this) { inProgress -> btnShare.isEnabled = !inProgress }
        // 회전 전에 시작한 공유의 결과도 여기로 온다. 받으면 바로 지워 한 번만 처리한다.
        shareViewModel.shareResult.observe(this) { result ->
            if (result != null) {
                shareViewModel.consumeShareResult()
                handleShareResult(result)
            }
        }

        // 대화상자(AppDialog) 버튼 결과. 화면이 다시 만들어져도 새 화면이 받는다.
        supportFragmentManager.setFragmentResultListener(AppDialog.REQUEST_KEY, this) { _, bundle ->
            val action = bundle.getString(AppDialog.RESULT_ACTION)
                ?.let { name -> AppDialog.Action.entries.firstOrNull { it.name == name } } ?: return@setFragmentResultListener
            onDialogAction(action, bundle.getBundle(AppDialog.RESULT_PAYLOAD) ?: Bundle(), bundle.getInt(AppDialog.RESULT_WHICH, -1))
        }

        mapView = findViewById(R.id.mapView)
        mapView.start(object : MapLifeCycleCallback() {
            override fun onMapDestroy() {}

            override fun onMapError(error: Exception?) {
                mapFailed = true
                if (isFinishing || isDestroyed) return
                if (AppDialog.isShowing(supportFragmentManager, DLG_MAP_ERROR)) return
                if (BuildConfig.DEBUG) {
                    // 개발용: 패키지명·키 해시·서명자·스택을 보여 카카오 콘솔 등록값과 맞춰 볼 수 있게 한다.
                    val diagnosticText = buildAuthDiagnosticText(error)
                    Log.e(TAG, "Kakao map error diagnostic:\n$diagnosticText", error)
                    showAuthDiagnosticDialog(diagnosticText)
                } else {
                    // 릴리스: 진단 정보(키 해시·서명자·스택) 없이 일반 안내와 다시 시도만.
                    val code = (error as? MapAuthException)?.errorCode
                    Log.w(TAG, "Kakao map error: ${error?.javaClass?.simpleName} code=$code")
                    showMapErrorDialog(SearchErrors.classifyMapError(code))
                }
            }
        }, object : KakaoMapReadyCallback() {
            override fun onMapReady(map: KakaoMap) {
                kakaoMap = map
                mapFailed = false
                markerStyles.clear()
                // 지도가 떴으면 앞서 남은 지도 오류 안내는 맞지 않으므로 닫는다.
                if (!supportFragmentManager.isStateSaved) {
                    (supportFragmentManager.findFragmentByTag(DLG_MAP_ERROR) as? androidx.fragment.app.DialogFragment)
                        ?.dismissAllowingStateLoss()
                }
                moveToCurrentLocation()

                // 검색 핀(화장실·편의시설)을 누르면 상세를 띄운다.
                map.setOnLabelClickListener { _, _, label ->
                    val item = label.tag as? JSONObject
                    if (item != null) {
                        showPlaceDetail(item)
                        true
                    } else {
                        false
                    }
                }
            }
        })

        // 회전 등으로 다시 만들어질 때는 묻지 않는다(진행 중인 권한 창의 결과는 새 화면으로 온다).
        if (savedInstanceState == null) {
            checkAndRequestPermissions()
        }

        // Zoom Control Listeners
        btnZoomIn.setOnClickListener {
            kakaoMap?.moveCamera(CameraUpdateFactory.zoomIn())
        }

        btnZoomOut.setOnClickListener {
            kakaoMap?.moveCamera(CameraUpdateFactory.zoomOut())
        }

        btnMyLocation.setOnClickListener {
            moveToCurrentLocation(LocateTrigger.USER)
        }

        // 화장실 통합 검색(화장실 키워드 2종 + 공공기관·주유소)
        btnRestroom.setOnClickListener {
            startSearch(SearchCatalog.Request.Restroom)
        }

        // Nearby places (편의시설) search: pick a category, then search
        btnPlaces.setOnClickListener {
            showPlaceCategoryDialog()
        }

        // 지도 중심(지도가 없으면 기본 좌표)의 로드뷰
        btnRoadview.setOnClickListener {
            val cameraPos = kakaoMap?.cameraPosition?.position
            val lat = cameraPos?.latitude ?: DEFAULT_LAT
            val lng = cameraPos?.longitude ?: DEFAULT_LNG
            openRoadview(lat, lng)
        }

        btnShare.setOnClickListener {
            shareLocation()
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.resume()
        // 권한·위치 설정 화면에서 돌아왔으면 한 번 다시 현재 위치를 찾아 본다(#30).
        if (locateOnResume) {
            locateOnResume = false
            if (kakaoMap != null && (hasFineLocation() || hasCoarseLocation())) moveToCurrentLocation(LocateTrigger.RETURNED_FROM_SETTINGS)
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.pause()
    }

    /**
     * 실제 기기 위치(FusedLocationProvider)로 지도를 옮기고 현재 위치 마커를 찍는다.
     * 위치를 얻지 못하면(권한 없음·위치 꺼짐·조회 실패) 마커를 찍지 않는다(#30) — 기본 좌표는 현재 위치가 아니다.
     * 못 얻었을 때의 동작은 [trigger] 에 따른다:
     * - [LocateTrigger.USER](🎯 버튼): 권한 또는 위치 설정 안내 대화상자만 띄우고 지도는 그대로 둔다.
     * - [LocateTrigger.START](지도 준비·권한 응답): 처음 보이는 지역만 기본 좌표(마곡나루역 부근)로 두고 짧게 알린다.
     * - [LocateTrigger.RETURNED_FROM_SETTINGS]: 아무것도 하지 않는다(안내를 되풀이하지 않는다).
     * 콜백은 화면에 묶어(onStop 때 해제) 회전으로 사라진 화면·지도에 닿지 않게 한다.
     */
    private fun moveToCurrentLocation(trigger: LocateTrigger = LocateTrigger.START) {
        val hasFine = hasFineLocation()
        if (!hasFine && !hasCoarseLocation()) {
            when (trigger) {
                LocateTrigger.USER -> showLocationPermissionGuide()
                LocateTrigger.START -> showDefaultArea()
                LocateTrigger.RETURNED_FROM_SETTINGS -> Unit
            }
            return
        }

        val onNoFix = {
            when (trigger) {
                LocateTrigger.USER -> showLocationUnavailableGuide()
                LocateTrigger.START -> {
                    Toast.makeText(this, "현재 위치를 확인하지 못해 기본 지역(마곡나루역 부근)을 보여 줍니다.", Toast.LENGTH_SHORT).show()
                    showDefaultArea()
                }
                LocateTrigger.RETURNED_FROM_SETTINGS -> Unit
            }
        }

        // 대략적 위치만 허용됐어도 그 위치로 옮긴다(#11). 요청은 HIGH_ACCURACY 로 하고, 대략적 위치 권한이면
        // FLP 가 결과를 대략적 수준으로 낮춰 준다(BALANCED 는 네트워크 위치가 없으면 시간 안에 위치를 못 준다).
        val cts = CancellationTokenSource()
        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener(this) { location ->
                    if (location != null) {
                        rememberFix(location, hasFine)
                        showLocationOnMap(location.latitude, location.longitude)
                    } else {
                        // No fresh fix (e.g. GPS off) — try the cached one before giving up
                        fusedLocationClient.lastLocation
                            .addOnSuccessListener(this) { cached ->
                                if (cached != null) {
                                    rememberFix(cached, hasFine)
                                    showLocationOnMap(cached.latitude, cached.longitude)
                                } else {
                                    onNoFix()
                                }
                            }
                            .addOnFailureListener(this) { onNoFix() }
                    }
                }
                .addOnFailureListener(this) { onNoFix() }
        } catch (e: SecurityException) {
            onNoFix()
        }
    }

    /** 현재 위치를 찾게 된 계기. 위치를 못 얻었을 때 어떻게 알릴지 정한다. */
    private enum class LocateTrigger { START, USER, RETURNED_FROM_SETTINGS }

    /** 현재 위치 없이 처음 보일 지역(기본 좌표)으로 카메라만 옮긴다. 현재 위치 마커는 찍지 않는다. */
    private fun showDefaultArea() {
        kakaoMap?.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(DEFAULT_LAT, DEFAULT_LNG), 16))
    }

    /** 🎯: 위치 권한이 없을 때. 시스템이 다시 물어볼 수 있으면 권한 창을, 아니면 앱 설정을 연다. */
    private fun showLocationPermissionGuide() {
        val canAsk = ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_COARSE_LOCATION) ||
            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (canAsk) {
            AppDialog.show(
                supportFragmentManager, DLG_LOCATION, "위치 권한이 필요합니다",
                "위치 권한이 없어 현재 위치를 표시할 수 없습니다.\n내 위치를 보려면 위치 권한을 허용해 주세요.",
                positive = AppDialog.Button("권한 허용", AppDialog.Action.REQUEST_PERMISSION),
                negative = AppDialog.Button("닫기")
            )
        } else {
            AppDialog.show(
                supportFragmentManager, DLG_LOCATION, "위치 권한이 필요합니다",
                "위치 권한이 없어 현재 위치를 표시할 수 없습니다.\n설정 > 권한 > 위치에서 「앱 사용 중에만 허용」을 골라 주세요.",
                positive = AppDialog.Button("설정 열기", AppDialog.Action.OPEN_APP_SETTINGS),
                negative = AppDialog.Button("닫기")
            )
        }
    }

    /** 🎯: 권한은 있는데 위치를 얻지 못했을 때. 기기 위치가 꺼져 있으면 위치 켜기를, 아니면 다시 시도를 권한다. */
    private fun showLocationUnavailableGuide() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val locationOn = locationManager == null || LocationManagerCompat.isLocationEnabled(locationManager)
        if (locationOn) {
            AppDialog.show(
                supportFragmentManager, DLG_LOCATION, "현재 위치를 확인할 수 없습니다",
                "현재 위치를 확인하지 못했습니다.\n실내·지하에서는 시간이 걸릴 수 있습니다. 잠시 후 다시 시도해 주세요.",
                positive = AppDialog.Button("다시 시도", AppDialog.Action.RETRY_LOCATE),
                negative = AppDialog.Button("닫기")
            )
        } else {
            AppDialog.show(
                supportFragmentManager, DLG_LOCATION, "현재 위치를 확인할 수 없습니다",
                "기기의 위치가 꺼져 있어 현재 위치를 확인할 수 없습니다.\n위치를 켜 주세요.",
                positive = AppDialog.Button("위치 켜기", AppDialog.Action.ENABLE_LOCATION),
                negative = AppDialog.Button("닫기")
            )
        }
    }

    /**
     * 기기 위치 켜기(#18). Play 서비스의 「위치 켜기」 시스템 창을 먼저 띄우고, 띄울 수 없으면 위치 설정 화면을 연다.
     * 권한 목록은 그대로다(위치 설정 확인은 권한이 필요 없다).
     */
    private fun requestEnableLocation() {
        val request = LocationSettingsRequest.Builder()
            .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L).build())
            .setAlwaysShow(true)
            .build()
        LocationServices.getSettingsClient(this).checkLocationSettings(request)
            .addOnSuccessListener(this) { moveToCurrentLocation(LocateTrigger.USER) }
            .addOnFailureListener(this) { e ->
                if (e is ResolvableApiException) {
                    try {
                        enableLocationLauncher.launch(IntentSenderRequest.Builder(e.resolution).build())
                        return@addOnFailureListener
                    } catch (ignored: Exception) {
                        // 아래에서 위치 설정 화면으로
                    }
                }
                openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
    }

    private fun openSettings(intent: Intent) {
        try {
            locateOnResume = true
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            locateOnResume = false
            Toast.makeText(this, "설정 화면을 열 수 없습니다. 기기 설정에서 직접 바꿔 주세요.", Toast.LENGTH_LONG).show()
        }
    }

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasCoarseLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun rememberFix(location: Location, precise: Boolean) {
        shareViewModel.rememberFix(location, precise)
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
                LabelStyles.from(LabelStyle.from(bitmap).setAnchorPoint(0.5f, 0.5f).setApplyDpScale(false))
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
     * 지도 인증 오류(onMapError) 진단 정보를 사람이 읽을 수 있는 텍스트로 조립한다. 디버그 빌드 전용.
     */
    private fun buildAuthDiagnosticText(error: Exception?): String {
        val signers = computeAllSignerHashes().joinToString(", ")
        val stackLines = error?.stackTrace
            ?.take(3)
            ?.joinToString("\n") { "\tat $it" }
            ?: ""

        return buildString {
            append("pkg=$packageName\n")
            append("keyHash=$keyHash\n")
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
     * 지도 인증 오류 진단 대화상자(디버그 빌드 전용). 본문은 길게 눌러 고를 수 있고, 「복사」 버튼으로 전체를 복사한다.
     */
    private fun showAuthDiagnosticDialog(diagnosticText: String) {
        AppDialog.show(
            supportFragmentManager, DLG_MAP_ERROR, "지도 인증 오류", diagnosticText,
            positive = AppDialog.Button("복사", AppDialog.Action.COPY_DIAGNOSTIC),
            neutral = AppDialog.Button("다시 시도", AppDialog.Action.RETRY_MAP),
            negative = AppDialog.Button("닫기"),
            payload = bundleOf(P_TEXT to diagnosticText),
            selectable = true
        )
    }

    /**
     * 릴리스 빌드의 지도 오류 안내. 진단 정보 없이 원인 갈래(연결·한도·그 밖)만 알리고 다시 시도를 준다.
     * 다시 시도는 화면을 새로 만들어 지도 SDK 를 처음부터 다시 시작한다.
     */
    private fun showMapErrorDialog(kind: FailureKind) {
        AppDialog.show(
            supportFragmentManager, DLG_MAP_ERROR, "지도를 불러오지 못했습니다", SearchErrors.mapFailureMessage(kind),
            positive = AppDialog.Button("다시 시도", AppDialog.Action.RETRY_MAP),
            negative = AppDialog.Button("닫기")
        )
    }

    /**
     * 서명 키 해시(keyHash)를 이용해 Kakao KA 헤더를 조립한다. Local API 는 네이티브 앱 키(KakaoAK)와
     * 이 헤더의 키 해시·패키지명으로 인증된다(카카오 콘솔에 등록한 Android 플랫폼 정보와 맞아야 한다).
     */
    private fun buildKaHeader(): String {
        // 지도 SDK 의존성과 같은 버전(app/build.gradle.kts 의 kakaoMapSdkVersion, #19)
        val sdkVersion = BuildConfig.KAKAO_MAP_SDK_VERSION
        val osVersion = "android-${Build.VERSION.SDK_INT}"
        val pkgName = packageName
        val origin = keyHash

        return "sdk/$sdkVersion os/$osVersion origin/$origin android_pkg/$pkgName"
    }

    /**
     * 지도 중심 주변을 검색한다(#18). 앞 검색이 끝나기 전에 다시 누르면(연타) 새 요청을 보내지 않는다.
     * 요청은 화면 밖([LocalSearchClient], IO 스레드)에서 돌고, 화면이 닫히면 lifecycleScope 와 함께 취소되어
     * 사라진 화면·지도에 닿지 않는다. 결과는 화면이 보일 때(STARTED) 보인다.
     */
    private fun startSearch(request: SearchCatalog.Request) {
        if (searchJob?.isActive == true) {
            Toast.makeText(this, "앞의 검색이 아직 진행 중입니다. 잠시만 기다려 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val cameraPos = kakaoMap?.cameraPosition?.position
        val lat = cameraPos?.latitude ?: DEFAULT_LAT
        val lng = cameraPos?.longitude ?: DEFAULT_LNG

        val searching = when (request) {
            SearchCatalog.Request.Restroom -> "지도 중심 주변 화장실(공중·개방 화장실, 공공기관·주유소) 검색 중..."
            is SearchCatalog.Request.Category -> "지도 중심 주변 ${request.label} 검색 중..."
        }
        Toast.makeText(this, searching, Toast.LENGTH_SHORT).show()

        val client = LocalSearchClient(BuildConfig.KAKAO_MAP_API_KEY, buildKaHeader())
        searchJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { client.search(request, lat, lng) }
            withStarted { showSearchResult(request, result) }
        }
    }

    /**
     * 검색 결론을 화면에 보인다. 모든 요청이 성공했는데 결과가 없을 때만 「주변에 없음」으로 안내하고,
     * 실패(네트워크·인증·한도·서비스 오류)는 원인별 안내와 다시 시도를 보인다.
     * 새 결과(또는 결과 없음)가 오면 앞 검색의 핀을 지운다. 실패하면 앞 핀을 그대로 둔다.
     */
    private fun showSearchResult(request: SearchCatalog.Request, result: LocalSearchClient.Result) {
        if (isFinishing || isDestroyed) return
        val categoryLabel = request.label
        when (val summary = result.summary) {
            is SearchSummary.Failed ->
                AppDialog.show(
                    supportFragmentManager, DLG_SEARCH_FAILED, "검색하지 못했습니다",
                    SearchErrors.searchFailureMessage(summary.cause, categoryLabel),
                    positive = AppDialog.Button("다시 시도", AppDialog.Action.RETRY_SEARCH),
                    negative = AppDialog.Button("닫기"),
                    payload = bundleOf(P_SEARCH to request.key)
                )
            SearchSummary.NoResults -> {
                clearSearchMarkers()
                Toast.makeText(this, "주변 2km 이내에 검색된 ${categoryLabel} 정보가 없습니다.", Toast.LENGTH_SHORT).show()
            }
            is SearchSummary.Found -> {
                val shown = displayMarkers(result.documents, SearchCatalog.markerColor(request))
                if (shown == 0) {
                    Toast.makeText(this, "지도를 불러오지 못해 검색 결과를 표시할 수 없습니다.", Toast.LENGTH_LONG).show()
                    return
                }
                val note = summary.partialFailure?.let { "\n" + SearchErrors.partialFailureNote(it) } ?: ""
                Toast.makeText(this, "검색 완료! 총 ${shown}개의 ${categoryLabel}을(를) 표시했습니다. 핀을 터치해 상세 정보를 확인하세요!$note", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun searchLayer(create: Boolean): LabelLayer? {
        val labelManager = kakaoMap?.labelManager ?: return null
        return labelManager.getLayer(SEARCH_LAYER)
            ?: if (create) labelManager.addLayer(LabelLayerOptions.from(SEARCH_LAYER).setZOrder(10000)) else null
    }

    /** 앞 검색의 핀을 모두 지운다. */
    private fun clearSearchMarkers() {
        searchLayer(create = false)?.removeAll()
    }

    /**
     * 앞 검색의 핀을 지우고 장소 핀을 올린다. 핀은 장소 종류 아이콘(🚽·🏛️·⛽·☕ …)을 [color] 원 안에 그린다.
     * 올린 핀 수를 돌려준다(지도가 없으면 0).
     */
    private fun displayMarkers(documents: List<JSONObject>, color: Int): Int {
        val map = kakaoMap ?: return 0
        val labelManager = map.labelManager ?: return 0
        val layer = searchLayer(create = true) ?: return 0
        layer.removeAll()

        var shown = 0
        documents.forEachIndexed { i, item ->
            val itemLat = item.optString("y").toDoubleOrNull()
            val itemLng = item.optString("x").toDoubleOrNull()
            if (itemLat == null || itemLng == null) return@forEachIndexed
            val id = item.optString("id", "item_$i")
            val icon = item.optString(LocalSearchClient.KEY_ICON, "📍")

            val styleKey = "marker_${Integer.toHexString(color)}_$icon"
            val styles = markerStyles[styleKey]
                ?: labelManager.addLabelStyles(
                    LabelStyles.from(
                        styleKey,
                        LabelStyle.from(makeMarkerBitmap(icon, color)).setAnchorPoint(0.5f, 0.5f).setApplyDpScale(false)
                    )
                )?.also { markerStyles[styleKey] = it }
                ?: return@forEachIndexed

            // 장소 JSON 을 태그로 달아 두고, 핀을 누르면 상세에 쓴다.
            val options = LabelOptions.from("${SEARCH_LAYER}_label_$id", LatLng.from(itemLat, itemLng))
                .setStyles(styles)
                .setTag(item)
            layer.addLabel(options)
            shown++
        }
        return shown
    }

    /** 검색 핀 그림: dp 기준 지름의 원(테두리 흰색) 안에 장소 종류 아이콘. 밀도에 맞춰 px 로 그린다(#18). */
    private fun makeMarkerBitmap(icon: String, color: Int): Bitmap {
        val density = resources.displayMetrics.density
        val size = SearchCatalog.dpToPx(SearchCatalog.MARKER_DP, density)
        val stroke = SearchCatalog.dpToPx(2, density).toFloat()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = size / 2f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFFFFFFFF.toInt() }
        canvas.drawCircle(radius, radius, radius, fill)
        fill.color = color
        canvas.drawCircle(radius, radius, radius - stroke, fill)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * 0.5f
            textAlign = Paint.Align.CENTER
        }
        val baseline = radius - (text.descent() + text.ascent()) / 2f
        canvas.drawText(icon, radius, baseline, text)
        return bitmap
    }

    /** 검색 핀을 눌렀을 때의 상세: 주소(도로명·지번)·전화, 로드뷰·전화 걸기 버튼. */
    private fun showPlaceDetail(item: JSONObject) {
        val icon = item.optString(LocalSearchClient.KEY_ICON, "📍")
        val name = item.optString("place_name", "장소")
        val phone = PlaceText.cleanPhone(item.optString("phone", ""))
        // 화장실 4소스·편의시설 8개 카테고리 결과가 모두 이 한 경로로 상세를 띄운다(#8).
        // 도로명 주소(road_address_name) 첫 줄 + 지번 주소(address_name) 둘째 줄, 빈 쪽은 숨김.
        val detail = PlaceText.placeDetail(
            item.optString("road_address_name", ""),
            item.optString("address_name", ""),
            phone,
            item.optString(LocalSearchClient.KEY_NOTE, "")
        )
        val lat = item.optString("y").toDoubleOrNull() ?: return
        val lng = item.optString("x").toDoubleOrNull() ?: return

        AppDialog.show(
            supportFragmentManager, DLG_PLACE, "$icon $name", detail,
            positive = AppDialog.Button("📷 로드뷰 보기", AppDialog.Action.ROADVIEW),
            neutral = if (phone.isNotEmpty()) AppDialog.Button("📞 전화 걸기", AppDialog.Action.DIAL) else null,
            negative = AppDialog.Button("닫기"),
            payload = bundleOf(P_LAT to lat, P_LNG to lng, P_PHONE to phone)
        )
    }

    /** 로드뷰 링크를 연다. 열 앱(브라우저 등)이 없으면 알리기만 한다(#18). */
    private fun openRoadview(lat: Double, lng: Double) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://map.kakao.com/link/roadview/$lat,$lng"))
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "로드뷰를 열 수 있는 앱(인터넷 브라우저)이 없습니다.", Toast.LENGTH_LONG).show()
        }
    }

    /** 장소 전화번호로 전화 앱의 다이얼 화면을 연다(바로 걸지 않는다 — 전화 권한이 필요 없다, #20). */
    private fun openDialer(phone: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "전화를 걸 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 주변 편의시설 카테고리 선택 대화상자. 선택하면 해당 카테고리를 검색한다.
     */
    private fun showPlaceCategoryDialog() {
        val labels = SearchCatalog.placeCategories.map { "${it.icon} ${it.label}" }.toTypedArray()
        AppDialog.show(
            supportFragmentManager, DLG_CATEGORY, "주변에서 찾을 편의시설을 선택하세요",
            negative = AppDialog.Button("취소"),
            items = labels,
            itemsAction = AppDialog.Action.PICK_CATEGORY
        )
    }

    /** 대화상자 버튼 결과를 처리한다. 값은 대화상자에 실어 둔 [payload] 에서 읽는다. */
    private fun onDialogAction(action: AppDialog.Action, payload: Bundle, which: Int) {
        when (action) {
            AppDialog.Action.NONE -> Unit
            AppDialog.Action.ROADVIEW -> openRoadview(payload.getDouble(P_LAT), payload.getDouble(P_LNG))
            AppDialog.Action.DIAL -> payload.getString(P_PHONE)?.takeIf { it.isNotEmpty() }?.let { openDialer(it) }
            AppDialog.Action.REQUEST_PERMISSION -> requestLocationPermissions()
            AppDialog.Action.OPEN_APP_SETTINGS ->
                openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            AppDialog.Action.RETRY_LOCATE -> moveToCurrentLocation(LocateTrigger.USER)
            AppDialog.Action.ENABLE_LOCATION -> requestEnableLocation()
            AppDialog.Action.COPY_DIAGNOSTIC -> {
                val text = payload.getString(P_TEXT).orEmpty()
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("지도 인증 오류", text))
                Toast.makeText(this, "복사됨", Toast.LENGTH_SHORT).show()
            }
            AppDialog.Action.RETRY_MAP -> {
                // 눌린 지도 오류 대화상자가 새 화면에 복원되면 새 onMapError 가 안내를 건너뛰고, 지도가 떠도
                // 옛 안내가 남는다. 다시 만들기 전에 바로 없앤다(PR #38 QA).
                AppDialog.dismissNow(supportFragmentManager, DLG_MAP_ERROR)
                recreate()
            }
            AppDialog.Action.RETRY_SEARCH -> SearchCatalog.Request.fromKey(payload.getString(P_SEARCH))?.let { startSearch(it) }
            AppDialog.Action.PICK_CATEGORY ->
                SearchCatalog.placeCategories.getOrNull(which)?.let { startSearch(SearchCatalog.Request.Category(it)) }
            AppDialog.Action.SHARE_MAP_PICKED -> shareMapCenter()
        }
    }

    /** 현재 위치 마커 등 도형 그림을 그 그림의 dp 크기(밀도에 맞춘 px)로 비트맵에 그린다. */
    private fun getBitmapFromVector(context: Context, drawableId: Int): Bitmap {
        val fallback = SearchCatalog.dpToPx(20, resources.displayMetrics.density)
        val drawable = ContextCompat.getDrawable(context, drawableId)
            ?: return Bitmap.createBitmap(fallback, fallback, Bitmap.Config.ARGB_8888)
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: fallback
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: fallback
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    /**
     * 위치 공유: 공유 시점에 위치를 다시 요청한다(최대 6초, LocationShareViewModel).
     * 정확한 위치 권한이면 「현재 위치」로, 대략적 위치 권한만 있으면 대략적 위치임을 밝혀 보낸다(#11).
     * 위치를 아예 얻지 못했을 때만 지도 중심이나 기본 좌표를 "현재 위치"로 보내지 않고,
     * 안내 대화상자에서 "지도에서 고른 위치"로 보낼지 묻는다.
     * 위치 확인 중 화면이 회전돼도 요청은 ViewModel 에서 이어지고, 새 화면이 결과를 받아 처리한다(#29).
     */
    private fun shareLocation() {
        if (shareViewModel.sharing.value == true) return
        val precise = hasFineLocation()
        if (!precise && !hasCoarseLocation()) {
            showNoFixDialog("위치 권한이 없어 현재 위치를 확인할 수 없습니다.")
            return
        }

        if (shareViewModel.startShare(precise)) {
            val checking = if (precise) "현재 위치를 확인하는 중입니다…" else "대략적인 현재 위치를 확인하는 중입니다…"
            Toast.makeText(this, checking, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleShareResult(result: LocationShareViewModel.ShareResult) {
        if (isFinishing || isDestroyed) return
        // 공유를 누르고 홈으로 나갔다가 한참 뒤 돌아왔으면 그때의 결과로 공유 시트를 띄우지 않는다(#18).
        if (!SearchCatalog.isShareResultFresh(result.createdAtMs, SystemClock.elapsedRealtime())) {
            Toast.makeText(this, "시간이 지나 위치 공유를 취소했습니다. 다시 공유하려면 「위치 공유」를 눌러 주세요.", Toast.LENGTH_LONG).show()
            return
        }
        when (result) {
            is LocationShareViewModel.ShareResult.Ready ->
                startShareChooser(result.kind, result.latitude, result.longitude)
            is LocationShareViewModel.ShareResult.NoFix ->
                showNoFixDialog("현재 위치를 확인하지 못했습니다. 위치(GPS)가 켜져 있는지 확인해 주세요.")
        }
    }

    /**
     * 현재 위치를 보낼 수 없을 때. 지도가 있으면(또는 아직 준비 중이면) 「지도에서 고른 위치」 공유를 권한다.
     * 위치는 버튼을 누를 때의 지도 중앙을 쓴다 — 화면이 다시 만들어진 직후 지도가 아직 준비되지 않았어도
     * 버튼이 빠지지 않게 한다(1.0.9 QA 관찰). 지도 자체가 오류로 뜨지 않았으면 권하지 않는다.
     */
    private fun showNoFixDialog(reason: String) {
        if (mapFailed && kakaoMap == null) {
            AppDialog.show(
                supportFragmentManager, DLG_NO_FIX, "현재 위치를 보낼 수 없습니다", reason,
                negative = AppDialog.Button("취소")
            )
            return
        }
        AppDialog.show(
            supportFragmentManager, DLG_NO_FIX, "현재 위치를 보낼 수 없습니다",
            "$reason\n\n대신 지도에서 고른 위치(화면 중앙)를 보낼 수 있습니다. 이 경우 \"현재 위치\"가 아니라 \"지도에서 고른 위치\"로 표시됩니다.",
            positive = AppDialog.Button("지도에서 고른 위치 공유", AppDialog.Action.SHARE_MAP_PICKED),
            negative = AppDialog.Button("취소")
        )
    }

    /** 「지도에서 고른 위치 공유」: 지금 지도 중앙을 보낸다. 지도가 아직 없으면 잠시 뒤 다시 하도록 알린다. */
    private fun shareMapCenter() {
        val center = kakaoMap?.cameraPosition?.position
        if (center == null) {
            Toast.makeText(this, "지도가 아직 준비되지 않았습니다. 잠시 후 다시 「위치 공유」를 눌러 주세요.", Toast.LENGTH_LONG).show()
            return
        }
        startShareChooser(ShareKind.MAP_PICKED, center.latitude, center.longitude)
    }

    private fun startShareChooser(kind: ShareKind, lat: Double, lng: Double) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, PlaceText.shareText(kind, lat, lng))
        }
        try {
            startActivity(Intent.createChooser(send, "위치 공유"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "위치를 공유할 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 위치 권한이 하나도 없을 때만 묻는다(Android 12+ 권한 창에서 정확/대략을 고를 수 있다).
     * 대략적 위치만 허용된 상태면 정확한 위치를 다시 조르지 않고, 더 정확해질 수 있다는 안내만 한 번 한다(#11).
     */
    private fun checkAndRequestPermissions() {
        if (hasFineLocation()) return
        if (hasCoarseLocation()) {
            showPreciseLocationHintOnce()
            return
        }
        requestLocationPermissions()
    }

    private fun requestLocationPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            PERMISSION_REQUEST_CODE
        )
    }

    /**
     * 대략적 위치만 허용됐을 때 정확한 위치를 켜면 더 정확하다는 짧은 안내. 설치 후 한 번만 보인다.
     * 표시 여부는 백업에서 빠지는 noBackupFilesDir 의 빈 표시 파일로만 기억한다(기기 밖으로 나가지 않음).
     */
    private fun showPreciseLocationHintOnce() {
        val marker = File(noBackupFilesDir, PRECISE_HINT_MARKER)
        if (marker.exists()) return
        try {
            marker.createNewFile()
        } catch (e: IOException) {
            // 기억하지 못하면 다음 실행 때 한 번 더 보일 뿐이다.
        }
        Toast.makeText(this, "대략적인 위치로 표시합니다. 설정에서 「정확한 위치」를 켜면 더 정확해요.", Toast.LENGTH_LONG).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE) return
        when {
            hasFineLocation() -> moveToCurrentLocation()
            hasCoarseLocation() -> {
                // 대략적 위치만 허용: 거부가 아니다. 대략적 현재 위치로 옮기고 안내는 한 번만.
                moveToCurrentLocation()
                showPreciseLocationHintOnce()
            }
            grantResults.isEmpty() -> Unit // 요청이 중간에 끊김(화면 재생성 등) — 거부로 보지 않는다
            else -> Toast.makeText(this, "위치 권한이 거부되어 현재 위치 표시와 위치 공유가 제한됩니다.", Toast.LENGTH_SHORT).show()
        }
    }
}
