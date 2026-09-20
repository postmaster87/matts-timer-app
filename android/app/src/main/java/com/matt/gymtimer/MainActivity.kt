package com.matt.gymtimer

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.min

/**
 * Gym timer - the screen. Every piece of timer state lives in [TimerEngine], so
 * a set survives this Activity being destroyed, the app being closed and the
 * screen going off; this class draws it and takes the taps.
 *
 * One rule drives the layout: RESTART reloads the selected preset and starts it
 * immediately, so back-to-back sets are one tap.
 */
class MainActivity : Activity(), TimerEngine.Listener {

    private var mode = MODE_TIMER
    private var started = false

    // picker overlay (kept across a rotation rebuild)
    private var pickOpen = false
    private var pickMinVal = 0
    private var pickSecVal = 35

    // ---------------------------------------------------------------- views
    private lateinit var prefs: SharedPreferences
    private val h = Handler(Looper.getMainLooper())

    private lateinit var tabs: LinearLayout
    private lateinit var tabTimer: Button
    private lateinit var tabSw: Button
    private lateinit var togSound: Button
    private lateinit var togVibe: Button
    private lateinit var presetBox: LinearLayout
    private lateinit var stage: LinearLayout
    private lateinit var digitsBox: LinearLayout
    private lateinit var stageLabel: TextView
    private lateinit var digits: TextView
    private lateinit var barTrack: FrameLayout
    private lateinit var barFill: View
    private lateinit var lapsScroll: ScrollView
    private lateinit var lapsBox: LinearLayout
    private lateinit var btnGo: Button
    private lateinit var btnMid: Button
    private lateinit var btnAlt: Button
    private lateinit var pickOverlay: LinearLayout
    private lateinit var pickMin: NumberPicker
    private lateinit var pickSec: NumberPicker
    private lateinit var pickCancel: Button
    private lateinit var pickSet: Button

    private val presetBtns = ArrayList<Button>()

    private var cBg = 0; private var cPanel = 0; private var cPanel2 = 0; private var cLine = 0
    private var cText = 0; private var cDim = 0; private var cGreen = 0; private var cAmber = 0
    private var cCyan = 0; private var cRed = 0; private var cDoneBg = 0; private var cInkGreen = 0
    private var cInkCyan = 0; private var cBarTrack = 0; private var cTogOff = 0

    // ------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("mt", Context.MODE_PRIVATE)
        TimerEngine.init(applicationContext)

        cBg = getColor(R.color.bg); cPanel = getColor(R.color.panel)
        cPanel2 = getColor(R.color.panel2); cLine = getColor(R.color.line)
        cText = getColor(R.color.text); cDim = getColor(R.color.dim)
        cGreen = getColor(R.color.green); cAmber = getColor(R.color.amber)
        cCyan = getColor(R.color.cyan); cRed = getColor(R.color.red)
        cDoneBg = getColor(R.color.done_bg); cInkGreen = getColor(R.color.ink_green)
        cInkCyan = getColor(R.color.ink_cyan); cBarTrack = getColor(R.color.bar_track)
        cTogOff = getColor(R.color.tog_off)

        // nothing live in the timer: open on 35s, whatever ran last. A set that
        // is still running, paused or finished is picked up exactly as it stands.
        if (!TimerEngine.timerActive) TimerEngine.selectPreset(TimerEngine.DEFAULT_SEC)
        mode = if (!TimerEngine.timerActive && (TimerEngine.swRunning || TimerEngine.swMs() > 0))
            MODE_SW else MODE_TIMER

        bind()
        buildPresets()
        buildPicker()
        styleChrome()

        syncPresets()
        setMode(mode)
        watchStage()
    }

    override fun onStart() {
        super.onStart()
        started = true
        TimerEngine.addListener(this)
        syncPresets()
        if (mode == MODE_SW) renderLaps()
        syncFlash()
        keepAwake(TimerEngine.running || TimerEngine.swRunning || TimerEngine.alarming)
        render()
        loopOn()
    }

    override fun onStop() {
        super.onStop()
        started = false
        TimerEngine.removeListener(this)
        loopOff()
        stopFlash()
    }

    /** the engine's cues and finish are NOT cancelled here - they outlive us */
    override fun onDestroy() {
        super.onDestroy()
        TimerEngine.removeListener(this)
        h.removeCallbacksAndMessages(null)
    }

    /** the engine calls this on every state change, however it was caused */
    override fun onEngineState() {
        if (!started) return
        syncPresets()
        if (mode == MODE_SW) renderLaps()
        syncFlash()
        keepAwake(TimerEngine.running || TimerEngine.swRunning || TimerEngine.alarming)
        if (pickOpen && (TimerEngine.running || mode != MODE_TIMER)) closePicker()
        render()
        loopOn()
    }

    /**
     * Rotation does NOT recreate the activity - a live set must survive it - so the
     * view tree is rebuilt by hand against the new orientation's layout. The state
     * lives in the engine, so nothing is lost; the open picker is carried over.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val wasOpen = pickOpen
        stopFlash()

        setContentView(R.layout.activity_main)
        bind()
        buildPresets()
        buildPicker()
        styleChrome()
        lastBoxH = -1
        watchStage()

        syncPresets()
        if (mode != MODE_TIMER) renderLaps()
        setMode(mode)                       // the re-render path: never alarmStop
        if (wasOpen) {
            pickOpen = true
            pickOverlay.visibility = View.VISIBLE
        }
        syncFlash()
        keepAwake(TimerEngine.running || TimerEngine.swRunning || TimerEngine.alarming)
    }

    /**
     * Autosize only works against a bounded height, so the digits get an explicit
     * one: a fixed share of the stage. That also keeps the label sitting right on
     * top of the numbers instead of floating at the top of an empty panel.
     */
    private var lastBoxH = -1

    private fun watchStage() {
        digitsBox.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val hpx = bottom - top
            if (hpx > 0 && hpx != lastBoxH) {
                lastBoxH = hpx
                sizeDigits(hpx)
            }
        }
    }

    private fun sizeDigits(boxH: Int) {
        val target = (boxH * 0.70f).toInt().coerceAtLeast(dp(48f).toInt())
        if (digits.layoutParams.height != target) {
            digits.layoutParams.height = target
            digits.requestLayout()
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (pickOpen) {
            closePicker()
            return
        }
        super.onBackPressed()
    }

    private fun bind() {
        tabs = findViewById(R.id.tabs)
        tabTimer = findViewById(R.id.tabTimer)
        tabSw = findViewById(R.id.tabSw)
        togSound = findViewById(R.id.togSound)
        togVibe = findViewById(R.id.togVibe)
        presetBox = findViewById(R.id.presetBox)
        stage = findViewById(R.id.stage)
        digitsBox = findViewById(R.id.digitsBox)
        stageLabel = findViewById(R.id.stageLabel)
        digits = findViewById(R.id.digits)
        barTrack = findViewById(R.id.barTrack)
        barFill = findViewById(R.id.barFill)
        lapsScroll = findViewById(R.id.lapsScroll)
        lapsBox = findViewById(R.id.lapsBox)
        btnGo = findViewById(R.id.btnGo)
        btnMid = findViewById(R.id.btnMid)
        btnAlt = findViewById(R.id.btnAlt)
        pickOverlay = findViewById(R.id.pickOverlay)
        pickMin = findViewById(R.id.pickMin)
        pickSec = findViewById(R.id.pickSec)
        pickCancel = findViewById(R.id.pickCancel)
        pickSet = findViewById(R.id.pickSet)

        btnGo.setOnClickListener { onGo() }
        btnMid.setOnClickListener { onMid() }
        btnAlt.setOnClickListener { onAlt() }
        tabTimer.setOnClickListener { onTab(MODE_TIMER) }
        tabSw.setOnClickListener { onTab(MODE_SW) }
        togSound.setOnClickListener { TimerEngine.cycleSound(); syncToggles() }
        togVibe.setOnClickListener { TimerEngine.toggleVibe(); syncToggles() }

        // the clock itself is the way in to a one-off length
        digitsBox.setOnClickListener { openPicker() }

        pickCancel.setOnClickListener { closePicker(); TimerEngine.buzz(20) }
        pickSet.setOnClickListener {
            // commit anything typed into a wheel before reading it
            pickMin.clearFocus(); pickSec.clearFocus()
            val sec = pickMin.value * 60 + pickSec.value
            if (sec <= 0) return@setOnClickListener
            closePicker()
            askNotif()
            TimerEngine.setAndStart(sec)     // SET & START - the RESTART path
        }
    }

    // ------------------------------------------------------------- chrome
    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun rounded(color: Int, radiusDp: Float, stroke: Int? = null): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = dp(radiusDp)
        d.setColor(color)
        if (stroke != null) d.setStroke(dp(1f).toInt(), stroke)
        return d
    }

    private fun styleBtn(b: Button, bg: Int, fg: Int, radius: Float = 14f, stroke: Int? = null) {
        b.background = rounded(bg, radius, stroke)
        b.setTextColor(fg)
        b.stateListAnimator = null
        b.elevation = 0f
        b.isAllCaps = false
        b.minWidth = 0; b.minimumWidth = 0
        b.minHeight = 0; b.minimumHeight = 0
        b.setPadding(dp(2f).toInt(), dp(2f).toInt(), dp(2f).toInt(), dp(2f).toInt())
    }

    private fun styleChrome() {
        tabs.background = rounded(cPanel, 12f)
        stage.background = rounded(cPanel, 18f)
        barTrack.background = rounded(cBarTrack, 0f)
        barFill.background = rounded(cGreen, 0f)
        barFill.pivotX = 0f
        pickOverlay.setBackgroundColor(Color.rgb(6, 9, 13))
        btnAlt.maxLines = 2
        btnMid.maxLines = 2
        digits.setTextColor(cText)
        syncToggles()
    }

    private fun syncToggles() {
        val sound = TimerEngine.sound
        val vibe = TimerEngine.vibe
        styleBtn(togSound, if (sound) cPanel2 else cPanel, if (sound) cCyan else cTogOff, 12f,
            if (sound) cLine else null)
        styleBtn(togVibe, if (vibe) cPanel2 else cPanel, if (vibe) cCyan else cTogOff, 12f,
            if (vibe) cLine else null)
        togSound.text = TimerEngine.voiceName()
        togVibe.text = if (vibe) "VIB" else "OFF"
        togSound.textSize = 12f
        togVibe.textSize = if (vibe) 15f else 13f
    }

    // ------------------------------------------------------------ presets
    private fun buildPresets() {
        presetBox.removeAllViews()
        presetBtns.clear()
        var i = 0
        for (row in 0 until 3) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            val rp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (row > 0) rp.topMargin = dp(8f).toInt()
            r.layoutParams = rp

            for (col in 0 until 3) {
                val b = Button(this)
                val lp = LinearLayout.LayoutParams(
                    0, resources.getDimensionPixelSize(R.dimen.preset_h), 1f
                )
                if (col > 0) lp.marginStart = dp(8f).toInt()
                b.layoutParams = lp
                b.textSize = 19f
                b.setTypeface(b.typeface, android.graphics.Typeface.BOLD)

                val sec = TimerEngine.PRESETS[i]
                b.text = TimerEngine.presetLabel(sec)
                b.setOnClickListener {
                    if (TimerEngine.running) return@setOnClickListener  // never kill a live set
                    TimerEngine.selectPreset(sec)
                    TimerEngine.playPick()
                }
                presetBtns.add(b)
                r.addView(b)
                i++
            }
            presetBox.addView(r)
        }
    }

    private fun syncPresets() {
        val locked = TimerEngine.running
        for (i in presetBtns.indices) {
            val on = TimerEngine.PRESETS[i] == TimerEngine.presetSec
            styleBtn(
                presetBtns[i], if (on) cCyan else cPanel, if (on) cInkCyan else cText, 12f,
                if (on) null else cLine
            )
            presetBtns[i].alpha = if (locked) (if (on) 0.75f else 0.34f) else 1f
        }
    }

    // ------------------------------------------------------------- picker
    /**
     * Two framework wheels, MIN : SEC. Flick them, or tap a number and type it.
     * No keypad screen, no CUSTOM tile - the clock is the button.
     */
    private fun buildPicker() {
        pickMin.minValue = 0
        pickMin.maxValue = 99
        pickMin.wrapSelectorWheel = true
        pickSec.minValue = 0
        pickSec.maxValue = 59
        pickSec.displayedValues = SEC_LABELS
        pickSec.wrapSelectorWheel = true

        for (np in arrayOf(pickMin, pickSec)) {
            np.background = rounded(cPanel, 16f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                np.setTextColor(cText)
                np.setTextSize(dp(30f))
                np.selectionDividerHeight = dp(2f).toInt()
            }
        }
        pickMin.setOnValueChangedListener { _, _, v -> pickMinVal = v; paintPick() }
        pickSec.setOnValueChangedListener { _, _, v -> pickSecVal = v; paintPick() }
        pickMin.value = pickMinVal
        pickSec.value = pickSecVal

        styleBtn(pickCancel, cPanel2, cText, 14f, cLine)
        styleBtn(pickSet, cCyan, cInkCyan, 14f)
        paintPick()
    }

    private fun paintPick() {
        val ok = pickMinVal * 60 + pickSecVal > 0
        pickSet.alpha = if (ok) 1f else 0.3f
    }

    private fun openPicker() {
        if (mode != MODE_TIMER || TimerEngine.running) return
        val sec = TimerEngine.presetSec
        pickMinVal = (sec / 60).coerceIn(0, 99)
        pickSecVal = sec % 60
        pickMin.value = pickMinVal
        pickSec.value = pickSecVal
        paintPick()
        pickOverlay.visibility = View.VISIBLE
        pickOpen = true
        TimerEngine.buzz(15)
    }

    private fun closePicker() {
        pickOverlay.visibility = View.GONE
        pickOpen = false
    }

    // ---------------------------------------------------------------- laps
    private fun renderLaps() {
        lapsBox.removeAllViews()
        for (l in TimerEngine.laps) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(dp(12f).toInt(), dp(7f).toInt(), dp(12f).toInt(), dp(7f).toInt())

            fun cell(text: String, color: Int, weight: Float, gravity: Int, bold: Boolean): TextView {
                val t = TextView(this)
                t.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
                t.text = text
                t.setTextColor(color)
                t.textSize = 14f
                t.gravity = gravity
                t.typeface = android.graphics.Typeface.MONOSPACE
                if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
                return t
            }
            row.addView(cell("LAP ${l[0]}", cDim, 1f, Gravity.START, false))
            row.addView(cell(fmtSw(l[2]), cText, 1f, Gravity.CENTER, true))
            row.addView(cell(fmtSw(l[1]), cDim, 1f, Gravity.END, false))
            lapsBox.addView(row)
        }
        val lp = lapsScroll.layoutParams as LinearLayout.LayoutParams
        if (TimerEngine.laps.isEmpty()) {
            lapsScroll.visibility = View.GONE
            lp.weight = 0f
        } else {
            lapsScroll.visibility = View.VISIBLE
            lp.weight = 0.9f
        }
        lapsScroll.layoutParams = lp
    }

    // ----------------------------------------------------------- controls
    private fun onGo() {
        if (mode == MODE_TIMER) {
            when {
                TimerEngine.finished -> { TimerEngine.timerReset(); askNotif() }
                TimerEngine.running -> TimerEngine.timerPause()
                else -> { TimerEngine.timerStart(); askNotif() }
            }
        } else {
            if (TimerEngine.swRunning) TimerEngine.swStop()
            else { TimerEngine.swStart(); askNotif() }
        }
    }

    /** middle button: back to the original time, stopped - never starts anything */
    private fun onMid() {
        if (mode == MODE_TIMER) TimerEngine.timerBack() else TimerEngine.swReset()
    }

    private fun onAlt() {
        if (mode == MODE_TIMER) { TimerEngine.timerReset(); askNotif() }
        else if (TimerEngine.swRunning) TimerEngine.swLap()
    }

    /**
     * Android 13+ wants to be asked before an app may post a notification, and
     * the lock-screen timer is a notification. Asked once, on the first start;
     * the timer runs either way.
     */
    private fun askNotif() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (prefs.getBoolean("askedNotif", false)) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        prefs.edit().putBoolean("askedNotif", true).apply()
        try {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        } catch (_: Exception) {
        }
    }

    /**
     * A tab tap, as against the setMode calls that only redraw (onCreate and the
     * rotation rebuild): his hand is on the phone, so a ring-out at TIME has
     * been heard and stops here. Rotation must NOT silence it.
     */
    private fun onTab(m: String) {
        TimerEngine.alarmStop()
        setMode(m)
    }

    private fun setMode(m: String) {
        mode = m
        if (pickOpen) closePicker()
        presetBox.visibility = if (m == MODE_TIMER) View.VISIBLE else View.GONE
        barTrack.visibility = if (m == MODE_TIMER) View.VISIBLE else View.GONE
        if (m == MODE_TIMER) {
            lapsScroll.visibility = View.GONE
        } else {
            renderLaps()
        }
        syncFlash()
        render()
        loopOn()
    }

    // -------------------------------------------------------------- clock
    private var ticking = false

    /** redraw only - the finish is the engine's call, never this loop's */
    private val tick = object : Runnable {
        override fun run() {
            ticking = false
            if (!started || !(TimerEngine.running || TimerEngine.swRunning)) return
            render()
            loopOn()
        }
    }

    private fun loopOn() {
        if (ticking || !started) return
        if (!(TimerEngine.running || TimerEngine.swRunning)) return
        ticking = true
        h.postDelayed(tick, if (mode == MODE_SW) 40L else 100L)
    }

    private fun loopOff() {
        h.removeCallbacks(tick)
        ticking = false
    }

    // -------------------------------------------------------------- flash
    private var flashing = false
    private var flashOn = false
    private val flasher = object : Runnable {
        override fun run() {
            flashOn = !flashOn
            stage.background = rounded(if (flashOn) cDoneBg else cPanel, 18f)
            h.postDelayed(this, 550)
        }
    }

    /**
     * The flash follows the ring, not the state: it runs exactly while the
     * engine says `alarming`. When the chimes stop - the 2-minute cut, a tab
     * tap, the stopwatch, RESET, RESTART - the flash stops with them, and a
     * finished set that is no longer ringing sits still on red TIME. The engine
     * is the only source of truth; this is called from the listener, onStart,
     * setMode and the rotation rebuild, so it is right after every change.
     */
    private fun syncFlash() {
        val want = started && mode == MODE_TIMER && TimerEngine.alarming
        if (want && !flashing) startFlash() else if (!want && flashing) stopFlash()
    }

    private fun startFlash() {
        h.removeCallbacks(flasher)
        flashing = true; flashOn = false
        h.post(flasher)
    }

    private fun stopFlash() {
        h.removeCallbacks(flasher)
        flashing = false
        stage.background = rounded(cPanel, 18f)
    }

    // ------------------------------------------------------------- render
    private fun fmtSw(msIn: Long): String {
        val ms = msIn.coerceAtLeast(0)
        val hh = ms / 3600000; val mm = ms % 3600000 / 60000
        val ss = ms % 60000 / 1000; val cs = ms % 1000 / 10
        val base = "${pad2(mm)}:${pad2(ss)}.${pad2(cs)}"
        return if (hh > 0) "$hh:$base" else base
    }

    private fun pad2(v: Long) = v.toString().padStart(2, '0')

    private fun twoLine(head: String, sub: String, subColor: Int): CharSequence {
        val sb = SpannableStringBuilder(head).append('\n').append(sub)
        val start = head.length + 1
        sb.setSpan(RelativeSizeSpan(0.42f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(subColor), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun render() {
        // header tabs
        styleBtn(tabTimer, if (mode == MODE_TIMER) cPanel2 else Color.TRANSPARENT,
            if (mode == MODE_TIMER) cText else cDim, 9f, if (mode == MODE_TIMER) cLine else null)
        styleBtn(tabSw, if (mode == MODE_SW) cPanel2 else Color.TRANSPARENT,
            if (mode == MODE_SW) cText else cDim, 9f, if (mode == MODE_SW) cLine else null)

        if (mode == MODE_TIMER) {
            val presetSec = TimerEngine.presetSec
            val running = TimerEngine.running
            val finished = TimerEngine.finished
            val ms = TimerEngine.remainingMs()
            digits.text = TimerEngine.fmtClock(ceil(ms / 1000.0).toLong())

            val frac = if (presetSec > 0) min(1.0, ms.toDouble() / (presetSec * 1000.0)) else 0.0
            barFill.scaleX = frac.toFloat().coerceAtLeast(0f)

            val warn = running && ms <= 10000
            val paused = TimerEngine.paused

            val accent = when {
                finished -> cRed
                warn -> cAmber
                paused -> cDim
                else -> cText
            }
            digits.setTextColor(accent)
            barFill.background = rounded(
                when {
                    finished -> cRed
                    warn -> cAmber
                    else -> cGreen
                }, 0f
            )

            val label = TimerEngine.presetLabel(presetSec).uppercase()
            stageLabel.text = when {
                finished -> "TIME"
                warn -> "FINISH IT"
                running -> "RUNNING"
                paused -> "PAUSED"
                else -> "READY  ·  $label  ·  TAP TO SET"
            }

            btnGo.text = when {
                finished -> "GO AGAIN"
                running -> "PAUSE"
                paused -> "RESUME"
                else -> "START"
            }
            styleBtn(btnGo, if (running) cAmber else cGreen, cInkGreen, 16f)

            btnMid.text = twoLine("RESET", "BACK TO $label", cDim)
            styleBtn(btnMid, cPanel2, cText, 16f, cLine)
            btnMid.isEnabled = true
            btnMid.alpha = 1f

            btnAlt.text = twoLine("RESTART", "RESETS & RUNS", SUB_ON_CYAN)
            styleBtn(btnAlt, cCyan, cInkCyan, 16f)
            btnAlt.isEnabled = true
            btnAlt.alpha = 1f
        } else {
            val swRunning = TimerEngine.swRunning
            val e = TimerEngine.swMs()
            digits.text = fmtSw(e)
            digits.setTextColor(if (swRunning) cText else if (e > 0) cDim else cText)
            stageLabel.text = if (swRunning) "RUNNING" else if (e > 0) "STOPPED" else "STOPWATCH"

            btnGo.text = if (swRunning) "STOP" else if (e > 0) "RESUME" else "START"
            styleBtn(btnGo, if (swRunning) cAmber else cGreen, cInkGreen, 16f)

            btnMid.text = "RESET"
            styleBtn(btnMid, cPanel2, cText, 16f, cLine)
            val canReset = e > 0 || TimerEngine.laps.isNotEmpty()
            btnMid.isEnabled = canReset
            btnMid.alpha = if (canReset) 1f else 0.35f

            btnAlt.text = "LAP"
            styleBtn(btnAlt, cCyan, cInkCyan, 16f)
            btnAlt.isEnabled = swRunning
            btnAlt.alpha = if (swRunning) 1f else 0.35f
        }
    }

    // ------------------------------------------------------------- system
    /** on while a clock is moving AND while the chime is still repeating */
    private fun keepAwake(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        private const val MODE_TIMER = "timer"
        private const val MODE_SW = "stopwatch"
        private val SUB_ON_CYAN = Color.argb(190, 4, 32, 46)
        private val SEC_LABELS = Array(60) { it.toString().padStart(2, '0') }
    }
}
