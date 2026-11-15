package app.evcoach

import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.HvacAcStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import app.evcoach.coach.CoachEngine
import app.evcoach.coach.CoachState
import app.evcoach.coach.LimitEstimator
import app.evcoach.coach.SaveEvent
import app.evcoach.coach.DriveEvent
import app.evcoach.coach.GradeEstimator
import app.evcoach.coach.VirtualLeague
import app.evcoach.coach.TripLedger
import app.evcoach.physics.Cars
import app.evcoach.physics.Conditions
import app.evcoach.ui.CoachApp
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf

/**
 * 신호 → 코칭 엔진 → 화면·음성.
 *   차속 10Hz = AAOS PERF_VEHICLE_SPEED(엔진 tick 박자) · 외기온 = AAOS ENV_OUTSIDE_TEMPERATURE
 *   탑승·공조 = Pleos Vehicle SDK · 제한속도 = Pleos NaviHelper(없으면 속도대로 추정, 화면에 «추정»)
 *   경사 = GPS 고도(LocationManager) ÷ 차속 적분 주행거리(GradeEstimator) → Conditions.grade
 * 정차(2초 이상 1km/h 미만) = 상세 리포트 화면 · 주행 중 = 큰 숫자 1개 + 음성(DO).
 */
class MainActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences("evcoach", MODE_PRIVATE) }
    private lateinit var engine: CoachEngine
    private lateinit var speaker: Speaker
    private val chime = Chime()
    private val main = Handler(Looper.getMainLooper())

    // UI 상태
    private val ui = mutableStateOf(CoachState())
    private val parked = mutableStateOf(true)
    private val tripView = mutableStateOf(TripLedger())
    private val tripVersion = mutableStateOf(0)
    private val league = mutableStateOf<VirtualLeague.Result?>(null)
    private var liveRankAt = 0L
    private val rankExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val carState = mutableStateOf(Cars.DEFAULT)
    private val signalNote = mutableStateOf<String?>(null) // 권한 거부·차량 신호 연결 실패 안내(없으면 null)
    private val limitEst = LimitEstimator()
    private val impact = mutableStateOf<SaveEvent?>(null) // 절약 순간 카드(1.5초)
    private val todayWon = mutableStateOf(0)

    // 신호 최신값
    @Volatile private var tempC = 20.0
    private val seats = mutableMapOf<String, Boolean>()
    @Volatile private var acOn: Boolean? = null
    @Volatile private var naviLimit = 0
    @Volatile private var naviLimitAt = 0L
    @Volatile private var odoM = 0.0 // 앱 켠 뒤 누적 주행거리(차속 적분) — 경사 계산의 «달린 거리»
    private val gradeEst = GradeEstimator()
    private var lm: LocationManager? = null
    private val locListener = LocationListener { loc ->
        if (loc.hasAltitude()) gradeEst.onAltitude(odoM, loc.altitude, SystemClock.elapsedRealtime())
    }

    private var car: Car? = null
    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null

    private var lastNs = 0L
    private var lastEvent: DriveEvent? = null
    private var slowSince = 0L
    private var overSince = 0L
    private var lastUiAt = 0L
    private var lastLogAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        carState.value = Cars.byId(prefs.getString("car", null))
        engine = CoachEngine(carState.value, WON_PER_KWH)
        // 오늘 누적 절약은 운행이 바뀌어도 유지(날짜가 바뀌면 0부터)
        if (prefs.getString("today_date", null) == today()) engine.todaySavedWon = prefs.getFloat("today_won", 0f).toDouble()
        todayWon.value = engine.todaySavedWon.toInt()
        speaker = Speaker(this)

        setContent {
            CoachApp(
                state = ui.value, parked = parked.value, trip = tripView.value, tripVersion = tripVersion.value,
                car = carState.value, league = league.value, tempC = tempC, wonPerKwh = WON_PER_KWH, signalNote = signalNote.value,
                impact = impact.value, todaySavedWon = todayWon.value,
                onPickCar = { c -> carState.value = c; engine.car = c; prefs.edit().putString("car", c.id).apply(); publishTrip() },
                onNewTrip = { engine.resetTrip(); lastEvent = null; publishTrip() },
            )
        }

        val perms = arrayOf("android.car.permission.CAR_SPEED", "android.car.permission.CAR_EXTERIOR_ENVIRONMENT", LOC_PERM)
        if (perms.all { checkSelfPermission(it) == 0 }) startSignals() else requestPermissions(perms, 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 위치(경사)는 없어도 코칭 가능 → 평지 가정으로 계속 · 차속·외기온만 안내
        if (grantResults.isEmpty() || permissions.indices.any { permissions[it] != LOC_PERM && grantResults.getOrNull(it) != 0 }) {
            signalNote.value = "차속·외기온 권한 꺼짐 — 설정 > 앱 > 전비코치에서 허용 필요"
            Log.w(TAG, "permission denied ${permissions.toList()}")
        }
        startSignals()
    }

    private fun startSignals() {
        runCatching { startCarProperties() }.onFailure {
            Log.w(TAG, "aaos fail", it)
            if (signalNote.value == null) signalNote.value = "차량 속도 신호 연결 실패 — 코칭 불가"
        }
        runCatching { startPleos() }.onFailure { Log.w(TAG, "pleos fail", it) }
        runCatching { startNavi() }.onFailure { Log.w(TAG, "navi fail", it) }
        runCatching { startLocation() }.onFailure { Log.w(TAG, "location fail (경사 = 평지 가정)", it) }
    }

    /** GPS 고도 1Hz → 경사. 권한 없거나 GPS 없으면 경사 0(평지 가정 · 기존 동작). */
    @android.annotation.SuppressLint("MissingPermission")
    private fun startLocation() {
        if (checkSelfPermission(LOC_PERM) != 0) { Log.w(TAG, "no location permission → grade=0"); return }
        val m = getSystemService(LOCATION_SERVICE) as LocationManager
        lm = m
        m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, locListener, Looper.getMainLooper())
    }

    private fun startCarProperties() {
        val c = Car.createCar(this).also { car = it }
        val cpm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        cpm.registerCallback(object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) { (value.value as? Float)?.let { onSpeed(it.toDouble()) } }
            override fun onErrorEvent(propId: Int, zone: Int) = Unit
        }, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FASTEST)
        runCatching {
            cpm.registerCallback(object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) { (value.value as? Float)?.let { tempC = it.toDouble() } }
                override fun onErrorEvent(propId: Int, zone: Int) = Unit
            }, VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE, 1f)
        }
    }

    private fun startPleos() {
        val v = Vehicle(this).also { vehicle = it }
        v.initialize()
        val seat = v.getSeat()
        for (s in listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT)) {
            seat.registerSeatOccupancy(SeatArea(s), object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(p0: Boolean?) { if (p0 != null) synchronized(seats) { seats[s.toString()] = p0 } }
                override fun onFailed(p0: Exception) = Unit
            })
        }
        v.getHVAC().registerHvacAcState(SeatArea(SeatValue.ROW_1_LEFT), object : HvacAcStateListener {
            override fun onHvacAcStateUpdated(p0: Boolean?) { acOn = p0 }
            override fun onFailed(p0: Exception) = Unit
        })
    }

    private fun startNavi() {
        val n = NaviHelper(applicationContext).also { navi = it }
        n.initialize({
            n.addListener(object : NaviHelperEventListener {
                override fun onCurrentRoadSpeedLimit(p0: Int) { if (p0 > 0) { naviLimit = p0; naviLimitAt = SystemClock.elapsedRealtime() } }
                override fun onCameraAlert(p0: List<CameraAlert>) = Unit
                override fun onCurrentLocationInfo(p0: CurrentLocationInfo) = Unit
                override fun onNavigationStatus(p0: NavigationStatus) = Unit
                override fun onError(p0: NaviErrorCode) = Unit
            })
        }, { Log.w(TAG, "navi init fail $it") })
    }

    private fun conditions(): Conditions {
        val occupied = synchronized(seats) { if (seats.isEmpty()) 1 else seats.values.count { it }.coerceAtLeast(1) }
        return Conditions(tempC = tempC, extraKg = (occupied - 1) * 70.0, hvacOn = acOn, grade = gradeEst.current(SystemClock.elapsedRealtime()))
    }

    /** 10Hz — 엔진 박자. */
    private fun onSpeed(v: Double) {
        val ns = SystemClock.elapsedRealtimeNanos()
        val dt = if (lastNs == 0L) 0.1 else ((ns - lastNs) / 1e9).coerceIn(0.02, 0.5)
        lastNs = ns
        odoM += kotlin.math.max(0.0, v) * dt
        val c = conditions()
        engine.tick(dt, v, c)?.let { e -> lastEvent = e; onEvent(e) }

        val now = SystemClock.elapsedRealtime()
        val kph = v * 3.6
        val fresh = now - naviLimitAt < 10_000 && naviLimit > 0
        val limit = if (fresh) naviLimit else limitEst.update(kph, now)
        val st = engine.state(v, limit, !fresh, c, lastEvent)
        engine.paceTick(dt, v, st, c)
        engine.takeSave()?.let { onSave(it) } // 가감속·권장 유지 절약 둘 다 여기 한 곳에서만 꺼낸다(중복 방지)

        // 정차 판정 + 운행 끝 요약
        if (kph < 1) { if (slowSince == 0L) slowSince = now } else slowSince = 0L
        val isParked = slowSince != 0L && now - slowSince > 2_000
        if (isParked != parked.value) {
            parked.value = isParked; publishTrip()
            if (isParked && engine.trip.km > 0.3) onTripPaused()
        }

        // 과속(권장 대비) 지속 10초 → 60초 쿨다운으로 1회 · 게임 톤 명사형
        if (st.savingPer100kmWon >= 300 && kph > st.recKph + 5) {
            if (overSince == 0L) overSince = now
            if (now - overSince > 10_000) speaker.say("speed",
                (if (st.limitEstimated) "추정 기준. " else "") +
                    "권장 ${st.recKph}! 100킬로당 온실가스 ${g(st.savingPer100kmWon.toDouble())} 감축 가능!", 60_000)
        } else overSince = 0L

        if (now - lastUiAt > 200) { ui.value = st; lastUiAt = now }
        // 실시간 순위: 주행 중 10초마다 같은 길 가상 운전자 100명과 다시 비교(1분 이상 달린 뒤부터) · 계산은 뒤쪽 스레드
        if (!parked.value && now - liveRankAt > 10_000 && engine.trip.trace.size >= 60) {
            liveRankAt = now
            val tr = engine.trip.trace.toList(); val car = engine.car; val cond = conditions().copy(grade = 0.0) // 가상 운전자 비교 = 같은 길 속도 습관만(경사는 모두에게 같음)
            rankExec.execute { val r = VirtualLeague.rank(car, tr, cond); main.post { if (r != null) league.value = r } }
        }
        if (now - lastLogAt > 1_000) {
            lastLogAt = now
            Log.i(TAG, "st kph=%.0f lim=%d%s rec=%d save=%d whkm=%.0f trip=%.2fkm %.2fkm/kWh %d원 t=%.0f extraKg=%.0f ac=%s grade=%.3f odo=%.0f".format(
                kph, limit, if (fresh) "" else "?", st.recKph, st.savingPer100kmWon, st.instWhPerKm, st.tripKm, st.tripKmPerKwh, st.tripWon, c.tempC, c.extraKg, c.hvacOn, c.grade, odoM))
        }
    }

    private fun onEvent(e: DriveEvent) {
        Log.i(TAG, "event ${e.kind} ${e.fromKph}->${e.toKph} peak=%.1f loss=%.1fWh %.1f원 ${e.grade}".format(e.peakAccel, e.lossWh, e.lossWon))
        // 급가속만 음성 벌점(급제동은 안전 행동일 수 있어 음성 없음 · 리포트에만 기록)
        if (e.kind == DriveEvent.Kind.ACCEL && e.grade >= 'C')
            speaker.say("event", "급가속! 온실가스 ${g(e.lossWon)} 추가 배출!", 8_000, minGapMs = 4_000)
    }

    /** 절약 순간: 정적 카드 1.5초 + 효과음(띠링) + 0.35초 뒤 짧은 명사형 음성. 정차 중엔 카드·소리 없음. */
    private fun onSave(s: SaveEvent) {
        Log.i(TAG, "save ${s.kind} ${s.won}원 combo=${s.combo} today=${s.todayWon}")
        todayWon.value = s.todayWon
        prefs.edit().putString("today_date", today()).putFloat("today_won", engine.todaySavedWon.toFloat()).apply()
        if (parked.value) return
        impact.value = s
        main.removeCallbacksAndMessages(IMPACT_TOKEN)
        main.postAtTime({ impact.value = null }, IMPACT_TOKEN, SystemClock.uptimeMillis() + 1_500)
        chime.play()
        val milestone = s.combo >= 3 && (s.combo == 3 || s.combo % 5 == 0)
        val line = when {
            milestone -> "콤보 ${s.combo}! 오늘 온실가스 ${g(s.todayWon.toDouble())} 감축!"
            s.kind == SaveEvent.Kind.GENTLE -> if (s.combo >= 2) "부드러운 가속! 콤보 ${s.combo}!" else null
            else -> "${s.kind.title}! 온실가스 ${g(s.won.toDouble())} 감축!"
        } ?: return
        main.postDelayed({ speaker.say("save", line, 4_000, minGapMs = 3_000) }, 350)
    }

    private fun today() = java.time.LocalDate.now().toString()

    /** 원(절약액) → 음성용 온실가스 «13그램» — 화면과 같은 환산(Tree). */
    private fun g(won: Double) = app.evcoach.coach.Tree.spokenG(app.evcoach.coach.Tree.co2g(won, WON_PER_KWH))

    private fun onTripPaused() {
        val t = engine.trip
        val r = league.value
        val rank = r?.let { " 상위 ${it.topPercent}퍼센트!" } ?: ""
        // 마지막 절약 음성과 겹치지 않게 3초 뒤
        val light = app.evcoach.coach.Tree.spoken(app.evcoach.coach.Tree.pineDays(engine.trip.savedWon, WON_PER_KWH))?.let { " 소나무 $it 치 숨!" } ?: ""
        main.postDelayed({ speaker.say("trip", "운행 정리! 온실가스 ${g(engine.trip.savedWon)} 감축!$light 충전비 ${engine.trip.savedWon.toInt()}원 절감!$rank", 30_000, minGapMs = 0) }, 3_000)
    }

    private fun publishTrip() {
        val t = engine.trip
        league.value = if (t.km >= VirtualLeague.MIN_KM) VirtualLeague.rank(engine.car, t.trace.toList(), conditions().copy(grade = 0.0)) else null
        league.value?.let { Log.i(TAG, "league top=%d%% mine=%.2f median=%.2f best10=%.2f".format(it.topPercent, it.mine, it.median, it.best10)) }
        tripView.value = t; tripVersion.value++
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        speaker.release()
        runCatching { lm?.removeUpdates(locListener) }
        runCatching { navi?.release() }
        runCatching { vehicle?.release() }
        car?.disconnect()
        super.onDestroy()
    }

    companion object {
        const val TAG = "EVCOACH"
        private const val LOC_PERM = "android.permission.ACCESS_FINE_LOCATION"
        private val IMPACT_TOKEN = Any()
        const val WON_PER_KWH = 300.0 // 충전 단가 가정(완속·급속 혼합) — 화면에 표기
    }
}
