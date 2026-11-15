package app.evprobe

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.EvBatteryEfficiencyListener
import ai.pleos.playground.vehicle.listener.EvBatteryLevelListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.HvacAcStateListener
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.RangeRemainingListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TirePressureListener
import android.Manifest
import android.app.Activity
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * 신호 실측용 probe (에픽 전비코치 A4·A5).
 * 모든 이벤트를 logcat 태그 EVPROBE 로 «경과ms 출처.신호 값» 한 줄씩 남긴다 → 주기·단위를 로그로 잰다.
 */
class MainActivity : Activity() {

    private val t0 = SystemClock.elapsedRealtime()
    private val latest = sortedMapOf<String, String>()
    private lateinit var view: TextView
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var tts: TextToSpeech? = null
    private var navi: NaviHelper? = null
    private var fused: FusedLocation? = null

    private fun log(key: String, value: Any?) {
        val ms = SystemClock.elapsedRealtime() - t0
        Log.i(TAG, "$ms $key $value")
        runOnUiThread {
            latest[key] = value.toString()
            view.text = latest.entries.joinToString("\n") { "${it.key} = ${it.value}" }
        }
    }

    private fun fail(key: String) = { e: Exception -> log("$key.FAIL", "${e.javaClass.simpleName}: ${e.message}") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TextView(this).apply { textSize = 22f; setPadding(40, 40, 40, 40) }
        setContentView(ScrollView(this).apply { addView(view) })

        val wanted = arrayOf(
            "android.car.permission.CAR_SPEED",
            "android.car.permission.CAR_ENERGY",
            "android.car.permission.CAR_EXTERIOR_ENVIRONMENT",
            "android.car.permission.CAR_POWERTRAIN",
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else startAll()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        permissions.forEachIndexed { i, p -> log("perm.${p.substringAfterLast('.')}", grantResults[i] == 0) }
        startAll()
    }

    private fun startAll() {
        runCatching { startPleos() }.onFailure { log("pleos.INIT_FAIL", it) }
        runCatching { startCarProperties() }.onFailure { log("aaos.INIT_FAIL", it) }
        runCatching { startLocation() }.onFailure { log("loc.INIT_FAIL", it) }
        runCatching { startTts() }.onFailure { log("tts.INIT_FAIL", it) }
        runCatching { startNavi() }.onFailure { log("navi.INIT_FAIL", it) }
        runCatching { startFused() }.onFailure { log("fused.INIT_FAIL", it) }
    }

    // ── Pleos Vehicle SDK ──
    private fun startPleos() {
        val v = Vehicle(this).also { vehicle = it }
        v.initialize()
        log("pleos.init", "ok")

        val ev = v.getEvBattery()
        ev.registerInstantaneousEvEfficiency(effListener("pleos.ev.effInstant"))
        ev.registerCurrentEvEfficiency(effListener("pleos.ev.effCurrent"))
        ev.registerSinceChargeEvEfficiency(effListener("pleos.ev.effSinceCharge"))
        ev.registerCumulativeEvEfficiency(effListener("pleos.ev.effCumulative"))
        ev.registerEvBatteryStateOfCharge(object : EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(p0: Int?) = log("pleos.ev.soc", p0)
            override fun onFailed(p0: Exception) = fail("pleos.ev.soc")(p0)
        })
        ev.registerEvBatteryLevel(object : EvBatteryLevelListener {
            override fun onEvBatteryLevelUpdated(p0: Float?) = log("pleos.ev.level", p0)
            override fun onFailed(p0: Exception) = fail("pleos.ev.level")(p0)
        })

        val odo = v.getOdometer()
        odo.registerDistanceDriven(distListener("pleos.odo.total"))
        odo.registerTripCurrentDrivingDistance(distListener("pleos.odo.tripCurrent"))
        odo.registerTripInstantaneousDrivingDistance(distListener("pleos.odo.tripInstant"))
        odo.registerRangeRemainingListener(object : RangeRemainingListener {
            override fun onRangeRemainingChanged(p0: Float?) = log("pleos.odo.range", p0)
            override fun onFailed(p0: Exception) = fail("pleos.odo.range")(p0)
        })

        v.getGear().registerGearSelection(object : GearSelectionListener {
            override fun onSelected(p0: VehicleGear?) = log("pleos.gear", p0)
            override fun onFailed(p0: Exception) = fail("pleos.gear")(p0)
        })
        v.getBrake().registerBrakePedalStatus(object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(p0: Boolean?) = log("pleos.brake", p0)
            override fun onFailed(p0: Exception) = fail("pleos.brake")(p0)
        })

        val tire = v.getTire()
        for (w in listOf(WheelValue.LEFT_FRONT, WheelValue.RIGHT_FRONT, WheelValue.LEFT_REAR, WheelValue.RIGHT_REAR)) {
            runCatching {
                tire.registerTirePressure(WheelArea(w), object : TirePressureListener {
                    override fun onTirePressureUpdated(p0: Float?) = log("pleos.tire.$w", p0)
                    override fun onFailed(p0: Exception) = fail("pleos.tire.$w")(p0)
                })
            }.onFailure { log("pleos.tire.$w.REG_FAIL", it) }
        }

        val seat = v.getSeat()
        for (s in listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT)) {
            seat.registerSeatOccupancy(SeatArea(s), object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(p0: Boolean?) = log("pleos.seat.$s", p0)
                override fun onFailed(p0: Exception) = fail("pleos.seat.$s")(p0)
            })
        }

        val hvac = v.getHVAC()
        val driver = SeatArea(SeatValue.ROW_1_LEFT)
        hvac.registerHvacAcState(driver, object : HvacAcStateListener {
            override fun onHvacAcStateUpdated(p0: Boolean?) = log("pleos.hvac.ac", p0)
            override fun onFailed(p0: Exception) = fail("pleos.hvac.ac")(p0)
        })
        hvac.registerHvacFanSpeed(driver, object : HvacFanSpeedListener {
            override fun onHvacFanSpeedUpdated(p0: Int?) = log("pleos.hvac.fan", p0)
            override fun onFailed(p0: Exception) = fail("pleos.hvac.fan")(p0)
        })
    }

    private fun effListener(key: String) = object : EvBatteryEfficiencyListener {
        override fun onEvBatteryEfficiencyUpdated(p0: Float?) = log(key, p0)
        override fun onFailed(p0: Exception) = fail(key)(p0)
    }

    private fun distListener(key: String) = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(p0: Float?) = log(key, p0)
        override fun onFailed(p0: Exception) = fail(key)(p0)
    }

    // ── Android Automotive 표준 CarPropertyManager ──
    private fun startCarProperties() {
        val c = Car.createCar(this).also { car = it }
        val cpm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        log("aaos.init", "ok")
        val props = mapOf(
            VehiclePropertyIds.PERF_VEHICLE_SPEED to "speed",
            VehiclePropertyIds.PERF_VEHICLE_SPEED_DISPLAY to "speedDisplay",
            VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE to "outsideTemp",
            VehiclePropertyIds.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE to "evPowerRate",
            VehiclePropertyIds.EV_BATTERY_LEVEL to "evBatteryWh",
            VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY to "evCapacityWh",
            VehiclePropertyIds.INFO_MAKE to "make",
            VehiclePropertyIds.INFO_MODEL to "model",
            VehiclePropertyIds.INFO_MODEL_YEAR to "year",
            VehiclePropertyIds.CURRENT_GEAR to "gear",
            VehiclePropertyIds.GEAR_SELECTION to "gearSel",
            VehiclePropertyIds.PERF_ODOMETER to "odometer",
            VehiclePropertyIds.PERF_STEERING_ANGLE to "steering",
            VehiclePropertyIds.TIRE_PRESSURE to "tirePressure",
        )
        val cb = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) =
                log("aaos.${props[value.propertyId]}", "${value.value} area=${value.areaId} st=${value.status}")
            override fun onErrorEvent(propId: Int, zone: Int) = log("aaos.${props[propId]}.ERR", "zone=$zone")
        }
        for ((id, name) in props) {
            val cfg = runCatching { cpm.getCarPropertyConfig(id) }.getOrNull()
            if (cfg == null) { log("aaos.$name.config", "없음"); continue }
            log("aaos.$name.config", "rate=${cfg.minSampleRate}~${cfg.maxSampleRate} change=${cfg.changeMode}")
            runCatching { cpm.registerCallback(cb, id, CarPropertyManager.SENSOR_RATE_FASTEST) }
                .onFailure { log("aaos.$name.REG_FAIL", it) }
        }
    }

    // ── 위치 (경사·속도 대체원 확인) ──
    @Suppress("MissingPermission")
    private fun startLocation() {
        val lm = getSystemService(LocationManager::class.java)
        log("loc.providers", lm.getProviders(true))
        val l = LocationListener { loc: Location ->
            log("loc.fix", "spd=${loc.speed} alt=${loc.altitude} hasAlt=${loc.hasAltitude()} acc=${loc.accuracy} ${loc.provider}")
        }
        for (p in lm.getProviders(true)) lm.requestLocationUpdates(p, 0L, 0f, l, mainLooper)
    }

    // ── Pleos TextToSpeech SDK (ON_DEVICE = 앱 등록 불필요) ──
    private fun startTts() {
        val t = TextToSpeech(applicationContext, Mode.ON_DEVICE).also { tts = it }
        t.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                log("tts.init", "ok")
                t.addEventListener(object : EventListener {
                    override fun onReady() = log("tts.ready", true)
                    override fun onStart() = log("tts.start", true)
                    override fun onUpdatedRms(rms: Double) = Unit
                    override fun onDone() = log("tts.done", true)
                    override fun onStop() = log("tts.stop", true)
                    override fun onError(errorType: ErrorType) = log("tts.ERR", errorType)
                })
                t.setLocale(Locale.KOREA)
                t.speak("전비코치 음성 테스트입니다. 지금 속도를 시속 구십 킬로미터로 줄이면 백 킬로미터당 천이백 원을 아낍니다.")
            }
            override fun onFailure(errorType: ErrorType) = log("tts.init", "fail $errorType")
        })
    }

    // ── Pleos NaviHelper (제한속도 = 권장속도 상한) ──
    private fun startNavi() {
        val n = NaviHelper(applicationContext).also { navi = it }
        n.initialize({
            log("navi.init", "ok")
            n.addListener(object : NaviHelperEventListener {
                override fun onCurrentRoadSpeedLimit(p0: Int) = log("navi.speedLimit", p0)
                override fun onCameraAlert(p0: List<CameraAlert>) = log("navi.camera", p0)
                override fun onCurrentLocationInfo(p0: CurrentLocationInfo) =
                    log("navi.location", "${p0.latitude},${p0.longitude} hdg=${p0.heading} ${p0.address}")
                override fun onNavigationStatus(p0: NavigationStatus) = log("navi.status", p0)
                override fun onError(p0: NaviErrorCode) = log("navi.ERR", p0)
            })
            n.getCurrentLocationInfo()
        }, { log("navi.init", "fail $it") })
    }

    // ── Pleos FusedLocation (고도 = 경사 재료) ──
    private fun startFused() {
        val f = FusedLocation(applicationContext).also { fused = it }
        f.initialize()
        log("fused.supported", f.isSupported())
        f.registerFusedLocationCallback(object : FusedLocationListener {
            override fun onChanged(p0: LocationData?) = log("fused.fix", p0)
            override fun onError(p0: Exception) = log("fused.ERR", "${p0.javaClass.simpleName}: ${p0.message}")
        })
    }

    override fun onDestroy() {
        tts?.release()
        navi?.release()
        fused?.release()
        vehicle?.release()
        car?.disconnect()
        super.onDestroy()
    }

    companion object { const val TAG = "EVPROBE" }
}
