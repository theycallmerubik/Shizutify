package ir.ru_bic.shizutify

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object DebugLogger {

    private const val MAX_LOGS = 600
    private val logs = ArrayDeque<String>(MAX_LOGS)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(tag: String, message: String) {
        val timestamp = timeFormat.format(Date())
        val entry = "[$timestamp] [$tag] $message"

        if (logs.size >= MAX_LOGS) {
            logs.removeFirst()
        }
        logs.addLast(entry)

        Log.d("Shizutify_$tag", message)
        notifyListeners()
    }

    @Synchronized
    fun getAllLogsText(): String {
        return if (logs.isEmpty()) {
            "No logs recorded yet. Play a track or trigger a notification to see live events."
        } else {
            logs.joinToString("\n")
        }
    }

    @Synchronized
    fun clear() {
        logs.clear()
        log("DEBUG", "Logs cleared by user")
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        for (listener in listeners) {
            listener.invoke()
        }
    }
}