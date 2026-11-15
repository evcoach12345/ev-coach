package app.evcoach

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 절약 순간 효과음(«띠링» · 0.3초) — 음원 파일 없이 코드로 만든 두 음(E6 → B6, 감쇠).
 * 길 안내 음성과 같은 용도(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)로 내보내 음악 위에 짧게 섞인다.
 */
class Chime {
    private val rate = 44_100
    private val pcm: ShortArray = run {
        val notes = listOf(1318.5 to 0.09, 1975.5 to 0.20) // (Hz, 초)
        val out = ArrayList<Short>()
        for ((hz, sec) in notes) {
            val n = (rate * sec).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / rate
                val env = exp(-t * 14) * minOf(1.0, i / 120.0) // 짧은 어택 + 빠른 감쇠
                val s = (sin(2 * PI * hz * t) + 0.25 * sin(4 * PI * hz * t)) * env * 0.32
                out += (s * Short.MAX_VALUE).toInt().toShort()
            }
        }
        out.toShortArray()
    }

    fun play() {
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) { t.release() }
                override fun onPeriodicNotification(t: AudioTrack) = Unit
            })
            track.play()
        }.onFailure { Log.w(Speaker.TAG, "chime fail", it) }
    }
}
