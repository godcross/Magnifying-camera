package com.godcross.magnifier

import android.content.Context

/** Small wrapper around SharedPreferences. Everything stays on the device. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("magnifier_prefs", Context.MODE_PRIVATE)

    var stabilization: Boolean
        get() = sp.getBoolean("stabilization", true)
        set(v) = sp.edit().putBoolean("stabilization", v).apply()

    var grid: Boolean
        get() = sp.getBoolean("grid", true)
        set(v) = sp.edit().putBoolean("grid", v).apply()

    var steadyShot: Boolean
        get() = sp.getBoolean("steady_shot", true)
        set(v) = sp.edit().putBoolean("steady_shot", v).apply()

    /** 1 = single shot, 3 or 5 = take a burst and keep the sharpest. */
    var burstCount: Int
        get() = sp.getInt("burst_count", 3)
        set(v) = sp.edit().putInt("burst_count", v).apply()

    /** Self-timer seconds, 0 = off. */
    var timerSeconds: Int
        get() = sp.getInt("timer_seconds", 0)
        set(v) = sp.edit().putInt("timer_seconds", v).apply()

    var lensIndex: Int
        get() = sp.getInt("lens_index", 0)
        set(v) = sp.edit().putInt("lens_index", v).apply()
}
