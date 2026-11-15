package app.evcoach

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.Locale

/**
 * Pleos TTS(ON_DEVICE) + 말 많음 방지. 주행 중 운전자 주의 분산을 줄이려고
 * 모든 안내 사이 최소 간격, 종류별 쿨다운을 둔다.
 */
class Speaker(ctx: Context) {
    private val tts = TextToSpeech(ctx.applicationContext, Mode.ON_DEVICE)
    private var ready = false
    private var lastAny = 0L
    private val lastByKind = mutableMapOf<String, Long>()

    init {
        runCatching {
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() { tts.setLocale(Locale.KOREA); ready = true; Log.i(TAG, "tts ready") }
                override fun onFailure(errorType: ErrorType) = Log.w(TAG, "tts init fail $errorType").let { }
            })
        }.onFailure { Log.w(TAG, "tts init error", it) }
    }

    /** kind별 쿨다운(ms). 반환 = 실제로 말했는지. */
    fun say(kind: String, text: String, cooldownMs: Long, minGapMs: Long = 6_000): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAny < minGapMs) return false
        if (now - (lastByKind[kind] ?: -cooldownMs) < cooldownMs) return false
        lastAny = now; lastByKind[kind] = now
        Log.i(TAG, "say[$kind] $text")
        if (ready) runCatching { tts.speak(text) }
        return true
    }

    fun release() = runCatching { tts.release() }

    companion object { const val TAG = "EVCOACH" }
}
