package com.matt.gymtimer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms every alarm after a restart (before and after the first unlock, D1),
 * an app update, a clock change or a time-zone change (D6). Not exported,
 * direct-boot aware. Built to docs/ALARM_SPEC.md Section 8.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmEngine.init(context)
        AlarmEngine.armAll(System.currentTimeMillis())
    }
}
