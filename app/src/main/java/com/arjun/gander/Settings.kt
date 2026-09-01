package com.arjun.gander

import android.content.Context

/**
 * The reader's own preferences, as opposed to [Recents], which is a record of what
 * they opened.
 *
 * There is one so far. Gander has never had a settings screen and does not want one;
 * night mode is here rather than reset on every open because a mode you turn on to
 * read in bed is not a mode you want to turn on again at the next chapter.
 *
 * Not backed up: `allowBackup="false"` in the manifest covers this file along with the
 * recents list, for the reason given there.
 */
object Settings {

    private const val PREFS = "viewer"
    private const val KEY_NIGHT = "night"

    /** Whether PDF pages are drawn turned over. See issue #19 and `pdf.html`. */
    fun night(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_NIGHT, false)

    fun setNight(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NIGHT, on).apply()
    }
}
