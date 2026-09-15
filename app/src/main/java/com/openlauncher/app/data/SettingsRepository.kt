package com.openlauncher.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "launcher_settings")

// Bump when defaultWidgetLayout()/GRID_COLS/GRID_ROWS change in a way that needs
// migrating previously saved layouts — see SettingsRepository.migrateWidgetGridIfNeeded.
private const val CURRENT_WIDGET_GRID_VERSION = 3

class SettingsRepository(private val context: Context) {

    private val gson = Gson()

    private object Keys {
        val VEHICLE_NAME       = stringPreferencesKey("vehicle_name")
        val ACCENT_COLOR       = intPreferencesKey("accent_color")
        val USE_CUSTOM_SIDEBAR_COLOR = booleanPreferencesKey("use_custom_sidebar_color")
        val SIDEBAR_COLOR      = intPreferencesKey("sidebar_color")
        val FONT_COLOR         = intPreferencesKey("font_color")
        val WALLPAPER_URI      = stringPreferencesKey("wallpaper_uri")
        val FONT_BOLD          = booleanPreferencesKey("font_bold")
        val TEXT_SCALE         = floatPreferencesKey("text_scale")
        val UI_SCALE           = floatPreferencesKey("ui_scale")
        val APP_ICON_SCALE     = floatPreferencesKey("app_icon_scale")
        val APP_GRID_COLUMNS   = intPreferencesKey("app_grid_columns")
        val APP_GRID_ROWS      = intPreferencesKey("app_grid_rows")
        val CLOCK_STYLE        = stringPreferencesKey("clock_style")
        val UNIT_SYSTEM        = stringPreferencesKey("unit_system")
        val APP_FONT           = stringPreferencesKey("app_font")
        val SHOW_WEATHER       = booleanPreferencesKey("show_weather")
        val SHOW_CLOCK         = booleanPreferencesKey("show_clock")
        val SHOW_TELEMETRY     = booleanPreferencesKey("show_telemetry")
        val SHOW_NOW_PLAYING   = booleanPreferencesKey("show_now_playing")
        val SHOW_ALTIMETER     = booleanPreferencesKey("show_altimeter")
        val SHOW_SPEEDOMETER   = booleanPreferencesKey("show_speedometer")
        val SHORTCUTS_JSON     = stringPreferencesKey("shortcuts_json")
        val WIDGET_LAYOUT_JSON = stringPreferencesKey("widget_layout_json")
        val CAR_PLAY_PACKAGE      = stringPreferencesKey("car_play_package")
        val ANDROID_AUTO_PACKAGE  = stringPreferencesKey("android_auto_package")
        val WALLPAPER_DIM         = floatPreferencesKey("wallpaper_dim")
        val RIGHT_HAND_DRIVE      = booleanPreferencesKey("right_hand_drive") // kept for migration
        val SIDEBAR_POSITION           = stringPreferencesKey("sidebar_position")
        val BOTTOM_BAR_SHORTCUTS_RIGHT = booleanPreferencesKey("bottom_bar_shortcuts_right")
        val DAY_NIGHT_MODE        = stringPreferencesKey("day_night_mode")
        val APP_LANGUAGE          = stringPreferencesKey("app_language")
        val SHOW_PIP              = booleanPreferencesKey("show_pip")
        val PIP_APP_PACKAGES_JSON = stringPreferencesKey("pip_app_packages_json")
        val PIP_PANE_SPLIT        = floatPreferencesKey("pip_pane_split")
        val PIP_PANE_SPLIT2       = floatPreferencesKey("pip_pane_split2")
        val PIP_APP_COUNT         = intPreferencesKey("pip_app_count")
        val PIP_PANE_ORDER_JSON   = stringPreferencesKey("pip_pane_order_json")
        val AUTOSTART_PACKAGES_JSON = stringPreferencesKey("autostart_packages_json")
        val FAVORITE_APPS_JSON      = stringPreferencesKey("favorite_apps_json")
        val RADIO_PACKAGE         = stringPreferencesKey("radio_package")
        val ONBOARDING_COMPLETED  = booleanPreferencesKey("onboarding_completed")
        val SHOW_VITALS           = booleanPreferencesKey("show_vitals")
        val SHOW_TRIP_TRACKER     = booleanPreferencesKey("show_trip_tracker")
        val COMPASS_OFFSET        = floatPreferencesKey("compass_offset")
        val SHOW_SOUNDBOARD       = booleanPreferencesKey("show_soundboard")
        val SOUNDBOARD_PADS_JSON  = stringPreferencesKey("soundboard_pads_json")
        val VITALS_AS_BARS        = booleanPreferencesKey("vitals_as_bars")
        val SPEEDOMETER_DIGITAL_ONLY = booleanPreferencesKey("speedometer_digital_only")
        val HIDE_APP_HEADER        = booleanPreferencesKey("hide_app_header")
        val HIDE_SYSTEM_STATUS_BAR = booleanPreferencesKey("hide_system_status_bar")
        val HIDE_SYSTEM_NAV_BAR    = booleanPreferencesKey("hide_system_nav_bar")
        val WIDGET_GRID_VERSION    = intPreferencesKey("widget_grid_version")
    }

    /**
     * One-time widget-layout migration, run once at startup rather than on every
     * settings read — [readSettings] used to re-run this heuristic on every single
     * emission, which meant any saved layout with every widget's gridX < 2 (true
     * of a full-grid PIP resized to sit anywhere it still starts at column 0) kept
     * getting silently reset back to the default full-grid layout right after being
     * saved, because a resize can never be told apart from "still legacy format" by
     * the heuristic alone. A persisted version number makes the migration idempotent.
     */
    suspend fun migrateWidgetGridIfNeeded() {
        context.dataStore.edit { prefs ->
            val version = prefs[Keys.WIDGET_GRID_VERSION] ?: 0
            if (version >= CURRENT_WIDGET_GRID_VERSION) return@edit

            val defaults   = AppSettings()
            val widgetJson = prefs[Keys.WIDGET_LAYOUT_JSON]
            var loaded = widgetJson?.let {
                runCatching {
                    gson.fromJson<List<WidgetConfig>>(it, object : TypeToken<List<WidgetConfig>>() {}.type)
                }.getOrNull()
            }
            if (loaded != null) {
                if (version < 2) {
                    // Migrate: old 2×2 layout has no widget with gridX≥2 — replace with new 3×2 default
                    val afterLegacyGridFix = if (loaded!!.none { it.gridX >= 2 }) defaults.widgetLayout else loaded!!
                    // Migrate: a layout saved under the old 3×2 grid has every widget fitting
                    // within its bounds — scale ×2 so widgets keep their exact on-screen size
                    // and position on the 6×4 grid instead of shrinking into a corner.
                    loaded = if (afterLegacyGridFix.all { it.gridX + it.spanX <= 3 && it.gridY + it.spanY <= 2 }) {
                        afterLegacyGridFix.map {
                            it.copy(gridX = it.gridX * 2, gridY = it.gridY * 2, spanX = it.spanX * 2, spanY = it.spanY * 2)
                        }
                    } else afterLegacyGridFix
                }
                if (version < 3) {
                    // Migrate: 6×4 → 8×6 is not a clean integer multiple in either
                    // axis, so rescale proportionally instead (position/size fraction
                    // of the old grid preserved, not the raw cell count).
                    loaded = loaded!!.map { w ->
                        val newGridX = (w.gridX * 8 / 6).coerceIn(0, 7)
                        val newGridY = (w.gridY * 6 / 4).coerceIn(0, 5)
                        val newSpanX = (w.spanX * 8 / 6).coerceIn(1, 8 - newGridX)
                        val newSpanY = (w.spanY * 6 / 4).coerceIn(1, 6 - newGridY)
                        w.copy(gridX = newGridX, gridY = newGridY, spanX = newSpanX, spanY = newSpanY)
                    }
                }
                prefs[Keys.WIDGET_LAYOUT_JSON] = gson.toJson(loaded)
            }
            prefs[Keys.WIDGET_GRID_VERSION] = CURRENT_WIDGET_GRID_VERSION
        }
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> readSettings(prefs) }

    private fun readSettings(prefs: Preferences): AppSettings {
            val defaults = AppSettings()
            val shortcutsJson = prefs[Keys.SHORTCUTS_JSON]
            val shortcuts = if (shortcutsJson != null) {
                runCatching {
                    gson.fromJson<List<ShortcutConfig>>(
                        shortcutsJson,
                        object : TypeToken<List<ShortcutConfig>>() {}.type
                    )
                }.getOrNull() ?: defaults.shortcuts
            } else defaults.shortcuts

            val widgetJson = prefs[Keys.WIDGET_LAYOUT_JSON]
            val widgets = if (widgetJson != null) {
                val loaded = runCatching {
                    gson.fromJson<List<WidgetConfig>>(
                        widgetJson,
                        object : TypeToken<List<WidgetConfig>>() {}.type
                    )
                }.getOrNull() ?: defaults.widgetLayout
                // One-time format migrations run separately, see migrateWidgetGridIfNeeded()
                loaded
            } else defaults.widgetLayout

            return AppSettings(
                vehicleName    = prefs[Keys.VEHICLE_NAME]     ?: defaults.vehicleName,
                accentColor    = prefs[Keys.ACCENT_COLOR]     ?: defaults.accentColor,
                useCustomSidebarColor = prefs[Keys.USE_CUSTOM_SIDEBAR_COLOR] ?: defaults.useCustomSidebarColor,
                sidebarColor    = prefs[Keys.SIDEBAR_COLOR]   ?: defaults.sidebarColor,
                fontColor      = prefs[Keys.FONT_COLOR]       ?: defaults.fontColor,
                wallpaperUri   = prefs[Keys.WALLPAPER_URI]    ?: defaults.wallpaperUri,
                fontBold       = prefs[Keys.FONT_BOLD]        ?: defaults.fontBold,
                textScale      = prefs[Keys.TEXT_SCALE]       ?: defaults.textScale,
                uiScale        = prefs[Keys.UI_SCALE]         ?: defaults.uiScale,
                appIconScale   = prefs[Keys.APP_ICON_SCALE]   ?: defaults.appIconScale,
                appGridColumns = prefs[Keys.APP_GRID_COLUMNS] ?: defaults.appGridColumns,
                appGridRows    = prefs[Keys.APP_GRID_ROWS]    ?: defaults.appGridRows,
                clockStyle     = prefs[Keys.CLOCK_STYLE]?.let { runCatching { ClockStyle.valueOf(it) }.getOrNull() } ?: defaults.clockStyle,
                unitSystem     = prefs[Keys.UNIT_SYSTEM]?.let { runCatching { UnitSystem.valueOf(it) }.getOrNull() } ?: defaults.unitSystem,
                appFont        = prefs[Keys.APP_FONT]?.let { runCatching { AppFont.valueOf(it) }.getOrNull() } ?: defaults.appFont,
                showWeather    = prefs[Keys.SHOW_WEATHER]     ?: defaults.showWeather,
                showClock      = prefs[Keys.SHOW_CLOCK]       ?: defaults.showClock,
                showTelemetry  = prefs[Keys.SHOW_TELEMETRY]   ?: defaults.showTelemetry,
                showNowPlaying = prefs[Keys.SHOW_NOW_PLAYING] ?: defaults.showNowPlaying,
                showAltimeter   = prefs[Keys.SHOW_ALTIMETER]   ?: defaults.showAltimeter,
                showSpeedometer = prefs[Keys.SHOW_SPEEDOMETER] ?: defaults.showSpeedometer,
                shortcuts      = shortcuts,
                widgetLayout   = widgets,
                carPlayPackage      = prefs[Keys.CAR_PLAY_PACKAGE]      ?: defaults.carPlayPackage,
                androidAutoPackage  = prefs[Keys.ANDROID_AUTO_PACKAGE]  ?: defaults.androidAutoPackage,
                wallpaperDim     = prefs[Keys.WALLPAPER_DIM]       ?: defaults.wallpaperDim,
                sidebarPosition  = prefs[Keys.SIDEBAR_POSITION]?.let { runCatching { SidebarPosition.valueOf(it) }.getOrNull() }
                                   ?: if (prefs[Keys.RIGHT_HAND_DRIVE] == true) SidebarPosition.RIGHT else defaults.sidebarPosition,
                bottomBarShortcutsRight = prefs[Keys.BOTTOM_BAR_SHORTCUTS_RIGHT] ?: defaults.bottomBarShortcutsRight,
                dayNightMode     = prefs[Keys.DAY_NIGHT_MODE]?.let { runCatching { DayNightMode.valueOf(it) }.getOrNull() } ?: defaults.dayNightMode,
                appLanguage      = prefs[Keys.APP_LANGUAGE]?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() } ?: defaults.appLanguage,
                showPip          = prefs[Keys.SHOW_PIP]         ?: defaults.showPip,
                pipAppPackages   = prefs[Keys.PIP_APP_PACKAGES_JSON]?.let {
                    runCatching {
                        gson.fromJson<List<String>>(it, object : TypeToken<List<String>>() {}.type)
                    }.getOrNull()
                } ?: defaults.pipAppPackages,
                pipPaneSplit     = prefs[Keys.PIP_PANE_SPLIT]    ?: defaults.pipPaneSplit,
                pipPaneSplit2    = prefs[Keys.PIP_PANE_SPLIT2]   ?: defaults.pipPaneSplit2,
                pipAppCount      = prefs[Keys.PIP_APP_COUNT]     ?: defaults.pipAppCount,
                pipPaneOrder     = prefs[Keys.PIP_PANE_ORDER_JSON]?.let {
                    runCatching {
                        gson.fromJson<List<Int>>(it, object : TypeToken<List<Int>>() {}.type)
                    }.getOrNull()
                } ?: defaults.pipPaneOrder,
                autostartPackages = prefs[Keys.AUTOSTART_PACKAGES_JSON]?.let {
                    runCatching {
                        gson.fromJson<List<String>>(it, object : TypeToken<List<String>>() {}.type)
                    }.getOrNull()
                } ?: defaults.autostartPackages,
                favoriteApps     = prefs[Keys.FAVORITE_APPS_JSON]?.let {
                    runCatching {
                        gson.fromJson<List<String>>(it, object : TypeToken<List<String>>() {}.type)
                    }.getOrNull()
                } ?: defaults.favoriteApps,
                radioPackage     = prefs[Keys.RADIO_PACKAGE]    ?: defaults.radioPackage,
                onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: defaults.onboardingCompleted,
                showVitals       = prefs[Keys.SHOW_VITALS]      ?: defaults.showVitals,
                showTripTracker  = prefs[Keys.SHOW_TRIP_TRACKER] ?: defaults.showTripTracker,
                compassOffset    = prefs[Keys.COMPASS_OFFSET]    ?: defaults.compassOffset,
                showSoundboard   = prefs[Keys.SHOW_SOUNDBOARD]   ?: defaults.showSoundboard,
                soundboardPads   = prefs[Keys.SOUNDBOARD_PADS_JSON]?.let {
                    runCatching {
                        gson.fromJson<List<SoundPadConfig>>(it, object : com.google.gson.reflect.TypeToken<List<SoundPadConfig>>() {}.type)
                    }.getOrNull()
                } ?: defaults.soundboardPads,
                vitalsAsBars     = prefs[Keys.VITALS_AS_BARS] ?: defaults.vitalsAsBars,
                speedometerDigitalOnly = prefs[Keys.SPEEDOMETER_DIGITAL_ONLY] ?: defaults.speedometerDigitalOnly,
                hideAppHeader        = prefs[Keys.HIDE_APP_HEADER]        ?: defaults.hideAppHeader,
                hideSystemStatusBar  = prefs[Keys.HIDE_SYSTEM_STATUS_BAR] ?: defaults.hideSystemStatusBar,
                hideSystemNavBar     = prefs[Keys.HIDE_SYSTEM_NAV_BAR]    ?: defaults.hideSystemNavBar
            )
    }

    suspend fun saveSettings(s: AppSettings) {
        context.dataStore.edit { prefs -> writeSettings(prefs, s) }
    }

    /**
     * Atomic read-modify-write. DataStore serializes edit blocks, so concurrent
     * updates can't overwrite each other (unlike transforming a stale snapshot).
     */
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs -> writeSettings(prefs, transform(readSettings(prefs))) }
    }

    private fun writeSettings(prefs: MutablePreferences, s: AppSettings) {
            prefs[Keys.VEHICLE_NAME]       = s.vehicleName
            prefs[Keys.ACCENT_COLOR]       = s.accentColor
            prefs[Keys.USE_CUSTOM_SIDEBAR_COLOR] = s.useCustomSidebarColor
            prefs[Keys.SIDEBAR_COLOR]      = s.sidebarColor
            prefs[Keys.FONT_COLOR]         = s.fontColor
            prefs[Keys.WALLPAPER_URI]      = s.wallpaperUri
            prefs[Keys.FONT_BOLD]          = s.fontBold
            prefs[Keys.TEXT_SCALE]         = s.textScale
            prefs[Keys.UI_SCALE]           = s.uiScale
            prefs[Keys.APP_ICON_SCALE]     = s.appIconScale
            prefs[Keys.APP_GRID_COLUMNS]   = s.appGridColumns
            prefs[Keys.APP_GRID_ROWS]      = s.appGridRows
            prefs[Keys.CLOCK_STYLE]        = s.clockStyle.name
            prefs[Keys.UNIT_SYSTEM]        = s.unitSystem.name
            prefs[Keys.APP_FONT]           = s.appFont.name
            prefs[Keys.SHOW_WEATHER]       = s.showWeather
            prefs[Keys.SHOW_CLOCK]         = s.showClock
            prefs[Keys.SHOW_TELEMETRY]     = s.showTelemetry
            prefs[Keys.SHOW_NOW_PLAYING]   = s.showNowPlaying
            prefs[Keys.SHOW_ALTIMETER]     = s.showAltimeter
            prefs[Keys.SHOW_SPEEDOMETER]   = s.showSpeedometer
            prefs[Keys.SHORTCUTS_JSON]     = gson.toJson(s.shortcuts)
            prefs[Keys.WIDGET_LAYOUT_JSON] = gson.toJson(s.widgetLayout)
            prefs[Keys.CAR_PLAY_PACKAGE]      = s.carPlayPackage
            prefs[Keys.ANDROID_AUTO_PACKAGE]  = s.androidAutoPackage
            prefs[Keys.WALLPAPER_DIM]      = s.wallpaperDim
            prefs[Keys.SIDEBAR_POSITION]           = s.sidebarPosition.name
            prefs[Keys.BOTTOM_BAR_SHORTCUTS_RIGHT] = s.bottomBarShortcutsRight
            prefs[Keys.DAY_NIGHT_MODE]     = s.dayNightMode.name
            prefs[Keys.APP_LANGUAGE]       = s.appLanguage.name
            prefs[Keys.SHOW_PIP]           = s.showPip
            prefs[Keys.PIP_APP_PACKAGES_JSON] = gson.toJson(s.pipAppPackages)
            prefs[Keys.PIP_PANE_SPLIT]     = s.pipPaneSplit
            prefs[Keys.PIP_PANE_SPLIT2]    = s.pipPaneSplit2
            prefs[Keys.PIP_APP_COUNT]      = s.pipAppCount
            prefs[Keys.PIP_PANE_ORDER_JSON] = gson.toJson(s.pipPaneOrder)
            prefs[Keys.AUTOSTART_PACKAGES_JSON] = gson.toJson(s.autostartPackages)
            prefs[Keys.FAVORITE_APPS_JSON] = gson.toJson(s.favoriteApps)
            prefs[Keys.RADIO_PACKAGE]      = s.radioPackage
            prefs[Keys.ONBOARDING_COMPLETED] = s.onboardingCompleted
            prefs[Keys.SHOW_VITALS]        = s.showVitals
            prefs[Keys.SHOW_TRIP_TRACKER]  = s.showTripTracker
            prefs[Keys.COMPASS_OFFSET]     = s.compassOffset
            prefs[Keys.SHOW_SOUNDBOARD]    = s.showSoundboard
            prefs[Keys.SOUNDBOARD_PADS_JSON] = gson.toJson(s.soundboardPads)
            prefs[Keys.VITALS_AS_BARS]     = s.vitalsAsBars
            prefs[Keys.SPEEDOMETER_DIGITAL_ONLY] = s.speedometerDigitalOnly
            prefs[Keys.HIDE_APP_HEADER]        = s.hideAppHeader
            prefs[Keys.HIDE_SYSTEM_STATUS_BAR] = s.hideSystemStatusBar
            prefs[Keys.HIDE_SYSTEM_NAV_BAR]    = s.hideSystemNavBar
    }

    suspend fun resetToDefaults() {
        context.dataStore.edit { it.clear() }
    }

    // Locale has to be applied in Activity.attachBaseContext, which runs
    // before the ViewModel (and so the normal settingsFlow collection)
    // exists — a one-off blocking read of just this one key is the standard
    // workaround for that ordering problem.
    fun readAppLanguageBlocking(): AppLanguage = runBlocking {
        val prefs = context.dataStore.data.first()
        prefs[Keys.APP_LANGUAGE]?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() } ?: AppSettings().appLanguage
    }
}
