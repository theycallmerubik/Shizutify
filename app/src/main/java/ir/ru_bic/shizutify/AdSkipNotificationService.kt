package ir.ru_bic.shizutify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AdSkipNotificationService : NotificationListenerService() {

    private lateinit var prefs: PrefsManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastTriggerTime = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsManager(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!prefs.isServiceEnabled) return

        val pkg = sbn.packageName
        if (!prefs.selectedPackages.contains(pkg)) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        val titlePattern = prefs.titleFilter
        val textPattern = prefs.textFilter

        if (titlePattern.isEmpty() && textPattern.isEmpty()) return

        val titleMatches = if (titlePattern.isNotEmpty()) {
            matchesRegex(title, titlePattern, prefs.isTitleCaseSensitive)
        } else {
            false
        }

        val textMatches = if (textPattern.isNotEmpty()) {
            matchesRegex(text, textPattern, prefs.isTextCaseSensitive)
        } else {
            false
        }

        if (titleMatches || textMatches) {
            val now = System.currentTimeMillis()
            // 5-second cooldown prevents re-triggering while the app restarts
            if (now - lastTriggerTime > 5000L) {
                lastTriggerTime = now
                serviceScope.launch {
                    ShizukuShell.restartAndResumeApp(applicationContext, pkg)
                }
            }
        }
    }

    private fun matchesRegex(input: String, pattern: String, caseSensitive: Boolean): Boolean {
        return try {
            val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            Regex(pattern, options).containsMatchIn(input)
        } catch (e: Exception) {
            input.contains(pattern, ignoreCase = !caseSensitive)
        }
    }
}