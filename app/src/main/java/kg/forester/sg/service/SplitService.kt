package kg.forester.sg.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Split screen without root: Android 10 opens an app "adjacent" only when split screen is already on, and only the
 * system can turn it on. An accessibility service may ask for it (GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN): our app goes
 * to the first half, the app launched right after takes the other one. The driver enables it once in the
 * Android accessibility settings; it reads nothing on the screen.
 */
class SplitService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        @Volatile var instance: SplitService? = null
            private set

        /** Turns split screen on (the app on screen goes to the first half); false if the service is off. */
        fun toggleSplit(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) == true

        /** Whether the driver enabled the service in the Android settings (it may not be connected yet). */
        fun enabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.startsWith(ctx.packageName + "/") }
        }
    }
}
