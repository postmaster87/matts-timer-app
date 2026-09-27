package com.matt.gymtimer

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The ringing screen, over the lock screen: ALARM, the alarm's time, one STOP.
 * His answer 10: "B" - full-screen takeover on a locked phone. Built to
 * docs/ALARM_SPEC.md Section 10.3. Touches AlarmEngine only.
 */
class AlarmActivity : Activity(), AlarmEngine.Listener {

    private lateinit var timeView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlarmEngine.init(applicationContext)
        if (!AlarmEngine.ringing) {
            finish()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(build())
    }

    override fun onStart() {
        super.onStart()
        AlarmEngine.addListener(this)
        onAlarmState()
    }

    override fun onStop() {
        super.onStop()
        AlarmEngine.removeListener(this)
    }

    /** the ring ended some other way (the card's STOP, the 15-minute cut): go */
    override fun onAlarmState() {
        if (!AlarmEngine.ringing) {
            finish()
            return
        }
        if (::timeView.isInitialized) timeView.text = AlarmEngine.labelFor(AlarmEngine.ringId)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (AlarmEngine.ringing) return          // back does nothing while ringing
        super.onBackPressed()
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun build(): FrameLayout {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        root.addView(
            col, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.VERTICAL
        mid.gravity = Gravity.CENTER
        col.addView(mid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val head = TextView(this)
        head.text = "ALARM"
        head.setTextColor(getColor(R.color.dim))
        head.textSize = 18f
        head.letterSpacing = 0.22f
        head.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        head.gravity = Gravity.CENTER
        mid.addView(head)

        timeView = TextView(this)
        timeView.text = AlarmEngine.labelFor(AlarmEngine.ringId)
        timeView.setTextColor(Color.WHITE)
        timeView.textSize = 60f
        timeView.maxLines = 1
        timeView.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        timeView.gravity = Gravity.CENTER
        val tp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        tp.topMargin = dp(8f)
        mid.addView(timeView, tp)

        val stop = Button(this)
        stop.text = "STOP"
        stop.textSize = 34f
        stop.isAllCaps = false
        stop.letterSpacing = 0.1f
        stop.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        stop.setTextColor(getColor(R.color.ink_green))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(18f).toFloat()
        bg.setColor(getColor(R.color.green))
        stop.background = bg
        stop.stateListAnimator = null
        stop.setOnClickListener {
            AlarmEngine.stop()
            finish()
        }
        col.addView(stop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140f)))
        return root
    }
}
