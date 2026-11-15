package app.evcoach

import app.evcoach.coach.CoachEngine
import app.evcoach.coach.DriveEvent
import app.evcoach.coach.GradeEstimator
import app.evcoach.coach.LimitEstimator
import app.evcoach.coach.Tree
import app.evcoach.coach.VirtualLeague
import app.evcoach.physics.Cars
import app.evcoach.physics.Conditions
import app.evcoach.physics.EnergyModel
import app.evcoach.ui.gradeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachTest {
    private val car = Cars.DEFAULT
    private val mild = Conditions(tempC = 20.0, hvacOn = false)

    /** 정속 비용이 상식 범위(아이오닉5: 100km/h ≈ 150~210 Wh/km)이고 속도↑ → 비용↑. */
    @Test fun steadyCostIsSane() {
        val w100 = EnergyModel.whPerKm(car, 100 / 3.6, mild)
        val w80 = EnergyModel.whPerKm(car, 80 / 3.6, mild)
        assertTrue("100km/h $w100", w100 in 150.0..210.0)
        assertTrue(w80 < w100)
        // 추위(−5°C, 공조 켬)는 공기 밀도·난방으로 더 든다
        assertTrue(EnergyModel.whPerKm(car, 100 / 3.6, Conditions(tempC = -5.0, hvacOn = true)) > w100 * 1.15)
    }

    /** 🔴 불변식: 권장 속도는 항상 제한속도 이하 · 흐름 하한 이상. */
    @Test fun recommendedNeverAboveLimit() {
        for (lim in listOf(30, 50, 60, 80, 100, 110, 120)) for (t in listOf(-10.0, 5.0, 20.0, 35.0)) {
            val r = EnergyModel.recommendedKph(car, lim, Conditions(tempC = t))
            assertTrue("lim=$lim t=$t r=$r", r <= lim)
            assertTrue("lim=$lim r=$r 흐름 하한", r >= if (lim >= 80) lim * 0.85 - 2.5 else (lim - 10.0).coerceAtLeast(20.0))
        }
        assertEquals(95, EnergyModel.recommendedKph(car, 110, mild))
    }

    @Test fun savingPositiveWhenFaster() {
        val s = EnergyModel.savingPer100km(car, 115.0, 95, mild, 300.0)
        assertTrue("115→95 절약 $s 원/100km", s in 500.0..3000.0)
    }

    /** 같은 0→60 가속: 급가속(4 m/s²)이 부드러운 가속(1 m/s²)보다 등급·손실이 나쁘다. */
    @Test fun harshAccelScoresWorse() {
        fun run(accel: Double): DriveEvent {
            val e = CoachEngine(car)
            var v = 0.0; var out: DriveEvent? = null
            e.tick(0.1, 0.0, mild)
            repeat(600) {
                v = (v + accel * 0.1).coerceAtMost(60 / 3.6)
                e.tick(0.1, v, mild)?.let { out = it }
            }
            return out!!
        }
        val harsh = run(4.0); val gentle = run(1.0)
        assertEquals(DriveEvent.Kind.ACCEL, harsh.kind)
        assertTrue("harsh ${harsh.lossWon} gentle ${gentle.lossWon}", harsh.lossWon > gentle.lossWon)
        assertTrue(harsh.grade > gentle.grade)
    }

    /** 급제동(7 m/s²)은 회생 한계를 넘어 마찰 손실 → 부드러운 감속(1.5 m/s²)보다 손실 큼. */
    @Test fun hardBrakeLosesEnergy() {
        fun run(decel: Double): DriveEvent {
            val e = CoachEngine(car)
            var v = 80 / 3.6; var out: DriveEvent? = null
            e.tick(0.1, v, mild)
            repeat(400) {
                v = (v - decel * 0.1).coerceAtLeast(0.0)
                e.tick(0.1, v, mild)?.let { out = it }
            }
            return out!!
        }
        val hard = run(7.0); val soft = run(1.5)
        assertEquals(DriveEvent.Kind.DECEL, hard.kind)
        assertTrue("hard ${hard.lossWon}원", hard.lossWon > 5)
        assertTrue("soft ${soft.lossWon}원", soft.lossWon < 1)
    }

    /** 고속 정속 운행의 실전비가 추정 분포 안(아이오닉5 15°C: 4~8 km/kWh). */
    @Test fun tripEfficiencyInRange() {
        val e = CoachEngine(car)
        e.tick(1.0, 100 / 3.6, Conditions(tempC = 15.0))
        repeat(600) { e.tick(1.0, 100 / 3.6, Conditions(tempC = 15.0)) }
        val k = e.trip.kmPerKwh
        assertTrue("km/kWh $k", k in 4.0..8.0)
    }

    /** 🔴 제한속도 추정 중엔 지금 속도보다 빠른 권장을 내지 않는다(실제 제한이 더 낮을 수 있음). */
    @Test fun estimatedLimitNeverRecommendsFaster() {
        val e = CoachEngine(car)
        for (kph in 20..130) {
            val est = CoachEngine.estimateLimit(kph.toDouble())
            val st = e.state(kph / 3.6, est, true, mild, null)
            assertTrue("kph=$kph rec=${st.recKph}", st.recKph <= kph)
        }
        // 내비 제한속도가 확실하면 기존대로(110 → 95)
        assertEquals(95, e.state(100 / 3.6, 110, false, mild, null).recKph)
    }

    /** 실제 시연 주기(HWFET ×1.15)에서 추정 제한이 70↔95로 깜빡이지 않고, «고속인데 권장 70» 음성 조건이 생기지 않는다. */
    @Test fun hwfetNoFlickerNoSeventyOnHighway() {
        val rows = java.io.File("../../tools/cycles/hwfet_kr.csv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split(",") }.map { it[0].toDouble() to it[1].toDouble() }
        val est = LimitEstimator(); val e = CoachEngine(car)
        var changes = 0; var last = 0; var overSince = -1L; var badVoice = 0
        var t = 0L
        while (t / 1000.0 <= rows.last().first) {
            val s = t / 1000.0
            val i = rows.indexOfLast { it.first <= s }.coerceAtLeast(0)
            val j = (i + 1).coerceAtMost(rows.size - 1)
            val f = if (j == i) 0.0 else (s - rows[i].first) / (rows[j].first - rows[i].first)
            val kph = Math.round(rows[i].second + (rows[j].second - rows[i].second) * f).toDouble() // VC = 정수 km/h
            val lim = est.update(kph, t)
            if (last != 0 && lim != last) changes++
            last = lim
            e.tick(0.1, kph / 3.6, mild)
            val st = e.state(kph / 3.6, lim, true, mild, null)
            if (st.savingPer100kmWon >= 300 && kph > st.recKph + 5) { if (overSince < 0) overSince = t } else overSince = -1
            if (overSince >= 0 && t - overSince > 10_000 && kph >= 88 && st.recKph <= 70) badVoice++
            t += 100
        }
        assertEquals("고속(≥88)에서 «권장 70» 음성 조건", 0, badVoice)
        assertTrue("추정 제한 바뀜 $changes 회(765초 주기 · 실제 감속·가속 구간만)", changes <= 12)
    }

    /** 속도 신호 튐(0→100→0 계단)·콘솔 급조작에도 효율·에너지가 물리 범위를 벗어나지 않는다. */
    @Test fun spikeInputStaysPhysical() {
        val e = CoachEngine(car)
        e.tick(0.1, 0.0, mild)
        repeat(20) { e.tick(0.1, 100 / 3.6, mild) }
        repeat(20) { e.tick(0.1, 0.0, mild) }
        assertTrue("motor loss ${e.trip.driveLossWh}", e.trip.driveLossWh >= 0)
        assertTrue("battery ${e.trip.batteryWh}", e.trip.batteryWh > 0)
        assertTrue(EnergyModel.etaDrive(car, 1_000_000.0) >= EnergyModel.ETA_MIN)
        // 후진(음수 차속)은 거리를 줄이지 않는다
        val r = CoachEngine(car); r.tick(0.1, 0.0, mild); repeat(10) { r.tick(0.1, -1.5, mild) }
        assertTrue(r.trip.distanceM >= 0)
    }

    /** 급제동 직후 급가속: 감속 이벤트가 바로 닫히고 가속은 별도 이벤트로 채점된다. */
    @Test fun brakeThenAccelAreSeparateEvents() {
        val e = CoachEngine(car); val out = mutableListOf<DriveEvent>()
        var v = 80 / 3.6
        e.tick(0.1, v, mild)
        repeat(30) { v = (v - 6.0 * 0.1).coerceAtLeast(0.0); e.tick(0.1, v, mild)?.let { out += it } }
        repeat(40) { v += 4.0 * 0.1; e.tick(0.1, v, mild)?.let { out += it } }
        repeat(30) { e.tick(0.1, v, mild)?.let { out += it } }
        assertEquals(listOf(DriveEvent.Kind.DECEL, DriveEvent.Kind.ACCEL), out.map { it.kind })
    }

    /** 같은 길 가상 리그: 탑승 무게가 달라도 같은 운전이면 순위가 크게 변하지 않는다(운전만 비교). */
    @Test fun leagueIgnoresPassengerLoad() {
        val flow = (0 until 900).map { t -> (70 + 20 * kotlin.math.sin(t * 2 * Math.PI / 60)) / 3.6 }
        val alone = VirtualLeague.rank(car, flow, mild)!!
        val four = VirtualLeague.rank(car, flow, mild.copy(extraKg = 210.0))!!
        assertTrue("혼자 ${alone.topPercent}% · 4명 ${four.topPercent}%", kotlin.math.abs(alone.topPercent - four.topPercent) <= 6)
    }

    /** 같은 길 가상 리그: 부드럽게 달린 기록은 상위권, 같은 흐름을 거칠게 달린 기록은 하위권. */
    @Test fun virtualLeagueRewardsSmoothDriving() {
        // 60초 주기로 50↔90km/h 오가는 흐름 15분
        val flow = (0 until 900).map { t -> (70 + 20 * kotlin.math.sin(t * 2 * Math.PI / 60)) / 3.6 }
        val smooth = flow.indices.map { i -> flow.subList(maxOf(0, i - 8), minOf(flow.size, i + 9)).average() }
        val rough = flow.indices.map { i -> (smooth[i] + (flow[i] - smooth[i]) * 2.5 + if (i % 20 < 10) 2.5 else -2.5).coerceAtLeast(0.0) }
        val good = VirtualLeague.rank(car, smooth, mild)!!
        val bad = VirtualLeague.rank(car, rough, mild)!!
        assertTrue("smooth top ${good.topPercent}%", good.topPercent <= 40)
        assertTrue("rough top ${bad.topPercent}%", bad.topPercent >= 60)
        assertTrue(good.mine > bad.mine)
    }

    // ── H1.5 게임 피드백 ──
    private fun drive(e: CoachEngine, profile: List<Pair<Double, Double>>): List<app.evcoach.coach.SaveEvent> {
        // profile = (목표 km/h, 가속도 m/s²) 구간들 · 각 구간 끝나면 3초 정속
        val out = mutableListOf<app.evcoach.coach.SaveEvent>()
        var v = 0.0
        e.tick(0.1, 0.0, mild)
        for ((target, acc) in profile) {
            val tv = target / 3.6
            var guard = 0
            while (kotlin.math.abs(v - tv) > 1e-6 && guard++ < 10000) {
                v = if (v < tv) (v + acc * 0.1).coerceAtMost(tv) else (v - acc * 0.1).coerceAtLeast(tv)
                e.tick(0.1, v, mild); e.takeSave()?.let { out += it }
            }
            repeat(30) { e.tick(0.1, v, mild); e.takeSave()?.let { out += it } }
        }
        return out
    }

    /** 부드러운 가속 = 콤보 카드(돈 0원 표기 · 1원 미만 몫은 합계에만) · 급가속 = 카드 없음 + 콤보 0. */
    @Test fun gentleAccelEmitsCombo() {
        val e = CoachEngine(car)
        val s = drive(e, listOf(60.0 to 1.0))
        println("gentle saves=$s today=${e.todaySavedWon}")
        val g = s.single { it.kind == app.evcoach.coach.SaveEvent.Kind.GENTLE }
        assertEquals(0, g.won); assertEquals(1, g.combo) // 0→60은 1원 미만 → 콤보 카드
        assertTrue(e.todaySavedWon in 0.0..1.0)
        val h = CoachEngine(car)
        val hs = drive(h, listOf(60.0 to 4.5))
        assertTrue("$hs", hs.isEmpty())
        assertEquals(0, h.combo)
    }

    /** 부드러운 감속 60→0 = 급제동 대비 더 돌려받은 회생(원) · 회생 전체보다 작고 0보다 크다 · 오늘 합계에 들어감. */
    @Test fun smoothDecelEmitsSaving() {
        val e = CoachEngine(car)
        val s = drive(e, listOf(60.0 to 1.0, 0.0 to 1.0))
        val regenWon = e.trip.regenWh / 1000 * 300
        println("decel saves=$s regenWon=%.1f".format(regenWon))
        val r = s.first { it.kind == app.evcoach.coach.SaveEvent.Kind.SMOOTH_DECEL }
        assertTrue("${r.won} vs regen $regenWon", r.won >= 2 && r.won < regenWon)
        assertEquals(r.todayWon, e.todaySavedWon.toInt())
        assertEquals(2, e.combo)
    }

    /** 급제동은 콤보를 깨지 않는다(안전 행동 벌점 X) · 급가속은 깬다. */
    @Test fun hardBrakeKeepsCombo() {
        val e = CoachEngine(car)
        drive(e, listOf(60.0 to 1.0, 0.0 to 7.0))
        assertEquals(1, e.combo)
    }

    /** 110 → 권장 95로 줄여 5km 달리면 10원 단위 절약이 실제 비용 차와 맞게 쌓인다. */
    @Test fun paceSavingMatchesPhysics() {
        val e = CoachEngine(car)
        val lim = 110
        fun st(kph: Double) = e.state(kph / 3.6, lim, false, mild, null)
        var got = 0
        repeat(200) { e.paceTick(0.1, 110 / 3.6, st(110.0), mild) } // 20초 과속 → ref 110
        val dt = 0.1; var dist = 0.0
        while (dist < 5000) { val v = 95 / 3.6; e.paceTick(dt, v, st(95.0), mild)?.let { got += it.won }; dist += v * dt }
        val expect = (EnergyModel.whPerKm(car, 110 / 3.6, mild) - EnergyModel.whPerKm(car, 95 / 3.6, mild)) * 5 / 1000 * 300
        println("pace got=$got expect=%.1f".format(expect))
        assertTrue("got=$got expect=$expect", got <= expect + 0.01 && got >= expect - 10)
        // 10분 지나면 더 안 쌓인다
        var late = 0
        repeat(6000) { e.paceTick(0.1, 95 / 3.6, st(95.0), mild)?.let { late += it.won } }
        assertTrue("late=$late", got + late <= (EnergyModel.whPerKm(car, 110 / 3.6, mild) - EnergyModel.whPerKm(car, 95 / 3.6, mild)) * (95.0 / 6) / 1000 * 300 + 0.01)
    }

    /** 추정 제한(권장이 지금 속도를 따라 내려감) 상태에서 110 유지 → 95 유지 → 정지: 정지 감속 구간은 적립 안 됨 · 기준은 110. */
    @Test fun paceUsesHeldSpeedAndIgnoresStopping() {
        val e = CoachEngine(car)
        fun st(kph: Double) = e.state(kph / 3.6, 110, true, mild, null)
        repeat(200) { e.paceTick(0.1, 110 / 3.6, st(110.0), mild) }
        var k = 110.0
        while (k > 95) { k -= 0.1; e.paceTick(0.1, k / 3.6, st(k), mild) } // 천천히 감속(과속 구간 중 최고 = 110 유지)
        var cruise = 0; var dist = 0.0
        while (dist < 3000) { e.paceTick(0.1, 95 / 3.6, st(95.0), mild)?.let { cruise += it.won }; dist += 95 / 3.6 * 0.1 }
        val expect = (EnergyModel.whPerKm(car, 110 / 3.6, mild) - EnergyModel.whPerKm(car, 95 / 3.6, mild)) * 3.0 / 1000 * 300
        println("pace held cruise=$cruise expect=%.1f".format(expect))
        assertTrue("cruise=$cruise expect=$expect", cruise >= expect - 10 && cruise <= expect + 0.01)
        var stopping = 0
        while (k > 0) { k = (k - 0.1).coerceAtLeast(0.0); e.paceTick(0.1, k / 3.6, st(k), mild)?.let { stopping += it.won } }
        assertTrue("stopping=$stopping", stopping <= 10) // 권장 −5 안쪽을 지나는 몇 초 몫(남은 잔량)만 · 정차 감속 전체는 적립 X
    }

    /** 소나무 환산: 300원/kWh에서 10원 = 33.3Wh → CO₂ 13.9g = 소나무(하루 18.1g) 18시간 · 37원 = 2.8일 · 아주 작은 값은 표시 안 함. */
    @Test fun pineEquivalent() {
        assertEquals(13.91, Tree.co2g(10.0, 300.0), 0.01)
        assertEquals("18시간", Tree.label(Tree.pineDays(10.0, 300.0)))
        assertEquals("2.8일", Tree.label(Tree.pineDays(37.0, 300.0)))
        assertEquals("3일", Tree.spoken(Tree.pineDays(37.0, 300.0)))
        assertEquals("11일", Tree.label(Tree.pineDays(143.0, 300.0)))
        assertEquals(null, Tree.label(Tree.pineDays(0.5, 300.0)))
        assertEquals(0.0, Tree.pineDays(10.0, 0.0), 0.0)
    }

    /** 경사 추정: 1km에 +30m = 3% · 내리막 음수 · 클램프 ±12% · 5m 미만 이동 무시 · 신호 끊기면 0. */
    @Test fun gradeEstimator() {
        val g = GradeEstimator()
        var t = 0L
        for (k in 0..40) { g.onAltitude(k * 25.0, k * 25.0 * 0.03, t); t += 1000 } // 25m마다(90km/h·1Hz) 3% 오르막
        assertEquals(0.03, g.current(t), 0.002)
        for (k in 41..80) { g.onAltitude(k * 25.0, 40 * 25.0 * 0.03 - (k - 40) * 25.0 * 0.05, t); t += 1000 } // 5% 내리막
        assertEquals(-0.05, g.current(t), 0.003)
        assertEquals(0.0, g.current(t + 20_000), 0.0) // 고도 신호 끊김 → 평지 가정
        val s = GradeEstimator()
        for (k in 0..20) s.onAltitude(k * 25.0, k * 25.0 * 0.5, k * 1000L) // 50% 비현실 → 12% 클램프
        assertEquals(GradeEstimator.MAX_GRADE, s.current(20_000), 1e-4)
        val p = GradeEstimator()
        for (k in 0..30) p.onAltitude(100.0, if (k % 2 == 0) 0.0 else 8.0, k * 1000L) // 정차 중 GPS 고도 흔들림
        assertEquals(0.0, p.current(30_000), 0.0)
        assertEquals("오르막 3%", gradeLabel(0.031)); assertEquals("내리막 5%", gradeLabel(-0.05)); assertEquals(null, gradeLabel(0.004))
    }

    /** 경사 반영: 오르막 > 평지 > 내리막 비용 · 권장 속도 불변식은 경사 ±12%에서도 유지 · 감속 감축량은 오르막에서 더 큼. */
    @Test fun gradeAffectsCostButNotSafety() {
        val up = mild.copy(grade = 0.04); val down = mild.copy(grade = -0.04)
        val v = 90 / 3.6
        assertTrue(EnergyModel.whPerKm(car, v, up) > EnergyModel.whPerKm(car, v, mild) + 80) // 2t·g·0.04 ≈ 220Wh/km 바퀴 기준
        assertTrue(EnergyModel.whPerKm(car, v, down) < EnergyModel.whPerKm(car, v, mild))
        for (gr in listOf(-0.12, -0.06, 0.0, 0.06, 0.12)) for (lim in listOf(30, 50, 60, 80, 100, 110)) {
            val c = mild.copy(grade = gr)
            val r = EnergyModel.recommendedKph(car, lim, c)
            val floor = if (lim >= 80) Math.round(lim * 0.85 / 5).toInt() * 5 else maxOf(20, lim - 10)
            assertTrue("grade=$gr lim=$lim rec=$r", r <= lim && r >= floor)
        }
        val sFlat = EnergyModel.savingPer100km(car, 120.0, 95, mild, 300.0)
        val sUp = EnergyModel.savingPer100km(car, 120.0, 95, up, 300.0)
        assertTrue("flat=$sFlat up=$sUp", sUp > sFlat)
        // 엔진 상태에 경사가 실린다(화면 표시 재료)
        val e = CoachEngine(car)
        assertEquals(0.04, e.state(25.0, 110, false, up, null).grade, 1e-9)
    }
}
