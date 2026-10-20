package dev.ahmed.savlino.locale

import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object AppLocale {
    fun current(context: Context): String = if (Build.VERSION.SDK_INT >= 33) {
        val manager = context.getSystemService(LocaleManager::class.java)
        manager.applicationLocales[0]?.language?.takeIf { it == "ar" || it == "en" }
            ?: AppPreferences.phoneLanguage(manager.systemLocales[0]?.language ?: "en")
    } else AppPreferences(context).language(Resources.getSystem().configuration.locales[0].language, null)

    /** [context] with the app language and Latin digits as its locale. */
    fun localized(context: Context, language: String = current(context)): Context {
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(NezzelFormat.numericTag(language)))
        return context.createConfigurationContext(config)
    }

    /** AppCompat restores API29–32 choices before attaching the Activity; API33+ owns its choices. */
    fun attach(context: Context): Context {
        applyTheme(AppPreferences(context).theme)
        val language = current(context)
        if (Build.VERSION.SDK_INT < 33) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(NezzelFormat.numericTag(language)))
        }
        return localized(context, language)
    }
    fun applyTheme(theme: ThemePref) {
        AppCompatDelegate.setDefaultNightMode(when (theme) {
            ThemePref.System -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemePref.Light -> AppCompatDelegate.MODE_NIGHT_NO
            ThemePref.Dark -> AppCompatDelegate.MODE_NIGHT_YES
        })
    }
    fun choose(context: Context, language: String) {
        require(language == "ar" || language == "en")
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(NezzelFormat.numericTag(language))
        } else {
            AppPreferences(context).chooseLanguage(language)
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(NezzelFormat.numericTag(language)))
        }
    }
}
