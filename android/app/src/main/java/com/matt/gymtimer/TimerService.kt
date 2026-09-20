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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the set alive with the app closed and puts it on the lock screen.
 *
 * Not exported, not bound: the Activity talks to TimerEngine directly and this
 * service only mirrors the engine into a notification and holds the CPU awake
 * while a countdown is running. Every notification action calls the same engine
 * function the on-screen button does.
 */
class TimerService : Service() {

    private var fg = false
    private var wl: PowerManager.WakeLock? = null

    private val nm: NotificationManager
        get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun onCreate() {
        super.onCreate()
        TimerEngine.service = this
        channel()
        TimerEngine.init(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // first thing, always: the system gives a foreground service seconds to
        // show its notification and kills it if it does not
        goForeground()

        when (intent?.action) {
            ACT_PAUSE -> TimerEngine.timerPause()
            ACT_RESUME -> TimerEngine.timerStart()
            ACT_RESTART -> TimerEngine.timerReset()
            ACT_SW_STOP -> TimerEngine.swStop()
            else -> refresh()           // null intent = sticky restart
        }

        if (!TimerEngine.serviceWanted) finishUp()
        return START_STICKY
    }

    /** swiping the app out of recents must not touch a running set */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // deliberately nothing
    }

    override fun onDestroy() {
        releaseWake()
        fg = false
        if (TimerEngine.service === this) TimerEngine.service = null
        super.onDestroy()
    }

    // ------------------------------------------------------------- engine in
    /** called by the engine on every state change while this service is alive */
    fun refresh() {
        if (!fg) return
        try {
            nm.notify(NID, build())
        } catch (_: Exception) {
        }
        syncWake()
    }

    fun finishUp() {
        releaseWake()
        fg = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground() {
        val n = build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NID, n)
            }
            fg = true
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ wake lock
    /**
     * A partial wake lock is what makes the finish land on time with the screen
     * off: the cue Handler runs on uptime, which stops in deep sleep. Held while
     * the countdown is running, and then for the whole ring-out at TIME - the
     * chime repeats for up to two minutes and each repeat is a Handler cue, so
     * the CPU has to stay up for all of it. The lock goes the moment the engine
     * says the ring is over. The stopwatch needs none - it has no timed event
     * and its math is elapsedRealtime.
     */
    private fun syncWake() {
        var w = wl
        if (w == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            w = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "matttimer:run")
            w.setReferenceCounted(false)
            wl = w
        }
        try {
            when {
                TimerEngine.running -> w.acquire(TimerEngine.remainingMs() + 10_000L)
                TimerEngine.alarming -> w.acquire(130_000L)
                else -> if (w.isHeld) w.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWake() {
        try {
            wl?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------- notification
    /**
     * DEFAULT importance, not LOW: Samsung keeps a silent (LOW) notification off
     * the lock screen as a small icon in the top row instead of a card with the
     * countdown and the buttons [measured, 2026-09-20, n=1 on RFGL4275NVH]. An
     * existing channel's importance cannot be raised, so this is a new channel
     * id and the old one is deleted. Silent all the same: no sound, no
     * vibration, and setOnlyAlertOnce on the notification keeps state changes
     * from popping a banner. The engine makes every sound.
     */
    private fun channel() {
        val ch = NotificationChannel(CH, "Timer", NotificationManager.IMPORTANCE_DEFAULT)
        ch.setSound(null, null)          // the engine makes the sounds, not this
        ch.enableVibration(false)
        ch.setShowBadge(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        ch.description = "The running timer, on the lock screen"
        try {
            nm.createNotificationChannel(ch)
        } catch (_: Exception) {
        }
        try {
            nm.deleteNotificationChannel(CH_OLD)
        } catch (_: Exception) {
        }
    }

    private fun act(code: Int, action: String, label: String): Notification.Action {
        val i = Intent(this, TimerService::class.java).setAction(action)
        val pi = PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stat_timer), label, pi
        ).build()
    }

    private fun build(): Notification {
        val e = TimerEngine
        val open = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val b = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setContentIntent(
                PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE)
            )
        // show the card at once instead of after the system's ~10 s foreground
        // service deferral - the lock screen is where he looks first
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        val set = "Timer  ·  " + e.presetLabel(e.presetSec)
        when {
            e.running -> {
                // the system runs the countdown itself: no per-second wakeups from us
                b.setContentTitle("RUNNING").setContentText(set)
                    .setWhen(System.currentTimeMillis() + e.remainingMs())
                    .setShowWhen(true)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                b.addAction(act(1, ACT_PAUSE, "PAUSE"))
                b.addAction(act(2, ACT_RESTART, "RESTART"))
            }
            e.finished -> {
                b.setContentTitle("TIME").setContentText(set).setShowWhen(false)
                b.addAction(act(2, ACT_RESTART, "RESTART"))
            }
            e.paused -> {
                b.setContentTitle("PAUSED  " + e.fmtClock((e.remainMs + 999) / 1000))
                    .setContentText(set).setShowWhen(false)
                b.addAction(act(3, ACT_RESUME, "RESUME"))
                b.addAction(act(2, ACT_RESTART, "RESTART"))
            }
            else -> {                                     // stopwatch running
                b.setContentTitle("STOPWATCH").setContentText("Running")
                    .setWhen(System.currentTimeMillis() - e.swMs())
                    .setShowWhen(true)
                    .setUsesChronometer(true)
                b.addAction(act(4, ACT_SW_STOP, "STOP"))
            }
        }
        return b.build()
    }

    companion object {
        private const val CH = "timer_v2"
        private const val CH_OLD = "timer"   // IMPORTANCE_LOW; deleted on create
        private const val NID = 7

        const val ACT_PAUSE = "com.matt.gymtimer.PAUSE"
        const val ACT_RESUME = "com.matt.gymtimer.RESUME"
        const val ACT_RESTART = "com.matt.gymtimer.RESTART"
        const val ACT_SW_STOP = "com.matt.gymtimer.SW_STOP"
    }
}
