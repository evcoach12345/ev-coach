package app.evcoach.ui

import app.evcoach.coach.CoachState
import app.evcoach.coach.DriveEvent
import app.evcoach.coach.Tree
import app.evcoach.coach.SaveEvent
import app.evcoach.coach.TripLedger
import app.evcoach.coach.VirtualLeague
import app.evcoach.physics.CarSpec
import app.evcoach.physics.Cars
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Pleos Connect 순정 라이트 테마 토큰(출처 = docs/design/UI_RESEARCH.md · Pleos 디자인 가이드).
 * 글꼴 = 시스템 sans-serif = Asta Sans(에뮬레이터 fonts_customization.xml 실측) → 기본 글꼴 그대로 쓴다.
 * 에뮬레이터 밀도 160(1dp = 1px) · 앱 창 ≈ 1706×1210 → 시안(853×605)의 2배 수치.
 */
private object T {
    val Bg = Color(0xFFF7F8FA)
    val Card = Color(0xFFFFFFFF)
    val Sub = Color(0xFFEDEEF2)
    val Line = Color(0xFFE0E1E6)
    val Ink = Color(0xFF131417)
    val Ink2 = Color(0xA3131417) // 64%
    val Ink3 = Color(0x52131417) // 32%
    // 🟢 환경(온실가스·숲) — 연한 바탕 → 진한 글자 톤온톤
    val G1 = Color(0xFFE8F5EE); val G2 = Color(0xFFB5E0C8); val G3 = Color(0xFF6CC496); val G4 = Color(0xFF2F9566)
    // 🔵 전기(전비·충전비·에너지)
    val B1 = Color(0xFFE9F1FC); val B2 = Color(0xFFB7CFF2); val B3 = Color(0xFF7FA8E6); val B4 = Color(0xFF3B74C9)
    // 🔴 비효율(급가속·초과 속도·버린 에너지)
    val R1 = Color(0xFFFCECEB); val R2 = Color(0xFFF4C2BF); val R3 = Color(0xFFE48F89); val R4 = Color(0xFFC75650)
    val Shadow = Color(0x14000000)
    // 소나무 그림도 초록 계열 안에서(줄기 = 가장 진한 초록 · 볼 = 연한 빨강)
    val Leaf = G4
    val Leaf2 = G3
    val Trunk = Color(0xFF2B6B4C)
    val Blush = R2
}

private fun won(x: Double) = "%,d원".format(x.toInt())
/** 온실가스 표기: 1kg 미만 = «13g» · 이상 = «1.2kg». */
private fun co2(g: Double) = if (g < 1000) "%,dg".format(Math.round(g).toInt()) else "%.1fkg".format(g / 1000)
private fun gOf(won: Double, wonPerKwh: Double) = Tree.co2g(won, wonPerKwh)
private fun gradeColor(g: Char) = when (g) { 'A', 'B' -> T.G4; 'C' -> T.R3; else -> T.R4 }

@Composable
fun CoachApp(
    state: CoachState, parked: Boolean, trip: TripLedger, tripVersion: Int,
    car: CarSpec, league: VirtualLeague.Result?, tempC: Double, wonPerKwh: Double,
    onPickCar: (CarSpec) -> Unit, onNewTrip: () -> Unit, signalNote: String? = null,
    impact: SaveEvent? = null, todaySavedWon: Int = 0,
) {
    Box(Modifier.fillMaxSize().background(T.Bg)) {
        if (parked) ParkedScreen(trip, tripVersion, car, league, tempC, wonPerKwh, todaySavedWon, onPickCar, onNewTrip)
        else {
            DrivingScreen(state, wonPerKwh, league)
            impact?.let { ImpactCard(it, wonPerKwh) }
        }
        signalNote?.let {
            Text(it, color = T.R4, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().background(T.R1).padding(16.dp))
        }
    }
}

/** 주행 중(DO): 큰 숫자 1개 = 권장 속도 · 그 아래 온실가스 감축(주) + 충전비(부) · 바닥에 오늘 감축·콤보. 움직이는 요소 없음 · 문구 = 명사형. */
@Composable
private fun DrivingScreen(s: CoachState, wonPerKwh: Double, league: VirtualLeague.Result?) {
    val over = s.speedKph > s.recKph + 5 && s.savingPer100kmWon > 0
    Box(Modifier.fillMaxSize()) {
        Text("제한 ${s.limitKph}${if (s.limitEstimated) " · 추정" else ""}", color = T.Ink2, fontSize = 24.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 48.dp).clip(RoundedCornerShape(12.dp)).background(T.Sub).padding(horizontal = 20.dp, vertical = 10.dp))
        // 경사(GPS 고도 기반 · 1% 이상만) — 권장 속도·감축량 계산에 이미 반영된 값 · 명사형
        gradeLabel(s.grade)?.let {
            Text(it, color = T.Ink2, fontSize = 24.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 104.dp, end = 48.dp).clip(RoundedCornerShape(12.dp)).background(T.Sub).padding(horizontal = 20.dp, vertical = 10.dp))
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("권장 속도", color = T.Ink2, fontSize = 30.sp, fontWeight = FontWeight.Medium)
            Text("${s.recKph}", color = T.B4, fontSize = 336.sp, lineHeight = 336.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-12).sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("현재 ", color = T.Ink2, fontSize = 36.sp)
                Text("${s.speedKph.toInt()}", color = T.Ink, fontSize = 52.sp, fontWeight = FontWeight.Bold)
                Text(" km/h", color = T.Ink2, fontSize = 36.sp)
            }
            Spacer(Modifier.height(28.dp))
            Row(Modifier.shadow(24.dp, RoundedCornerShape(24.dp), ambientColor = T.Shadow, spotColor = T.Shadow)
                .clip(RoundedCornerShape(24.dp)).background(T.Card).padding(horizontal = 40.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (over) {
                    DownIcon(T.R3, 44.dp); Spacer(Modifier.width(20.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${s.recKph}km/h 감속 시 ", color = T.Ink, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                            Text("100km당 온실가스 ${co2(gOf(s.savingPer100kmWon.toDouble(), wonPerKwh))} 감축", color = T.G4, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("충전비 ${won(s.savingPer100kmWon.toDouble())} 절감", color = T.B4, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    CheckIcon(T.G4, 44.dp); Spacer(Modifier.width(20.dp))
                    Text("권장 속도 유지", color = T.Ink, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                }
            }
            // 급가속만 짧게 표시(급제동은 안전 행동일 수 있어 주행 중엔 벌점 표시 안 함 · 리포트에만)
            s.lastEvent?.takeIf { it.kind == DriveEvent.Kind.ACCEL && it.grade >= 'C' }?.let { e ->
                Spacer(Modifier.height(20.dp))
                Text("급가속 ${e.grade} · 온실가스 ${co2(gOf(e.lossWon, wonPerKwh))} 추가 배출", color = gradeColor(e.grade), fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
        }
        // 바닥 HUD(항상 떠 있음): 모은 온실가스 감축 · 충전비 · 실시간 순위 · 콤보 — 모으는 재미
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 40.dp, vertical = 32.dp)
            .shadow(20.dp, RoundedCornerShape(32.dp), ambientColor = T.Shadow, spotColor = T.Shadow)
            .clip(RoundedCornerShape(32.dp)).background(T.Card).padding(horizontal = 36.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CuteTree(Modifier.size(56.dp)); Spacer(Modifier.width(14.dp))
                Column {
                    Text("오늘 온실가스 감축", color = T.Ink2, fontSize = 22.sp)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(co2(gOf(s.todaySavedWon.toDouble(), wonPerKwh)), color = T.G4, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold)
                        Tree.label(Tree.pineDays(s.todaySavedWon.toDouble(), wonPerKwh))?.let { d ->
                            Text("  소나무 $d", color = T.G4, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                        }
                    }
                }
            }
            HudStat("충전비 절감", "+${won(s.todaySavedWon.toDouble())}", T.B4)
            HudStat("실시간 순위", league?.let { "상위 ${it.topPercent}%" } ?: "1분 주행 후", if (league != null) T.G4 else T.Ink3,
                sub = league?.let { "가상 운전자 ${VirtualLeague.N}명 중" })
            HudStat("콤보", "×${s.combo}", T.Ink)
        }
    }
}

@Composable
private fun HudStat(label: String, value: String, color: Color, sub: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = T.Ink2, fontSize = 22.sp)
        Text(value, color = color, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold)
        sub?.let { Text(it, color = T.Ink3, fontSize = 16.sp) }
    }
}

/**
 * 절약 순간 임팩트(1.5초 뒤 사라짐 · 🔴 Pleos 주행 중 애니메이션 금지 → 움직임 없는 정적 카드).
 * 감축(SMOOTH_DECEL·PACE) = 큰 숫자 «온실가스 Ng» + 작은 «충전비 +N원» · 부드러운 가속(GENTLE · 1원 미만) = «콤보 ×N».
 */
@Composable
private fun ImpactCard(e: SaveEvent, wonPerKwh: Double) {
    Box(Modifier.fillMaxSize().background(T.Bg), contentAlignment = Alignment.Center) { // 불투명: 뒤 숫자가 비치면 1~2초 판독 방해
        Box(Modifier.size(1040.dp).clip(CircleShape).background(T.G1))
        Box(Modifier.size(760.dp).clip(CircleShape).background(T.G2.copy(alpha = 0.45f)))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.shadow(24.dp, CircleShape, ambientColor = T.Shadow, spotColor = T.Shadow)
                .clip(CircleShape).background(T.Card).padding(horizontal = 36.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                CheckIcon(T.G4, 40.dp); Spacer(Modifier.width(16.dp))
                Text(e.kind.title, color = T.Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(20.dp))
            if (e.won > 0) {
                Text(co2(gOf(e.won.toDouble(), wonPerKwh)), color = T.G4, fontSize = 208.sp, lineHeight = 220.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-6).sp)
                Text("온실가스 감축", color = T.Ink, fontSize = 80.sp, lineHeight = 96.sp, fontWeight = FontWeight.ExtraBold)
                Tree.label(Tree.pineDays(e.won.toDouble(), wonPerKwh))?.let { d ->
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CuteTree(Modifier.size(120.dp)); Spacer(Modifier.width(12.dp))
                        Text("소나무 $d 치 숨", color = T.G4, fontSize = 52.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("충전비 +${won(e.won.toDouble())} 절감", color = T.B4, fontSize = 40.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(T.B1).padding(horizontal = 24.dp, vertical = 8.dp))
            } else {
                Text("콤보 ×${e.combo}", color = T.G4, fontSize = 192.sp, lineHeight = 210.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-6).sp)
                Text("연속 부드러운 운전", color = T.Ink, fontSize = 72.sp, lineHeight = 90.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(24.dp))
            val basis = when (e.kind) {
                SaveEvent.Kind.SMOOTH_DECEL -> "급제동 대비 회생 회수"
                SaveEvent.Kind.PACE -> "감속 전 속도 대비"
                SaveEvent.Kind.GENTLE -> "급가속 없는 출발"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$basis · 오늘 누적 감축 ", color = T.Ink2, fontSize = 32.sp)
                Text(co2(gOf(e.todayWon.toDouble(), wonPerKwh)), color = T.G4, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(" · 콤보 ×${e.combo}", color = T.Ink2, fontSize = 32.sp)
            }
        }
    }
}

/** 정차 중: 운행 리포트(온실가스 감축 주 · 충전비 부) + 같은 길 가상 리그 + 에너지 내역 + 가감속 기록 + 차종 선택. */
@Composable
private fun ParkedScreen(
    t: TripLedger, @Suppress("UNUSED_PARAMETER") version: Int, car: CarSpec, league: VirtualLeague.Result?, tempC: Double, wonPerKwh: Double,
    todaySavedWon: Int, onPickCar: (CarSpec) -> Unit, onNewTrip: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(32.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("이번 운행", color = T.Ink, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    Text(car.label + "  ▾", color = T.Ink2, fontSize = 24.sp,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(T.Sub).clickable { picking = !picking }.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(co2(gOf(t.savedWon, wonPerKwh)), color = T.G4, fontSize = 112.sp, lineHeight = 120.sp, fontWeight = FontWeight.ExtraBold)
                    Text("  온실가스 감축", color = T.Ink, fontSize = 36.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 14.dp))
                }
                Tree.label(Tree.pineDays(t.savedWon, wonPerKwh))?.let { d -> MyForest(Tree.pineDays(t.savedWon, wonPerKwh), d, Tree.co2g(t.savedWon, wonPerKwh)) }
                Row(Modifier.fillMaxWidth()) {
                    Stat("거리", "%.1f km".format(t.km), Modifier.weight(1f))
                    Stat("전비 km/kWh", if (t.batteryWh > 1) "%.1f".format(t.kmPerKwh) else "—", Modifier.weight(1f), T.B4)
                    Stat("충전비 절감", "+${won(t.savedWon)}", Modifier.weight(1f), T.B4)
                    Stat("충전비", won(t.won(wonPerKwh)), Modifier.weight(1f))
                }
                Text("오늘 누적 감축 ${co2(gOf(todaySavedWon.toDouble(), wonPerKwh))} · 충전비 +${won(todaySavedWon.toDouble())} · 기온 ${tempC.toInt()}°C · 단가 ${wonPerKwh.toInt()}원/kWh(가정)", color = T.Ink2, fontSize = 22.sp)
            }
            Card(Modifier.fillMaxWidth().weight(1f)) {
                Text("같은 길 · 같은 날씨 가상 운전자 ${VirtualLeague.N}명 중", color = T.Ink, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                if (league != null) {
                    Text("상위 ${league.topPercent}%", color = T.B4, fontSize = 88.sp, lineHeight = 96.sp, fontWeight = FontWeight.ExtraBold)
                    Box(Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(10.dp)).background(T.B1)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(((100 - league.topPercent) / 100f).coerceIn(0.02f, 1f)).clip(RoundedCornerShape(10.dp)).background(T.B3))
                    }
                    Text("시뮬레이션 기준 · 나 %.1f · 가운데 %.1f · 상위 10%% %.1f km/kWh".format(league.mine, league.median, league.best10), color = T.Ink2, fontSize = 24.sp)
                } else {
                    Text("%.0fkm 이상 주행 후 표시".format(VirtualLeague.MIN_KM), color = T.Ink3, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                }
                Text("가감속·속도·공조만 다른 가상 리그 · 추후 ${car.rankModel} 실사용자 순위 전환", color = T.Ink3, fontSize = 20.sp)
            }
            Text("새 운행 시작", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.B4).clickable { onNewTrip() }.padding(vertical = 22.dp))
        }
        Card(Modifier.weight(1f).fillMaxHeight()) {
            if (picking) {
                Text("차종 선택", color = T.Ink, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                LazyColumn { items(Cars.ALL) { c ->
                    Text(c.label, color = if (c.id == car.id) T.B4 else T.Ink, fontSize = 30.sp, fontWeight = if (c.id == car.id) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clickable { onPickCar(c); picking = false }.padding(vertical = 16.dp))
                } }
            } else {
                Text("에너지 사용 내역", color = T.Ink, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Breakdown(t, wonPerKwh)
                Spacer(Modifier.height(8.dp))
                Text("가감속 기록", color = T.Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                if (t.events.isEmpty()) Text("기록 없음", color = T.Ink3, fontSize = 24.sp)
                LazyColumn { items(t.events.takeLast(6).reversed()) { e -> EventRow(e, wonPerKwh) } }
            }
        }
    }
}

@Composable
private fun Breakdown(t: TripLedger, wonPerKwh: Double) {
    val parts = listOf(
        Triple("공기저항", t.aeroWh, T.B4), Triple("구름저항", t.rollWh, T.B3), Triple("경사", t.gradeWh.coerceAtLeast(0.0), T.B3),
        Triple("모터 손실", t.driveLossWh, T.B2), Triple("공조·전장", t.auxWh, T.B2), Triple("마찰 브레이크(버린 에너지)", t.frictionWh, T.R3),
    )
    val max = parts.maxOf { it.second }.coerceAtLeast(1.0)
    parts.forEach { (name, wh, color) ->
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name, color = T.Ink, fontSize = 26.sp)
                Text("%,.0f Wh".format(wh), color = T.Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(T.Sub)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth((wh / max).toFloat().coerceIn(0.01f, 1f)).clip(RoundedCornerShape(7.dp)).background(color))
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(T.Line))
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("회생 회수 에너지", color = T.Ink, fontSize = 28.sp)
        Text("+%,.0f Wh · %s".format(t.regenWh, won(t.regenWh / 1000 * wonPerKwh)), color = T.B4, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun EventRow(e: DriveEvent, wonPerKwh: Double) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${e.grade}", color = gradeColor(e.grade), fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.width(48.dp))
        Text("${e.kind.ko} ${e.fromKph}→${e.toKph}km/h · 최대 %.1fm/s²".format(e.peakAccel), color = T.Ink, fontSize = 24.sp, modifier = Modifier.weight(1f))
        Text("+${co2(gOf(e.lossWon, wonPerKwh))}", color = if (e.lossWon >= 1) T.R4 else T.Ink3, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Card(modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.shadow(24.dp, RoundedCornerShape(48.dp), ambientColor = T.Shadow, spotColor = T.Shadow)
        .clip(RoundedCornerShape(48.dp)).background(T.Card).padding(horizontal = 48.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) { content() }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, color: Color = T.Ink) {
    Column(modifier) {
        Text(label, color = T.Ink2, fontSize = 24.sp)
        Text(value, color = color, fontSize = 36.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CheckIcon(color: Color, size: Dp) = Canvas(Modifier.size(size)) {
    val w = this.size.width
    val p = Path().apply { moveTo(w * 0.21f, w * 0.5f); lineTo(w * 0.375f, w * 0.667f); lineTo(w * 0.79f, w * 0.25f) }
    drawPath(p, color, style = Stroke(width = w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

@Composable
private fun DownIcon(color: Color, size: Dp) = Canvas(Modifier.size(size)) {
    val w = this.size.width
    val sw = w * 0.09f
    drawLine(color, Offset(w * 0.5f, w * 0.12f), Offset(w * 0.5f, w * 0.62f), strokeWidth = sw, cap = StrokeCap.Round)
    drawPath(Path().apply { moveTo(w * 0.29f, w * 0.42f); lineTo(w * 0.5f, w * 0.62f); lineTo(w * 0.71f, w * 0.42f) }, color,
        style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawLine(color, Offset(w * 0.21f, w * 0.83f), Offset(w * 0.79f, w * 0.83f), strokeWidth = sw, cap = StrokeCap.Round)
}

/** 나의 숲: 소나무 하루 치 = 다 자란 나무 1그루 · 남은 몫 = 새싹 · 최대 8그루까지 그리고 나머지는 숫자로. */
@Composable
private fun MyForest(days: Double, label: String, co2g: Double) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(T.G1).padding(horizontal = 24.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            val full = days.toInt()
            repeat(minOf(full, 8)) { CuteTree(Modifier.size(64.dp)) }
            if (full < 8 && days - full >= 0.1) CuteTree(Modifier.size(64.dp), sapling = true)
            if (full > 8) Text(" +${full - 8}", color = T.G4, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(16.dp))
            Column {
                Text("나의 숲 · 소나무 $label 치 흡수량", color = T.G4, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                Text("온실가스 ${co2(co2g)} 감축 · 소나무 1그루 흡수 시간 환산", color = T.Ink2, fontSize = 20.sp, maxLines = 1)
            }
        }
        Text("환산: 전력 배출계수 0.417kg/kWh(2023) · 30년생 소나무 1그루 연 6.6kg(산림청)", color = T.Ink3, fontSize = 16.sp)
    }
}

/** 귀여운 소나무(정적 그림 · 움직임 없음). sapling = 새싹(아직 하루가 안 찬 몫). */
@Composable
fun CuteTree(modifier: Modifier, sapling: Boolean = false) = Canvas(modifier) {
    val w = size.width; val h = size.height
    if (sapling) {
        drawLine(T.Leaf, Offset(w * 0.5f, h * 0.95f), Offset(w * 0.5f, h * 0.55f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
        drawOval(T.Leaf2, topLeft = Offset(w * 0.18f, h * 0.42f), size = androidx.compose.ui.geometry.Size(w * 0.32f, h * 0.2f))
        drawOval(T.Leaf, topLeft = Offset(w * 0.5f, h * 0.34f), size = androidx.compose.ui.geometry.Size(w * 0.32f, h * 0.2f))
        return@Canvas
    }
    drawRoundRect(T.Trunk, topLeft = Offset(w * 0.42f, h * 0.74f), size = androidx.compose.ui.geometry.Size(w * 0.16f, h * 0.22f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.05f))
    fun tier(top: Float, bottom: Float, half: Float, c: Color) = drawPath(Path().apply {
        moveTo(w * 0.5f, h * top); lineTo(w * (0.5f + half), h * bottom); lineTo(w * (0.5f - half), h * bottom); close()
    }, c, style = androidx.compose.ui.graphics.drawscope.Fill).also {
        drawPath(Path().apply { moveTo(w * 0.5f, h * top); lineTo(w * (0.5f + half), h * bottom); lineTo(w * (0.5f - half), h * bottom); close() },
            c, style = Stroke(width = w * 0.08f, join = StrokeJoin.Round))
    }
    tier(0.06f, 0.40f, 0.26f, T.Leaf2)
    tier(0.22f, 0.58f, 0.34f, T.Leaf)
    tier(0.38f, 0.78f, 0.42f, T.Leaf)
    // 얼굴: 눈 2개 · 볼터치 · 미소
    val eyeY = h * 0.60f
    drawCircle(T.Ink, radius = w * 0.04f, center = Offset(w * 0.40f, eyeY))
    drawCircle(T.Ink, radius = w * 0.04f, center = Offset(w * 0.60f, eyeY))
    drawCircle(T.Blush, radius = w * 0.05f, center = Offset(w * 0.30f, eyeY + h * 0.06f))
    drawCircle(T.Blush, radius = w * 0.05f, center = Offset(w * 0.70f, eyeY + h * 0.06f))
    drawArc(T.Ink, startAngle = 20f, sweepAngle = 140f, useCenter = false,
        topLeft = Offset(w * 0.44f, eyeY - h * 0.01f), size = androidx.compose.ui.geometry.Size(w * 0.12f, h * 0.08f),
        style = Stroke(width = w * 0.03f, cap = StrokeCap.Round))
}

/** 경사 표시 문구: «오르막 4%» / «내리막 3%» · 1% 미만 = 표시 없음(평지). */
internal fun gradeLabel(grade: Double): String? {
    val pct = kotlin.math.round(grade * 100).toInt()
    return when {
        pct >= 1 -> "오르막 $pct%"
        pct <= -1 -> "내리막 ${-pct}%"
        else -> null
    }
}
