package com.matt.gymtimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.min

/**
 * The alarm while it rings: foreground, wake lock, audio focus, the ring loop,
 * the notification with its full-screen screen, and the missed card. Built to
 * docs/ALARM_SPEC.md Section 6.
 *
 * Not exported, not bound, direct-boot aware. It never reads the timer's
 * settings: MUTE does not reach the alarm (his answer 6: "ignores mute").
 */
class AlarmService : Service() {

    private val h = Handler(Looper.getMainLooper())
    private var gen = 0
    private var live = false
    private var wl: PowerManager.WakeLock? = null
    private var playing: Tones.Playing? = null
    private var focusReq: AudioFocusRequest? = null
    private var hasFocus = false
    private var vib: Vibrator? = null

    override fun onCreate() {
        super.onCreate()
        AlarmEngine.service = this
        channels(this)
        AlarmEngine.init(applicationContext)
        vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val e = AlarmEngine
        val now = System.currentTimeMillis()
        val action = intent?.action
        val fireId = if (action == AlarmEngine.ACT_FIRE) intent.getIntExtra("id", -1) else -1
        val resumable = intent == null && e.ringId != -1 &&
            now - e.ringAtWall in 0..AlarmEngine.LATE_MS

        // first thing, always: the system kills a foreground service that does
        // not show its notification in time. The full-screen screen is attached
        // only when a ring is starting or held, so a STOP or a dead sticky
        // restart does not light the screen for nothing.
        val label = when {
            live -> e.labelFor(e.ringId)
            fireId != -1 -> e.labelFor(fireId)
            else -> e.labelFor(e.ringId)
        }
        goForeground(label, live || action == AlarmEngine.ACT_FIRE || resumable)

        when {
            action == AlarmEngine.ACT_FIRE -> {
                if (e.fire(fireId)) {
                    if (!live) begin()
                } else if (!live) {
                    quit()
                }
            }
            action == AlarmEngine.ACT_STOP -> endRing(false)
            intent == null -> {                          // sticky restart
                if (live) {
                    // nothing to do: the ring is running
                } else if (resumable) {
                    e.resumeRing(now)
                    begin()
                } else {
                    if (e.ringId != -1) {               // over 15 minutes: missed
                        e.missedRing()
                        e.ringEnded()
                    }
                    quit()
                }
            }
            else -> if (!live) quit()
        }
        return START_STICKY
    }

    /** swiping the app out of recents must not touch a ring */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // deliberately nothing
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        gen++
        playing?.stop()
        playing = null
        focusAbandon()
        releaseWake()
        if (AlarmEngine.service === this) AlarmEngine.service = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ ring
    private fun begin() {
        live = true
        gen++
        wake()
        focusRequest()
        val g = gen
        h.post { cue(g) }
    }

    /**
     * One phrase, then the cue for the next. Every decision is made against
     * elapsedRealtime - a cue the system held back cannot stretch the ring.
     * The voice and the buzz are read at each fire. Gain rises in a straight
     * line from 0.08 to 1.0 over the first 90 s (D3); 1.0 is his alarm volume
     * as it already is - no stream volume is ever written.
     */
    private fun cue(g: Int) {
        if (!live || g != gen) return
        val t = SystemClock.elapsedRealtime() - AlarmEngine.ringFrom
        if (t > CUT_MS) {
            endRing(true)
            return
        }
        val pcm = AlarmEngine.phrase()
        val gain = min(1.0, 0.08 + 0.92 * t.coerceAtLeast(0) / RAMP_MS.toDouble()).toFloat()
        playing = AlarmEngine.tones.play(pcm, gain)
        if (AlarmEngine.vibe) buzz()

        val len = Tones.durationMs(pcm)
        val period = len + GAP_MS
        if (t + period <= CUT_MS) {
            h.postDelayed({ cue(g) }, period)
        } else {
            // no phrase starts past 15 minutes: this one rings out, then MISSED
            h.postDelayed({ if (live && g == gen) endRing(true) }, len)
        }
    }

    /** STOP (screen or card), or the 15-minute cut with [missed] */
    fun endRing(missed: Boolean) {
        if (missed) AlarmEngine.missedRing()
        live = false
        gen++
        h.removeCallbacksAndMessages(null)
        playing?.stop()
        playing = null
        try {
            vib?.cancel()
        } catch (_: Exception) {
        }
        focusAbandon()
        releaseWake()
        AlarmEngine.ringEnded()
        quit()
    }

    private fun quit() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    private fun buzz() {
        val v = vib ?: return
        try {
            val fx = VibrationEffect.createWaveform(BUZZ, -1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(fx, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(fx, alarmAttrs())
            }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ wake lock
    private fun wake() {
        var w = wl
        if (w == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            w = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "matttimer:alarm")
            w.setReferenceCounted(false)
            wl = w
        }
        try {
            w.acquire(WAKE_MS)
        } catch (_: Exception) {
        }
    }

    private fun releaseWake() {
        try {
            wl?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
    }

    // ----------------------------------------------------------- audio focus
    private fun alarmAttrs(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun focusRequest() {
        if (hasFocus) return
        try {
            val r = focusReq ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(alarmAttrs())
                .setOnAudioFocusChangeListener { }
                .build()
            focusReq = r
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).requestAudioFocus(r)
            hasFocus = true
        } catch (_: Exception) {
        }
    }

    private fun focusAbandon() {
        if (!hasFocus) return
        hasFocus = false
        try {
            focusReq?.let {
                (getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it)
            }
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------- notification
    private fun goForeground(label: String, fullScreen: Boolean) {
        val n = build(label, fullScreen)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NID, n)
            }
        } catch (_: Exception) {
        }
    }

    private fun build(label: String, fullScreen: Boolean): Notification {
        val stopPi = PendingIntent.getService(
            this, 200001,
            Intent(this, AlarmService::class.java).setAction(AlarmEngine.ACT_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val screen = Intent(this, AlarmActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val screenPi = PendingIntent.getActivity(this, 200002, screen, PendingIntent.FLAG_IMMUTABLE)

        val b = Notification.Builder(this, CH_RING)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentTitle("ALARM")
            .setContentText(label)
            .setShowWhen(false)
            .setContentIntent(screenPi)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_timer), "STOP", stopPi
                ).build()
            )
        if (fullScreen) b.setFullScreenIntent(screenPi, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    companion object {
        const val CH_RING = "alarm_ring"
        const val CH_MISSED = "alarm_missed"
        private const val NID = 8

        private const val CUT_MS = 900_000L      // his answer 7: 15 minutes
        private const val RAMP_MS = 90_000L      // D3
        private const val GAP_MS = 1500L
        private const val WAKE_MS = 16 * 60_000L
        private val BUZZ = longArrayOf(0, 300, 120, 300, 120, 500)

        /**
         * Both channels silent: the service makes every sound. Also called
         * before a missed card is posted from outside the service (a restart
         * or a clock change), when this service may never have been created.
         */
        fun channels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            val ring = NotificationChannel(CH_RING, "Alarm ringing", NotificationManager.IMPORTANCE_HIGH)
            ring.setSound(null, null)
            ring.enableVibration(false)
            ring.setShowBadge(false)
            ring.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            ring.description = "The alarm while it rings, over the lock screen"
            val miss = NotificationChannel(CH_MISSED, "Missed alarm", NotificationManager.IMPORTANCE_DEFAULT)
            miss.setSound(null, null)
            miss.enableVibration(false)
            miss.description = "An alarm that rang 15 minutes untouched, or could not ring"
            try {
                nm.createNotificationChannel(ring)
                nm.createNotificationChannel(miss)
            } catch (_: Exception) {
            }
        }
    }
}
