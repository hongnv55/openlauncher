package com.openlauncher.app.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

enum class ClockStyle { DIGITAL, ANALOG }
enum class UnitSystem { METRIC, IMPERIAL }
enum class AppFont { SYSTEM, JETBRAINS_MONO, SOURCE_CODE_PRO }
enum class DayNightMode { DARK, LIGHT, AUTO, SYSTEM }
enum class SidebarPosition { LEFT, RIGHT, BOTTOM }
enum class GradientDirection { TOP_TO_BOTTOM, LEFT_TO_RIGHT, DIAGONAL, RADIAL }

enum class DefaultShortcutIcon {
    NONE,
    // Navigation & vehicle
    RADIO, CAMERA, PHONE, MAP, NAVIGATION, CAR, GAS_STATION, DASHBOARD,
    // Audio & media
    MUSIC, SPEAKER, HEADSET, EQUALIZER, VOLUME_UP,
    // Connectivity
    BLUETOOTH, WIFI,
    // Lighting & climate
    LIGHTBULB, BRIGHTNESS, AC, THERMOSTAT,
    // General utility
    TV, VIDEOCAM, STAR, MESSAGE, TIMER, LOCK, SETTINGS, FAVORITE,
    // Web / location
    GLOBE
}

data class SoundPadConfig(
    val label: String,
    val audioUri: String = "",
    val synthType: String = "BEEP"
)

fun defaultSoundboardPads() = listOf(
    SoundPadConfig("mario_jump",   synthType = "mario_jump"),
    SoundPadConfig("mario_coin",   synthType = "mario_coin"),
    SoundPadConfig("boom",         synthType = "boom"),
    SoundPadConfig("loud_fart",    synthType = "loud_fart"),
    SoundPadConfig("+",            synthType = ""),
    SoundPadConfig("+",            synthType = "")
)

data class ShortcutConfig(
    val packageName: String = "",
    val label: String = "",
    val isDefault: Boolean = false,
    val defaultIcon: DefaultShortcutIcon = DefaultShortcutIcon.NONE,
    // null = native app icon; non-null = override with this vector icon
    val customIconOverride: DefaultShortcutIcon? = null
)

const val GRID_COLS = 3
const val GRID_ROWS = 2

data class WidgetConfig(
    val id: String,          // "CLOCK" | "WEATHER" | "TELEMETRY" | "NOW_PLAYING"
    val gridX: Int,          // column 0..(GRID_COLS-1)
    val gridY: Int,          // row    0..(GRID_ROWS-1)
    val spanX: Int = 1,
    val spanY: Int = 1,
    val enabled: Boolean = true
)

data class AppSettings(
    val vehicleName: String = "MY CAR",
    val accentColor: Int = Color(0xFF32FAA9).toArgb(),
    // A near-black, cool-toned graphite rather than flat #000000 (which reads
    // as dead/cheap) or the earlier pale lavender-gray (too close in
    // brightness to most embedded apps' own white UI, so the gap/border
    // frame around PIP panes barely read as a frame at all). Dark enough
    // that colorful app icons and the mint accent pop against it.
    val backgroundColor: Int = Color(0xFF202226).toArgb(),
    val fontColor: Int = Color.White.toArgb(),
    val wallpaperUri: String = "",
    val fontBold: Boolean = false,
    val textScale: Float = 1.2f,
    val uiScale: Float = 1.0f,
    // Scales the app icon (and its decode resolution) in the App Library grid tiles
    val appIconScale: Float = 1.9f,
    // Columns in the App Library grid — tile width = available width / appGridColumns
    val appGridColumns: Int = 7,
    // Rows visible without scrolling — tile height = available height / appGridRows
    val appGridRows: Int = 3,
    val clockStyle: ClockStyle = ClockStyle.DIGITAL,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val appFont: AppFont = AppFont.JETBRAINS_MONO,
    val showWeather: Boolean = false,
    val showClock: Boolean = false,
    val showTelemetry: Boolean = false,
    val showNowPlaying: Boolean = false,
    val shortcuts: List<ShortcutConfig> = defaultShortcuts(),
    val widgetLayout: List<WidgetConfig> = defaultWidgetLayout(),
    val carPlayPackage: String = "",
    val androidAutoPackage: String = "",
    val useGradient: Boolean = false,
    val gradientEndColor: Int = Color.Black.toArgb(),
    val wallpaperDim: Float = 0.55f,
    val sidebarPosition: SidebarPosition = SidebarPosition.LEFT,
    // When off, the sidebar's color is derived automatically from
    // backgroundColor (an "elevated card" tint, lighter than the background
    // by an amount that scales with how dark that background already is —
    // see Sidebar.kt). Turning this on overrides that with an exact color.
    val useCustomSidebarColor: Boolean = false,
    val sidebarColor: Int = Color(0xFF46484C).toArgb(),
    val bottomBarShortcutsRight: Boolean = false,
    val showAltimeter: Boolean = false,
    val showSpeedometer: Boolean = false,
    val dayNightMode: DayNightMode = DayNightMode.LIGHT,
    val showPip: Boolean = true,
    // Up to 3 apps shown side-by-side in the PIP widget ("" = slot unassigned)
    val pipAppPackages: List<String> = listOf("", "", ""),
    // Fraction of the pane area (excluding the divider(s)) given to the
    // first visual position
    val pipPaneSplit: Float = 0.5f,
    // With 3 panes: fraction of whatever's left after the first position
    // that goes to the second visual position (the third gets the rest)
    val pipPaneSplit2: Float = 0.5f,
    // How many of pipAppPackages are actually shown (1-3)
    val pipAppCount: Int = 1,
    // paneOrder[visual position] = slot index — purely a display arrangement,
    // independent of pipAppPackages, so tapping a divider grip to swap two
    // adjacent panes' sides never touches slot identity and never reloads
    // any of the embedded apps
    val pipPaneOrder: List<Int> = listOf(0, 1, 2),
    // Apps silently launched (embedded on a hidden, tiny VirtualDisplay — same
    // mechanism as the PIP panes, just never shown) as soon as the launcher
    // starts. Best suited to apps that do background work while resumed
    // (music, sync); a video-heavy app still fully decodes/renders even
    // though nothing ever displays it. Capped at 3 (max 3 backgrounded app
    // processes at once, matching the PIP pane cap).
    val autostartPackages: List<String> = emptyList(),
    // Head unit's radio app — mirrored & controlled via its MediaSession
    val radioPackage: String = "",
    val onboardingCompleted: Boolean = false,
    val showVitals: Boolean = false,
    val showTripTracker: Boolean = false,
    val compassOffset: Float = 0f,
    val showSoundboard: Boolean = false,
    val soundboardPads: List<SoundPadConfig> = defaultSoundboardPads(),
    val vitalsAsBars: Boolean = false,
    val speedometerDigitalOnly: Boolean = false,
    val gradientDirection: GradientDirection = GradientDirection.DIAGONAL,
    val useCustomBackgroundColor: Boolean = true,
    // In-app header row (vehicle name, wifi/data icons, edit-widgets button) —
    // distinct from the two below, which are the real Android system bars.
    val hideAppHeader: Boolean = true,
    val hideSystemStatusBar: Boolean = true,
    val hideSystemNavBar: Boolean = true
)

fun defaultShortcuts() = listOf(
    ShortcutConfig()
)

fun defaultWidgetLayout() = listOf(
    WidgetConfig("PIP", gridX = 0, gridY = 0, spanX = GRID_COLS, spanY = GRID_ROWS)
)

fun AppSettings.activeWidgetIds(): Set<String> = buildSet {
    if (showClock) add("CLOCK")
    if (showWeather) add("WEATHER")
    if (showNowPlaying) add("NOW_PLAYING")
    if (showTelemetry) add("TELEMETRY")
    if (showAltimeter) add("ALTIMETER")
    if (showSpeedometer) add("SPEEDOMETER")
    if (showVitals) add("VITALS")
    if (showTripTracker) add("TRIP_TRACKER")
    if (showSoundboard) add("SOUNDBOARD")
    if (showPip) add("PIP")
}

/**
 * Moves [movingId] to ([targetX], [targetY]) and pushes any displaced widgets to the
 * first available free cell, cascading until all conflicts are resolved.
 * Operates only on the supplied [layout] list — callers should pass only active/enabled widgets.
 */
fun computeWidgetMove(
    layout: List<WidgetConfig>,
    movingId: String,
    targetX: Int,
    targetY: Int
): List<WidgetConfig> {
    val moving = layout.find { it.id == movingId } ?: return layout
    val placed = moving.copy(
        gridX = targetX.coerceIn(0, GRID_COLS - moving.spanX),
        gridY = targetY.coerceIn(0, GRID_ROWS - moving.spanY)
    )

    val others  = layout.filter { it.id != movingId }
    val result  = mutableListOf(placed)
    val occupied = buildOccupied(result).toMutableSet()

    // Stable widgets that don't conflict go first; displaced ones are pushed afterwards
    val (stable, displaced) = others.partition { w -> result.none { widgetsOverlap(it, w) } }

    for (w in stable) {
        result.add(w)
        for (dx in 0 until w.spanX) for (dy in 0 until w.spanY) occupied.add(w.gridX + dx to w.gridY + dy)
    }

    for (w in displaced) {
        val pos = firstFreeGridPos(w.spanX, w.spanY, occupied)
        val resolved = if (pos != null) w.copy(gridX = pos.first, gridY = pos.second) else w
        result.add(resolved)
        for (dx in 0 until resolved.spanX) for (dy in 0 until resolved.spanY) occupied.add(resolved.gridX + dx to resolved.gridY + dy)
    }

    return result
}

private fun buildOccupied(widgets: List<WidgetConfig>) = buildSet<Pair<Int, Int>> {
    widgets.forEach { w -> for (dx in 0 until w.spanX) for (dy in 0 until w.spanY) add(w.gridX + dx to w.gridY + dy) }
}

private fun widgetsOverlap(a: WidgetConfig, b: WidgetConfig): Boolean =
    a.gridX < b.gridX + b.spanX && a.gridX + a.spanX > b.gridX &&
    a.gridY < b.gridY + b.spanY && a.gridY + a.spanY > b.gridY

private fun firstFreeGridPos(spanX: Int, spanY: Int, occupied: Set<Pair<Int, Int>>): Pair<Int, Int>? {
    for (row in 0 until GRID_ROWS) for (col in 0 until GRID_COLS) {
        if (col + spanX > GRID_COLS || row + spanY > GRID_ROWS) continue
        if ((0 until spanX).all { dx -> (0 until spanY).all { dy -> (col + dx to row + dy) !in occupied } })
            return col to row
    }
    return null
}
