package com.matt.gymtimer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * Pleasant, not piercing.
 *
 * Every cue is built from sine partials with a soft attack and a natural
 * exponential decay - a struck bell rather than a buzzer.
 *
 * Three voices, cycled from the header button:
 *   BELL  - C major arpeggio landing on a full triad. Warm, the default.
 *   CHIME - falling G-E-C resolving to an open fifth. Calmer, longer ring.
 *   PULSE - three clipped tones then a high hold. Cuts through a loud gym.
 *
 * Everything is synthesised once at startup into 16-bit PCM, so there are no
 * audio assets to ship and nothing to load at run time.
 */
class Tones {

    private class Ev(
        val at: Double, val freq: Double, val dur: Double, val vol: Double,
        val pure: Boolean = false
    )

    class Voice(
        val name: String,
        val tick: ShortArray,
        val go: ShortArray,
        val chime: ShortArray,
        val preview: ShortArray
    )

    val voices: List<Voice>

    // shared interface sounds, the same whichever voice is selected
    val stop: ShortArray
    val lap: ShortArray
    val pick: ShortArray
    val key: ShortArray

    init {
        stop = render(bell(0.0, C5, 0.40, 0.18))
        lap = render(bell(0.0, B5, 0.28, 0.16))
        pick = render(bell(0.0, G5, 0.26, 0.15))
        key = render(listOf(Ev(0.0, C6, 0.09, 0.06, pure = true)))

        voices = listOf(buildBell(), buildChime(), buildPulse())
    }

    // ---------------------------------------------------------------- voices
    private fun buildBell(): Voice {
        val c = ArrayList<Ev>()
        c += bell(0.00, C6, 0.90, 0.30)
        c += bell(0.17, E6, 0.90, 0.30)
        c += bell(0.34, G6, 1.50, 0.34)
        c += bell(1.05, C6, 1.80, 0.26)
        c += bell(1.05, E6, 1.80, 0.24)
        c += bell(1.05, G6, 2.00, 0.28)

        val p = ArrayList<Ev>()
        p += bell(0.00, C6, 0.60, 0.30)
        p += bell(0.17, E6, 0.60, 0.30)
        p += bell(0.34, G6, 0.90, 0.34)

        return Voice(
            "BELL",
            render(bell(0.0, G5, 0.30, 0.17)),
            render(bell(0.0, E5, 0.45, 0.22)),
            render(c), render(p)
        )
    }

    private fun buildChime(): Voice {
        val c = ArrayList<Ev>()
        c += bell(0.00, G6, 1.20, 0.26)
        c += bell(0.30, E6, 1.20, 0.26)
        c += bell(0.60, C6, 2.10, 0.30)
        c += bell(1.45, C6, 2.40, 0.22)   // open fifth, long ring-out
        c += bell(1.45, G6, 2.40, 0.18)

        val p = ArrayList<Ev>()
        p += bell(0.00, G6, 0.60, 0.26)
        p += bell(0.30, E6, 0.60, 0.26)
        p += bell(0.60, C6, 1.00, 0.30)

        return Voice(
            "CHIME",
            render(bell(0.0, A5, 0.42, 0.13)),
            render(bell(0.0, D5, 0.55, 0.20)),
            render(c), render(p)
        )
    }

    private fun buildPulse(): Voice {
        val c = ArrayList<Ev>()
        for (i in 0 until 3) {
            val t = i * 0.26
            c += Ev(t, E6, 0.17, 0.40, pure = true)
            c += Ev(t, E6 * 2, 0.09, 0.07, pure = true)
        }
        c += Ev(0.88, A6, 0.85, 0.42, pure = true)
        c += Ev(0.88, A6 * 2, 0.30, 0.06, pure = true)
        for (i in 0 until 3) {
            val t = 1.95 + i * 0.26
            c += Ev(t, E6, 0.17, 0.38, pure = true)
        }
        c += Ev(2.83, A6, 1.10, 0.42, pure = true)

        val p = ArrayList<Ev>()
        for (i in 0 until 3) p += Ev(i * 0.26, E6, 0.17, 0.40, pure = true)
        p += Ev(0.88, A6, 0.70, 0.42, pure = true)

        return Voice(
            "PULSE",
            render(listOf(Ev(0.0, A5, 0.10, 0.22, pure = true))),
            render(listOf(Ev(0.0, E6, 0.12, 0.24, pure = true))),
            render(c), render(p)
        )
    }

    /** fundamental + a shimmer octave + a faint upper partial */
    private fun bell(at: Double, freq: Double, dur: Double, vol: Double): List<Ev> = listOf(
        Ev(at, freq, dur, vol),
        Ev(at, freq * 2.0, dur * 0.55, vol * 0.15),
        Ev(at, freq * 2.997, dur * 0.30, vol * 0.05)
    )

    // ---------------------------------------------------------------- render
    private fun render(events: List<Ev>): ShortArray {
        var total = 0.0
        for (e in events) total = max(total, e.at + e.dur)
        val n = ((total + 0.06) * SR).toInt()
        val buf = DoubleArray(n)

        for (e in events) {
            val start = (e.at * SR).toInt()
            val len = (e.dur * SR).toInt()
            if (len <= 1) continue
            val w = 2.0 * PI * e.freq / SR
            val atk = max(1, (ATTACK * SR).toInt())
            val tail = max(1, len - atk)
            val decay = if (e.pure) -3.2 else -5.5   // pure tones hold, bells fall away
            for (i in 0 until len) {
                val idx = start + i
                if (idx >= n) break
                val env = if (i < atk) i.toDouble() / atk
                else exp(decay * (i - atk).toDouble() / tail)
                buf[idx] += sin(w * i) * e.vol * env
            }
        }

        val out = ShortArray(n)
        for (i in 0 until n) {
            val v = buf[i].coerceIn(-1.0, 1.0)
            out[i] = (v * 32767.0).toInt().toShort()
        }
        return out
    }

    fun play(pcm: ShortArray) {
        if (pcm.isEmpty()) return
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(pcm, 0, pcm.size)
            track.notificationMarkerPosition = pcm.size
            track.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack) {
                        try {
                            t.stop(); t.release()
                        } catch (_: Exception) {
                        }
                    }

                    override fun onPeriodicNotification(t: AudioTrack) {}
                })
            track.play()
        } catch (_: Exception) {
            // a dead audio device must never take the timer down with it
        }
    }

    companion object {
        /** plenty for these partials (highest is ~5.3 kHz) and a quarter the memory of 44.1k */
        private const val SR = 22050
        private const val ATTACK = 0.014

        const val C6 = 1046.50
        const val E6 = 1318.51
        const val G6 = 1567.98
        const val A6 = 1760.00
        const val A5 = 880.00
        const val B5 = 987.77
        const val G5 = 783.99
        const val E5 = 659.25
        const val D5 = 587.33
        const val C5 = 523.25
    }
}
