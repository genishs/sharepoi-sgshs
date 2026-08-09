package com.example.mapssmsapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.KakaoMapSdk
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.Label
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

        // Restroom Search Listener
        btnRestroom.setOnClickListener {
            fetchNearbyRestrooms()
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

        val label = currentLocationLabel
        if (label == null) {
            val styles = map.labelManager?.addLabelStyles(
                LabelStyles.from(LabelStyle.from(R.drawable.current_location_marker).setAnchorPoint(0.5f, 0.5f))
            )
            val options = LabelOptions.from(position).setStyles(styles)
            currentLocationLabel = map.labelManager?.layer?.addLabel(options)
        } else {
            label.moveTo(position)
        }
    }

    private fun fetchNearbyRestrooms() {
        val loc = currentLocation
        val lat = loc?.latitude ?: DEFAULT_LAT
        val lng = loc?.longitude ?: DEFAULT_LNG

        Toast.makeText(this, "주변 2km 내 화장실을 검색 중입니다...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val query = URLEncoder.encode("화장실", "UTF-8")
                val urlString = "https://dapi.kakao.com/v2/local/search/keyword.json?query=$query&x=$lng&y=$lat&radius=2000&sort=distance"
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "KakaoAK ${BuildConfig.KAKAO_REST_API_KEY}")

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val inputStream = conn.inputStream
                    val responseText = inputStream.bufferedReader().use { it.readText() }
                    val jsonObject = JSONObject(responseText)
                    val documents = jsonObject.getJSONArray("documents")

                    runOnUiThread {
                        displayRestroomMarkers(documents)
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "화장실 검색 실패 ($responseCode)", Toast.LENGTH_SHORT).show()
                    }
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
        val layer = labelManager.layer ?: return

        val styles = labelManager.addLabelStyles(
            LabelStyles.from(LabelStyle.from(R.drawable.restroom_marker).setAnchorPoint(0.5f, 0.5f))
        )

        for (i in 0 until count) {
            val item = documents.getJSONObject(i)
            val itemLat = item.getString("y").toDouble()
            val itemLng = item.getString("x").toDouble()

            val position = LatLng.from(itemLat, itemLng)
            val options = LabelOptions.from(position).setStyles(styles)
            layer.addLabel(options)
        }

        Toast.makeText(this, "주변 ${count}개의 화장실 위치(초록색 핀)를 표시했습니다!", Toast.LENGTH_LONG).show()
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
