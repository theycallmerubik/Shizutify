package ir.ru_bic.shizutify

import android.content.Context
import android.content.SharedPreferences

class PrefsManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("shizutify_prefs", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_SPOTIFY_PKG = "com.spotify.music"
        const val DEFAULT_TITLE_FILTER = "Advertisement"
        const val DEFAULT_TEXT_FILTER = "Advertisement"
    }

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean("service_enabled", false)
        set(value) = prefs.edit().putBoolean("service_enabled", value).apply()

    var titleFilter: String
        get() = prefs.getString("title_filter", DEFAULT_TITLE_FILTER) ?: DEFAULT_TITLE_FILTER
        set(value) = prefs.edit().putString("title_filter", value).apply()

    var isTitleCaseSensitive: Boolean
        get() = prefs.getBoolean("title_case_sensitive", false)
        set(value) = prefs.edit().putBoolean("title_case_sensitive", value).apply()

    var textFilter: String
        get() = prefs.getString("text_filter", DEFAULT_TEXT_FILTER) ?: DEFAULT_TEXT_FILTER
        set(value) = prefs.edit().putString("text_filter", value).apply()

    var isTextCaseSensitive: Boolean
        get() = prefs.getBoolean("text_case_sensitive", false)
        set(value) = prefs.edit().putBoolean("text_case_sensitive", value).apply()

    var selectedPackages: Set<String>
        get() = prefs.getStringSet("selected_packages", setOf(DEFAULT_SPOTIFY_PKG))
            ?: setOf(DEFAULT_SPOTIFY_PKG)
        set(value) = prefs.edit().putStringSet("selected_packages", value).apply()
}