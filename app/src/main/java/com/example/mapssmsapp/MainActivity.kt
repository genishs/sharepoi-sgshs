package com.example.mapssmsapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private var kakaoMap: KakaoMap? = null
    private var currentLocationLabel: Label? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLocation: Location? = null

    // Default Fallback Coords: Magoknaru Station (37.5667, 126.8273)
    private val DEFAULT_LAT = 37.5667
    private val DEFAULT_LNG = 126.8273

    private lateinit var etPhoneNumber: EditText
    private lateinit var btnSendSms: Button
    private lateinit var btnRoadview: Button
    private lateinit var btnRestroom: Button
    private lateinit var btnZoomIn: Button
    private lateinit var btnZoomOut: Button
    private lateinit var btnMyLocation: Button

    private val PERMISSION_REQUEST_CODE = 1000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize Kakao SDK before setContentView
        KakaoMapSdk.init(this, BuildConfig.KAKAO_MAP_API_KEY)
        
        setContentView(R.layout.activity_main)

        etPhoneNumber = findViewById(R.id.etPhoneNumber)
        btnSendSms = findViewById(R.id.btnSendSms)
        btnRoadview = findViewById(R.id.btnRoadview)
        btnRestroom = findViewById(R.id.btnRestroom)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnZoomOut = findViewById(R.id.btnZoomOut)
        btnMyLocation = findViewById(R.id.btnMyLocation)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        mapView = findViewById(R.id.mapView)
        mapView.start(object : MapLifeCycleCallback() {
            override fun onMapDestroy() {}

            override fun onMapError(error: Exception?) {
                Toast.makeText(this@MainActivity, "지도를 로드하는 중 오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
            }
        }, object : KakaoMapReadyCallback() {
            override fun onMapReady(map: KakaoMap) {
                kakaoMap = map
                getCurrentLocationAndMark()
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
            getCurrentLocationAndMark()
        }

        // Restroom Search Listener (4-in-1 multi-source search)
        btnRestroom.setOnClickListener {
            fetchNearbyRestroomsMultiSource()
        }

        // Roadview Listener with Seoul fallback
        btnRoadview.setOnClickListener {
            val loc = currentLocation
            val lat = loc?.latitude ?: DEFAULT_LAT
            val lng = loc?.longitude ?: DEFAULT_LNG
            
            val finalLat = if (lat < 33.0 || lat > 39.0 || lng < 124.0 || lng > 132.0) DEFAULT_LAT else lat
            val finalLng = if (lat < 33.0 || lat > 39.0 || lng < 124.0 || lng > 132.0) DEFAULT_LNG else lng

            val roadviewUrl = "https://map.kakao.com/link/roadview/$finalLat,$finalLng"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(roadviewUrl))
            startActivity(intent)
        }

        btnSendSms.setOnClickListener {
            val phone = etPhoneNumber.text.toString().trim()
            if (phone.isEmpty()) {
                Toast.makeText(this, "전화번호를 입력해 주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendLocationSms(phone)
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

    private fun getCurrentLocationAndMark() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            showLocationOnMap(DEFAULT_LAT, DEFAULT_LNG)
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                currentLocation = location
                val lat = if (location.latitude < 33.0 || location.latitude > 39.0) DEFAULT_LAT else location.latitude
                val lng = if (location.longitude < 124.0 || location.longitude > 132.0) DEFAULT_LNG else location.longitude
                showLocationOnMap(lat, lng)
            } else {
                showLocationOnMap(DEFAULT_LAT, DEFAULT_LNG)
            }
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

    private fun fetchNearbyRestroomsMultiSource() {
        val loc = currentLocation
        val lat = loc?.latitude ?: DEFAULT_LAT
        val lng = loc?.longitude ?: DEFAULT_LNG

        Toast.makeText(this, "4가지 데이터 소스(공중/개방/관공서/주유소) 통합 검색 중...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val allDocuments = JSONArray()
                val seenIds = HashSet<String>()

                val searchUrls = listOf(
                    "https://dapi.kakao.com/v2/local/search/keyword.json?query=${URLEncoder.encode("공중화장실", "UTF-8")}&x=$lng&y=$lat&radius=2000&sort=distance",
                    "https://dapi.kakao.com/v2/local/search/keyword.json?query=${URLEncoder.encode("개방화장실", "UTF-8")}&x=$lng&y=$lat&radius=2000&sort=distance",
                    "https://dapi.kakao.com/v2/local/search/category.json?category_group_code=PO3&x=$lng&y=$lat&radius=2000&sort=distance",
                    "https://dapi.kakao.com/v2/local/search/category.json?category_group_code=OL7&x=$lng&y=$lat&radius=2000&sort=distance"
                )

                val kaHeader = "sdk/2.14.1 os/android-34 origin/X8P0djq2A0FbvV77Y1eC1EpJDW8= android_pkg/com.example.mapssmsapp"

                for (urlString in searchUrls) {
                    try {
                        val url = URL(urlString)
                        val conn = url.openConnection() as HttpURLConnection
                        conn.requestMethod = "GET"
                        conn.setRequestProperty("Authorization", "KakaoAK ${BuildConfig.KAKAO_REST_API_KEY}")
                        conn.setRequestProperty("KA", kaHeader)

                        if (conn.responseCode == 200) {
                            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                            val jsonObject = JSONObject(responseText)
                            val docs = jsonObject.getJSONArray("documents")

                            for (i in 0 until docs.length()) {
                                val item = docs.getJSONObject(i)
                                val id = item.optString("id", "")
                                val name = item.optString("place_name", "")
                                val key = if (id.isNotEmpty()) id else name

                                if (!seenIds.contains(key)) {
                                    seenIds.add(key)
                                    allDocuments.put(item)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                runOnUiThread {
                    displayRestroomMarkers(allDocuments)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "네트워크 오류: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun displayRestroomMarkers(documents: JSONArray) {
        val map = kakaoMap ?: return
        val count = documents.length()
        if (count == 0) {
            Toast.makeText(this, "주변 2km 이내에 검색된 화장실이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val labelManager = map.labelManager ?: return
        
        // Use custom layer with Z-Order 10000 so pins render ON TOP of 3D map tiles
        val layer = labelManager.getLayer("restroomLayer")
            ?: labelManager.addLayer(LabelLayerOptions.from("restroomLayer").setZOrder(10000))

        val bitmap = getBitmapFromVector(this, R.drawable.restroom_marker)
        val styles = labelManager.addLabelStyles(
            LabelStyles.from(LabelStyle.from(bitmap).setAnchorPoint(0.5f, 0.5f))
        )

        for (i in 0 until count) {
            val item = documents.getJSONObject(i)
            val id = item.optString("id", "restroom_$i")
            val itemLat = item.getString("y").toDouble()
            val itemLng = item.getString("x").toDouble()

            val position = LatLng.from(itemLat, itemLng)
            val options = LabelOptions.from("restroom_label_$id", position)
                .setStyles(styles)
            layer?.addLabel(options)
        }

        // Center camera on the first restroom
        val firstItem = documents.getJSONObject(0)
        val firstLat = firstItem.getString("y").toDouble()
        val firstLng = firstItem.getString("x").toDouble()
        map.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(firstLat, firstLng), 15))

        Toast.makeText(this, "통합 검색 완료! 총 ${count}개의 화장실(초록색 핀)을 지도 최상단에 표시했습니다.", Toast.LENGTH_LONG).show()
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

    private fun sendLocationSms(phoneNumber: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "SMS 전송 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val location = currentLocation
        val lat = location?.latitude ?: DEFAULT_LAT
        val lng = location?.longitude ?: DEFAULT_LNG

        val mapLink = "https://map.kakao.com/link/map/$lat,$lng"
        val message = "[내 위치 전송]\n현재 제 위치는 이곳입니다:\n$mapLink"

        try {
            val smsManager: SmsManager = this.getSystemService(SmsManager::class.java)
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            Toast.makeText(this, "SMS 전송 성공!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "SMS 전송 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS
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
                getCurrentLocationAndMark()
            } else {
                Toast.makeText(this, "필요한 권한이 거부되어 일부 기능이 제한됩니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
