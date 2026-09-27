package com.matt.gymtimer

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * The alarm clock - the alarm list, its storage, the next-trigger math, arming,
 * the fire decision and the ring state. Built to docs/ALARM_SPEC.md Section 5.
 *
 * His words, 2026-09-27: "Okay lets add in an alarm clock feature next".
 *
 * Everything here works before the first unlock after a restart: storage is
 * the device-protected "alarms" prefs, never the timer's credential-protected
 * "mt" prefs, and nothing in the four alarm files reaches the timer's engine.
 * The one link to the timer is [mirrorVibe], which the timer calls.
 */
object AlarmEngine {

    const val ACT_FIRE = "com.matt.gymtimer.ALARM_FIRE"
    const val ACT_STOP = "com.matt.gymtimer.ALARM_STOP"

    /** D7: the list is capped in code; the cap is never shown */
    const val MAX_ALARMS = 20

    /** his answer 7: rings 15 minutes; D2: a late ring is allowed this far past */
    const val LATE_MS = 15 * 60_000L

    /** a fire intent for an alarm armed further out than this is stale */
    private const val EARLY_MS = 60_000L

    class Alarm(
        val id: Int,
        var h: Int,         // 0..23
        var m: Int,         // 0..59
        var days: Int,      // bit 0 Sunday .. bit 6 Saturday; 0 = one-shot
        var on: Boolean,
        var next: Long      // wall-clock ms it is armed for; 0 when off
    )

    interface Listener {
        fun onAlarmState()
    }

    // ----------------------------------------------------------------- state
    val alarms = ArrayList<Alarm>()
    var voice = 0; private set
    var vibe = true; private set

    /** the alarm ringing now, -1 when none - persisted so a sticky restart can resume */
    var ringId = -1; private set
    var ringAtWall = 0L; private set

    /** elapsedRealtime the ring began; the ramp and the 15-minute cut run from it */
    var ringFrom = 0L; private set

    /** a ring is live in THIS process (the persisted ringId may be left from a dead one) */
    var ringing = false; private set

    /** the last arm threw SecurityException - the ALARM tab says so */
    var armFailed = false; private set

    /** set by the service itself while it is alive; nothing binds */
    internal var service: AlarmService? = null

    // --------------------------------------------------------------- plumbing
    private var app: Context? = null
    private var prefs: SharedPreferences? = null
    private var ready = false
    private var nextId = 1
    private var pendingVibe: Boolean? = null
    private val listeners = ArrayList<Listener>()

    /** rendered only when something plays - never at startup */
    val tones: Tones by lazy { Tones() }

    /** idempotent: MainActivity, AlarmService, AlarmReceiver and AlarmActivity all call it */
    fun init(ctx: Context) {
        if (ready) return
        ready = true
        val c = ctx.applicationContext
        app = c
        prefs = c.createDeviceProtectedStorageContext()
            .getSharedPreferences("alarms", Context.MODE_PRIVATE)
        load()
        pendingVibe?.let {
            pendingVibe = null
            vibe = it
        }
        armAll(System.currentTimeMillis())
    }

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun find(id: Int): Alarm? = alarms.firstOrNull { it.id == id }

    /** the soonest armed alarm, for the status line */
    fun nextUp(): Alarm? = alarms.filter { it.on && it.next > 0 }.minByOrNull { it.next }

    fun voiceName(): String = Tones.ALARM_VOICE_NAMES[voice]

    /** the selected voice's ring phrase - renders it the first time */
    fun phrase(): ShortArray = tones.alarmVoices[voice].phrase

    // ------------------------------------------------------ next-trigger math
    /**
     * The next wall-clock instant at h:m, local time, after [nowWall]; for a
     * repeating alarm, on one of its days. Calendar stays lenient, so a time in
     * a daylight-saving gap lands on the next valid instant. The time is set
     * again after each day added, so a gap-shifted day does not carry its shift
     * to the next.
     */
    fun nextTrigger(h: Int, m: Int, days: Int, nowWall: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = nowWall
        c.set(Calendar.HOUR_OF_DAY, h)
        c.set(Calendar.MINUTE, m)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= nowWall) addDay(c, h, m)
        if (days != 0) {
            var n = 0
            while (n < 7 && (days shr (c.get(Calendar.DAY_OF_WEEK) - 1)) and 1 == 0) {
                addDay(c, h, m)
                n++
            }
        }
        return c.timeInMillis
    }

    private fun addDay(c: Calendar, h: Int, m: Int) {
        c.add(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.HOUR_OF_DAY, h)
        c.set(Calendar.MINUTE, m)
    }

    // ----------------------------------------------------------------- arming
    private fun fireOp(c: Context, id: Int): PendingIntent =
        PendingIntent.getForegroundService(
            c, id,
            Intent(c, AlarmService::class.java).setAction(ACT_FIRE).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun arm(a: Alarm) {
        val c = app ?: return
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val show = PendingIntent.getActivity(
            c, 100000 + a.id, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(a.next, show), fireOp(c, a.id))
            armFailed = false
        } catch (_: SecurityException) {
            armFailed = true            // API 31/32 without the exact-alarm permission
        }
    }

    private fun disarm(a: Alarm) {
        val c = app
        if (c != null) {
            try {
                c.getSystemService(AlarmManager::class.java)?.cancel(fireOp(c, a.id))
            } catch (_: Exception) {
            }
        }
        a.next = 0
    }

    /**
     * Every enabled alarm, re-armed from [nowWall]: on start-up, after a
     * restart, an update, a clock or time-zone change (D6). An alarm whose time
     * passed 15 minutes ago or less rings now (D2); older than that it is
     * reported missed and rolled on.
     */
    fun armAll(nowWall: Long) {
        if (app == null) return
        for (a in ArrayList(alarms)) {
            if (!a.on) continue
            when {
                a.next == 0L || a.next > nowWall -> {
                    a.next = nextTrigger(a.h, a.m, a.days, nowWall)   // always: the zone may have moved
                    arm(a)
                }
                nowWall - a.next <= LATE_MS -> lateRing(a)
                else -> {
                    missed(a)
                    roll(a, nowWall)
                }
            }
        }
        persist(true)
        changed()
    }

    private fun lateRing(a: Alarm) {
        val c = app ?: return
        try {
            c.startForegroundService(
                Intent(c, AlarmService::class.java).setAction(ACT_FIRE).putExtra("id", a.id)
            )
        } catch (_: Exception) {
            // not allowed from here: the next armAll finds it late or missed
        }
    }

    /** one-shot switches off; repeating moves to its next day after [from] */
    private fun roll(a: Alarm, from: Long) {
        if (a.days == 0) {
            a.on = false
            disarm(a)
        } else {
            a.next = nextTrigger(a.h, a.m, a.days, from)
            arm(a)
        }
    }

    // ----------------------------------------------------------- public edits
    /** a new alarm, switched on; its id, or -1 at the cap */
    fun add(h: Int, m: Int, days: Int): Int {
        if (alarms.size >= MAX_ALARMS) return -1
        val a = Alarm(nextId++, h.coerceIn(0, 23), m.coerceIn(0, 59), days and 0x7F, true, 0)
        a.next = nextTrigger(a.h, a.m, a.days, System.currentTimeMillis())
        alarms.add(a)
        arm(a)
        persist(false)
        changed()
        return a.id
    }

    fun update(id: Int, h: Int, m: Int, days: Int) {
        val a = find(id) ?: return
        a.h = h.coerceIn(0, 23)
        a.m = m.coerceIn(0, 59)
        a.days = days and 0x7F
        a.on = true
        a.next = nextTrigger(a.h, a.m, a.days, System.currentTimeMillis())
        arm(a)
        persist(false)
        changed()
    }

    fun setOn(id: Int, on: Boolean) {
        val a = find(id) ?: return
        if (on) {
            a.on = true
            a.next = nextTrigger(a.h, a.m, a.days, System.currentTimeMillis())
            arm(a)
        } else {
            a.on = false
            disarm(a)
        }
        persist(false)
        changed()
    }

    fun delete(id: Int) {
        val a = find(id) ?: return
        disarm(a)
        alarms.remove(a)
        persist(false)
        changed()
    }

    fun setVoice(i: Int) {
        val n = Tones.ALARM_VOICE_NAMES.size
        voice = ((i % n) + n) % n
        persist(false)
        changed()
    }

    /**
     * D5: the alarm buzzes when the timer's VIB is on, as it stood the last
     * time the app was open. Called by the timer at its init and on every VIB
     * tap; one that arrives before this engine is up is held and applied at init.
     */
    fun mirrorVibe(v: Boolean) {
        if (!ready) {
            pendingVibe = v
            return
        }
        vibe = v
        persist(false)
        changed()
    }

    // -------------------------------------------------------------------- fire
    /**
     * The fire decision, called by AlarmService on ACT_FIRE. True when a ring
     * should be running. The alarm is rolled BEFORE any sound, so a process
     * that dies mid-ring never rings the same alarm twice.
     */
    fun fire(id: Int): Boolean {
        val a = find(id) ?: return false
        if (!a.on) return false
        val now = System.currentTimeMillis()
        if (a.next - now > EARLY_MS) {          // stale intent
            arm(a)
            persist(true)
            return false
        }
        roll(a, now + EARLY_MS)
        persist(true)
        if (ringing) {                          // keep the ring that is running
            changed()
            return true
        }
        ringId = id
        ringAtWall = now
        ringFrom = SystemClock.elapsedRealtime()
        ringing = true
        persist(true)
        changed()
        return true
    }

    /** STOP, from the screen or the notification */
    fun stop() {
        val s = service
        if (s != null) s.endRing(false) else ringEnded()
    }

    /** a sticky restart picks the ring back up where the wall clock says it is */
    internal fun resumeRing(nowWall: Long) {
        ringFrom = SystemClock.elapsedRealtime() - (nowWall - ringAtWall).coerceAtLeast(0)
        ringing = true
        changed()
    }

    /** the service calls this as the last step of ending a ring, however it ended */
    internal fun ringEnded() {
        ringId = -1
        ringAtWall = 0L
        ringFrom = 0L
        ringing = false
        persist(true)
        changed()
    }

    /** the ringing alarm's time, 12-hour: its own h:m, or the clock at the ring's start */
    fun labelFor(id: Int): String {
        val a = find(id)
        if (a != null) return fmt12(a.h, a.m)
        val c = Calendar.getInstance()
        c.timeInMillis = if (ringAtWall > 0) ringAtWall else System.currentTimeMillis()
        return fmt12(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    // ------------------------------------------------------------ missed card
    private fun missed(a: Alarm) = postMissed(a.id, fmt12(a.h, a.m))

    /** the ring that was live when the process died, or the 15-minute cut */
    internal fun missedRing() {
        if (ringId == -1) return
        postMissed(ringId, labelFor(ringId))
    }

    private fun postMissed(id: Int, label: String) {
        val c = app ?: return
        AlarmService.channels(c)
        val open = Intent(c, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val n = Notification.Builder(c, AlarmService.CH_MISSED)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle("MISSED ALARM")
            .setContentText(label)
            .setOngoing(false)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(
                PendingIntent.getActivity(c, 400000 + id, open, PendingIntent.FLAG_IMMUTABLE)
            )
            .build()
        try {
            c.getSystemService(NotificationManager::class.java)?.notify(9 + id, n)
        } catch (_: Exception) {
        }
    }

    // -------------------------------------------------------------- format
    fun fmt12(h: Int, m: Int): String {
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12:${m.toString().padStart(2, '0')} ${if (h < 12) "AM" else "PM"}"
    }

    // ----------------------------------------------------------- persistence
    private fun changed() {
        for (i in listeners.indices.reversed()) listeners[i].onAlarmState()
    }

    /** commit() in the fire path and from the receiver (the process may die right after) */
    private fun persist(sync: Boolean) {
        val p = prefs ?: return
        val arr = JSONArray()
        for (a in alarms) {
            arr.put(
                JSONObject()
                    .put("id", a.id).put("h", a.h).put("m", a.m).put("days", a.days)
                    .put("on", a.on).put("next", a.next)
            )
        }
        val e = p.edit()
            .putString("list", arr.toString())
            .putInt("nextId", nextId)
            .putInt("voice", voice)
            .putBoolean("vibe", vibe)
            .putInt("ringId", ringId)
            .putLong("ringAtWall", ringAtWall)
        if (sync) e.commit() else e.apply()
    }

    /** a malformed entry or a bad field skips that alarm; the rest load */
    private fun load() {
        val p = prefs ?: return
        alarms.clear()
        val raw = p.getString("list", null)
        val arr = try {
            if (raw == null) null else JSONArray(raw)
        } catch (_: Exception) {
            null
        }
        if (arr != null) {
            for (i in 0 until arr.length()) {
                if (alarms.size >= MAX_ALARMS) break
                val a = try {
                    val o = arr.getJSONObject(i)
                    Alarm(
                        o.getInt("id"), o.getInt("h"), o.getInt("m"), o.getInt("days"),
                        o.getBoolean("on"), o.getLong("next")
                    )
                } catch (_: Exception) {
                    null
                } ?: continue
                if (a.h !in 0..23 || a.m !in 0..59 || a.days !in 0..0x7F || a.next < 0) continue
                if (find(a.id) != null) continue
                alarms.add(a)
            }
        }
        nextId = maxOf(p.getInt("nextId", 1), (alarms.maxOfOrNull { it.id } ?: 0) + 1)
        voice = p.getInt("voice", 0).coerceIn(0, Tones.ALARM_VOICE_NAMES.size - 1)
        vibe = p.getBoolean("vibe", true)
        ringId = p.getInt("ringId", -1)
        ringAtWall = p.getLong("ringAtWall", 0L)
    }
}
