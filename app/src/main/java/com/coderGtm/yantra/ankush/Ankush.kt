package com.coderGtm.yantra.ankush

import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.Calendar

/**
 * Personal features for the "Ankush" build:
 *  - renaming commands (e.g. launch -> luck); the original name stops working
 *  - time-locked aliases: an alias only runs when followed by (hour + minute), 24h clock
 *  - password-protected ankush-help that shows every rename and alias
 */
object Ankush {
    const val HELP_COMMAND = "ankush-help"
    const val RENAME_COMMAND = "ankush-rename"
    const val RESET_COMMAND = "ankush-reset"
    const val PASSWD_COMMAND = "ankush-passwd"
    val MANAGEMENT_COMMANDS = setOf(HELP_COMMAND, RENAME_COMMAND, RESET_COMMAND, PASSWD_COMMAND)

    private const val PREF_RENAMES = "ankushRenames"          // StringSet of "original=custom"
    private const val PREF_PASSWORD_HASH = "ankushPasswordHash"
    private const val DEFAULT_PASSWORD = "spytro26"

    private val NAME_REGEX = Regex("^[a-z][a-z0-9]*$")

    // ---------- command renames ----------

    /** original command name -> custom name */
    fun getRenames(prefs: SharedPreferences): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        prefs.getStringSet(PREF_RENAMES, emptySet())?.forEach { entry ->
            val parts = entry.split("=", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                map[parts[0]] = parts[1]
            }
        }
        return map
    }

    fun saveRenames(prefs: SharedPreferences, renames: Map<String, String>) {
        prefs.edit().putStringSet(PREF_RENAMES, renames.map { "${it.key}=${it.value}" }.toSet()).apply()
    }

    /** custom name -> original command name */
    fun getReverseRenames(prefs: SharedPreferences): Map<String, String> =
        getRenames(prefs).entries.associate { it.value to it.key }

    fun isValidName(name: String): Boolean = NAME_REGEX.matches(name)

    // ---------- time code for aliases ----------

    /** hour (0-23) + minute (0-59). 1:02 -> 3, 13:45 -> 58. */
    fun timeCode(calendar: Calendar = Calendar.getInstance()): Int =
        calendar.get(Calendar.HOUR_OF_DAY) + calendar.get(Calendar.MINUTE)

    /**
     * Codes accepted right now: the current minute, plus the previous minute as a
     * small grace period in case the clock ticks over while you are typing.
     */
    fun acceptedTimeCodes(): Set<Int> {
        val now = Calendar.getInstance()
        val previous = (now.clone() as Calendar).apply { add(Calendar.MINUTE, -1) }
        return setOf(timeCode(now), timeCode(previous))
    }

    // ---------- password ----------

    fun checkPassword(prefs: SharedPreferences, password: String): Boolean {
        val stored = prefs.getString(PREF_PASSWORD_HASH, null) ?: sha256(DEFAULT_PASSWORD)
        return MessageDigest.isEqual(stored.toByteArray(), sha256(password).toByteArray())
    }

    fun setPassword(prefs: SharedPreferences, password: String) {
        prefs.edit().putString(PREF_PASSWORD_HASH, sha256(password)).apply()
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
