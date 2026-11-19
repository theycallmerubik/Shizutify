package ir.ru_bic.shizutify

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import ir.ru_bic.shizutify.databinding.ActivityMoreInfoBinding

class MoreInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMoreInfoBinding
    private var heartTapCount = 0
    private var lastHeartTapTime = 0L

    companion object {
        private const val SOURCE_CODE_URL = "https://github.com/theycallmerubik/Shizutify"
        private const val PERSONAL_SITE_URL = "https://ru-bic.ir"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMoreInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applySystemBarInsets(binding.rootMoreInfo)

        binding.btnBack.setOnClickListener { finish() }

        binding.cardSourceCode.setOnClickListener {
            openUrl(SOURCE_CODE_URL)
        }

        binding.cardPersonalSite.setOnClickListener {
            openUrl(PERSONAL_SITE_URL)
        }

        binding.tvHeart.setOnClickListener { view ->
            val now = System.currentTimeMillis()
            if (now - lastHeartTapTime > 2000L) {
                heartTapCount = 0
            }
            lastHeartTapTime = now
            heartTapCount++

            if (heartTapCount >= 5) {
                heartTapCount = 0
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                DebugLogger.log("UI", "Hidden Debug Console opened")
                startActivity(Intent(this, DebugActivity::class.java))
            }
        }
    }

    private fun openUrl(url: String) {
        DebugLogger.log("UI", "Opening external URL: $url")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
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