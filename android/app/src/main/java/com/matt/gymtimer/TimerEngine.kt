package com.matt.gymtimer

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.abs

/**
 * The timer itself - the single source of truth for the countdown, the
 * stopwatch, the cues and the sound settings.
 *
 * It lives in the process, not in the Activity, so a set keeps running with the
 * app closed, the screen off or the task swiped away. The cues and the finish
 * fire from this object's own main-looper Handler; the Activity only draws.
 *
 * Clock math is elapsedRealtime() based throughout:
 *   endsAt  = now + remainMs          (start)
 *   remainMs = endsAt - now, >= 0     (pause)
 *   swBase  = now - swElapsed         (stopwatch start)
 */
object TimerEngine {

    const val DEFAULT_SEC = 35
    val PRESETS = intArrayOf(35, 45, 60, 300, 600, 900, 1200, 1800, 3600)

    /** music is ducked from this many ms before zero until RESET / RESTART */
    private const val DUCK_AT = 3000L

    /**
     * The ring-out after TIME. His words, 2026-09-20: "Can you gradually
     * increase the timer volume once it has expired". The chime repeats until
     * he taps RESTART / RESET / the lock-screen button, soft on the first one
     * and full by the fifth, then full for the rest.
     *
     * The gain is the AudioTrack's own volume - 1.0 is whatever his alarm
     * volume already is. No stream volume is ever written.
     */
    private val ALARM_GAIN = floatArrayOf(0.30f, 0.475f, 0.65f, 0.825f, 1.0f)

    /** silence between the end of one chime and the start of the next */
    private const val ALARM_GAP = 1000L

    /** nothing new starts later than this past the finish */
    private const val ALARM_MAX = 120_000L

    private val ALARM_BUZZ = longArrayOf(0, 300, 120, 300, 120, 500)

    /** same-boot tolerance for the elapsedRealtime-vs-wall-clock offset */
    private const val BOOT_SLACK = 60_000L

    /**
     * The stale rule. His words, 2026-09-20: "if the app is "killed with a timer
     * running for more than ten minutes kill it and shut down the timer app."
     * A set whose finish is further than this in the past is dropped when the
     * process comes back, instead of being restored onto a red TIME screen.
     */
    private const val STALE_MS = 600_000L

    interface Listener {
        fun onEngineState()
    }

    // ----------------------------------------------------------------- state
    var presetSec = DEFAULT_SEC; private set
    var remainMs = DEFAULT_SEC * 1000L; private set
    var endsAt = 0L; private set
    var running = false; private set
    var finished = false; private set

    /**
     * elapsedRealtime of the finish, persisted with the rest of the state: it is
     * what tells a restore a set that just ended from one that ended hours ago.
     * 0 whenever the set is not finished.
     */
    private var finishedAt = 0L

    /** the repeating chime after TIME is live - cleared by any of the cue-killers */
    var alarming = false; private set

    var swRunning = false; private set
    var swBase = 0L; private set
    var swElapsed = 0L; private set
    val laps = ArrayList<LongArray>()          // [n, total, split], newest first

    var sound = true; private set
    var soundIdx = 0; private set
    var vibe = true; private set

    // --------------------------------------------------------------- plumbing
    private var app: Context? = null
    private var prefs: SharedPreferences? = null
    private var tones: Tones? = null
    private var vibrator: Vibrator? = null
    private val h = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Listener>()
    private var ready = false

    /** set by the service itself while it is alive; the engine never binds */
    internal var service: TimerService? = null

    /** idempotent - called from both Activity.onCreate and Service.onCreate */
    fun init(ctx: Context) {
        if (ready) return
        ready = true
        val c = ctx.applicationContext
        app = c
        prefs = c.getSharedPreferences("mt", Context.MODE_PRIVATE)
        tones = Tones()
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        loadSettings()
        restore()
        changed()
    }

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    // -------------------------------------------------------------- derived
    fun remainingMs(): Long =
        if (running) (endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0) else remainMs

    fun swMs(): Long =
        if (swRunning) SystemClock.elapsedRealtime() - swBase else swElapsed

    /** stopped part-way through a set - not at the top, not at zero */
    val paused: Boolean get() = !running && !finished && remainMs < presetSec * 1000L

    val timerActive: Boolean get() = running || finished || paused

    /** the foreground service is wanted exactly while one of these holds */
    val serviceWanted: Boolean get() = timerActive || swRunning

    // ----------------------------------------------------------------- cues
    private val cues = ArrayList<Runnable>()
    private var gen = 0

    /**
     * Kills every pending cue, and with them the repeat chime: the sequence is
     * one cue at a time, so this is the single place the ring-out stops.
     * `alarming` is cleared here and nowhere else, which is why timerReset,
     * timerBack, selectPreset, setAndStart, timerStart, timerPause and
     * alarmStop (the tab tap and the stopwatch) all end it without a line of
     * their own.
     */
    private fun clearCues() {
        for (r in cues) h.removeCallbacks(r)
        cues.clear()
        gen++                       // anything already dequeued is inert too
        alarming = false
    }

    private fun postCue(delay: Long, action: () -> Unit) {
        val g = gen
        val r = Runnable { if (g == gen) action() }
        cues.add(r)
        h.postDelayed(r, delay)
    }

    private fun scheduleCues(msLeft: Long) {
        for (n in 3 downTo 1) {
            val d = msLeft - n * 1000L
            if (d > 50) postCue(d) {
                if (n == 3) focusRequest()      // duck the music, then tick
                if (sound) tones?.play(voice().tick)
            }
        }
        postCue(msLeft) { finishCue() }
    }

    /**
     * The finish is decided here and nowhere else. A cue that arrives while the
     * clock still has time left (a Handler runs on uptime, which can lag the
     * elapsed clock) re-posts itself instead of firing early.
     */
    private fun finishCue() {
        if (!running) return
        val left = endsAt - SystemClock.elapsedRealtime()
        if (left > 60) {
            postCue(left) { finishCue() }
            return
        }
        timerFinish()
    }

    // ---------------------------------------------------------------- timer
    fun timerStart() {
        if (running || remainMs <= 0) return
        endsAt = SystemClock.elapsedRealtime() + remainMs
        running = true
        finished = false
        clearCues()
        scheduleCues(remainMs)
        if (remainMs - DUCK_AT <= 50) focusRequest()   // started inside the last 3s
        if (sound) tones?.play(voice().go)
        buzz(60)
        changed()
    }

    /**
     * PAUSE on the last instant is a finish, not a pause: a set stopped with
     * nothing left has to ring like any other, and "paused at 0:00" is a state
     * START refuses to leave. `paused` can therefore never hold at remainMs 0.
     */
    fun timerPause() {
        if (!running) return
        val left = endsAt - SystemClock.elapsedRealtime()
        if (left <= 0) {
            timerFinish()
            return
        }
        remainMs = left
        running = false
        clearCues()
        focusAbandon()
        changed()
    }

    fun timerFinish() {
        clearCues()
        running = false
        remainMs = 0
        finished = true
        alarmFrom = SystemClock.elapsedRealtime()
        finishedAt = alarmFrom          // same instant; the stale rule ages from it
        alarmK = 0
        alarming = true
        alarmFire()
        // focus is NOT abandoned here: the music stays down until RESET/RESTART
        changed()
    }

    // ------------------------------------------------------- ring-out at TIME
    /** elapsedRealtime at the finish - the 2-minute cut is measured from it */
    private var alarmFrom = 0L
    private var alarmK = 0

    /**
     * One chime, then the cue for the next one. Nothing is counted: every
     * decision is made against elapsedRealtime, so a cue the system held back
     * cannot push the sequence past two minutes.
     *
     * `sound`, the voice and `vibe` are read here, at each fire - MUTE silences
     * the chimes while the buzz goes on, VIB off stops the buzz, both off is a
     * silent sequence that still ends on time.
     */
    private fun alarmFire() {
        val v = voice()
        if (sound) tones?.play(v.chime, ALARM_GAIN[alarmK.coerceAtMost(ALARM_GAIN.size - 1)])
        buzzPattern(ALARM_BUZZ)
        alarmK++

        val ring = Tones.durationMs(v.chime)
        val period = ring + ALARM_GAP
        val nextAt = SystemClock.elapsedRealtime() - alarmFrom + period
        if (nextAt <= ALARM_MAX) {
            postCue(period) { alarmFire() }
        } else {
            // hold `alarming` until this last chime has rung out, so the wake
            // lock is not dropped mid-tone with the screen off
            postCue(ring) { alarmEnd() }
        }
    }

    /** the sequence ran its two minutes: state stays `finished`, focus stays held */
    private fun alarmEnd() {
        if (!alarming) return
        alarming = false
        changed()
    }

    /**
     * Stop the ring-out because he has moved on - started the stopwatch, or
     * switched tabs - without touching the set itself. In the finished state the
     * only pending cues are the ring-out's, so clearing them IS stopping the
     * chime; `clearCues` drops `alarming` with them.
     *
     * The state stays `finished` at 0:00 and the audio focus stays HELD: the
     * music comes back on RESET / RESTART and nowhere else, unchanged.
     */
    fun alarmStop() {
        if (!alarming) return
        clearCues()
        changed()
    }

    /** RESTART: reload the selected preset AND run it - one tap between sets */
    fun timerReset() {
        clearCues()
        focusAbandon()
        remainMs = presetSec * 1000L
        finished = false
        running = false
        timerStart()
    }

    /** RESET: back to the selected preset, stopped - never starts anything */
    fun timerBack() {
        clearCues()
        focusAbandon()
        remainMs = presetSec * 1000L
        running = false
        finished = false
        changed()
    }

    fun selectPreset(sec: Int) {
        if (running) return                 // never kill a live set
        clearCues()
        focusAbandon()
        presetSec = sec
        remainMs = sec * 1000L
        finished = false
        running = false
        changed()
    }

    /** the picker's SET & START: take the value, then the RESTART path */
    fun setAndStart(sec: Int) {
        if (sec <= 0 || running) return
        selectPreset(sec)
        timerReset()
    }

    // ------------------------------------------------------------ stopwatch
    fun swStart() {
        alarmStop()                 // starting a set's clock silences the last one
        if (swRunning) return
        swBase = SystemClock.elapsedRealtime() - swElapsed
        swRunning = true
        if (sound) tones?.play(voice().go)
        buzz(50)
        changed()
    }

    fun swStop() {
        if (!swRunning) return
        swElapsed = SystemClock.elapsedRealtime() - swBase
        swRunning = false
        tones?.let { if (sound) it.play(it.stop) }
        buzz(40)
        changed()
    }

    fun swReset() {
        swRunning = false
        swElapsed = 0
        laps.clear()
        changed()
    }

    fun swLap() {
        val t = swMs()
        val prev = if (laps.isEmpty()) 0L else laps[0][1]
        laps.add(0, longArrayOf((laps.size + 1).toLong(), t, t - prev))
        tones?.let { if (sound) it.play(it.lap) }
        buzz(30)
        changed()
    }

    // ----------------------------------------------------------- audio focus
    private var focusReq: AudioFocusRequest? = null
    private var hasFocus = false
    private val focusNoop = AudioManager.OnAudioFocusChangeListener { }

    private fun audio(): AudioManager? =
        app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun focusReq(): AudioFocusRequest {
        var r = focusReq
        if (r == null) {
            r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(focusNoop)
                .build()
            focusReq = r
        }
        return r
    }

    /** duck whatever is playing - never touches a stream volume */
    private fun focusRequest() {
        if (!sound || hasFocus) return
        try {
            audio()?.requestAudioFocus(focusReq())
            hasFocus = true
        } catch (_: Exception) {
        }
    }

    /** idempotent: hands the music back and it comes up on its own */
    private fun focusAbandon() {
        if (!hasFocus) return
        hasFocus = false
        try {
            audio()?.abandonAudioFocusRequest(focusReq())
        } catch (_: Exception) {
        }
    }

    // --------------------------------------------------------- sound / buzz
    fun voice(): Tones.Voice {
        val v = tones?.voices ?: return Tones.Voice("BELL", ShortArray(0), ShortArray(0), ShortArray(0), ShortArray(0))
        return v[soundIdx.coerceIn(0, v.size - 1)]
    }

    fun voiceName(): String = if (sound) voice().name else "MUTE"

    /** header button: BELL -> CHIME -> PULSE -> MUTE -> BELL */
    fun cycleSound() {
        val n = tones?.voices?.size ?: 1
        when {
            !sound -> { sound = true; soundIdx = 0 }
            soundIdx < n - 1 -> soundIdx++
            else -> sound = false
        }
        saveSettings()
        // cues read `sound` and the voice when they fire, so a live set needs no
        // rescheduling - the change takes effect on the next tick
        if (sound) tones?.play(voice().preview)
        fire()
    }

    fun toggleVibe() {
        vibe = !vibe
        saveSettings()
        if (vibe) buzz(80)
        fire()
    }

    fun playPick() {
        tones?.let { if (sound) it.play(it.pick) }
    }

    fun buzz(ms: Long) {
        if (!vibe) return
        try {
            vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    private fun buzzPattern(pattern: LongArray) {
        if (!vibe) return
        try {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {
        }
    }

    // -------------------------------------------------------------- format
    fun fmtClock(secIn: Long): String {
        val sec = secIn.coerceAtLeast(0)
        val hh = sec / 3600; val mm = sec % 3600 / 60; val ss = sec % 60
        return if (hh > 0) "$hh:${p2(mm)}:${p2(ss)}" else "$mm:${p2(ss)}"
    }

    fun presetLabel(sec: Int): String = when {
        sec < 60 -> "${sec}s"
        sec % 60 == 0 -> "${sec / 60}m"
        else -> "${sec / 60}m${(sec % 60).toString().padStart(2, '0')}"
    }

    private fun p2(v: Long) = v.toString().padStart(2, '0')

    // ------------------------------------------------------------- plumbing
    private fun fire() {
        for (i in listeners.indices.reversed()) listeners[i].onEngineState()
    }

    private fun changed() {
        persist()
        syncService()
        fire()
    }

    private fun syncService() {
        val c = app ?: return
        val s = service
        if (serviceWanted) {
            if (s == null) {
                try {
                    c.startForegroundService(Intent(c, TimerService::class.java))
                } catch (_: Exception) {
                    // a blocked start must never take the countdown down with it
                }
            } else {
                s.refresh()
            }
        } else {
            s?.finishUp()
        }
    }

    // ----------------------------------------------------------- persistence
    private fun loadSettings() {
        val p = prefs ?: return
        sound = p.getBoolean("sound", true)
        soundIdx = p.getInt("soundIdx", 0).coerceIn(0, (tones?.voices?.size ?: 1) - 1)
        vibe = p.getBoolean("vibe", true)
    }

    private fun saveSettings() {
        prefs?.edit()?.putBoolean("sound", sound)?.putInt("soundIdx", soundIdx)
            ?.putBoolean("vibe", vibe)?.apply()
    }

    /** every state change is written, so a killed process can pick it back up */
    private fun persist() {
        val p = prefs ?: return
        val now = SystemClock.elapsedRealtime()
        p.edit()
            .putInt("presetSec", presetSec)
            .putBoolean("running", running)
            .putLong("endsAt", endsAt)
            .putLong("remainMs", remainMs)
            .putBoolean("finished", finished)
            .putLong("finishedAt", if (finished) finishedAt else 0L)
            .putBoolean("swRunning", swRunning)
            .putLong("swBase", swBase)
            .putLong("swElapsed", swElapsed)
            .putLong("bootWall", System.currentTimeMillis() - now)
            .putLong("endsAtWall", System.currentTimeMillis() + (if (running) endsAt - now else 0L))
            .remove("customSec")            // the keypad's value - gone in v2
            .apply()
    }

    /**
     * The stale rule's landing state: the old set is not picked up at all. With
     * nothing active `serviceWanted` is false, so the `changed()` at the end of
     * init() also takes the service down - see syncService() and
     * TimerService.onStartCommand. The process itself is left alone.
     */
    private fun dropSet() {
        presetSec = DEFAULT_SEC
        remainMs = DEFAULT_SEC * 1000L
        running = false
        finished = false
        alarming = false
    }

    /**
     * Process death. The elapsedRealtime clock only means anything within one
     * boot, so the stored wall-clock/elapsed offset is the check: if it still
     * matches, the stored clock values are ours and are used; if not (a reboot),
     * the app opens fresh on 35s.
     *
     * Within the same boot a finished set is only picked back up while it is
     * fresh: his words, 2026-09-20, "if the app is "killed with a timer running
     * for more than ten minutes kill it and shut down the timer app." A set that
     * is still counting is restored however long the process was dead.
     */
    private fun restore() {
        val p = prefs ?: return
        val now = SystemClock.elapsedRealtime()
        val storedOff = p.getLong("bootWall", Long.MIN_VALUE)
        val sameBoot = storedOff != Long.MIN_VALUE &&
            abs((System.currentTimeMillis() - now) - storedOff) < BOOT_SLACK

        presetSec = DEFAULT_SEC
        remainMs = DEFAULT_SEC * 1000L
        if (!sameBoot) return

        val ps = p.getInt("presetSec", DEFAULT_SEC).coerceIn(1, 99 * 60 + 59)
        val wasRunning = p.getBoolean("running", false)
        val wasFinished = p.getBoolean("finished", false)
        val savedRemain = p.getLong("remainMs", 0L)
        val savedEnds = p.getLong("endsAt", 0L)

        if (wasRunning) {
            val left = savedEnds - now
            when {
                left > 0 && left <= ps * 1000L -> {          // still counting
                    presetSec = ps; endsAt = savedEnds; remainMs = left
                    running = true; finished = false
                    scheduleCues(left)
                    // restored inside the last 3s: the duck cue is already past,
                    // so take the focus now - same test timerStart uses
                    if (left - DUCK_AT <= 50) focusRequest()
                }
                left <= 0 && -left <= STALE_MS -> {          // ran out while dead
                    presetSec = ps; remainMs = 0
                    running = false; finished = true         // no chime: it is history
                    finishedAt = savedEnds                   // a later restore ages it right
                }
                // over ten minutes past the finish, or nonsense - open fresh
                else -> dropSet()
            }
        } else if (wasFinished) {
            val savedFin = p.getLong("finishedAt", 0L)
            // missing, zero, or in the future is treated as stale
            if (savedFin > 0 && savedFin <= now && now - savedFin <= STALE_MS) {
                presetSec = ps; remainMs = 0; finished = true
                finishedAt = savedFin
            } else {
                dropSet()
            }
        } else if (savedRemain in 1 until ps * 1000L) {      // paused mid-set
            presetSec = ps; remainMs = savedRemain
        }

        if (p.getBoolean("swRunning", false)) {
            val sb = p.getLong("swBase", 0L)
            if (sb in 0..now) {
                swBase = sb; swElapsed = now - sb; swRunning = true
            }
        } else {
            swElapsed = p.getLong("swElapsed", 0L).coerceAtLeast(0)
        }
    }
}
