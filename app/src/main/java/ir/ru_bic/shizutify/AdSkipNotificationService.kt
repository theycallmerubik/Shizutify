package ir.ru_bic.shizutify

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AdSkipNotificationService : NotificationListenerService() {

    companion object {
        @Volatile
        var isListenerConnected: Boolean = false
    }

    private lateinit var prefs: PrefsManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastTriggerTime = 0L

    private var mediaSessionManager: MediaSessionManager? = null
    private val registeredCallbacks = mutableMapOf<MediaSession.Token, MediaController.Callback>()

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            val pkgs = controllers?.map { it.packageName } ?: emptyList()
            DebugLogger.log("MEDIA", "Active sessions changed: $pkgs")
            registerMediaControllerCallbacks(controllers)
        }

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsManager(this)
        DebugLogger.log("SERVICE", "AdSkipNotificationService onCreate()")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isListenerConnected = true
        DebugLogger.log("SERVICE", "NotificationListener CONNECTED")
        setupMediaSessionMonitoring()
        try {
            val active = activeNotifications
            DebugLogger.log("SERVICE", "Scanning ${active?.size ?: 0} existing active notifications")
            active?.forEach { onNotificationPosted(it) }
        } catch (e: Exception) {
            DebugLogger.log("ERROR", "activeNotifications failed: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isListenerConnected = false
        DebugLogger.log("SERVICE", "NotificationListener DISCONNECTED - requesting rebind")
        cleanupMediaSessionMonitoring()
        try {
            requestRebind(ComponentName(this, AdSkipNotificationService::class.java))
        } catch (e: Exception) {
            DebugLogger.log("ERROR", "requestRebind failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        isListenerConnected = false
        DebugLogger.log("SERVICE", "AdSkipNotificationService onDestroy()")
        cleanupMediaSessionMonitoring()
        super.onDestroy()
    }

    private fun setupMediaSessionMonitoring() {
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            mediaSessionManager = msm
            val componentName = ComponentName(this, AdSkipNotificationService::class.java)
            msm.addOnActiveSessionsChangedListener(sessionsChangedListener, componentName)
            val currentSessions = msm.getActiveSessions(componentName)
            DebugLogger.log("MEDIA", "Monitoring ${currentSessions.size} initial MediaSessions: ${currentSessions.map { it.packageName }}")
            registerMediaControllerCallbacks(currentSessions)
        } catch (e: Exception) {
            DebugLogger.log("ERROR", "setupMediaSessionMonitoring failed: ${e.message}")
        }
    }

    private fun cleanupMediaSessionMonitoring() {
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (e: Exception) {
            DebugLogger.log("ERROR", "cleanupMediaSessionMonitoring failed: ${e.message}")
        }
        registeredCallbacks.clear()
    }

    private fun registerMediaControllerCallbacks(controllers: List<MediaController>?) {
        if (controllers == null) return

        for (controller in controllers) {
            val token = controller.sessionToken
            if (!registeredCallbacks.containsKey(token)) {
                val callback = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        DebugLogger.log("META", "onMetadataChanged from ${controller.packageName}")
                        checkMediaMetadata(controller.packageName, metadata)
                    }

                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        if (prefs.selectedPackages.contains(controller.packageName)) {
                            DebugLogger.log("META", "PlaybackState(${controller.packageName}) -> state=${state?.state}")
                        }
                    }
                }
                try {
                    controller.registerCallback(callback)
                    registeredCallbacks[token] = callback
                    DebugLogger.log("MEDIA", "Registered callback for ${controller.packageName}")
                    checkMediaMetadata(controller.packageName, controller.metadata)
                } catch (e: Exception) {
                    DebugLogger.log("ERROR", "registerCallback(${controller.packageName}) failed: ${e.message}")
                }
            }
        }
    }

    private fun checkMediaMetadata(pkg: String, meta: MediaMetadata?) {
        if (!prefs.selectedPackages.contains(pkg)) return
        if (meta == null) {
            DebugLogger.log("META", "Metadata for $pkg is NULL")
            return
        }
        if (!prefs.isServiceEnabled) {
            DebugLogger.log("META", "Ignored metadata from $pkg (service paused)")
            return
        }

        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
        val displayTitle = meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val subtitle = meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val desc = meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)
        val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)

        DebugLogger.log(
            "META",
            "[$pkg] title='$title' | displayTitle='$displayTitle' | artist='$artist' | album='$album' | subtitle='$subtitle' | desc='$desc' | duration=${duration}ms"
        )

        val titles = listOfNotNull(title, displayTitle)
        val texts = listOfNotNull(artist, album, subtitle, desc)

        evaluateAndTrigger("MediaMetadata", pkg, titles, texts)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName

        if (!prefs.selectedPackages.contains(pkg)) return

        if (!prefs.isServiceEnabled) {
            DebugLogger.log("NOTIF", "Ignored notification from $pkg (service paused)")
            return
        }

        val extras = sbn.notification.extras
        val titleCandidates = mutableListOf<String>()
        val textCandidates = mutableListOf<String>()

        // Dump all non-binary extras so hidden/unusual Spotify ad fields are visible in logs
        val rawDump = extras.keySet().mapNotNull { key ->
            val value = extras.get(key)
            if (value is CharSequence || value is String || value is Number || value is Boolean) {
                "$key='$value'"
            } else {
                null
            }
        }.joinToString(" | ")
        DebugLogger.log("NOTIF", "Posted [$pkg] id=${sbn.id} tag=${sbn.tag} -> $rawDump")

        extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.let { titleCandidates.add(it) }
        extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.let { titleCandidates.add(it) }
        sbn.notification.tickerText?.toString()?.let { titleCandidates.add(it) }

        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let { textCandidates.add(it) }
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.let { textCandidates.add(it) }
        extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.let { textCandidates.add(it) }

        // Inspect attached MediaSession token if present
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
            DebugLogger.log("ERROR", "Notification MediaSession token read failed: ${e.message}")
        }

        evaluateAndTrigger("Notification", pkg, titleCandidates, textCandidates)
    }

    private fun evaluateAndTrigger(
        source: String,
        pkg: String,
        titleCandidates: List<String>,
        textCandidates: List<String>
    ) {
        val titlePattern = prefs.titleFilter
        val textPattern = prefs.textFilter
        if (titlePattern.isEmpty() && textPattern.isEmpty()) {
            DebugLogger.log("FILTER", "Both filters are empty - skipping evaluation")
            return
        }

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

        DebugLogger.log(
            "FILTER",
            "[$source] titles=$titleCandidates (match=$titleMatches vs '$titlePattern') | texts=$textCandidates (match=$textMatches vs '$textPattern')"
        )

        if (titleMatches || textMatches) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastTriggerTime
            if (elapsed > 5000L) {
                lastTriggerTime = now
                DebugLogger.log("TRIGGER", "AD DETECTED via $source for $pkg! Launching restart sequence...")
                serviceScope.launch {
                    ShizukuShell.restartAndResumeApp(applicationContext, pkg)
                }
            } else {
                DebugLogger.log("COOLDOWN", "Match suppressed (${elapsed}ms since last trigger; cooldown=5000ms)")
            }
        }
    }

    private fun matchesRegex(input: String, pattern: String, caseSensitive: Boolean): Boolean {
        if (input.isBlank()) return false
        return try {
            val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            Regex(pattern, options).containsMatchIn(input)
        } catch (e: Exception) {
            DebugLogger.log("ERROR", "Regex error for '$pattern': ${e.message}")
            input.contains(pattern, ignoreCase = !caseSensitive)
        }
    }
}