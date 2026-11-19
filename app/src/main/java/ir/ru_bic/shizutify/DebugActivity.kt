package ir.ru_bic.shizutify

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import ir.ru_bic.shizutify.databinding.ActivityDebugBinding

class DebugActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebugBinding
    private lateinit var prefs: PrefsManager

    private val logListener: () -> Unit = {
        runOnUiThread {
            refreshLogs()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applySystemBarInsets(binding.rootDebug)
        prefs = PrefsManager(this)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnCopyLogs.setOnClickListener {
            val fullDump = buildString {
                appendLine("=== SHIZUTIFY DIAGNOSTICS ===")
                appendLine(buildStateSummary())
                appendLine()
                appendLine("=== LIVE LOGS ===")
                append(DebugLogger.getAllLogsText())
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Shizutify Debug Logs", fullDump))
            Toast.makeText(this, "Debug logs copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearLogs.setOnClickListener {
            DebugLogger.clear()
            refreshLogs()
        }

        refreshStateSummary()
        refreshLogs()
    }

    override fun onStart() {
        super.onStart()
        DebugLogger.addListener(logListener)
        refreshStateSummary()
        refreshLogs()
    }

    override fun onStop() {
        DebugLogger.removeListener(logListener)
        super.onStop()
    }

    private fun refreshStateSummary() {
        binding.tvStateSummary.text = buildStateSummary()
    }

    private fun buildStateSummary(): String {
        val cn = ComponentName(this, AdSkipNotificationService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val notifPerm = flat != null && flat.contains(cn.flattenToString())
        val shizukuOk = ShizukuShell.isAvailableAndGranted()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)

        return buildString {
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT})")
            appendLine("Service Toggle: ${prefs.isServiceEnabled} | Listener Bound: ${AdSkipNotificationService.isListenerConnected}")
            appendLine("Perms -> Notif: $notifPerm | Shizuku: $shizukuOk | BatteryOptIgnored: $batteryOk")
            appendLine("Title Filter: \"${prefs.titleFilter}\" (caseSensitive=${prefs.isTitleCaseSensitive})")
            appendLine("Text Filter : \"${prefs.textFilter}\" (caseSensitive=${prefs.isTextCaseSensitive})")
            append("Target Apps : ${prefs.selectedPackages.joinToString(", ")}")
        }
    }

    private fun refreshLogs() {
        binding.tvLogs.text = DebugLogger.getAllLogsText()
        binding.scrollLogs.post {
            binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun applySystemBarInsets(view: View) {
        val initialLeft = view.paddingLeft
        val initialTop = view.paddingTop
        val initialRight = view.paddingRight
        val initialBottom = view.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                initialLeft + bars.left,
                initialTop + bars.top,
                initialRight + bars.right,
                initialBottom + bars.bottom
            )
            insets
        }
    }
}