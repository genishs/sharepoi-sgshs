package com.sgshs.sharepoi

import android.app.Application
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

/**
 * 「위치 공유」의 위치 확인(최대 [SHARE_FIX_TIMEOUT_MS])을 화면 재생성(회전 등) 너머로 이어 가는 ViewModel (#29).
 *
 * 위치 요청과 시간 제한은 화면(Activity)이 아니라 여기서 돌고, 결과는 [shareResult] 에 남아
 * 다시 만들어진 화면이 받아 공유 시트나 안내 대화상자를 띄운다. 화면이 잠시 보이지 않는 동안 끝나도
 * 결과는 화면이 다시 보일 때 전달된다. 화면을 완전히 닫으면(onCleared) 요청을 취소한다.
 *
 * 마지막으로 얻은 실제 위치([rememberFix])도 여기 두어 회전 뒤에도 「최근 위치」로 쓸 수 있다.
 */
class LocationShareViewModel(application: Application) : AndroidViewModel(application) {

    /** 실제로 얻은 위치 한 건. [precise]: 정확한 위치 권한으로 얻었는지(대략적 위치면 false) */
    class Fix(val location: Location, val precise: Boolean)

    /** 공유용 위치 확인 결과. 화면이 받으면 [consumeShareResult] 로 지운다. */
    sealed class ShareResult {
        class Ready(val kind: ShareKind, val latitude: Double, val longitude: Double) : ShareResult()
        object NoFix : ShareResult()
    }

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)
    private val handler = Handler(Looper.getMainLooper())
    private var cancellation: CancellationTokenSource? = null
    private var cleared = false

    private val _sharing = MutableLiveData(false)

    /** 위치 확인이 진행 중인지(공유 버튼을 잠근다) */
    val sharing: LiveData<Boolean> get() = _sharing

    private val _shareResult = MutableLiveData<ShareResult?>(null)

    /** 아직 화면이 처리하지 않은 공유 결과(없으면 null) */
    val shareResult: LiveData<ShareResult?> get() = _shareResult

    private var lastFix: Fix? = null

    fun rememberFix(location: Location, precise: Boolean) {
        lastFix = Fix(location, precise)
    }

    /** 최근([CACHE_MAX_AGE_MS] 이내)에 얻은 실제 위치만 인정한다. 오래된 캐시는 쓰지 않는다. */
    private fun freshCachedFix(): Fix? {
        val known = lastFix ?: return null
        val ageMs = (SystemClock.elapsedRealtimeNanos() - known.location.elapsedRealtimeNanos) / 1_000_000
        return if (ageMs in 0..CACHE_MAX_AGE_MS) known else null
    }

    /**
     * 공유할 위치를 새로 확인한다. [precise] 는 지금 정확한 위치 권한이 있는지.
     * 호출 전에 위치 권한(정확·대략 중 하나)이 있는지 화면에서 확인한다.
     * @return 이미 진행 중이면 false
     */
    fun startShare(precise: Boolean): Boolean {
        if (_sharing.value == true) return false
        _sharing.value = true

        val token = CancellationTokenSource()
        cancellation = token
        var finished = false
        // fixPrecise: 그 위치가 정확한 위치 권한으로 얻은 것인지
        val finish: (Location?, Boolean) -> Unit = finish@{ location, fixPrecise ->
            if (finished || cleared) return@finish
            finished = true
            handler.removeCallbacksAndMessages(null)
            cancellation = null
            _sharing.value = false
            _shareResult.value = if (location != null) {
                rememberFix(location, fixPrecise)
                val kind = if (fixPrecise) ShareKind.CURRENT_PRECISE else ShareKind.CURRENT_APPROXIMATE
                ShareResult.Ready(kind, location.latitude, location.longitude)
            } else {
                ShareResult.NoFix
            }
        }
        val finishWithCache = {
            val cached = freshCachedFix()
            // 캐시가 대략적 위치였거나 지금 권한이 대략적 위치뿐이면 대략적 위치로 본다.
            finish(cached?.location, cached != null && cached.precise && precise)
        }

        handler.postDelayed({ token.cancel(); finishWithCache() }, SHARE_FIX_TIMEOUT_MS)
        try {
            val priority = if (precise) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
            fusedLocationClient.getCurrentLocation(priority, token.token)
                .addOnSuccessListener { location -> if (location != null) finish(location, precise) else finishWithCache() }
                .addOnFailureListener { finishWithCache() }
        } catch (e: SecurityException) {
            finish(null, false)
        }
        return true
    }

    fun consumeShareResult() {
        _shareResult.value = null
    }

    override fun onCleared() {
        cleared = true
        handler.removeCallbacksAndMessages(null)
        cancellation?.cancel()
        cancellation = null
    }

    companion object {
        const val SHARE_FIX_TIMEOUT_MS = 6000L
        const val CACHE_MAX_AGE_MS = 120_000L
    }
}
