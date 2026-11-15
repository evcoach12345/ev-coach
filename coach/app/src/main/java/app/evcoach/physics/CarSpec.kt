package app.evcoach.physics

/**
 * 차종 제원 + 보정값. 출처 = `ev_specs_kr.csv` → `sim/ev_sim.py calibrate_family`(v1: 휠 공력·Crr 배율·η 보정).
 * mass = 공차중량 + 136kg(운전자·시험 적재). cda = Cd×전면적×휠 보정. regenKw·regenDecel = 미공개라 보수 추정.
 * rankModel = 차종 리그 이름(서버 일일 순위 묶음 단위 · 원본 `sim/rank_dist_*.csv`와 같은 이름).
 */
data class CarSpec(
    val id: String,
    val label: String,
    val massKg: Double,
    val cda: Double,
    val crr: Double,
    val etaDrive: Double,
    val motorKw: Double,
    val regenKw: Double,
    val batteryKwh: Double,
    val rankModel: String,
    val etaRegen: Double = 0.75,
    val kRot: Double = 0.03,
    val regenDecel: Double = 2.5, // m/s² — 이보다 센 감속은 마찰 브레이크(회생 못 함)
)

object Cars {
    val ALL = listOf(
        CarSpec("ioniq5_lr19", "아이오닉5 롱레인지 19in 2WD", 2151.0, 0.7425, 0.00950, 0.95, 168.0, 150.0, 84.0, "아이오닉5"),
        CarSpec("ioniq6_lr18", "더 뉴 아이오닉6 롱레인지 18in 2WD", 2061.0, 0.4917, 0.01027, 0.95, 168.0, 150.0, 84.0, "더 뉴 아이오닉6"),
        CarSpec("ioniq9_19", "아이오닉9 항속형 19in 2WD", 2641.0, 0.7804, 0.01037, 0.95, 160.0, 150.0, 110.3, "아이오닉9"),
        CarSpec("kona_lr17", "코나 일렉트릭 롱레인지 17in", 1856.0, 0.6332, 0.01003, 0.95, 150.0, 120.0, 64.8, "코나 일렉트릭(SX2)"),
        CarSpec("casper_15", "캐스퍼 일렉트릭 항속형 15in", 1471.0, 0.6126, 0.01125, 0.89, 84.5, 70.0, 49.0, "캐스퍼 일렉트릭"),
        CarSpec("ev3_lr17", "EV3 롱레인지 17in 2WD", 1971.0, 0.6193, 0.01129, 0.95, 150.0, 120.0, 81.4, "EV3"),
        CarSpec("ev4_lr17", "EV4 롱레인지 17in 2WD", 1971.0, 0.5167, 0.01087, 0.95, 150.0, 120.0, 81.4, "EV4"),
        CarSpec("ev6_lr19", "더 뉴 EV6 롱레인지 19in 2WD", 2121.0, 0.6936, 0.01045, 0.95, 168.0, 150.0, 84.0, "더 뉴 EV6"),
    )
    val DEFAULT = ALL.first()
    fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: DEFAULT
}
