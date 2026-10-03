package com.coderGtm.yantra.services

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.coderGtm.yantra.SHARED_PREFS_FILE_NAME
import com.coderGtm.yantra.ankush.Ankush

@SuppressLint("AccessibilityPolicy")
class YantraAccessibilityService : AccessibilityService() {

    companion object {
        private const val ACTION_REQUEST_LOCK_SCREEN = "com.coderGtm.yantra.action.REQUEST_LOCK_SCREEN"

        fun requestLockScreen(context: Context) {
            context.sendBroadcast(
                Intent(ACTION_REQUEST_LOCK_SCREEN).setPackage(context.packageName),
            )
        }
    }

    private val lockScreenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_REQUEST_LOCK_SCREEN) {
                performLockScreenAction()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter(ACTION_REQUEST_LOCK_SCREEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(lockScreenReceiver, filter, RECEIVER_NOT_EXPORTED)
        }
        else {
            registerReceiver(lockScreenReceiver, filter)
        }
    }

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(SHARED_PREFS_FILE_NAME, Context.MODE_PRIVATE)
    }

    override fun onAccessibilityEvent(accessibilityEvent: AccessibilityEvent) {
        // Ankush Guard: when enabled and locked, leaving to the Settings app bounces home.
        if (accessibilityEvent.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!Ankush.isGuard(prefs) || Ankush.isUnlocked(prefs)) return
        val pkg = accessibilityEvent.packageName?.toString() ?: return
        if (pkg in Ankush.GUARDED_PACKAGES) {
            try {
                performGlobalAction(GLOBAL_ACTION_HOME)
            } catch (_: Exception) {
            }
        }
    }

    private fun performLockScreenAction() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            }
            catch (_: Exception) {
                // Silently fail.
            }
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(lockScreenReceiver)
        }
        catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onInterrupt() {}


}