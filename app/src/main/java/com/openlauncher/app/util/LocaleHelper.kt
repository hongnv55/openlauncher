package com.openlauncher.app.util

import android.content.Context
import android.content.res.Configuration
import com.openlauncher.app.data.AppLanguage
import java.util.Locale

/**
 * Forces the app's own UI into a specific language regardless of the
 * device's system locale — this app has no per-app-language framework
 * support (that needs API 33's LocaleManager or the AndroidX AppCompat
 * backport, neither of which this project pulls in), so the classic
 * pre-that-era technique is used instead: wrap the base Context passed to
 * Activity.attachBaseContext with a Configuration carrying the overridden
 * Locale, before anything else in the Activity reads resources from it.
 */
object LocaleHelper {
    fun wrap(context: Context, language: AppLanguage): Context {
        val tag = when (language) {
            AppLanguage.ENGLISH    -> "en"
            AppLanguage.VIETNAMESE -> "vi"
        }
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
