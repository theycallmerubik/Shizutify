package ir.ru_bic.shizutify

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.os.Build
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

    private var mediaSessionManager: MediaSessionManager? = null
    private val registeredCallbacks = mutableMapOf<MediaSession.Token, MediaController.Callback>()

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            registerMediaControllerCallbacks(controllers)
        }

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsManager(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        setupMediaSessionMonitoring()
        // Check any notifications already sitting in the status bar
        try {
            activeNotifications?.forEach { onNotificationPosted(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        cleanupMediaSessionMonitoring()
        // Automatically ask Android to rebind the listener if killed
        try {
            requestRebind(ComponentName(this, AdSkipNotificationService::class.java))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        cleanupMediaSessionMonitoring()
        super.onDestroy()
    }

    private fun setupMediaSessionMonitoring() {
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            mediaSessionManager = msm
            val componentName = ComponentName(this, AdSkipNotificationService::class.java)
            msm.addOnActiveSessionsChangedListener(sessionsChangedListener, componentName)
            registerMediaControllerCallbacks(msm.getActiveSessions(componentName))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cleanupMediaSessionMonitoring() {
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        registeredCallbacks.clear()
    }

    /**
     * Attaches a real-time metadata listener directly to active media apps (e.g., Spotify).
     * This catches ads even when the app changes tracks without posting a new notification
     * or when streaming to Bluetooth headphones.
     */
    private fun registerMediaControllerCallbacks(controllers: List<MediaController>?) {
        if (controllers == null) return

        for (controller in controllers) {
            val token = controller.sessionToken
            if (!registeredCallbacks.containsKey(token)) {
                val callback = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        checkMediaMetadata(controller.packageName, metadata)
                    }
                }
                try {
                    controller.registerCallback(callback)
                    registeredCallbacks[token] = callback
                    // Immediately inspect current metadata
                    checkMediaMetadata(controller.packageName, controller.metadata)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun checkMediaMetadata(pkg: String, meta: MediaMetadata?) {
        if (meta == null) return
        if (!prefs.isServiceEnabled) return
        if (!prefs.selectedPackages.contains(pkg)) return

        val titles = listOfNotNull(
            meta.getString(MediaMetadata.METADATA_KEY_TITLE),
            meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        )
        val texts = listOfNotNull(
            meta.getString(MediaMetadata.METADATA_KEY_ARTIST),
            meta.getString(MediaMetadata.METADATA_KEY_ALBUM),
            meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)
        )

        evaluateAndTrigger(pkg, titles, texts)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!prefs.isServiceEnabled) return

        val pkg = sbn.packageName
        if (!prefs.selectedPackages.contains(pkg)) return

        val extras = sbn.notification.extras
        val titleCandidates = mutableListOf<String>()
        val textCandidates = mutableListOf<String>()

        extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.let { titleCandidates.add(it) }
        extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.let { titleCandidates.add(it) }
        sbn.notification.tickerText?.toString()?.let { titleCandidates.add(it) }

        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let { textCandidates.add(it) }
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.let { textCandidates.add(it) }
        extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.let { textCandidates.add(it) }

        // If the notification carries a MediaSession token, inspect its metadata directly as well
        try {
            val token = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
            }
            if (token != null) {
                val controller = MediaController(this, token)
                controller.metadata?.let { meta ->
                    meta.getString(MediaMetadata.METADATA_KEY_TITLE)?.let { titleCandidates.add(it) }
                    meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.let { titleCandidates.add(it) }
                    meta.getString(MediaMetadata.METADATA_KEY_ARTIST)?.let { textCandidates.add(it) }
                    meta.getString(MediaMetadata.METADATA_KEY_ALBUM)?.let { textCandidates.add(it) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        evaluateAndTrigger(pkg, titleCandidates, textCandidates)
    }

    private fun evaluateAndTrigger(
        pkg: String,
        titleCandidates: List<String>,
        textCandidates: List<String>
    ) {
        val titlePattern = prefs.titleFilter
        val textPattern = prefs.textFilter
        if (titlePattern.isEmpty() && textPattern.isEmpty()) return

        val titleMatches = if (titlePattern.isNotEmpty()) {
            titleCandidates.any { matchesRegex(it, titlePattern, prefs.isTitleCaseSensitive) }
        } else {
            false
        }

        val textMatches = if (textPattern.isNotEmpty()) {
            textCandidates.any { matchesRegex(it, textPattern, prefs.isTextCaseSensitive) }
        } else {
            false
        }

        if (titleMatches || textMatches) {
            val now = System.currentTimeMillis()
            if (now - lastTriggerTime > 5000L) {
                lastTriggerTime = now
                serviceScope.launch {
                    ShizukuShell.restartAndResumeApp(applicationContext, pkg)
                }
            }
        }
    }

    private fun matchesRegex(input: String, pattern: String, caseSensitive: Boolean): Boolean {
        if (input.isBlank()) return false
        return try {
            val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            Regex(pattern, options).containsMatchIn(input)
        } catch (e: Exception) {
            input.contains(pattern, ignoreCase = !caseSensitive)
        }
    }
}