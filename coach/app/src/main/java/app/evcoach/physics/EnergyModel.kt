package app.evcoach.physics

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 지금 주행 조건(매 tick 바뀌는 것). grade = 경사(rise/run), extraKg = 기본 1인 외 추가 탑승 무게. */
data class Conditions(
    val tempC: Double = 20.0,
    val extraKg: Double = 0.0,
    val grade: Double = 0.0,
    val hvacOn: Boolean? = null, // null = 신호 없음 → 기온으로 추정
)

/** 한 순간의 전력 분해(W). battery = 배터리에서 나간 전력(음수 = 회생 충전). */
data class PowerSplit(
    val aero: Double, val roll: Double, val grade: Double, val inertia: Double,
    val driveLoss: Double, val friction: Double, val regenBack: Double, val aux: Double, val battery: Double,
)

/**
 * 주행저항 물리 모델 (sim/ev_sim.py 와 같은 식 + 출력 의존 효율·회생 한계).
 *   F = m·g·Crr·cosθ + ½·ρ·CdA·v² + m·g·sinθ + m·(1+k)·a
 *   견인: P_batt = P_wheel / η(P)   (η(P) = η0 − 0.08·(P/Pmax)²  — 세게 밟을수록 손실↑)
 *   제동: 회생 = min(필요 제동력, m·a_regen, P_regen/v) → P_batt = −P_regen·η_regen, 나머지는 마찰 손실
 */
object EnergyModel {
    const val G = 9.81
    const val ETA_MIN = 0.6

    fun rho(tempC: Double) = 101325.0 / (287.05 * (tempC + 273.15))

    /** 공조 포함 보조전력(W). 냉방 22°C 초과·난방 16°C 미만부터 기온 차에 비례(히트펌프 가정, 상한 3kW). */
    fun auxW(c: Conditions): Double {
        val base = 150.0
        val on = c.hvacOn ?: (c.tempC > 24 || c.tempC < 16)
        if (!on) return base
        val hvac = when {
            c.tempC > 22 -> 400.0 + 80.0 * (c.tempC - 22)
            c.tempC < 16 -> 300.0 + 110.0 * (16 - c.tempC)
            else -> 250.0
        }
        return base + min(hvac, 3000.0)
    }

    fun etaDrive(car: CarSpec, wheelW: Double): Double {
        val r = wheelW / (car.motorKw * 1000)
        return max(ETA_MIN, car.etaDrive - 0.08 * r * r) // 하한: 모터 정격 넘는 입력에서도 효율이 음수가 되지 않게
    }

    fun power(car: CarSpec, v: Double, a: Double, c: Conditions): PowerSplit {
        val m = car.massKg + c.extraKg
        val th = atan(c.grade)
        val fAero = 0.5 * rho(c.tempC) * car.cda * v * v
        val fRoll = if (v > 0.05) m * G * car.crr * cos(th) else 0.0
        val fGrade = m * G * sin(th)
        val fInertia = m * (1 + car.kRot) * a
        val f = fAero + fRoll + fGrade + fInertia
        val pw = f * v
        val aux = auxW(c)
        var driveLoss = 0.0; var friction = 0.0; var regenBack = 0.0
        val batt: Double
        if (pw >= 0) {
            val pb = pw / etaDrive(car, pw)
            driveLoss = pb - pw
            batt = pb + aux
        } else {
            val brakeForce = -f
            val regenForceMax = if (v > 1.5) min(m * car.regenDecel, car.regenKw * 1000 / v) else 0.0 // 저속은 회생 컷오프
            val regenF = min(brakeForce, regenForceMax)
            friction = max(0.0, brakeForce - regenF) * v
            regenBack = regenF * v * car.etaRegen
            batt = -regenBack + aux
        }
        return PowerSplit(fAero * v, fRoll * v, fGrade * v, fInertia * v, driveLoss, friction, regenBack, aux, batt)
    }

    /** 정속 주행 비용(Wh/km). v = m/s. */
    fun whPerKm(car: CarSpec, v: Double, c: Conditions): Double {
        if (v < 0.5) return Double.POSITIVE_INFINITY
        return power(car, v, 0.0, c).battery / v / 3.6 // (J/s)/(m/s) = J/m → Wh/km = J/m ÷ 3.6
    }

    /**
     * 권장 속도(km/h, 정수 5단위) — 🔴 불변식: 항상 제한속도 이하 · 흐름 하한(제한의 85%, 저속 도로는 −10) 이상.
     * 물리적 최저 비용 속도는 보통 40~60km/h라 도로 흐름보다 낮다 → 그 경우 흐름 하한이 권장 속도가 된다.
     */
    fun recommendedKph(car: CarSpec, limitKph: Int, c: Conditions): Int {
        val floor = if (limitKph >= 80) round5(limitKph * 0.85) else max(20, limitKph - 10)
        var best = floor; var bestCost = Double.POSITIVE_INFINITY
        for (k in floor..limitKph) {
            val cost = whPerKm(car, k / 3.6, c)
            if (cost < bestCost) { bestCost = cost; best = k }
        }
        return min(limitKph, max(floor, round5Down(best, floor)))
    }

    /** 지금 속도 대신 권장 속도로 달리면 100km당 아끼는 돈(원). */
    fun savingPer100km(car: CarSpec, nowKph: Double, recKph: Int, c: Conditions, wonPerKwh: Double): Double {
        val d = whPerKm(car, nowKph / 3.6, c) - whPerKm(car, recKph / 3.6, c)
        return max(0.0, d) * 100 / 1000 * wonPerKwh
    }

    private fun round5(x: Double) = ((x / 5).roundToInt() * 5)
    private fun round5Down(x: Int, floor: Int) = max(floor, x / 5 * 5)
}
