package com.coderGtm.yantra.ankush

import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.Calendar

/**
 * Personal features for the "Ankush" build:
 *  - renaming commands (e.g. launch -> luck); the original name stops working
 *  - time-locked aliases: an alias only runs when followed by the time code (see timeCode)
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
    const val ALIAS_LOCK_COMMAND = "ankush-alias"
    const val OFFSET_COMMAND = "ankush-offset"
    val MANAGEMENT_COMMANDS = setOf(
        HELP_COMMAND, RENAME_COMMAND, RESET_COMMAND, PASSWD_COMMAND, DECOY_COMMAND, PRIVACY_COMMAND,
        ALIAS_LOCK_COMMAND, OFFSET_COMMAND
    )

    /** Built-in commands that only work while the alias lock is OFF. */
    val HIDDEN_WHILE_LOCKED = setOf("help")

    // ---------- privacy switches (compile-time; flip to true to bring a feature back) ----------
    /** Command suggestions while typing. */
    const val SUGGESTIONS_ENABLED = false
    /** Print the typed command on screen ("user> luck whatsapp"). */
    const val ECHO_COMMANDS = false
    /** Keep command history (up/down arrows, history command). */
    const val HISTORY_ENABLED = false

    // ---------- timings ----------
    /** After a correct password, protected commands run without asking again for this long. */
    const val UNLOCK_WINDOW_MS = 120_000L
    /** ankush-help output is wiped from the screen after this long. */
    const val HELP_AUTO_CLEAR_MS = 120_000L
    const val MAX_FAILED_ATTEMPTS = 10
    const val LOCKOUT_MS = 3 * 60_000L

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
    private const val PREF_ALIAS_LOCK = "ankushAliasLock"
    private const val PREF_OFFSET = "ankushTimeCodeOffset"
    const val DEFAULT_OFFSET = 5
    const val MAX_OFFSET = 99
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

    /**
     * Alias time code, 12-hour clock (hour 1-12, so 12 AM/PM counts as 12):
     *   ((hour + 1) + (minute + 1)) / 2, decimals dropped, then + offset (default 5).
     * Examples with offset 5: 1:02 -> (2 + 3) / 2 = 2.5 -> 2 -> 7
     *                         1:45 PM -> (2 + 46) / 2 = 24 -> 29
     *                         12:30 -> (13 + 31) / 2 = 22 -> 27
     */
    fun timeCode(offset: Int, calendar: Calendar = Calendar.getInstance()): Int {
        val hour12 = calendar.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        val minute = calendar.get(Calendar.MINUTE)
        return ((hour12 + 1) + (minute + 1)) / 2 + offset  // integer division drops the .5
    }

    fun getOffset(prefs: SharedPreferences): Int = prefs.getInt(PREF_OFFSET, DEFAULT_OFFSET)

    fun setOffset(prefs: SharedPreferences, offset: Int) {
        prefs.edit().putInt(PREF_OFFSET, offset).apply()
    }

    /**
     * Codes accepted right now: the current minute, plus the previous minute as a
     * small grace period in case the clock ticks over while you are typing.
     */
    fun acceptedTimeCodes(prefs: SharedPreferences): Set<Int> {
        val offset = getOffset(prefs)
        val now = Calendar.getInstance()
        val previous = (now.clone() as Calendar).apply { add(Calendar.MINUTE, -1) }
        return setOf(timeCode(offset, now), timeCode(offset, previous))
    }

    // ---------- passwords ----------

    enum class PasswordResult { CORRECT, DECOY, WRONG, LOCKED }

    /**
     * Checks a typed password. MAX_FAILED_ATTEMPTS (10) wrong attempts in a row lock all
     * password checks for LOCKOUT_MS (3 minutes); even the correct password is refused while locked.
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

    // ---------- alias lock (ankush-alias on|off) ----------
    /**
     * ON (default): aliases need the time code, original names of renamed commands are
     * disabled, and help is hidden. OFF: aliases work without a code, original command
     * names work alongside the renamed ones, and help is available.
     */
    fun isAliasLock(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_ALIAS_LOCK, true)

    fun setAliasLock(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(PREF_ALIAS_LOCK, enabled).apply()
    }

    private fun hashEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
