package app.evcoach.coach

/**
 * 도로 경사 추정 — GPS 고도 + 차속으로 적분한 주행거리.
 *   경사 = (지금 고도 − 약 [WINDOW_M] 뒤 지점 고도) ÷ 그 사이 주행거리
 *   · [MIN_MOVE_M] 미만 이동한 고도 샘플은 버림(정차 중 GPS 고도 흔들림이 경사로 번지지 않게)
 *   · 구간이 [MIN_SPAN_M] 미만이면 아직 모름(0) · ±[MAX_GRADE] 클램프 · 샘플마다 저역 필터([ALPHA])
 *   · 고도 신호가 [STALE_MS] 넘게 끊기면 0(평지 가정 = 기존 동작)
 * 위치 콜백(다른 스레드)과 차속 tick이 같이 부르므로 메서드 전부 동기화.
 */
class GradeEstimator {
    private val pts = ArrayDeque<DoubleArray>() // [주행거리 m, 고도 m]
    private var grade = 0.0
    private var lastAt = Long.MIN_VALUE / 2

    @Synchronized fun onAltitude(odoM: Double, altM: Double, nowMs: Long) {
        lastAt = nowMs
        if (pts.isNotEmpty() && odoM - pts.last()[0] < MIN_MOVE_M) return
        pts.addLast(doubleArrayOf(odoM, altM))
        while (pts.size > 2 && odoM - pts[1][0] >= WINDOW_M) pts.removeFirst()
        val first = pts.first()
        val span = odoM - first[0]
        if (span < MIN_SPAN_M) return
        val raw = ((altM - first[1]) / span).coerceIn(-MAX_GRADE, MAX_GRADE)
        grade += (raw - grade) * ALPHA
    }

    /** 지금 경사(rise/run · +오르막 −내리막). */
    @Synchronized fun current(nowMs: Long): Double = if (nowMs - lastAt > STALE_MS) 0.0 else grade

    @Synchronized fun reset() { pts.clear(); grade = 0.0 }

    companion object {
        const val WINDOW_M = 150.0
        const val MIN_SPAN_M = 50.0
        const val MIN_MOVE_M = 5.0
        const val MAX_GRADE = 0.12
        const val ALPHA = 0.5
        const val STALE_MS = 10_000L
    }
}
