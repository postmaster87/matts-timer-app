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
 * exponential decay - a struck bell rather than a buzzer. The finish cue is a
 * C major arpeggio that lands on a full triad and rings twice.
 *
 * Everything is synthesised once at startup into 16-bit PCM, so there are no
 * audio assets to ship and nothing to load at run time.
 */
class Tones {

    private class Ev(val at: Double, val freq: Double, val dur: Double, val vol: Double)

    val tick: ShortArray
    val go: ShortArray
    val stop: ShortArray
    val lap: ShortArray
    val pick: ShortArray
    val key: ShortArray
    val chime: ShortArray

    init {
        tick = render(bell(0.0, G5, 0.30, 0.17))
        go = render(bell(0.0, E5, 0.45, 0.22))
        stop = render(bell(0.0, C5, 0.40, 0.18))
        lap = render(bell(0.0, B5, 0.28, 0.16))
        pick = render(bell(0.0, G5, 0.26, 0.15))
        key = render(listOf(Ev(0.0, C6, 0.09, 0.06)))

        val c = ArrayList<Ev>()
        c += bell(0.00, C6, 0.90, 0.30)
        c += bell(0.17, E6, 0.90, 0.30)
        c += bell(0.34, G6, 1.50, 0.34)
        c += bell(1.05, C6, 1.80, 0.26)
        c += bell(1.05, E6, 1.80, 0.24)
        c += bell(1.05, G6, 2.00, 0.28)
        chime = render(c)
    }

    /** fundamental + a shimmer octave + a faint upper partial */
    private fun bell(at: Double, freq: Double, dur: Double, vol: Double): List<Ev> = listOf(
        Ev(at, freq, dur, vol),
        Ev(at, freq * 2.0, dur * 0.55, vol * 0.15),
        Ev(at, freq * 2.997, dur * 0.30, vol * 0.05)
    )

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
            for (i in 0 until len) {
                val idx = start + i
                if (idx >= n) break
                val env = if (i < atk) i.toDouble() / atk
                else exp(-5.5 * (i - atk).toDouble() / tail)
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
        /** plenty for these partials (highest is ~4.7 kHz) and a quarter the memory of 44.1k */
        private const val SR = 22050
        private const val ATTACK = 0.014

        // C major, one octave up
        const val C6 = 1046.50
        const val E6 = 1318.51
        const val G6 = 1567.98
        const val G5 = 783.99
        const val E5 = 659.25
        const val C5 = 523.25
        const val B5 = 987.77
    }
}
