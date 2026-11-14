package ir.ru_bic.shizutify

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
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
            false
        }
    }

    /**
     * Force-stops the target app, relaunches its main activity cleanly via 'am start'
     * (avoiding 'monkey' rotation side-effects), waits 2 seconds, and sends simulated Play.
     */
    suspend fun restartAndResumeApp(context: Context, packageName: String) {
        if (!isAvailableAndGranted()) return

        // Dynamically resolve the app's default launcher activity (e.g., com.spotify.music/.MainActivity)
        val launchComponent = context.packageManager
            .getLaunchIntentForPackage(packageName)
            ?.component
            ?.flattenToShortString()

        val startCmd = if (launchComponent != null) {
            "am start -n $launchComponent --activity-no-animation"
        } else {
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $packageName --activity-no-animation"
        }

        // 1. Force-stop and relaunch without touching screen rotation
        val restartCmd = "am force-stop $packageName ; $startCmd </dev/null >/dev/null 2>&1 &"
        runCommand(restartCmd)

        // 2. Wait 2 seconds
        delay(2000L)

        // 3. Trigger Tasker-style "Play [Simulated Only]"
        sendSimulatedPlay(context, packageName)
    }

    private fun sendSimulatedPlay(context: Context, packageName: String) {
        val now = SystemClock.uptimeMillis()
        val downEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0)
        val upEvent = KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0)

        // Method A: Direct MediaSession transport control via NotificationListenerService
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val listenerComponent = ComponentName(context, AdSkipNotificationService::class.java)
            val controllers = msm.getActiveSessions(listenerComponent)
            for (controller in controllers) {
                if (controller.packageName == packageName) {
                    controller.dispatchMediaButtonEvent(downEvent)
                    controller.dispatchMediaButtonEvent(upEvent)
                    controller.transportControls.play()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Method B: AudioManager simulated media key event
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.dispatchMediaKeyEvent(downEvent)
            audioManager.dispatchMediaKeyEvent(upEvent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Method C: Explicit ACTION_MEDIA_BUTTON broadcast targeted at the package
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
        } catch (e: Exception) {
            e.printStackTrace()
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

            process.waitFor(3, TimeUnit.SECONDS)
            process.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}