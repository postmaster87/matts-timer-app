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

    /**
     * [atk] is the attack in seconds; [decay] overrides the pure/bell decay
     * rate when set; [fade] is a linear fade to silence over the last [fade]
     * seconds, so a soft tone ends without a click. The defaults leave every
     * existing timer cue exactly as it was.
     */
    private class Ev(
        val at: Double, val freq: Double, val dur: Double, val vol: Double,
        val pure: Boolean = false,
        val atk: Double = ATTACK,
        val decay: Double = Double.NaN,
        val fade: Double = 0.0
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
            val atk = max(1, (e.atk * SR).toInt())
            val tail = max(1, len - atk)
            val decay = if (!e.decay.isNaN()) e.decay
            else if (e.pure) -3.2 else -5.5          // pure tones hold, bells fall away
            val fadeN = (e.fade * SR).toInt()
            for (i in 0 until len) {
                val idx = start + i
                if (idx >= n) break
                var env = if (i < atk) i.toDouble() / atk
                else exp(decay * (i - atk).toDouble() / tail)
                if (fadeN > 0 && len - i < fadeN) env *= (len - i).toDouble() / fadeN
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

    /**
     * [gain] scales THIS cue only - it is the AudioTrack's own volume, never a
     * stream volume, so 1f means "as loud as his alarm volume already is" and
     * the phone's sliders are never written.
     */
    fun play(pcm: ShortArray, gain: Float = 1f): Playing? {
        if (pcm.isEmpty()) return null
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

            val handle = Playing(track)
            track.write(pcm, 0, pcm.size)
            track.notificationMarkerPosition = pcm.size
            track.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack) {
                        handle.finish()
                    }

                    override fun onPeriodicNotification(t: AudioTrack) {}
                })
            track.setVolume(gain.coerceIn(0f, 1f))
            track.play()
            return handle
        } catch (_: Exception) {
            // a dead audio device must never take the timer down with it
            return null
        }
    }

    /**
     * One playing cue. The timer ignores it; the alarm keeps it so STOP can
     * silence a phrase mid-ring. Released exactly once, whichever comes first:
     * the end of the cue or [stop].
     */
    class Playing internal constructor(private val track: AudioTrack) {
        @Volatile
        private var done = false

        /** the cue rang out on its own */
        internal fun finish() {
            if (done) return
            done = true
            try {
                track.stop(); track.release()
            } catch (_: Exception) {
            }
        }

        /** cut it off now */
        fun stop() {
            if (done) return
            done = true
            try {
                track.pause(); track.stop()
            } catch (_: Exception) {
            }
            try {
                track.release()
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------ alarm voices
    /**
     * The alarm's own sound list. His words, 2026-09-27: "something that can
     * wake but not annoy me". Four wake voices - every fundamental at or below
     * 900 Hz, attacks of 40 ms or longer, no clipped pure tones, each ending on
     * a fade - then the timer's three chimes. Rendered on first use, never at
     * startup, so the timer opens no slower than it did.
     */
    class AlarmVoice(val name: String, mk: () -> ShortArray, mkPreview: () -> ShortArray) {
        val phrase: ShortArray by lazy(mk)
        val preview: ShortArray by lazy(mkPreview)
    }

    val alarmVoices: List<AlarmVoice> = listOf(
        AlarmVoice(ALARM_VOICE_NAMES[0], { render(dawn(false)) }, { render(dawn(true)) }),
        AlarmVoice(ALARM_VOICE_NAMES[1], { render(harp(false)) }, { render(harp(true)) }),
        AlarmVoice(ALARM_VOICE_NAMES[2], { render(tide(false)) }, { render(tide(true)) }),
        AlarmVoice(ALARM_VOICE_NAMES[3], { render(lilt(false)) }, { render(lilt(true)) }),
        AlarmVoice(ALARM_VOICE_NAMES[4], { voices[0].chime }, { voices[0].preview }),
        AlarmVoice(ALARM_VOICE_NAMES[5], { voices[1].chime }, { voices[1].preview }),
        AlarmVoice(ALARM_VOICE_NAMES[6], { voices[2].chime }, { voices[2].preview })
    )

    /** a soft struck tone: fundamental, a quiet octave, a faint twelfth, faded out */
    private fun soft(
        at: Double, f: Double, dur: Double, vol: Double,
        atk: Double = 0.06, decay: Double = -4.0
    ): List<Ev> = listOf(
        Ev(at, f, dur, vol, atk = atk, decay = decay, fade = 0.15),
        // a partial's attack never takes more than half of its own length
        Ev(at, f * 2.0, dur * 0.5, vol * 0.12, atk = minOf(atk, dur * 0.25), decay = decay, fade = 0.10),
        Ev(at, f * 3.0, dur * 0.25, vol * 0.03, atk = minOf(atk, dur * 0.125), decay = decay, fade = 0.05)
    )

    /** DAWN - four notes climbing slowly, G-C-E-G, settling on a C major chord. ~5.9 s */
    private fun dawn(preview: Boolean): List<Ev> {
        val rise = doubleArrayOf(G4, C5, E5, G5)
        val c = ArrayList<Ev>()
        if (preview) {                                   // ~2.8 s
            for (i in rise.indices) c += soft(i * 0.45, rise[i], 1.4, 0.22, atk = 0.08)
            return c
        }
        for (i in rise.indices) c += soft(i * 0.6, rise[i], 2.4, 0.22, atk = 0.08)
        c += soft(2.6, C5, 3.2, 0.16, atk = 0.12, decay = -3.0)
        c += soft(2.6, E5, 3.2, 0.14, atk = 0.12, decay = -3.0)
        c += soft(2.6, G5, 3.2, 0.12, atk = 0.12, decay = -3.0)
        return c
    }

    /** HARP - a quick pentatonic ripple up and back down, landing on C and G. ~4.6 s */
    private fun harp(preview: Boolean): List<Ev> {
        val up = doubleArrayOf(G4, A4, C5, D5, E5, G5, A5)
        val c = ArrayList<Ev>()
        for (i in up.indices) c += soft(i * 0.16, up[i], if (preview) 1.0 else 1.1, 0.15, atk = 0.04, decay = -5.0)
        if (preview) return c                            // ~2.1 s
        val down = doubleArrayOf(G5, E5, D5, C5, A4, G4)
        for (i in down.indices) c += soft(1.4 + i * 0.16, down[i], 1.1, 0.15, atk = 0.04, decay = -5.0)
        c += soft(2.5, C5, 2.0, 0.18, atk = 0.05, decay = -3.5)
        c += soft(2.5, G4, 2.0, 0.14, atk = 0.05, decay = -3.5)
        return c
    }

    /** TIDE - no melody: two soft chords that swell in and ebb away, F then C. ~6.7 s */
    private fun tide(preview: Boolean): List<Ev> {
        val c = ArrayList<Ev>()
        if (preview) {                                   // ~2.7 s
            for (f in doubleArrayOf(F4, A4, C5)) c += soft(0.0, f, 2.6, 0.14, atk = 0.7, decay = -2.6)
            return c
        }
        for (f in doubleArrayOf(F4, A4, C5)) c += soft(0.0, f, 3.2, 0.14, atk = 1.0, decay = -2.6)
        for (f in doubleArrayOf(G4, C5, E5)) c += soft(3.0, f, 3.6, 0.14, atk = 1.0, decay = -2.6)
        return c
    }

    /** LILT - falling three-note figures, one a second, resting on A and E. ~5.1 s */
    private fun lilt(preview: Boolean): List<Ev> {
        val c = ArrayList<Ev>()
        fun fig(at: Double, a: Double, b: Double, d: Double) {
            c += soft(at, a, 0.9, 0.18, atk = 0.045, decay = -4.5)
            c += soft(at + 0.2, b, 0.9, 0.18, atk = 0.045, decay = -4.5)
            c += soft(at + 0.4, d, 0.9, 0.18, atk = 0.045, decay = -4.5)
        }
        fig(0.0, E5, CS5, A4)
        fig(1.0, FS5, D5, B4)
        if (preview) {                                   // ~2.9 s
            c += soft(1.8, A4, 1.0, 0.16, atk = 0.06, decay = -3.5)
            c += soft(1.8, E5, 1.0, 0.14, atk = 0.06, decay = -3.5)
            return c
        }
        fig(2.0, E5, CS5, A4)
        c += soft(3.0, A4, 2.0, 0.16, atk = 0.06, decay = -3.5)
        c += soft(3.0, E5, 2.0, 0.14, atk = 0.06, decay = -3.5)
        return c
    }

    companion object {
        /** plenty for these partials (highest is ~5.3 kHz) and a quarter the memory of 44.1k */
        private const val SR = 22050
        private const val ATTACK = 0.014

        /** how long a rendered cue rings, in ms - the repeat spacing is built on it */
        fun durationMs(pcm: ShortArray): Long = pcm.size * 1000L / SR

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

        // the alarm's wake voices stay at or below 900 Hz
        const val FS5 = 739.99
        const val CS5 = 554.37
        const val B4 = 493.88
        const val A4 = 440.00
        const val G4 = 392.00
        const val F4 = 349.23

        /** the alarm's sound list, by name - readable without rendering anything */
        val ALARM_VOICE_NAMES = listOf("DAWN", "HARP", "TIDE", "LILT", "BELL", "CHIME", "PULSE")
    }
}
