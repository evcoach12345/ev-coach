package app.evcoach.coach

import app.evcoach.physics.CarSpec
import app.evcoach.physics.Conditions
import app.evcoach.physics.EnergyModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 가감속 한 번의 채점 결과. lossWon = 부드럽게 했을 때보다 더 쓴(또는 못 돌려받은) 돈. */
data class DriveEvent(val kind: Kind, val fromKph: Int, val toKph: Int, val peakAccel: Double, val lossWh: Double, val lossWon: Double, val grade: Char) {
    enum class Kind(val ko: String) { ACCEL("가속"), DECEL("감속") }
}

/**
 * 아낀 순간 = 화면 임팩트 카드 + 효과음 + 짧은 음성의 재료. won = 정수 원(1원 미만은 만들지 않음).
 *   GENTLE = 부드러운 가속(A·B). 보통 가속(2.5 m/s²) 대비 덜 쓴 전기 — 저속(0→60)은 1원 미만, 고속 합류(0→110)는 수 원.
 *            1원 미만이면 돈 대신 콤보 카드(won = 0). 1원 미만 몫도 오늘 절약 합계엔 그대로 더한다.
 *   SMOOTH_DECEL = 부드러운 감속: 같은 속도 변화를 급제동(4 m/s²)으로 했을 때보다 회생으로 더 돌려받은 전기
 *   PACE   = 권장 속도 안내 뒤 속도를 줄여 달린 거리 × (줄이기 전 속도 비용 − 지금 비용) · 10원 단위로 알림
 */
data class SaveEvent(val kind: Kind, val won: Int, val combo: Int, val todayWon: Int) {
    enum class Kind(val title: String) { GENTLE("부드러운 가속"), SMOOTH_DECEL("부드러운 감속"), PACE("권장 속도 유지") }
}

/** 운행 누적(Wh). 손실 내역 = 리포트 원그래프 재료. */
class TripLedger {
    var distanceM = 0.0; var seconds = 0.0
    var batteryWh = 0.0
    var aeroWh = 0.0; var rollWh = 0.0; var gradeWh = 0.0; var auxWh = 0.0; var driveLossWh = 0.0
    var frictionWh = 0.0; var regenWh = 0.0; var harshLossWh = 0.0
    var savedWon = 0.0 // 이번 운행 절약(GENTLE + PACE) — 회생 회수는 regenWh로 따로
    val events = mutableListOf<DriveEvent>()
    val trace = mutableListOf<Double>() // 1초 간격 속도(m/s) — 가상 리그 재료
    internal var traceAcc = 1.0

    val km get() = distanceM / 1000
    val kmPerKwh get() = if (batteryWh > 1) km / (batteryWh / 1000) else 0.0
    fun won(wonPerKwh: Double) = batteryWh / 1000 * wonPerKwh
    fun harshLossWon() = events.sumOf { it.lossWon }
}

/** 코칭 엔진 출력 = 화면·음성이 읽는 상태 하나. */
data class CoachState(
    val speedKph: Double = 0.0,
    val limitKph: Int = 0,
    val limitEstimated: Boolean = true,
    val recKph: Int = 0,
    val savingPer100kmWon: Int = 0,
    val instWhPerKm: Double = 0.0,
    val lastEvent: DriveEvent? = null,
    val tripKm: Double = 0.0,
    val tripKmPerKwh: Double = 0.0,
    val tripWon: Int = 0,
    val todaySavedWon: Int = 0,
    val combo: Int = 0,
    val grade: Double = 0.0, // 지금 도로 경사(rise/run · GradeEstimator) — 비용·권장 계산에 이미 들어간 값
)

/**
 * 10Hz 차속 → 물리 모델 → 권장 속도·가감속 채점·운행 누적.
 * 가감속 이벤트 = |a| > 0.6 m/s² 로 시작, |a| < 0.3 이 1초 이어지면 끝.
 *   가속 손실 = 실제 출력에서의 구동 손실 − «부드러운 가속(1.0 m/s²)» 출력에서의 구동 손실
 *   감속 손실 = 마찰 브레이크로 버린 에너지 × η_regen (회생이었다면 돌려받았을 몫)
 */
class CoachEngine(var car: CarSpec, var wonPerKwh: Double = 300.0) {
    var trip = TripLedger(); private set
    private var lastV = -1.0
    private var aSmooth = 0.0

    // 진행 중 이벤트
    private var evKind: DriveEvent.Kind? = null
    private var evFrom = 0.0; private var evPeak = 0.0; private var evLossWh = 0.0; private var evCalm = 0.0
    private var evSaveWh = 0.0 // 가속: 보통 가속 대비 / 감속: 급제동 대비 더 돌려받은 회생

    // 게임 상태: 오늘 누적 절약(원 · 운행이 바뀌어도 유지) · 콤보 = 연속 좋은 가감속 수(급가속이면 0)
    var todaySavedWon = 0.0
    var combo = 0; private set
    /** 마지막으로 나온 절약 순간(가감속 이벤트가 닫힐 때 채움 · 읽으면 비움). */
    private var pendingSave: SaveEvent? = null
    fun takeSave(): SaveEvent? = pendingSave.also { pendingSave = null }

    // 권장 속도 유지 절약: 권장보다 빨랐던 속도(ref)를 기억했다가 줄여 달린 거리만큼 차액 적립
    private var paceRefKph = 0.0
    private var paceRecKph = 0 // ref 잡을 때의 권장 속도(추정 제한일 땐 권장이 지금 속도를 따라 내려가므로 고정해 둔다)
    private var paceRefAge = 0.0 // ref 이후 흐른 초 — PACE_WINDOW_S 넘으면 더 적립 안 함(보수적)
    private var paceWh = 0.0

    fun resetTrip() { trip = TripLedger(); evKind = null; combo = 0; paceRefKph = 0.0; paceRefAge = 0.0; paceWh = 0.0 }

    /** 절약 적립(1원 미만도 합계엔 더함) · 카드는 1원 이상이거나 콤보 보상(GENTLE)일 때만. */
    private fun emitSave(kind: SaveEvent.Kind, wonD: Double): SaveEvent? {
        trip.savedWon += wonD; todaySavedWon += wonD
        val w = wonD.toInt()
        if (w < 1 && kind != SaveEvent.Kind.GENTLE) return null
        return SaveEvent(kind, w, combo, todaySavedWon.toInt()).also { pendingSave = it }
    }

    /** dt 초 동안 v(m/s). 끝난 이벤트가 있으면 반환. */
    fun tick(dt: Double, vIn: Double, c: Conditions): DriveEvent? {
        val v = max(0.0, vIn) // 후진(음수 차속)은 0으로 — 거리·에너지 부호 뒤집힘 방지
        if (lastV < 0) { lastV = v; return null }
        // 물리 한계 클램프: 신호 튐·콘솔 슬라이더 급조작이 «급가속 F 수백 원»·음수 에너지로 번지지 않게.
        // 원시값은 넉넉히(5Hz 계단 신호의 순간 2배 튐은 통과) → 걸러진 값은 차의 물리 한계로.
        val aRaw = ((v - lastV) / dt).coerceIn(-RAW_MAX_A, RAW_MAX_A)
        aSmooth += (aRaw - aSmooth) * minOf(1.0, dt / 0.5) // 0.5초 저역통과(10Hz 양자화 잡음 제거)
        aSmooth = aSmooth.coerceIn(-MAX_A, MAX_A)
        val vMid = (v + lastV) / 2
        lastV = v
        val a = aSmooth
        val p = EnergyModel.power(car, vMid, a, c)
        val h = dt / 3600.0
        trip.apply {
            seconds += dt; distanceM += vMid * dt; batteryWh += p.battery * h
            traceAcc += dt; if (traceAcc >= 1.0) { traceAcc -= 1.0; trace += v }
            aeroWh += p.aero * h; rollWh += p.roll * h; gradeWh += p.grade * h; auxWh += p.aux * h
            driveLossWh += p.driveLoss * h; frictionWh += p.friction * h; regenWh += p.regenBack * h
        }
        // 이 tick의 «부드럽게 했으면» 대비 초과 손실
        // 보통 가속(2.5 m/s²) 대비 덜 쓴 구동 손실 = 부드러운 가속 절약 재료
        if (a > 0.0 && a < NORMAL_ACCEL) {
            val pw = p.aero + p.roll + p.grade + p.inertia
            if (pw > 0) {
                val pn = EnergyModel.power(car, vMid, NORMAL_ACCEL, c).let { it.aero + it.roll + it.grade + it.inertia }
                evSaveWh += max(0.0, pw / EnergyModel.etaDrive(car, pn) - pw / EnergyModel.etaDrive(car, pw)) * h
            }
        }
        if (a < 0.0) {
            // 같은 속도 변화(|a|·dt)를 급제동으로 했다면 걸린 시간 = dt·|a|/HARD_BRAKE · 그때 회생으로 돌려받았을 전기와 비교
            val ref = EnergyModel.power(car, vMid, -HARD_BRAKE, c).regenBack * (dt * -a / HARD_BRAKE) / 3600.0
            evSaveWh += max(0.0, p.regenBack * h - ref)
        }
        val excessWh = when {
            a > GENTLE_ACCEL -> {
                // 같은 바퀴 에너지를 «부드러운 출력»으로 냈다면의 구동 효율과 비교
                val pw = p.aero + p.roll + p.grade + p.inertia
                val pg = EnergyModel.power(car, vMid, GENTLE_ACCEL, c).let { it.aero + it.roll + it.grade + it.inertia }
                max(0.0, pw / EnergyModel.etaDrive(car, pw) - pw / EnergyModel.etaDrive(car, pg)) * h
            }
            a > 0 -> 0.0
            else -> p.friction * car.etaRegen * h
        }
        return trackEvent(dt, v, a, excessWh)
    }

    private fun trackEvent(dt: Double, v: Double, a: Double, excessWh: Double): DriveEvent? {
        val kind = evKind
        if (kind == null) {
            if (abs(a) > START_A) {
                evKind = if (a > 0) DriveEvent.Kind.ACCEL else DriveEvent.Kind.DECEL
                evFrom = v; evPeak = abs(a); evLossWh = excessWh; evCalm = 0.0
            }
            if (evKind == null) evSaveWh = 0.0 // 이벤트 밖 잔량은 버림(이벤트 단위로만 계산)
            return null
        }
        // 반대 방향으로 세게 바뀌면(급제동 직후 급가속 등) 지금 이벤트를 바로 닫는다 — 다음 tick에 새 이벤트로 시작
        val reversed = if (kind == DriveEvent.Kind.ACCEL) a < -START_A else a > START_A
        if (!reversed) {
            evLossWh += excessWh
            evPeak = max(evPeak, abs(a))
            val sameDir = if (kind == DriveEvent.Kind.ACCEL) a > END_A else a < -END_A
            evCalm = if (sameDir) 0.0 else evCalm + dt
            if (evCalm < 1.0) return null
        }
        evKind = null
        val fromK = (evFrom * 3.6).toInt(); val toK = (v * 3.6).toInt()
        if (abs(toK - fromK) < 8) { evSaveWh = 0.0; return null } // 너무 작은 변화는 이벤트 아님
        val won = evLossWh / 1000 * wonPerKwh
        val e = DriveEvent(kind, fromK, toK, evPeak, evLossWh, won, gradeOf(won))
        trip.harshLossWh += evLossWh
        trip.events += e
        // «부드러움» = 손실 등급(A·B)과 최대 가감속(보통 가속·회생 한계 이하) 둘 다 만족
        val good = e.grade <= 'B' && evPeak <= (if (kind == DriveEvent.Kind.ACCEL) NORMAL_ACCEL else car.regenDecel)
        if (kind == DriveEvent.Kind.ACCEL) {
            val harsh = e.grade >= 'C' || evPeak > HARSH_ACCEL
            if (good) { combo += 1; emitSave(SaveEvent.Kind.GENTLE, evSaveWh / 1000 * wonPerKwh) }
            else if (harsh) combo = 0 // 그 사이(2.5~3.0)는 콤보 유지·카드 없음
        } else if (good) {
            // 감속: 급제동은 안전 행동일 수 있어 콤보를 깨지 않는다(벌점 음성도 없음) · 부드러운 감속만 콤보+1
            combo += 1
            emitSave(SaveEvent.Kind.SMOOTH_DECEL, evSaveWh / 1000 * wonPerKwh)
        }
        evSaveWh = 0.0
        return e
    }

    /**
     * 권장 속도 유지 절약(매 tick · state() 뒤에 호출). 권장보다 5km/h 넘게 빠르면 그 속도를 기준(ref)으로 기억하고,
     * 이후 권장 −5~+3 안에서 달린 거리만큼 «ref로 달렸을 때 비용 − 지금 비용»을 적립 → 10원 넘을 때마다 SaveEvent.
     * 보수 장치: ref 이후 [PACE_WINDOW_S] 지나거나 권장보다 10km/h 넘게 느려지면(정체·정차) 적립 중단 · 남은 몫 버림.
     */
    fun paceTick(dt: Double, v: Double, st: CoachState, c: Conditions): SaveEvent? {
        val kph = v * 3.6
        if (kph > st.recKph + 5 && st.savingPer100kmWon > 0) {
            // 과속 구간의 최고 속도 = «줄이기 전 속도» · 적립 중에 다시 과속하면 새 구간으로 시작
            paceRefKph = if (paceRefAge > 0) kph else max(paceRefKph, kph)
            paceRecKph = st.recKph; paceRefAge = 0.0; return null
        }
        if (paceRefKph <= 0) return null
        paceRefAge += dt
        if (paceRefAge > PACE_WINDOW_S || kph < paceRecKph - 10) { paceRefKph = 0.0; paceWh = 0.0; return null }
        if (kph < paceRecKph - 5 || kph > paceRecKph + 3) return null
        val d = EnergyModel.whPerKm(car, paceRefKph / 3.6, c) - EnergyModel.whPerKm(car, max(v, 0.5), c)
        if (d <= 0) return null
        paceWh += d * (v * dt / 1000)
        val step = PACE_STEP_WON / wonPerKwh * 1000 // 10원어치 Wh
        if (paceWh < step) return null
        paceWh -= step
        return emitSave(SaveEvent.Kind.PACE, PACE_STEP_WON)
    }

    fun state(v: Double, limitKph: Int, limitEstimated: Boolean, c: Conditions, last: DriveEvent?): CoachState {
        val kph = v * 3.6
        var rec = EnergyModel.recommendedKph(car, limitKph, c)
        // 제한속도를 모를 때(추정)는 지금보다 빠른 속도를 권하지 않는다 — 실제 제한이 더 낮을 수 있으므로
        if (limitEstimated) rec = min(rec, (kph / 5).toInt() * 5)
        val save = if (kph > rec + 2) EnergyModel.savingPer100km(car, kph, rec, c, wonPerKwh) else 0.0
        return CoachState(
            speedKph = kph, limitKph = limitKph, limitEstimated = limitEstimated, recKph = rec,
            savingPer100kmWon = save.toInt(), instWhPerKm = EnergyModel.whPerKm(car, max(v, 0.5), c),
            lastEvent = last, tripKm = trip.km, tripKmPerKwh = trip.kmPerKwh, tripWon = trip.won(wonPerKwh).toInt(),
            todaySavedWon = todaySavedWon.toInt(), combo = combo, grade = c.grade,
        )
    }

    companion object {
        const val GENTLE_ACCEL = 1.0 // m/s² ≈ 0→100km/h 28초
        const val NORMAL_ACCEL = 2.5 // m/s² — «보통 가속» 비교 기준(부드러운 가속 절약)
        const val HARSH_ACCEL = 3.0 // m/s² — 이보다 세면 콤보 끊김
        const val HARD_BRAKE = 4.0 // m/s² — 급제동 비교 기준(가정 · 회생 한계 2.5를 넘어 마찰 브레이크가 개입하는 세기)
        const val PACE_STEP_WON = 10.0
        const val PACE_WINDOW_S = 600.0 // 줄인 뒤 10분까지만 «줄여서 아낀 돈»으로 인정
        const val START_A = 0.6
        const val END_A = 0.3
        const val MAX_A = 6.0 // m/s² — 양산 EV 가감속 물리 한계(0→100km/h ≈ 4.6초 수준)
        const val RAW_MAX_A = 12.0
        fun gradeOf(won: Double) = when {
            won < 1 -> 'A'; won < 3 -> 'B'; won < 6 -> 'C'; won < 10 -> 'D'; else -> 'F'
        }

        /** 제한속도 신호가 없을 때(에뮬레이터 등) 속도대로 도로 종류 추정 — 화면에 «추정» 표시. */
        fun estimateLimit(kph: Double) = when { kph > 85 -> 110; kph > 65 -> 80; kph > 45 -> 60; else -> 50 }
    }
}

/**
 * 제한속도 추정(내비 신호 없을 때) — 경계 근처에서 70↔95가 깜빡이지 않게 히스테리시스:
 * 올림 = 경계를 넘은 채 [UP_HOLD_MS] 이어지면(권장은 지금 속도 이하로 묶여 있어 안전) ·
 * 내림 = 경계보다 [MARGIN] 더 내려간 채 [HOLD_MS] 이어져야(신호 대기·정체에 흔들리지 않게). 처음 값은 바로 채택.
 */
class LimitEstimator {
    var current = 0; private set
    private var cand = 0
    private var candSince = 0L

    fun update(kph: Double, nowMs: Long): Int {
        if (current == 0) { current = CoachEngine.estimateLimit(kph); return current }
        var e = CoachEngine.estimateLimit(kph)
        if (e < current && CoachEngine.estimateLimit(kph + MARGIN) >= current) e = current
        if (e == current) { cand = 0; return current }
        if (e != cand) { cand = e; candSince = nowMs }
        // 올리는 쪽은 빨리(권장은 어차피 지금 속도 이하로 묶임), 내리는 쪽은 천천히(신호 대기·정체에 흔들리지 않게)
        if (nowMs - candSince >= if (e > current) UP_HOLD_MS else HOLD_MS) { current = e; cand = 0 }
        return current
    }

    companion object {
        const val MARGIN = 5.0
        const val HOLD_MS = 10_000L
        const val UP_HOLD_MS = 0L // 올림은 즉시 — 권장은 지금 속도 이하로 묶여 있어 안전
    }
}
