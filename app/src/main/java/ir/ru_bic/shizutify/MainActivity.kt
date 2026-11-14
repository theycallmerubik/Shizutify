package ir.ru_bic.shizutify

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ir.ru_bic.shizutify.databinding.ActivityMainBinding
import ir.ru_bic.shizutify.databinding.DialogAppPickerBinding
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsManager

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ ->
            updateStatusUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsManager(this)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        setupInputs()
        setupButtons()
        updateSelectedAppsSummary()
        updateStatusUi()
    }

    override fun onResume() {
        super.onResume()
        updateStatusUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }

    private fun setupInputs() {
        binding.etTitleFilter.setText(prefs.titleFilter)
        binding.cbTitleCase.isChecked = prefs.isTitleCaseSensitive
        binding.etTextFilter.setText(prefs.textFilter)
        binding.cbTextCase.isChecked = prefs.isTextCaseSensitive

        binding.etTitleFilter.doAfterTextChanged { text ->
            val pattern = text?.toString() ?: ""
            prefs.titleFilter = pattern
            binding.tilTitleFilter.error = validateRegex(pattern)
        }

        binding.cbTitleCase.setOnCheckedChangeListener { _, isChecked ->
            prefs.isTitleCaseSensitive = isChecked
        }

        binding.etTextFilter.doAfterTextChanged { text ->
            val pattern = text?.toString() ?: ""
            prefs.textFilter = pattern
            binding.tilTextFilter.error = validateRegex(pattern)
        }

        binding.cbTextCase.setOnCheckedChangeListener { _, isChecked ->
            prefs.isTextCaseSensitive = isChecked
        }
    }

    private fun setupButtons() {
        binding.btnToggleService.setOnClickListener {
            if (!isNotificationListenerEnabled()) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                return@setOnClickListener
            }

            if (!ShizukuShell.isAvailableAndGranted()) {
                requestShizukuPermission()
                return@setOnClickListener
            }

            prefs.isServiceEnabled = !prefs.isServiceEnabled
            updateStatusUi()
        }

        binding.btnSelectApps.setOnClickListener {
            showAppPickerDialog()
        }
    }

    private fun requestShizukuPermission() {
        try {
            if (Shizuku.pingBinder()) {
                Shizuku.requestPermission(1001)
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Shizuku Not Running")
                    .setMessage("Please open the Shizuku app and start the Shizuku service first.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(this, AdSkipNotificationService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    private fun updateStatusUi() {
        val notifOk = isNotificationListenerEnabled()
        val shizukuOk = ShizukuShell.isAvailableAndGranted()

        if (!notifOk || !shizukuOk) {
            prefs.isServiceEnabled = false
        }

        val active = prefs.isServiceEnabled && notifOk && shizukuOk

        binding.tvStatusSub.text = when {
            !notifOk -> "Tap the big button to grant Notification Listener access."
            !shizukuOk -> "Tap the big button to grant Shizuku permission."
            active -> "Shizuku Ready • Listening for matching notifications"
            else -> "Permissions granted • Service is currently paused"
        }

        if (active) {
            binding.btnToggleService.text = getString(R.string.service_running)
            val colorPrimary = MaterialColors.getColor(
                binding.btnToggleService,
                com.google.android.material.R.attr.colorPrimary
            )
            binding.btnToggleService.backgroundTintList = ColorStateList.valueOf(colorPrimary)
        } else {
            binding.btnToggleService.text = getString(R.string.service_stopped)
            val colorError = MaterialColors.getColor(
                binding.btnToggleService,
                com.google.android.material.R.attr.colorError
            )
            binding.btnToggleService.backgroundTintList = ColorStateList.valueOf(colorError)
        }
    }

    private fun updateSelectedAppsSummary() {
        val selected = prefs.selectedPackages
        binding.tvSelectedAppsSummary.text =
            "Selected packages (${selected.size}): ${selected.joinToString(", ")}"
    }

    private fun validateRegex(pattern: String): String? {
        return try {
            Regex(pattern)
            null
        } catch (e: Exception) {
            "Invalid Regex syntax"
        }
    }

    private fun showAppPickerDialog() {
        val dialogBinding = DialogAppPickerBinding.inflate(layoutInflater)
        dialogBinding.rvApps.layoutManager = LinearLayoutManager(this)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Choose Apps to Monitor")
            .setView(dialogBinding.root)
            .setPositiveButton("Done") { _, _ -> updateSelectedAppsSummary() }
            .create()

        dialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val selectedSet = prefs.selectedPackages.toMutableSet()
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .map { appInfo ->
                    AppEntry(
                        label = appInfo.loadLabel(pm).toString(),
                        packageName = appInfo.packageName,
                        icon = appInfo.loadIcon(pm),
                        isSelected = selectedSet.contains(appInfo.packageName)
                    )
                }
                .sortedWith(compareByDescending<AppEntry> { it.isSelected }.thenBy { it.label.lowercase() })

            withContext(Dispatchers.Main) {
                dialogBinding.progressBar.visibility = View.GONE
                val adapter = AppListAdapter(installedApps) { updatedSelection ->
                    prefs.selectedPackages = updatedSelection
                    updateSelectedAppsSummary()
                }
                dialogBinding.rvApps.adapter = adapter

                dialogBinding.etSearchApps.doAfterTextChanged { query ->
                    adapter.filter(query?.toString() ?: "")
                }
            }
        }
    }
}