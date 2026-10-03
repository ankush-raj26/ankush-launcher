package com.coderGtm.yantra.ankush

import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.Calendar

/**
 * Personal features for the "Ankush" build:
 *  - renaming commands (e.g. launch -> luck); the original name stops working
 *  - time-locked aliases: an alias only runs when followed by (hour + minute), 24h clock
 *  - password-protected, hidden management commands (ankush-*)
 *  - privacy: no suggestions, no history, no echo, auto-clear, lockout, decoy password,
 *    protected commands, optional screenshot blocking (extra_privacy)
 */
object Ankush {
    // ---------- hidden management commands ----------
    const val HELP_COMMAND = "ankush-help"
    const val RENAME_COMMAND = "ankush-rename"
    const val RESET_COMMAND = "ankush-reset"
    const val PASSWD_COMMAND = "ankush-passwd"
    const val DECOY_COMMAND = "ankush-decoy"
    const val PRIVACY_COMMAND = "ankush-privacy"
    val MANAGEMENT_COMMANDS = setOf(
        HELP_COMMAND, RENAME_COMMAND, RESET_COMMAND, PASSWD_COMMAND, DECOY_COMMAND, PRIVACY_COMMAND
    )

    // ---------- privacy switches (compile-time; flip to true to bring a feature back) ----------
    /** Command suggestions while typing. */
    const val SUGGESTIONS_ENABLED = false
    /** Print the typed command on screen ("user> luck whatsapp"). */
    const val ECHO_COMMANDS = false
    /** Keep command history (up/down arrows, history command). */
    const val HISTORY_ENABLED = false

    // ---------- timings ----------
    /** After a correct password, protected commands run without asking again for this long. */
    const val UNLOCK_WINDOW_MS = 60_000L
    /** ankush-help output is wiped from the screen after this long. */
    const val HELP_AUTO_CLEAR_MS = 20_000L
    private const val MAX_FAILED_ATTEMPTS = 3
    private const val LOCKOUT_MS = 5 * 60_000L

    /** Built-in commands that reveal activity or change setup: these ask for the password. */
    val PROTECTED_COMMANDS = setOf(
        "alias", "unalias", "history", "screentime", "list", "info", "notepad", "todo", "settings", "backup"
    )

    private const val PREF_RENAMES = "ankushRenames"          // StringSet of "original=custom"
    private const val PREF_PASSWORD_HASH = "ankushPasswordHash"
    private const val PREF_DECOY_HASH = "ankushDecoyHash"
    private const val PREF_FAILED_ATTEMPTS = "ankushFailedAttempts"
    private const val PREF_LOCKED_UNTIL = "ankushLockedUntil"
    private const val PREF_EXTRA_PRIVACY = "ankushExtraPrivacy"
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

    // ---------- passwords ----------

    enum class PasswordResult { CORRECT, DECOY, WRONG, LOCKED }

    /**
     * Checks a typed password. 3 wrong attempts in a row lock all password checks for
     * 5 minutes (even the correct password is refused while locked).
     */
    fun verifyPassword(prefs: SharedPreferences, password: String): PasswordResult {
        val now = System.currentTimeMillis()
        if (now < prefs.getLong(PREF_LOCKED_UNTIL, 0L)) return PasswordResult.LOCKED
        val typed = sha256(password)
        val main = prefs.getString(PREF_PASSWORD_HASH, null) ?: sha256(DEFAULT_PASSWORD)
        if (hashEquals(typed, main)) {
            prefs.edit().putInt(PREF_FAILED_ATTEMPTS, 0).apply()
            return PasswordResult.CORRECT
        }
        val decoy = prefs.getString(PREF_DECOY_HASH, null)
        if (decoy != null && hashEquals(typed, decoy)) {
            prefs.edit().putInt(PREF_FAILED_ATTEMPTS, 0).apply()
            return PasswordResult.DECOY
        }
        val failed = prefs.getInt(PREF_FAILED_ATTEMPTS, 0) + 1
        if (failed >= MAX_FAILED_ATTEMPTS) {
            prefs.edit()
                .putInt(PREF_FAILED_ATTEMPTS, 0)
                .putLong(PREF_LOCKED_UNTIL, now + LOCKOUT_MS)
                .apply()
        } else {
            prefs.edit().putInt(PREF_FAILED_ATTEMPTS, failed).apply()
        }
        return PasswordResult.WRONG
    }

    fun setPassword(prefs: SharedPreferences, password: String) {
        prefs.edit().putString(PREF_PASSWORD_HASH, sha256(password)).apply()
    }

    fun isMainPassword(prefs: SharedPreferences, password: String): Boolean {
        val main = prefs.getString(PREF_PASSWORD_HASH, null) ?: sha256(DEFAULT_PASSWORD)
        return hashEquals(sha256(password), main)
    }

    /** null removes the decoy password. */
    fun setDecoyPassword(prefs: SharedPreferences, password: String?) {
        if (password == null) prefs.edit().remove(PREF_DECOY_HASH).apply()
        else prefs.edit().putString(PREF_DECOY_HASH, sha256(password)).apply()
    }

    fun hasDecoyPassword(prefs: SharedPreferences): Boolean = prefs.getString(PREF_DECOY_HASH, null) != null

    // ---------- extra_privacy (blocks screenshots / recents preview) ----------

    fun isExtraPrivacy(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_EXTRA_PRIVACY, false)

    fun setExtraPrivacy(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(PREF_EXTRA_PRIVACY, enabled).apply()
    }

    private fun hashEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
