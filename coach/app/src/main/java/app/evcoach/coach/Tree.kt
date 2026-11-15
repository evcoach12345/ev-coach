package app.evcoach.coach

/**
 * 아낀 전기 → 덜 나온 CO₂ → «소나무 한 그루가 흡수하는 데 걸리는 시간» 환산(공감용 · 근사치).
 * - 전력 배출계수 0.4173 kgCO₂/kWh = 2023년 국가 전력 배출계수(국가 온실가스 통계).
 * - 소나무 30~40년생 1그루 연 6.6kg CO₂ = 산림청·국립산림과학원 «주요 산림수종 표준 탄소흡수량»(2012) → 하루 약 18g.
 * 앱의 원 금액은 Wh × 단가로 만든 값이라 원 → kWh로 되돌려 같은 숫자 체계를 쓴다.
 */
object Tree {
    const val GRID_G_PER_KWH = 417.3
    const val PINE_G_PER_YEAR = 6600.0
    const val PINE_G_PER_DAY = PINE_G_PER_YEAR / 365.0

    fun co2g(won: Double, wonPerKwh: Double): Double = if (wonPerKwh <= 0) 0.0 else won / wonPerKwh * GRID_G_PER_KWH
    fun pineDays(won: Double, wonPerKwh: Double): Double = co2g(won, wonPerKwh) / PINE_G_PER_DAY

    /** 화면용: 1일 미만 = «18시간» · 10일 미만 = «2.8일» · 그 이상 = «12일». 1시간 미만이면 null. */
    fun label(days: Double): String? = when {
        days * 24 < 1 -> null
        days < 1 -> "%d시간".format(Math.round(days * 24).toInt())
        days < 10 -> "%.1f일".format(days)
        else -> "%d일".format(days.toInt())
    }

    /** 음성용 온실가스: «13그램» · «1.2킬로그램». */
    fun spokenG(g: Double): String = if (g < 1000) "${Math.round(g).coerceAtLeast(1)}그램" else "%.1f킬로그램".format(g / 1000)

    /** 음성용: 짧게 반올림(«18시간» · «3일»). */
    fun spoken(days: Double): String? = when {
        days * 24 < 1 -> null
        days < 1 -> "${Math.round(days * 24)}시간"
        else -> "${Math.round(days)}일"
    }
}
