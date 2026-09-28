package com.matt.gymtimer

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
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
class MainActivity : Activity(), TimerEngine.Listener, AlarmEngine.Listener {

    private var mode = MODE_TIMER
    private var started = false
    private var resumed = false                  // onResume..onPause: the ring may open over us
    private var ringShown = false                // this ring's screen already opened since onStart

    // picker overlay (kept across a rotation rebuild)
    private var pickOpen = false
    private var pickMinVal = 0
    private var pickSecVal = 35

    // alarm editor overlay (kept across a rotation rebuild)
    private var editOpen = false
    private var editId = -1                 // -1 = a new alarm
    private var editH12 = 6
    private var editM = 0
    private var editPmOn = false
    private var editDays = 0

    /** "RINGS IN 7H 12M" after SAVE, shown for a few seconds */
    private var noteText: String? = null

    // ---------------------------------------------------------------- views
    private lateinit var prefs: SharedPreferences
    private val h = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
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

    private lateinit var tabAlarm: Button
    private lateinit var btnRow: View
    private var body: View? = null           // landscape only: presets + stage
    private lateinit var alarmPanel: View
    private lateinit var alarmStatus: TextView
    private lateinit var alarmNote: TextView
    private lateinit var warnNotif: TextView
    private lateinit var warnFull: TextView
    private lateinit var warnArm: TextView
    private lateinit var alarmList: LinearLayout
    private lateinit var alarmSound: Button
    private lateinit var alarmAdd: Button
    private lateinit var editOverlay: LinearLayout
    private lateinit var editHour: NumberPicker
    private lateinit var editMin: NumberPicker
    private lateinit var editAm: Button
    private lateinit var editPm: Button
    private lateinit var editDaysBox: LinearLayout
    private lateinit var editCancel: Button
    private lateinit var editDelete: Button
    private lateinit var editSave: Button

    private val presetBtns = ArrayList<Button>()
    private val dayBtns = ArrayList<Button>()

    private var cBg = 0; private var cPanel = 0; private var cPanel2 = 0; private var cLine = 0
    private var cText = 0; private var cDim = 0; private var cGreen = 0; private var cAmber = 0
    private var cCyan = 0; private var cRed = 0; private var cDoneBg = 0; private var cInkGreen = 0
    private var cInkCyan = 0; private var cBarTrack = 0; private var cTogOff = 0

    // ------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("mt", Context.MODE_PRIVATE)
        AlarmEngine.init(applicationContext)     // first, so the timer's VIB mirror lands in it
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
        // the missed-alarm card or the status-bar alarm icon: open on ALARM.
        // A rebuild after the process was reclaimed is not a fresh open.
        if (savedInstanceState == null && alarmTabAsked(intent)) mode = MODE_ALARM

        bind()
        buildPresets()
        buildPicker()
        buildEditor()
        styleChrome()

        syncPresets()
        setMode(mode)
        watchStage()
    }

    /**
     * singleTop: a tap on the missed-alarm card or the status-bar alarm icon
     * while the app is up lands here. It redraws through setMode, never onTab,
     * so it does not silence a timer ring-out - only his own tab tap does that.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (alarmTabAsked(intent)) setMode(MODE_ALARM)
    }

    /** true once per intent: the extra is removed so a later return does not force the tab */
    private fun alarmTabAsked(i: Intent?): Boolean {
        if (i?.getStringExtra(AlarmEngine.EXTRA_TAB) != AlarmEngine.TAB_ALARM) return false
        i.removeExtra(AlarmEngine.EXTRA_TAB)
        return true
    }

    override fun onStart() {
        super.onStart()
        started = true
        TimerEngine.addListener(this)
        AlarmEngine.addListener(this)
        ringShown = false
        showRingIfLive()
        if (mode == MODE_ALARM) renderAlarms()
        syncPresets()
        if (mode == MODE_SW) renderLaps()
        syncFlash()
        keepAwake(TimerEngine.running || TimerEngine.swRunning || TimerEngine.alarming)
        render()
        loopOn()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    override fun onStop() {
        super.onStop()
        started = false
        TimerEngine.removeListener(this)
        AlarmEngine.removeListener(this)
        h.removeCallbacks(minuteTick)
        loopOff()
        stopFlash()
    }

    /** the engine's cues and finish are NOT cancelled here - they outlive us */
    override fun onDestroy() {
        super.onDestroy()
        TimerEngine.removeListener(this)
        AlarmEngine.removeListener(this)
        h.removeCallbacksAndMessages(null)
    }

    /** the alarm engine calls this on every change to the list, the voice or a ring */
    override fun onAlarmState() {
        if (!started) return
        if (!AlarmEngine.ringing) ringShown = false
        else if (resumed && !ringShown) showRingIfLive()
        if (mode == MODE_ALARM) renderAlarms()
    }

    /**
     * "the stop button should be on the screen when the alarm is firing" - Matt,
     * 2026-09-28. A ring live with this screen in front opens the ringing screen
     * (ALARM, the time, STOP) over it; whatever was open here stays as it was.
     */
    private fun showRingIfLive() {
        if (!AlarmEngine.ringing) return
        ringShown = true
        startActivity(Intent(this, AlarmActivity::class.java))
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
        val wasEdit = editOpen
        stopFlash()

        setContentView(R.layout.activity_main)
        bind()
        buildPresets()
        buildPicker()
        buildEditor()
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
        if (wasEdit && mode == MODE_ALARM) {
            editOpen = true
            editOverlay.visibility = View.VISIBLE
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
        if (editOpen) {
            closeEditor()
            return
        }
        super.onBackPressed()
    }

    private fun bind() {
        root = findViewById(R.id.root)
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

        tabAlarm = findViewById(R.id.tabAlarm)
        btnRow = findViewById(R.id.btnRow)
        body = findViewById(R.id.body)
        alarmPanel = findViewById(R.id.alarmPanel)
        alarmStatus = findViewById(R.id.alarmStatus)
        alarmNote = findViewById(R.id.alarmNote)
        warnNotif = findViewById(R.id.warnNotif)
        warnFull = findViewById(R.id.warnFull)
        warnArm = findViewById(R.id.warnArm)
        alarmList = findViewById(R.id.alarmList)
        alarmSound = findViewById(R.id.alarmSound)
        alarmAdd = findViewById(R.id.alarmAdd)
        editOverlay = findViewById(R.id.editOverlay)
        editHour = findViewById(R.id.editHour)
        editMin = findViewById(R.id.editMin)
        editAm = findViewById(R.id.editAm)
        editPm = findViewById(R.id.editPm)
        editDaysBox = findViewById(R.id.editDays)
        editCancel = findViewById(R.id.editCancel)
        editDelete = findViewById(R.id.editDelete)
        editSave = findViewById(R.id.editSave)

        tabAlarm.setOnClickListener { onTab(MODE_ALARM) }
        alarmAdd.setOnClickListener {
            if (AlarmEngine.alarms.size >= AlarmEngine.MAX_ALARMS) {
                TimerEngine.buzz(40)         // D7: the cap is not shown
                return@setOnClickListener
            }
            openEditor(-1)
        }
        alarmSound.setOnClickListener {
            AlarmEngine.setVoice(AlarmEngine.voice + 1)
            // the alarm ignores MUTE, so its preview does too - at full gain
            AlarmEngine.tones.play(AlarmEngine.tones.alarmVoices[AlarmEngine.voice].preview, 1f)
        }
        warnNotif.setOnClickListener { allowNotif() }
        warnFull.setOnClickListener { allowFullScreen() }
        editAm.setOnClickListener { readEditWheels(); editPmOn = false; paintEditor() }
        editPm.setOnClickListener { readEditWheels(); editPmOn = true; paintEditor() }
        editCancel.setOnClickListener { closeEditor(); TimerEngine.buzz(20) }
        editDelete.setOnClickListener {
            if (!delArmed) {
                armDelete()                 // first tap only asks
                return@setOnClickListener
            }
            if (editId != -1) AlarmEngine.delete(editId)
            closeEditor()
            TimerEngine.buzz(30)
        }
        editSave.setOnClickListener { saveEditor() }

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
            setAndStart(sec)                 // SET & START - the RESTART path
        }

        insetPad()
    }

    /**
     * The number pad must never sit over CANCEL and SET & START. The window is
     * edge-to-edge (targetSdk 35), so the keyboard does not shrink it by itself:
     * the bottom padding here does, by the larger of the navigation bar and the
     * IME. The wheels are what gives - they are the weighted child - and the two
     * buttons keep their full height above the keyboard, portrait and landscape.
     *
     * This listener replaces the root's fitsSystemWindows handling, so it carries
     * the system-bar padding too, and consumes: nothing below it re-applies them.
     * Re-registered by [bind] after every rotation rebuild.
     */
    private fun insetPad() {
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime))
                WindowInsets.CONSUMED
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
                @Suppress("DEPRECATION")
                insets.consumeSystemWindowInsets()
            }
        }
        root.requestApplyInsets()
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
        // A formatter, NOT displayedValues. With displayed values NumberPicker
        // matches typed text against them by prefix, so a typed 5 completed to
        // the first label starting with 5 - "50" - instead of 05 [measured,
        // 2026-09-20, n=1]. With none, typed text is parsed as an integer and
        // the filter caps it at 59, while the formatter keeps the wheel reading
        // 00..59. Set before the range so the wheel's string cache is built
        // with it.
        pickSec.setFormatter { v -> two(v) }
        pickSec.minValue = 0
        pickSec.maxValue = 59
        pickSec.wrapSelectorWheel = true

        for (np in arrayOf(pickMin, pickSec)) {
            np.background = rounded(cPanel, 16f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                np.setTextColor(cText)
                np.setTextSize(dp(30f))
                np.selectionDividerHeight = dp(2f).toInt()
            }
            typeable(np)
        }
        pickMin.setOnValueChangedListener { _, _, v -> pickMinVal = v; paintPick() }
        pickSec.setOnValueChangedListener { _, _, v -> pickSecVal = v; paintPick() }
        pickMin.value = pickMinVal
        pickSec.value = pickSecVal
        showSecText()

        styleBtn(pickCancel, cPanel2, cText, 14f, cLine)
        styleBtn(pickSet, cCyan, cInkCyan, 14f)
        paintPick()
    }

    /**
     * Which keyboard a wheel raises is decided by its own EditText. The seconds
     * wheel carries displayedValues ("00".."59") and NumberPicker puts a TEXT
     * input type on the field when it has them - which is why tapping a number
     * brought up the full QWERTY keyboard on the phone [measured, 2026-09-20,
     * n=1]. Forced back to a number pad here. NumberPicker's own input filter is
     * left alone, so typing 12 still means 12 and minutes still stop at 99.
     */
    private fun typeable(np: NumberPicker, onDone: () -> Unit = { commitTyped() }) {
        val et = pickInput(np) ?: return
        et.inputType = InputType.TYPE_CLASS_NUMBER
        // DONE = the check key; NO_EXTRACT_UI keeps landscape keyboards from
        // taking the whole screen and hiding the two buttons
        et.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        et.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onDone()
                true
            } else false
        }
    }

    private fun two(v: Int) = v.toString().padStart(2, '0')

    /**
     * NumberPicker re-runs its formatter over the input field only when the
     * value actually changes (setValueInternal returns early otherwise), so a
     * wheel opened on the value it already holds - or left showing what was
     * typed into it - can read "5" while the rows above and below read "05".
     * The field is seeded by hand here. The reflection trick on the framework's
     * private changeValueByOne is not used: it is a non-SDK interface, blocked
     * at this targetSdk, and it would move the value to do it.
     */
    private fun showSecText() {
        pickInput(pickSec)?.setText(two(pickSec.value))
    }

    /** the wheel's inner field - a direct child of the framework NumberPicker */
    private fun pickInput(np: NumberPicker): EditText? {
        for (i in 0 until np.childCount) {
            val c = np.getChildAt(i)
            if (c is EditText) return c
        }
        return null
    }

    /**
     * The check key on the number pad: commit what was typed - clearing focus is
     * what makes NumberPicker validate it - and then take the same path SET &
     * START takes. At 0:00 there is nothing to start, so it commits and drops
     * the keyboard, leaving the wheels up.
     */
    private fun commitTyped() {
        pickMin.clearFocus()
        pickSec.clearFocus()
        pickMinVal = pickMin.value
        pickSecVal = pickSec.value
        showSecText()
        paintPick()
        val sec = pickMinVal * 60 + pickSecVal
        if (sec <= 0) {
            hideKeyboard()
            return
        }
        setAndStart(sec)
    }

    /** SET & START, from the button or from the keyboard's check key */
    private fun setAndStart(sec: Int) {
        closePicker()
        askNotif()
        TimerEngine.setAndStart(sec)
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(pickOverlay.windowToken, 0)
        } catch (_: Exception) {
        }
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
        showSecText()
        paintPick()
        pickOverlay.visibility = View.VISIBLE
        pickOpen = true
        TimerEngine.buzz(15)
    }

    /** CANCEL, SET & START, the back button, a tab, a state change: the keyboard goes with it */
    private fun closePicker() {
        pickMin.clearFocus()
        pickSec.clearFocus()
        hideKeyboard()
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
        if (editOpen && m != MODE_ALARM) closeEditor()
        val alarm = m == MODE_ALARM
        presetBox.visibility = if (m == MODE_TIMER) View.VISIBLE else View.GONE
        barTrack.visibility = if (m == MODE_TIMER) View.VISIBLE else View.GONE
        // ALARM hides the presets, the stage, the bar, the laps and the three buttons
        stage.visibility = if (alarm) View.GONE else View.VISIBLE
        btnRow.visibility = if (alarm) View.GONE else View.VISIBLE
        body?.visibility = if (alarm) View.GONE else View.VISIBLE
        alarmPanel.visibility = if (alarm) View.VISIBLE else View.GONE
        if (m == MODE_TIMER) {
            lapsScroll.visibility = View.GONE
        } else if (!alarm) {
            renderLaps()
        }
        if (alarm) renderAlarms() else h.removeCallbacks(minuteTick)
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
        if (ticking || !started || mode == MODE_ALARM) return
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
        styleBtn(tabAlarm, if (mode == MODE_ALARM) cPanel2 else Color.TRANSPARENT,
            if (mode == MODE_ALARM) cText else cDim, 9f, if (mode == MODE_ALARM) cLine else null)

        if (mode == MODE_ALARM) return      // the ALARM tab draws itself: renderAlarms

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

    // ============================================================= ALARM tab
    /** the status line's countdown moves on the minute while the tab is showing */
    private val minuteTick = Runnable { if (started && mode == MODE_ALARM) renderAlarms() }

    private val clearNote = Runnable {
        noteText = null
        if (started && mode == MODE_ALARM) renderAlarms()
    }

    /** 7H 12M; a day or more reads 2D 3H 12M. Rounded up to the minute */
    private fun inText(ms: Long): String {
        val mins = ceil(ms.coerceAtLeast(0) / 60000.0).toLong()
        val d = mins / 1440
        val hh = mins % 1440 / 60
        val mm = mins % 60
        return when {
            d > 0 -> "${d}D ${hh}H ${mm}M"
            hh > 0 -> "${hh}H ${mm}M"
            else -> "${mm}M"
        }
    }

    /** ONCE, EVERY DAY, WEEKDAYS, WEEKENDS, or the days in week order: M W F */
    private fun daysText(d: Int): String = when (d) {
        0 -> "ONCE"
        0x7F -> "EVERY DAY"
        0x3E -> "WEEKDAYS"
        0x41 -> "WEEKENDS"
        else -> (0 until 7).filter { (d shr it) and 1 == 1 }.joinToString(" ") { DAY_LETTERS[it] }
    }

    private fun notifOk(): Boolean = try {
        (getSystemService(NotificationManager::class.java)).areNotificationsEnabled()
    } catch (_: Exception) {
        true
    }

    private fun fullScreenOk(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return try {
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } catch (_: Exception) {
            true
        }
    }

    private fun renderAlarms() {
        val now = System.currentTimeMillis()
        val nx = AlarmEngine.nextUp()
        alarmStatus.text = if (nx == null) "NO ALARM SET"
        else "NEXT  ${AlarmEngine.fmt12(nx.h, nx.m)}  ·  IN ${inText(nx.next - now)}"
        alarmStatus.setTextColor(if (nx == null) cDim else cText)

        val note = noteText
        alarmNote.text = note ?: ""
        alarmNote.visibility = if (note != null) View.VISIBLE else View.GONE

        warnNotif.visibility = if (notifOk()) View.GONE else View.VISIBLE
        warnFull.visibility = if (fullScreenOk()) View.GONE else View.VISIBLE
        warnArm.visibility = if (AlarmEngine.armFailed) View.VISIBLE else View.GONE

        alarmSound.text = "SOUND  ·  " + AlarmEngine.voiceName()
        styleBtn(alarmSound, cPanel2, cCyan, 14f, cLine)
        styleBtn(alarmAdd, cGreen, cInkGreen, 16f)
        alarmAdd.alpha = if (AlarmEngine.alarms.size >= AlarmEngine.MAX_ALARMS) 0.35f else 1f

        buildAlarmRows()

        h.removeCallbacks(minuteTick)
        if (started && mode == MODE_ALARM) {
            h.postDelayed(minuteTick, 60_000L - now % 60_000L + 50L)
        }
    }

    /** one row per alarm, by time of day: the row opens the editor, the switch only switches */
    private fun buildAlarmRows() {
        alarmList.removeAllViews()
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val sorted = AlarmEngine.alarms.sortedWith(compareBy({ it.h * 60 + it.m }, { it.id }))
        for ((i, a) in sorted.withIndex()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = rounded(cPanel, 14f, cLine)
            row.setPadding(dp(14f).toInt(), dp(8f).toInt(), dp(10f).toInt(), dp(8f).toInt())
            val rp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (i > 0) rp.topMargin = dp(8f).toInt()
            row.layoutParams = rp

            val left = LinearLayout(this)
            left.orientation = LinearLayout.VERTICAL
            left.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

            val label = AlarmEngine.fmt12(a.h, a.m)          // "5:30 AM"
            val cut = label.indexOf(' ')
            val sb = SpannableStringBuilder(label)
            sb.setSpan(RelativeSizeSpan(0.5f), cut, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val time = TextView(this)
            time.text = sb
            time.textSize = if (land) 28f else 34f
            time.setTextColor(if (a.on) cText else cDim)
            time.typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD
            )
            time.maxLines = 1
            left.addView(time)

            val days = TextView(this)
            days.text = daysText(a.days)
            days.textSize = 13f
            days.letterSpacing = 0.12f
            days.setTextColor(cDim)
            days.setTypeface(days.typeface, android.graphics.Typeface.BOLD)
            left.addView(days)
            row.addView(left)

            val sw = Button(this)
            sw.text = if (a.on) "ON" else "OFF"
            sw.textSize = 17f
            sw.setTypeface(sw.typeface, android.graphics.Typeface.BOLD)
            if (a.on) styleBtn(sw, cGreen, cInkGreen, 12f)
            else styleBtn(sw, cPanel2, cTogOff, 12f, cLine)
            sw.layoutParams = LinearLayout.LayoutParams(dp(76f).toInt(), dp(56f).toInt())
            val id = a.id
            val turnOn = !a.on
            sw.setOnClickListener {
                AlarmEngine.setOn(id, turnOn)
                TimerEngine.buzz(20)
            }
            row.addView(sw)

            row.setOnClickListener { openEditor(id) }
            alarmList.addView(row)
        }
    }

    /**
     * NOTIFICATIONS OFF: ask for the permission; when the system will no longer
     * show its dialog (denied twice), open this app's notification settings so
     * the tap still leads somewhere.
     */
    private fun allowNotif() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            try {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_ALARM_NOTIF)
            } catch (_: Exception) {
            }
            return
        }
        openNotifSettings()
    }

    private fun openNotifSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (_: Exception) {
        }
    }

    private fun allowFullScreen() {
        if (Build.VERSION.SDK_INT < 34) return
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_ALARM_NOTIF && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!granted && !shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
                openNotifSettings()
            }
        }
        if (started && mode == MODE_ALARM) renderAlarms()
    }

    // ---------------------------------------------------------- alarm editor
    /**
     * Same look as the timer's picker: an hour wheel 1-12 and a minute wheel
     * 00-59 (a formatter, not displayedValues - the typed-5 finding), both
     * typeable with the number pad, AM / PM, and the seven days. None lit is a
     * one-shot. The pad's check key only commits what was typed.
     */
    private fun buildEditor() {
        editHour.minValue = 1
        editHour.maxValue = 12
        editHour.wrapSelectorWheel = true
        editMin.setFormatter { v -> two(v) }
        editMin.minValue = 0
        editMin.maxValue = 59
        editMin.wrapSelectorWheel = true

        for (np in arrayOf(editHour, editMin)) {
            np.background = rounded(cPanel, 16f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                np.setTextColor(cText)
                np.setTextSize(dp(30f))
                np.selectionDividerHeight = dp(2f).toInt()
            }
            typeable(np) { commitEditTyped() }
        }
        editHour.setOnValueChangedListener { _, _, v -> editH12 = v; disarmDelete() }
        editMin.setOnValueChangedListener { _, _, v -> editM = v; disarmDelete() }

        editDaysBox.removeAllViews()
        dayBtns.clear()
        for (d in 0 until 7) {
            val b = Button(this)
            val lp = LinearLayout.LayoutParams(0, resources.getDimensionPixelSize(R.dimen.day_btn_h), 1f)
            if (d > 0) lp.marginStart = dp(6f).toInt()
            b.layoutParams = lp
            b.text = DAY_LETTERS[d]
            b.textSize = 17f
            b.setTypeface(b.typeface, android.graphics.Typeface.BOLD)
            b.setOnClickListener {
                readEditWheels()            // keep anything typed into a wheel
                editDays = editDays xor (1 shl d)
                paintEditor()
            }
            dayBtns.add(b)
            editDaysBox.addView(b)
        }

        editOverlay.setBackgroundColor(Color.rgb(6, 9, 13))
        styleBtn(editCancel, cPanel2, cText, 14f, cLine)
        delTextPx = editDelete.textSize     // the layout's size, fresh from inflation
        styleBtn(editDelete, cPanel2, cRed, 14f, cRed)
        styleBtn(editSave, cCyan, cInkCyan, 14f)
        paintEditor()
    }

    // DELETE asks once: the first tap arms it for DEL_ARM_MS, the second deletes
    private var delArmed = false
    private var delTextPx = 0f
    private val disarmRun = Runnable { disarmDelete() }

    private fun armDelete() {
        readEditWheels()                    // keep anything typed into a wheel
        delArmed = true
        editDelete.text = "TAP AGAIN TO DELETE"
        editDelete.textSize = 12f
        styleBtn(editDelete, cRed, cBg, 14f)
        TimerEngine.buzz(15)
        h.removeCallbacks(disarmRun)
        h.postDelayed(disarmRun, DEL_ARM_MS)
    }

    /** back to plain DELETE: time out, or any other action in the editor */
    private fun disarmDelete() {
        h.removeCallbacks(disarmRun)
        if (!delArmed) return
        delArmed = false
        editDelete.text = "DELETE"
        editDelete.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, delTextPx)
        styleBtn(editDelete, cPanel2, cRed, 14f, cRed)
    }

    /** the editor's state onto its views */
    private fun paintEditor() {
        disarmDelete()                      // AM / PM, a day, opening the editor
        editHour.value = editH12
        editMin.value = editM
        pickInput(editMin)?.setText(two(editMin.value))
        styleBtn(editAm, if (!editPmOn) cCyan else cPanel2, if (!editPmOn) cInkCyan else cDim, 12f,
            if (!editPmOn) null else cLine)
        styleBtn(editPm, if (editPmOn) cCyan else cPanel2, if (editPmOn) cInkCyan else cDim, 12f,
            if (editPmOn) null else cLine)
        for (d in dayBtns.indices) {
            val on = (editDays shr d) and 1 == 1
            styleBtn(dayBtns[d], if (on) cCyan else cPanel, if (on) cInkCyan else cDim, 10f,
                if (on) null else cLine)
        }
        editDelete.visibility = if (editId != -1) View.VISIBLE else View.GONE
    }

    /** a new alarm opens on 6:00 AM, no days; an existing one on its own values */
    private fun openEditor(id: Int) {
        val a = if (id == -1) null else AlarmEngine.find(id)
        if (a == null) {
            editId = -1
            editH12 = 6; editM = 0; editPmOn = false; editDays = 0
        } else {
            editId = a.id
            editH12 = if (a.h % 12 == 0) 12 else a.h % 12
            editM = a.m
            editPmOn = a.h >= 12
            editDays = a.days
        }
        paintEditor()
        editOverlay.visibility = View.VISIBLE
        editOpen = true
        TimerEngine.buzz(15)
    }

    /** CANCEL, SAVE, DELETE, back: the keyboard goes with it */
    private fun closeEditor() {
        disarmDelete()
        editHour.clearFocus()
        editMin.clearFocus()
        hideKeyboard()
        editOverlay.visibility = View.GONE
        editOpen = false
    }

    /** the number pad's check key: commit what was typed, drop the keyboard - it does not save */
    private fun commitEditTyped() {
        disarmDelete()
        readEditWheels()
        pickInput(editMin)?.setText(two(editMin.value))
        hideKeyboard()
    }

    /** clearing focus is what makes NumberPicker take a typed value */
    private fun readEditWheels() {
        editHour.clearFocus()
        editMin.clearFocus()
        editH12 = editHour.value
        editM = editMin.value
    }

    private fun saveEditor() {
        readEditWheels()
        val h24 = (editH12 % 12) + if (editPmOn) 12 else 0
        val id = if (editId == -1) AlarmEngine.add(h24, editM, editDays)
        else {
            AlarmEngine.update(editId, h24, editM, editDays)
            editId
        }
        closeEditor()
        TimerEngine.buzz(30)
        val a = AlarmEngine.find(id)
        if (a != null && a.next > 0) {
            noteText = "RINGS IN " + inText(a.next - System.currentTimeMillis())
            h.removeCallbacks(clearNote)
            h.postDelayed(clearNote, NOTE_MS)
        }
        askNotif()                          // asked by itself the first time
        renderAlarms()
    }

    companion object {
        private const val MODE_TIMER = "timer"
        private const val MODE_SW = "stopwatch"
        private const val MODE_ALARM = "alarm"
        private const val REQ_ALARM_NOTIF = 8
        private const val NOTE_MS = 6000L
        private const val DEL_ARM_MS = 4000L
        private val DAY_LETTERS = arrayOf("S", "M", "T", "W", "T", "F", "S")
        private val SUB_ON_CYAN = Color.argb(190, 4, 32, 46)
    }
}
