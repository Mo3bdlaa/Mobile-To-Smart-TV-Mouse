package io.github.mo3bdlaa.tvmouse.ui

import android.content.Context

data class TouchpadSettings(
    /** Pointer counts per dp of finger movement, before acceleration. */
    val sensitivity: Float = 1.5f,
    /** 0 = no acceleration; higher = fast swipes travel further. */
    val acceleration: Float = 0.8f,
    /** Finger travel (dp) per scroll step; lower scrolls faster. */
    val scrollStep: Float = 18f,
    /** Content follows the finger, like scrolling on the phone. */
    val naturalScroll: Boolean = true,
) {
    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("sensitivity", sensitivity)
            .putFloat("acceleration", acceleration)
            .putFloat("scrollStep", scrollStep)
            .putBoolean("naturalScroll", naturalScroll)
            .apply()
    }

    companion object {
        private const val PREFS = "touchpad"

        fun load(context: Context): TouchpadSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val defaults = TouchpadSettings()
            return TouchpadSettings(
                sensitivity = prefs.getFloat("sensitivity", defaults.sensitivity),
                acceleration = prefs.getFloat("acceleration", defaults.acceleration),
                scrollStep = prefs.getFloat("scrollStep", defaults.scrollStep),
                naturalScroll = prefs.getBoolean("naturalScroll", defaults.naturalScroll),
            )
        }
    }
}
