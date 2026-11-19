package ir.ru_bic.shizutify

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.ru_bic.shizutify.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsManager

    // Tracks current visual state so we only animate when the state actually changes
    private var currentVisualState: Int? = null
    private var pulseAnimator: ObjectAnimator? = null

    companion object {
        private const val STATE_PERMISSION_NEEDED = 0
        private const val STATE_PAUSED = 1
        private const val STATE_RUNNING = 2
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ ->
            updateStatusUi(animate = true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applySystemBarInsets(binding.rootMain)

        prefs = PrefsManager(this)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        setupButtons()
        updateStatusUi(animate = false)
    }

    override fun onResume() {
        super.onResume()
        ensureListenerRebound()
        updateStatusUi(animate = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        pulseAnimator?.cancel()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupButtons() {
        binding.btnInfo.setOnClickListener {
            startActivity(Intent(this, MoreInfoActivity::class.java))
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Tactile squish & spring-back animation on touch
        binding.btnToggleService.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pulseAnimator?.pause()
                    v.animate()
                        .scaleX(0.92f)
                        .scaleY(0.92f)
                        .setDuration(120)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(320)
                        .setInterpolator(OvershootInterpolator(2.2f))
                        .withEndAction {
                            if (currentVisualState == STATE_RUNNING) {
                                pulseAnimator?.resume()
                            }
                        }
                        .start()
                }
            }
            false // Return false so normal OnClickListener & ripple still fire
        }

        binding.btnToggleService.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

            if (!isNotificationListenerEnabled()) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                return@setOnClickListener
            }

            if (!ShizukuShell.isAvailableAndGranted()) {
                requestShizukuPermission()
                return@setOnClickListener
            }

            if (!isIgnoringBatteryOptimizations()) {
                openBatteryOptimizationSettings()
                return@setOnClickListener
            }

            prefs.isServiceEnabled = !prefs.isServiceEnabled
            DebugLogger.log("UI", "Service toggled -> enabled=${prefs.isServiceEnabled}")
            updateStatusUi(animate = true)
        }

        binding.cardAlertBanner.setOnClickListener {
            when {
                !isNotificationListenerEnabled() -> {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                !ShizukuShell.isAvailableAndGranted() -> {
                    requestShizukuPermission()
                }
                !isIgnoringBatteryOptimizations() -> {
                    openBatteryOptimizationSettings()
                }
            }
        }
    }

    private fun ensureListenerRebound() {
        if (isNotificationListenerEnabled()) {
            try {
                android.service.notification.NotificationListenerService.requestRebind(
                    ComponentName(this, AdSkipNotificationService::class.java)
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun updateStatusUi(animate: Boolean) {
        val notifOk = isNotificationListenerEnabled()
        val shizukuOk = ShizukuShell.isAvailableAndGranted()
        val batteryOk = isIgnoringBatteryOptimizations()

        if (!notifOk || !shizukuOk) {
            prefs.isServiceEnabled = false
        }

        val isRunning = prefs.isServiceEnabled && notifOk && shizukuOk

        // 1. Conditional Alert Banner
        when {
            !notifOk -> {
                showAlertBanner(getString(R.string.alert_notif_permission))
            }
            !shizukuOk -> {
                showAlertBanner(getString(R.string.alert_shizuku_permission))
            }
            !batteryOk -> {
                showAlertBanner(getString(R.string.alert_battery_saver))
            }
            else -> {
                hideAlertBanner()
            }
        }

        // 2. Determine Target State
        val newState = when {
            !notifOk || !shizukuOk || !batteryOk -> STATE_PERMISSION_NEEDED
            isRunning -> STATE_RUNNING
            else -> STATE_PAUSED
        }

        val targetButtonColor = ContextCompat.getColor(
            this,
            if (newState == STATE_PAUSED) R.color.paused_slate else R.color.primary_terracotta
        )
        val targetBadgeColor = ContextCompat.getColor(
            this,
            if (newState == STATE_RUNNING) R.color.status_running_sage else R.color.status_paused_sage
        )
        val targetIconRes =
            if (newState == STATE_PAUSED) R.drawable.ic_play_rounded else R.drawable.ic_pause_rounded
        val targetStatusText =
            getString(if (newState == STATE_RUNNING) R.string.status_running else R.string.status_paused)

        // Apply transitions only if state changed
        if (currentVisualState != newState) {
            val shouldAnimate = animate && currentVisualState != null
            currentVisualState = newState

            if (shouldAnimate) {
                animateCardColor(binding.btnToggleService, targetButtonColor)
                animateCardColor(binding.cardStatusBadge, targetBadgeColor)
                animateIconSwap(targetIconRes)
                animateStatusText(targetStatusText)
            } else {
                binding.btnToggleService.setCardBackgroundColor(targetButtonColor)
                binding.cardStatusBadge.setCardBackgroundColor(targetBadgeColor)
                binding.ivToggleIcon.setImageResource(targetIconRes)
                binding.tvStatusBadge.text = targetStatusText
            }

            updateBreathingPulse(enable = (newState == STATE_RUNNING))
        }
    }

    private fun animateCardColor(card: MaterialCardView, @ColorInt toColor: Int) {
        val fromColor = card.cardBackgroundColor.defaultColor
        if (fromColor == toColor) return

        ValueAnimator.ofObject(ArgbEvaluator(), fromColor, toColor).apply {
            duration = 350
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                card.setCardBackgroundColor(animator.animatedValue as Int)
            }
            start()
        }
    }

    private fun animateIconSwap(@DrawableRes newIconRes: Int) {
        binding.ivToggleIcon.animate()
            .scaleX(0.35f)
            .scaleY(0.35f)
            .rotationBy(75f)
            .alpha(0f)
            .setDuration(140)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                binding.ivToggleIcon.setImageResource(newIconRes)
                binding.ivToggleIcon.rotation = -75f
                binding.ivToggleIcon.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .rotation(0f)
                    .alpha(1f)
                    .setDuration(280)
                    .setInterpolator(OvershootInterpolator(1.8f))
                    .start()
            }
            .start()
    }

    private fun animateStatusText(newText: String) {
        if (binding.tvStatusBadge.text == newText) return
        binding.tvStatusBadge.animate()
            .alpha(0f)
            .translationY(10f)
            .setDuration(130)
            .withEndAction {
                binding.tvStatusBadge.text = newText
                binding.tvStatusBadge.translationY = -10f
                binding.tvStatusBadge.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(200)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    private fun updateBreathingPulse(enable: Boolean) {
        pulseAnimator?.cancel()
        if (enable) {
            val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.035f)
            val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.035f)
            pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
                binding.btnToggleService,
                scaleX,
                scaleY
            ).apply {
                duration = 1600
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        } else {
            binding.btnToggleService.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .start()
        }
    }

    private fun showAlertBanner(message: String) {
        binding.tvAlertBanner.text = message
        if (binding.cardAlertBanner.visibility != View.VISIBLE) {
            binding.cardAlertBanner.alpha = 0f
            binding.cardAlertBanner.translationY = 24f
            binding.cardAlertBanner.visibility = View.VISIBLE
            binding.cardAlertBanner.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun hideAlertBanner() {
        if (binding.cardAlertBanner.visibility == View.VISIBLE) {
            binding.cardAlertBanner.animate()
                .alpha(0f)
                .translationY(24f)
                .setDuration(200)
                .withEndAction {
                    binding.cardAlertBanner.visibility = View.GONE
                }
                .start()
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

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (ex: Exception) {
                val appDetailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(appDetailsIntent)
            }
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