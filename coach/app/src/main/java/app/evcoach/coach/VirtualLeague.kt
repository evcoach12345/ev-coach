package app.evcoach.coach

import app.evcoach.physics.CarSpec
import app.evcoach.physics.Conditions
import app.evcoach.physics.EnergyModel
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * «같은 길·같은 날씨» 가상 리그 — 사용자 0명일 때의 공정한 순위.
 * 내 운행의 1초 속도 기록 → 이동평균 = 그 길의 «흐름». 가상 운전자 100명이 같은 흐름을
 *   거칠기 g ~ N(0.25, 0.35)(각자 독립 AR(1) 흔들림 크기) · 속도 선택 ×N(1.0, 0.06) · 공조 습관 U(−100, 100)W(평균 0)
 *   탑승 무게·기온은 «나와 같은 조건»(운전만 비교 — 4명 탄 날 불리, 혼자 탄 날 유리해지지 않게)
 * 으로 다시 달린다(sim/rank_dist.py 와 같은 습관 분포). 나도 같은 1초 시뮬레이터로 계산해 사과-사과 비교.
 * 실사용자가 모이면 서버 일일 순위(같은 차종 리그)로 교체.
 */
object VirtualLeague {
    data class Result(val topPercent: Int, val mine: Double, val median: Double, val best10: Double)

    const val N = 100
    const val MIN_KM = 1.0
    private const val PHI = 0.9

    fun rank(car: CarSpec, trace: List<Double>, c: Conditions, seed: Int = 42): Result? {
        if (trace.size < 60) return null
        val flow = smooth(trace, 8)
        val mine = kmPerKwh(car, trace, c, 0.0) ?: return null
        val rng = Random(seed)
        val league = (0 until N).mapNotNull {
            val g = (gauss(rng) * 0.35 + 0.25).coerceIn(-0.4, 1.2)
            val speedK = (gauss(rng) * 0.06 + 1.0).coerceIn(0.88, 1.15)
            val auxExtra = rng.nextDouble(-100.0, 100.0)
            // 각자 독립된 흔들림: AR(1) 잡음, 정상 표준편차 = 0.8m/s × (1+g)  (내 흔들림을 복사하지 않는다)
            val sigma = 0.8 * (1 + g) * kotlin.math.sqrt(1 - PHI * PHI)
            var d = 0.0
            val drive = flow.indices.map { i ->
                d = PHI * d + gauss(rng) * sigma
                if (trace[i] <= 0.0 || flow[i] < 1.0) 0.0 else max(0.0, flow[i] * speedK + d)
            }
            kmPerKwh(car, drive, c, auxExtra)
        }.sorted()
        val better = league.count { it > mine }
        return Result(
            topPercent = ((better + 0.5) * 100 / league.size).toInt().coerceIn(1, 99),
            mine = mine, median = league[league.size / 2], best10 = league[league.size * 9 / 10],
        )
    }

    /** 1초 간격 속도(m/s) → km/kWh (배터리 기준). */
    fun kmPerKwh(car: CarSpec, v: List<Double>, c: Conditions, auxExtraW: Double): Double? {
        var wh = 0.0; var m = 0.0
        for (i in 1 until v.size) {
            val vm = (v[i] + v[i - 1]) / 2
            val p = EnergyModel.power(car, vm, v[i] - v[i - 1], c)
            wh += (p.battery + auxExtraW) / 3600.0
            m += vm
        }
        return if (wh > 1) (m / 1000) / (wh / 1000) else null
    }

    private fun smooth(x: List<Double>, w: Int) = x.indices.map { i ->
        val lo = max(0, i - w); val hi = min(x.size, i + w + 1)
        x.subList(lo, hi).average()
    }

    private fun gauss(r: Random): Double { // Box–Muller
        val u = r.nextDouble(1e-12, 1.0); val v = r.nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)
    }
}
