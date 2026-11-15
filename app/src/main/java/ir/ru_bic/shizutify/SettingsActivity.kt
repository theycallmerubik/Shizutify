package ir.ru_bic.shizutify

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.ru_bic.shizutify.databinding.ActivitySettingsBinding
import ir.ru_bic.shizutify.databinding.DialogAppPickerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PrefsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applySystemBarInsets(binding.rootSettings)

        prefs = PrefsManager(this)

        binding.btnBack.setOnClickListener { finish() }

        setupInputs()
        setupAppSelector()
        updateSelectedAppsSummary()
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

    private fun setupAppSelector() {
        binding.btnSelectApps.setOnClickListener {
            showAppPickerDialog()
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
                .sortedWith(
                    compareByDescending<AppEntry> { it.isSelected }
                        .thenBy { it.label.lowercase() }
                )

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

    private fun applySystemBarInsets(view: View) {
        val initialLeft = view.paddingLeft
        val initialTop = view.paddingTop
        val initialRight = view.paddingRight
        val initialBottom = view.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
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