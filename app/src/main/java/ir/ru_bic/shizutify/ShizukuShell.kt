package ir.ru_bic.shizutify

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.util.concurrent.TimeUnit

object ShizukuShell {

    fun isAvailableAndGranted(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            DebugLogger.log("SHIZUKU", "isAvailableAndGranted error: ${e.message}")
            false
        }
    }

    suspend fun restartAndResumeApp(context: Context, packageName: String) {
        if (!isAvailableAndGranted()) {
            DebugLogger.log("SHIZUKU", "Aborting restart: Shizuku not available or permission denied")
            return
        }

        val launchComponent = context.packageManager
            .getLaunchIntentForPackage(packageName)
            ?.component
            ?.flattenToShortString()

        val startCmd = if (launchComponent != null) {
            "am start -n $launchComponent --activity-no-animation"
        } else {
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $packageName --activity-no-animation"
        }

        val restartCmd = "am force-stop $packageName ; $startCmd </dev/null >/dev/null 2>&1 &"
        DebugLogger.log("SHIZUKU", "Executing: $restartCmd")
        runCommand(restartCmd)

        DebugLogger.log("SHIZUKU", "Waiting 2000ms before Play dispatch #1")
        delay(2000L)
        sendSimulatedPlay(context, packageName, attempt = 1)

        delay(800L)
        val playing = isPackagePlaying(context, packageName)
        DebugLogger.log("SHIZUKU", "Playback check at 2800ms -> isPlaying=$playing")
        if (!playing) {
            sendSimulatedPlay(context, packageName, attempt = 2)
        }
    }

    private fun isPackagePlaying(context: Context, packageName: String): Boolean {
        return try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val listenerComponent = ComponentName(context, AdSkipNotificationService::class.java)
            msm.getActiveSessions(listenerComponent).any { controller ->
                controller.packageName == packageName &&
                        controller.playbackState?.state == PlaybackState.STATE_PLAYING
            }
        } catch (e: Exception) {
            DebugLogger.log("SHIZUKU", "isPackagePlaying check failed: ${e.message}")
            false
        }
    }

    private fun sendSimulatedPlay(context: Context, packageName: String, attempt: Int) {
        DebugLogger.log("PLAY", "Dispatching simulated PLAY (attempt #$attempt) for $packageName")
        val now = SystemClock.uptimeMillis()
        val downEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0)
        val upEvent = KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0)

        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val listenerComponent = ComponentName(context, AdSkipNotificationService::class.java)
            val controllers = msm.getActiveSessions(listenerComponent)
            var matched = false
            for (controller in controllers) {
                if (controller.packageName == packageName) {
                    matched = true
                    controller.dispatchMediaButtonEvent(downEvent)
                    controller.dispatchMediaButtonEvent(upEvent)
                    controller.transportControls.play()
                }
            }
            DebugLogger.log("PLAY", "MediaSession dispatch -> foundSession=$matched (totalSessions=${controllers.size})")
        } catch (e: Exception) {
            DebugLogger.log("ERROR", "MediaSession play failed: ${e.message}")
        }

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.dispatchMediaKeyEvent(downEvent)
            audioManager.dispatchMediaKeyEvent(upEvent)
            DebugLogger.log("PLAY", "AudioManager.dispatchMediaKeyEvent sent")
        } catch (e: Exception) {
            DebugLogger.log("ERROR", "AudioManager play failed: ${e.message}")
        }

        try {
            val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                setPackage(packageName)
                putExtra(Intent.EXTRA_KEY_EVENT, downEvent)
            }
            context.sendBroadcast(downIntent)

            val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                setPackage(packageName)
                putExtra(Intent.EXTRA_KEY_EVENT, upEvent)
            }
            context.sendBroadcast(upIntent)
            DebugLogger.log("PLAY", "ACTION_MEDIA_BUTTON broadcast sent to $packageName")
        } catch (e: Exception) {
            DebugLogger.log("ERROR", "Broadcast play failed: ${e.message}")
        }
    }

    private fun runCommand(command: String) {
        try {
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }

            val process = newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as ShizukuRemoteProcess

            val finished = process.waitFor(3, TimeUnit.SECONDS)
            DebugLogger.log("SHIZUKU", "Shell process finishedInTime=$finished")
            process.destroy()
        } catch (e: Exception) {
            DebugLogger.log("ERROR", "Shizuku runCommand failed: ${e.message}")
        }
    }
}