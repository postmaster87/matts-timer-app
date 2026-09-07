package com.matt.gymtimer

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.min

/**
 * Gym timer. One rule drives the whole layout: RESET reloads the selected
 * preset and starts it immediately, so back-to-back sets are one tap.
 *
 * Everything runs on-device. No network, no permissions beyond vibration.
 */
class MainActivity : Activity() {

    // ---------------------------------------------------------------- state
    private var presetSec = DEFAULT_SEC
    private var customSec = 0
    private var remainMs = DEFAULT_SEC * 1000L
    private var endsAt = 0L
    private var running = false
    private var finished = false

    private var swRunning = false
    private var swBase = 0L
    private var swElapsed = 0L
    private val laps = ArrayList<LongArray>()   // [n, total, split], newest first

    private var mode = MODE_TIMER
    private var sound = true
    private var vibe = true

    private var padDigits = ""

    // ---------------------------------------------------------------- views
    private lateinit var prefs: SharedPreferences
    private lateinit var tones: Tones
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
    private lateinit var btnAlt: Button
    private lateinit var padOverlay: LinearLayout
    private lateinit var padVal: TextView
    private lateinit var padKeys: LinearLayout
    private lateinit var padCancel: Button
    private lateinit var padSet: Button

    private val presetBtns = ArrayList<Button>()
    private lateinit var btnCustom: Button

    private var cBg = 0; private var cPanel = 0; private var cPanel2 = 0; private var cLine = 0
    private var cText = 0; private var cDim = 0; private var cGreen = 0; private var cAmber = 0
    private var cCyan = 0; private var cRed = 0; private var cDoneBg = 0; private var cInkGreen = 0
    private var cInkCyan = 0; private var cBarTrack = 0; private var cTogOff = 0

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    // ------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("mt", Context.MODE_PRIVATE)
        tones = Tones()

        cBg = getColor(R.color.bg); cPanel = getColor(R.color.panel)
        cPanel2 = getColor(R.color.panel2); cLine = getColor(R.color.line)
        cText = getColor(R.color.text); cDim = getColor(R.color.dim)
        cGreen = getColor(R.color.green); cAmber = getColor(R.color.amber)
        cCyan = getColor(R.color.cyan); cRed = getColor(R.color.red)
        cDoneBg = getColor(R.color.done_bg); cInkGreen = getColor(R.color.ink_green)
        cInkCyan = getColor(R.color.ink_cyan); cBarTrack = getColor(R.color.bar_track)
        cTogOff = getColor(R.color.tog_off)

        bind()
        loadPrefs()
        buildPresets()
        buildKeypad()
        styleChrome()

        // the app always opens on 35s, whatever ran last
        presetSec = DEFAULT_SEC
        remainMs = presetSec * 1000L

        syncPresets()
        paintPad()
        render()
        watchStage()
    }

    override fun onDestroy() {
        super.onDestroy()
        h.removeCallbacksAndMessages(null)
    }

    /**
     * Rotation does NOT recreate the activity - a live set must survive it - so the
     * view tree is rebuilt by hand against the new orientation's layout. Every piece
     * of timer state lives in this instance, so nothing is lost.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val padWasOpen = padOverlay.visibility == View.VISIBLE
        h.removeCallbacks(flasher)

        setContentView(R.layout.activity_main)
        bind()
        buildPresets()
        buildKeypad()
        styleChrome()
        lastBoxH = -1
        watchStage()

        syncPresets()
        paintPad()
        if (mode != MODE_TIMER) renderLaps()
        setMode(mode)
        if (padWasOpen) padOverlay.visibility = View.VISIBLE
        if (finished) startFlash()
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
        if (padOverlay.visibility == View.VISIBLE) {
            closePad()
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
        btnAlt = findViewById(R.id.btnAlt)
        padOverlay = findViewById(R.id.padOverlay)
        padVal = findViewById(R.id.padVal)
        padKeys = findViewById(R.id.padKeys)
        padCancel = findViewById(R.id.padCancel)
        padSet = findViewById(R.id.padSet)

        btnGo.setOnClickListener { onGo() }
        btnAlt.setOnClickListener { onAlt() }
        tabTimer.setOnClickListener { setMode(MODE_TIMER) }
        tabSw.setOnClickListener { setMode(MODE_SW) }
        togSound.setOnClickListener {
            sound = !sound; savePrefs(); syncToggles()
            if (sound) tones.play(tones.pick)
        }
        togVibe.setOnClickListener {
            vibe = !vibe; savePrefs(); syncToggles()
            if (vibe) buzz(80)
        }
        padCancel.setOnClickListener { closePad(); buzz(20) }
        padSet.setOnClickListener {
            val sec = padSeconds()
            if (sec <= 0) return@setOnClickListener
            customSec = sec; savePrefs()
            closePad()
            selectPreset(sec)
            timerReset()          // SET & START - same one-tap intent as RESET
        }
    }

    private fun loadPrefs() {
        sound = prefs.getBoolean("sound", true)
        vibe = prefs.getBoolean("vibe", true)
        customSec = prefs.getInt("customSec", 0)
    }

    private fun savePrefs() {
        prefs.edit().putBoolean("sound", sound).putBoolean("vibe", vibe)
            .putInt("customSec", customSec).apply()
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
        padOverlay.setBackgroundColor(Color.rgb(6, 9, 13))
        btnAlt.maxLines = 2
        digits.setTextColor(cText)
        syncToggles()
    }

    private fun syncToggles() {
        styleBtn(togSound, if (sound) cPanel2 else cPanel, if (sound) cCyan else cTogOff, 12f,
            if (sound) cLine else null)
        styleBtn(togVibe, if (vibe) cPanel2 else cPanel, if (vibe) cCyan else cTogOff, 12f,
            if (vibe) cLine else null)
        togSound.text = if (sound) "SND" else "MUTE"
        togVibe.text = if (vibe) "VIB" else "OFF"
        togSound.textSize = if (sound) 15f else 12f
        togVibe.textSize = if (vibe) 15f else 13f
    }

    // ------------------------------------------------------------ presets
    private fun presetLabel(sec: Int): String = when {
        sec < 60 -> "${sec}s"
        sec % 60 == 0 -> "${sec / 60}m"
        else -> "${sec / 60}m${(sec % 60).toString().padStart(2, '0')}"
    }

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

                if (i < PRESETS.size) {
                    val sec = PRESETS[i]
                    b.text = presetLabel(sec)
                    b.setOnClickListener {
                        if (running) return@setOnClickListener  // never kill a live set
                        selectPreset(sec)
                        if (sound) tones.play(tones.pick)
                    }
                    presetBtns.add(b)
                } else {
                    btnCustom = b
                    b.textSize = 16f
                    b.setOnClickListener {
                        if (running) return@setOnClickListener
                        openPad()
                    }
                }
                r.addView(b)
                i++
            }
            presetBox.addView(r)
        }
    }

    private fun selectPreset(sec: Int) {
        clearCues()
        presetSec = sec
        remainMs = sec * 1000L
        finished = false
        running = false
        keepAwake(false)
        syncPresets()
        render()
    }

    private fun syncPresets() {
        val locked = running
        for (i in presetBtns.indices) {
            val on = PRESETS[i] == presetSec
            styleBtn(
                presetBtns[i], if (on) cCyan else cPanel, if (on) cInkCyan else cText, 12f,
                if (on) null else cLine
            )
            presetBtns[i].alpha = if (locked) (if (on) 0.75f else 0.34f) else 1f
        }
        val isCustom = !PRESETS.contains(presetSec)
        btnCustom.text = when {
            isCustom -> presetLabel(presetSec)
            customSec > 0 -> presetLabel(customSec) + "  EDIT"
            else -> "CUSTOM"
        }
        btnCustom.textSize = if (isCustom) 19f else 14f
        styleBtn(
            btnCustom, if (isCustom) cCyan else cPanel, if (isCustom) cInkCyan else cText, 12f,
            if (isCustom) null else cLine
        )
        btnCustom.alpha = if (locked) (if (isCustom) 0.75f else 0.34f) else 1f
    }

    // ------------------------------------------------------------- keypad
    private fun buildKeypad() {
        padKeys.removeAllViews()
        val rows = listOf(
            listOf("1", "2", "3"), listOf("4", "5", "6"),
            listOf("7", "8", "9"), listOf("CLR", "0", "DEL")
        )
        for ((ri, row) in rows.withIndex()) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            val rp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            if (ri > 0) rp.topMargin = dp(10f).toInt()
            r.layoutParams = rp

            for ((ci, k) in row.withIndex()) {
                val b = Button(this)
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                if (ci > 0) lp.marginStart = dp(10f).toInt()
                b.layoutParams = lp
                val fn = k == "CLR" || k == "DEL"
                b.text = if (k == "DEL") "DEL" else k
                b.textSize = if (fn) 17f else 28f
                b.setTypeface(b.typeface, android.graphics.Typeface.BOLD)
                styleBtn(b, cPanel, if (fn) cDim else cText, 14f, cLine)
                b.setOnClickListener {
                    when (k) {
                        "CLR" -> padDigits = ""
                        "DEL" -> padDigits = padDigits.dropLast(1)
                        else -> if (padDigits.length < 4)
                            padDigits = (padDigits + k).trimStart('0')
                    }
                    if (sound) tones.play(tones.key)
                    buzz(15)
                    paintPad()
                }
                r.addView(b)
            }
            padKeys.addView(r)
        }
        styleBtn(padCancel, cPanel2, cText, 14f, cLine)
        styleBtn(padSet, cCyan, cInkCyan, 14f)
    }

    private fun padSeconds(): Int {
        val d = padDigits.padStart(4, '0')
        return d.substring(0, 2).toInt() * 60 + d.substring(2).toInt()
    }

    private fun paintPad() {
        val d = padDigits.padStart(4, '0')
        val lead = 4 - padDigits.length
        val off = Color.rgb(0x41, 0x50, 0x5f)
        val sb = SpannableStringBuilder()
        for (i in 0 until 4) {
            if (i == 2) {
                val st = sb.length
                sb.append(":")
                if (lead > 2) sb.setSpan(
                    ForegroundColorSpan(off), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            val st = sb.length
            sb.append(d[i])
            if (i < lead) sb.setSpan(
                ForegroundColorSpan(off), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        padVal.text = sb
        val ok = padSeconds() > 0
        padSet.isEnabled = ok
        padSet.alpha = if (ok) 1f else 0.3f
    }

    private fun openPad() {
        padDigits = if (customSec > 0) {
            ((customSec / 60).toString() + (customSec % 60).toString().padStart(2, '0'))
                .trimStart('0').takeLast(4)
        } else ""
        paintPad()
        padOverlay.visibility = View.VISIBLE
    }

    private fun closePad() {
        padOverlay.visibility = View.GONE
    }

    // -------------------------------------------------------------- timer
    private val cues = ArrayList<Runnable>()

    private fun clearCues() {
        for (r in cues) h.removeCallbacks(r)
        cues.clear()
    }

    private fun postCue(delay: Long, action: () -> Unit) {
        val r = Runnable { action() }
        cues.add(r)
        h.postDelayed(r, delay)
    }

    private fun scheduleCues(msLeft: Long) {
        for (n in 3 downTo 1) {
            val d = msLeft - n * 1000L
            if (d > 50) postCue(d) { if (sound) tones.play(tones.tick) }
        }
        postCue(msLeft) { timerFinish() }
    }

    private fun timerStart() {
        if (remainMs <= 0) return
        endsAt = SystemClock.elapsedRealtime() + remainMs
        running = true
        finished = false
        clearCues()
        scheduleCues(remainMs)
        if (sound) tones.play(tones.go)
        buzz(60)
        keepAwake(true)
        syncPresets()
        loopOn()
        render()
    }

    private fun timerPause() {
        if (!running) return
        remainMs = (endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        running = false
        clearCues()
        loopOff()
        keepAwake(false)
        syncPresets()
        render()
    }

    private fun timerFinish() {
        clearCues()
        loopOff()
        running = false
        remainMs = 0
        finished = true
        if (sound) tones.play(tones.chime)
        buzzPattern(longArrayOf(0, 300, 120, 300, 120, 500))
        keepAwake(false)
        syncPresets()
        render()
        startFlash()
    }

    /** RESET reloads the selected preset AND starts it - back-to-back sets, one tap */
    private fun timerReset() {
        clearCues()
        stopFlash()
        remainMs = presetSec * 1000L
        finished = false
        running = false
        timerStart()
    }

    // ---------------------------------------------------------- stopwatch
    private fun swStart() {
        swBase = SystemClock.elapsedRealtime() - swElapsed
        swRunning = true
        if (sound) tones.play(tones.go)
        buzz(50)
        keepAwake(true)
        loopOn()
        render()
    }

    private fun swStop() {
        swElapsed = SystemClock.elapsedRealtime() - swBase
        swRunning = false
        loopOff()
        if (sound) tones.play(tones.stop)
        buzz(40)
        keepAwake(false)
        render()
    }

    private fun swReset() {
        loopOff()
        swRunning = false
        swElapsed = 0
        laps.clear()
        keepAwake(false)
        renderLaps()
        render()
    }

    private fun swLap() {
        val t = if (swRunning) SystemClock.elapsedRealtime() - swBase else swElapsed
        val prev = if (laps.isEmpty()) 0L else laps[0][1]
        laps.add(0, longArrayOf((laps.size + 1).toLong(), t, t - prev))
        if (sound) tones.play(tones.lap)
        buzz(30)
        renderLaps()
    }

    private fun renderLaps() {
        lapsBox.removeAllViews()
        for (l in laps) {
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
        if (laps.isEmpty()) {
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
                finished -> timerReset()
                running -> timerPause()
                else -> timerStart()
            }
        } else {
            if (swRunning) swStop() else swStart()
        }
    }

    private fun onAlt() {
        if (mode == MODE_TIMER) timerReset()
        else if (swRunning) swLap() else swReset()
    }

    private fun setMode(m: String) {
        mode = m
        presetBox.visibility = if (m == MODE_TIMER) View.VISIBLE else View.GONE
        barTrack.visibility = if (m == MODE_TIMER) View.VISIBLE else View.GONE
        if (m == MODE_TIMER) {
            lapsScroll.visibility = View.GONE
        } else {
            stopFlash()
            renderLaps()
        }
        loopOn()
        render()
    }

    // -------------------------------------------------------------- clock
    private var ticking = false

    private val tick = object : Runnable {
        override fun run() {
            ticking = false
            if (running && SystemClock.elapsedRealtime() >= endsAt) {
                timerFinish(); return
            }
            if (!running && !swRunning) return
            render()
            loopOn()
        }
    }

    private fun loopOn() {
        if (ticking || !(running || swRunning)) return
        ticking = true
        h.postDelayed(tick, if (mode == MODE_SW) 40L else 100L)
    }

    private fun loopOff() {
        h.removeCallbacks(tick)
        ticking = false
    }

    // -------------------------------------------------------------- flash
    private var flashOn = false
    private val flasher = object : Runnable {
        override fun run() {
            flashOn = !flashOn
            stage.background = rounded(if (flashOn) cDoneBg else cPanel, 18f)
            h.postDelayed(this, 550)
        }
    }

    private fun startFlash() {
        stopFlash(); flashOn = false; h.post(flasher)
    }

    private fun stopFlash() {
        h.removeCallbacks(flasher)
        stage.background = rounded(cPanel, 18f)
    }

    // ------------------------------------------------------------- render
    private fun fmtClock(secIn: Long): String {
        val sec = secIn.coerceAtLeast(0)
        val hh = sec / 3600; val mm = sec % 3600 / 60; val ss = sec % 60
        return if (hh > 0) "$hh:${pad2(mm)}:${pad2(ss)}" else "$mm:${pad2(ss)}"
    }

    private fun fmtSw(msIn: Long): String {
        val ms = msIn.coerceAtLeast(0)
        val hh = ms / 3600000; val mm = ms % 3600000 / 60000
        val ss = ms % 60000 / 1000; val cs = ms % 1000 / 10
        val base = "${pad2(mm)}:${pad2(ss)}.${pad2(cs)}"
        return if (hh > 0) "$hh:$base" else base
    }

    private fun pad2(v: Long) = v.toString().padStart(2, '0')

    private fun altResetLabel(): CharSequence {
        val sb = SpannableStringBuilder("RESET\nRESTARTS & RUNS")
        val s = 6
        sb.setSpan(RelativeSizeSpan(0.42f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(
            ForegroundColorSpan(Color.argb(190, 4, 32, 46)), s, sb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return sb
    }

    private fun render() {
        // header tabs
        styleBtn(tabTimer, if (mode == MODE_TIMER) cPanel2 else Color.TRANSPARENT,
            if (mode == MODE_TIMER) cText else cDim, 9f, if (mode == MODE_TIMER) cLine else null)
        styleBtn(tabSw, if (mode == MODE_SW) cPanel2 else Color.TRANSPARENT,
            if (mode == MODE_SW) cText else cDim, 9f, if (mode == MODE_SW) cLine else null)

        if (mode == MODE_TIMER) {
            val ms = if (running) (endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0) else remainMs
            digits.text = fmtClock(ceil(ms / 1000.0).toLong())

            val frac = if (presetSec > 0) min(1.0, ms.toDouble() / (presetSec * 1000.0)) else 0.0
            barFill.scaleX = frac.toFloat().coerceAtLeast(0f)

            val warn = running && ms <= 10000
            val paused = !running && !finished && ms < presetSec * 1000L

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

            stageLabel.text = when {
                finished -> "TIME"
                warn -> "FINISH IT"
                running -> "RUNNING"
                paused -> "PAUSED"
                else -> "READY  ·  " + presetLabel(presetSec).uppercase()
            }

            btnGo.text = when {
                finished -> "GO AGAIN"
                running -> "PAUSE"
                paused -> "RESUME"
                else -> "START"
            }
            styleBtn(btnGo, if (running) cAmber else cGreen, cInkGreen, 16f)
            btnAlt.text = altResetLabel()
            styleBtn(btnAlt, cCyan, cInkCyan, 16f)
            btnAlt.isEnabled = true
            btnAlt.alpha = 1f
        } else {
            val e = if (swRunning) SystemClock.elapsedRealtime() - swBase else swElapsed
            digits.text = fmtSw(e)
            digits.setTextColor(if (swRunning) cText else if (e > 0) cDim else cText)
            stageLabel.text = if (swRunning) "RUNNING" else if (e > 0) "STOPPED" else "STOPWATCH"

            btnGo.text = if (swRunning) "STOP" else if (e > 0) "RESUME" else "START"
            styleBtn(btnGo, if (swRunning) cAmber else cGreen, cInkGreen, 16f)
            btnAlt.text = if (swRunning) "LAP" else "RESET"
            styleBtn(btnAlt, cCyan, cInkCyan, 16f)
            val can = swRunning || e > 0 || laps.isNotEmpty()
            btnAlt.isEnabled = can
            btnAlt.alpha = if (can) 1f else 0.3f
        }
    }

    // ------------------------------------------------------------- system
    private fun keepAwake(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun buzz(ms: Long) {
        if (!vibe) return
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    private fun buzzPattern(pattern: LongArray) {
        if (!vibe) return
        try {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {
        }
    }

    companion object {
        private val PRESETS = intArrayOf(35, 45, 60, 300, 600, 1200, 1800, 3600)
        private const val DEFAULT_SEC = 35
        private const val MODE_TIMER = "timer"
        private const val MODE_SW = "stopwatch"
    }
}
