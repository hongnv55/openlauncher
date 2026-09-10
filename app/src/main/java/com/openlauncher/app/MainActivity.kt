package com.openlauncher.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.openlauncher.app.data.DayNightMode
import com.openlauncher.app.data.SidebarPosition
import com.openlauncher.app.model.NavDestination
import com.openlauncher.app.ui.components.BackdropSource
import com.openlauncher.app.ui.components.Sidebar
import com.openlauncher.app.ui.screen.*
import com.openlauncher.app.ui.theme.OpenLauncherTheme
import com.openlauncher.app.viewmodel.LauncherViewModel

@OptIn(ExperimentalLayoutApi::class)
class MainActivity : ComponentActivity() {

    // Runs before the ViewModel (and its normal settingsFlow collection)
    // exists, so the language has to be read with a one-off blocking call —
    // see SettingsRepository.readAppLanguageBlocking. A language change is
    // applied by a full process restart (restartLauncher()), which is what
    // gets this to run again with the new value.
    override fun attachBaseContext(newBase: android.content.Context) {
        val language = com.openlauncher.app.data.SettingsRepository(newBase).readAppLanguageBlocking()
        super.attachBaseContext(com.openlauncher.app.util.LocaleHelper.wrap(newBase, language))
    }

    private val vm: LauncherViewModel by viewModels()

    private val locationPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            vm.startLocationUpdates()
        }
    }

    // On this car ROM, WindowManager never reports a status bar content inset
    // to any app window (confirmed via dumpsys: content inset stays [0,0][0,0]
    // even with the real StatusBar surface shown at height 74) — unlike the
    // OEM's own left nav-style panel, which the system excludes by shrinking
    // the window's own frame instead. windowInsetsPadding(WindowInsets.
    // statusBars) has nothing to react to here, so the real, OS-reported
    // status_bar_height dimension resource is used as a manual fallback —
    // the classic pre-insets-API technique, still valid, and correct on
    // ordinary phones/emulators too since it reads the same value the system
    // itself uses for the bar's height.
    private fun statusBarHeightPx(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    private fun hideSystemBars() {
        val settings = vm.settings.value
        // SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN/LAYOUT_HIDE_NAVIGATION each mean,
        // literally, "lay out as if this bar isn't there — don't report an
        // inset for it", independent of the WindowInsetsController show()/
        // hide() calls below. Setting them unconditionally made the window
        // always report a zero inset for a bar even while it's genuinely
        // shown (confirmed via dumpsys on the car unit: content inset stayed
        // [144,0][0,0] — only the OEM's own left panel — with no top inset
        // despite the real StatusBar surface being drawn at height 74). Only
        // set each LAYOUT_* bit when we actually mean to hide that bar, so
        // Compose's windowInsetsPadding() gets a real, reactive inset value
        // for whichever one is actually visible.
        @Suppress("DEPRECATION")
        var flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        if (settings.hideSystemStatusBar) {
            @Suppress("DEPRECATION")
            flags = flags or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        if (settings.hideSystemNavBar) {
            @Suppress("DEPRECATION")
            flags = flags or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = flags
        // Called after setting the legacy flags above, not before: on API 28
        // this compat call itself falls back to setting LAYOUT_STABLE/
        // LAYOUT_FULLSCREEN/LAYOUT_HIDE_NAVIGATION as part of its own shim —
        // calling it first let our own conditional flags overwrite that
        // outcome; calling it last instead lets its edge-to-edge behavior be
        // the final word on whether the content view actually gets measured
        // against the full window instead of the system's separately-tracked
        // "content frame" (seen via dumpsys: content stayed clipped to 1860px
        // even with the window's own Requested width already at 1920, and
        // even on a genuinely fresh cold boot — not stale state left over
        // from an earlier frame).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (settings.hideSystemStatusBar) hide(WindowInsetsCompat.Type.statusBars()) else show(WindowInsetsCompat.Type.statusBars())
            if (settings.hideSystemNavBar) hide(WindowInsetsCompat.Type.navigationBars()) else show(WindowInsetsCompat.Type.navigationBars())
        }
        // DisplayPolicy's own window-bounds recompute (confirmed via dumpsys:
        // "Requested w" going from 1860 back to the full 1920 once the right
        // flags are in place) can land a frame or two after this call returns
        // — before Compose's own tree ever re-measures against it. Without an
        // explicit nudge here, PipWidget's BoxWithConstraints keeps whatever
        // (now stale, too-narrow) width it first measured, leaving a blank
        // gap where a bar used to be reserved until some unrelated
        // interaction happens to force a relayout. Only reproduced right
        // after a fresh launch/toggle change, not on every recomposition.
        window.decorView.requestLayout()
    }

    private fun setNavigationControlsDisabled(disabled: Boolean) {
        runCatching {
            val manager = getSystemService("statusbar")
            val statusBarManager = Class.forName("android.app.StatusBarManager")
            val flags = if (disabled) {
                statusBarManager.getField("DISABLE_BACK").getInt(null) or
                    statusBarManager.getField("DISABLE_HOME").getInt(null) or
                    statusBarManager.getField("DISABLE_RECENT").getInt(null)
            } else {
                statusBarManager.getField("DISABLE_NONE").getInt(null)
            }
            statusBarManager.getMethod("disable", Int::class.javaPrimitiveType)
                .invoke(manager, flags)
        }.onFailure {
            Log.w("OpenLauncher", "Unable to ${if (disabled) "disable" else "restore"} system navigation", it)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Manifest declares SplashTheme for this activity so WindowManager's
        // starting window (painted from the theme alone, before this method
        // even runs) shows the centered app icon instead of a blank black
        // screen. Switching back to the real theme here — before
        // super.onCreate() — means the very first window this Activity
        // itself is responsible for already carries the normal theme.
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
        hideSystemBars()
        @Suppress("DEPRECATION")
        window.decorView.setOnSystemUiVisibilityChangeListener {
            window.decorView.removeCallbacks(::hideSystemBars)
            window.decorView.post(::hideSystemBars)
        }

        setContent {
            val settingsLoaded by vm.settingsLoaded.collectAsStateWithLifecycle()
            val settings       by vm.settings.collectAsStateWithLifecycle()
            LaunchedEffect(settings.hideSystemStatusBar, settings.hideSystemNavBar) {
                hideSystemBars()
            }
            // Autostart Apps: settings/picker UI exists (see SettingsScreen),
            // but the actual "launch it silently" mechanism is intentionally
            // not wired up yet — settings.autostartPackages is only stored
            // for now, nothing reads it to actually start anything.
            val nav            by vm.nav.collectAsStateWithLifecycle()
            val apps        by vm.apps.collectAsStateWithLifecycle()
            val appsLoading by vm.appsLoading.collectAsStateWithLifecycle()
            val nowPlaying  by vm.nowPlaying.collectAsStateWithLifecycle()
            val weather     by vm.weather.collectAsStateWithLifecycle()
            val location    by vm.location.collectAsStateWithLifecycle()
            val bearing     by vm.compassBearing.collectAsStateWithLifecycle()
            val isWifi      by vm.isWifi.collectAsStateWithLifecycle()
            val isData      by vm.isData.collectAsStateWithLifecycle()
            val isDayModeVM by vm.isDayMode.collectAsStateWithLifecycle()
            val hardwareRadio by vm.hardwareRadio.collectAsStateWithLifecycle()
            val systemIsDark = isSystemInDarkTheme()
            val isDayMode = if (settings.dayNightMode == DayNightMode.SYSTEM) !systemIsDark else isDayModeVM
            val pickerSlot      by vm.shortcutPickerSlot.collectAsStateWithLifecycle()
            val appPickerTarget by vm.appPickerTarget.collectAsStateWithLifecycle()

            val accent         = Color(settings.accentColor)
            val textColor      = if (isDayMode) Color(0xFF111111) else Color(settings.fontColor)

            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density   = baseDensity.density * settings.uiScale,
                    fontScale = baseDensity.fontScale
                )
            ) {
                if (!settingsLoaded) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                } else OpenLauncherTheme(
                    accent     = accent,
                    textColor  = textColor,
                    fontBold   = settings.fontBold,
                    textScale  = settings.textScale,
                    appFont    = settings.appFont,
                    isDayMode  = isDayMode
                ) {
                if (!settings.onboardingCompleted) {
                    OnboardingScreen(
                        accent = accent,
                        onComplete = {
                            vm.updateSettings { copy(onboardingCompleted = true) }
                            // Start location updates immediately upon completion
                            vm.startLocationUpdates()
                        }
                    )
                } else {
                    val statusBarHeightDp = with(LocalDensity.current) { statusBarHeightPx().toDp() }
                    // The wallpaper layer's own window rect, in pixels. The sidebar
                    // needs it to place an aligned copy of the wallpaper behind its
                    // glass, and this cannot be derived in dp for two reasons: this
                    // Box is offset by the status-bar/nav-bar padding below, and the
                    // sidebar composes at its own density (see sidebarDensity), so
                    // the same dp value is a different number of pixels inside it.
                    // IntOffset/IntSize compare by value, so these writes settle
                    // after one extra layout pass rather than looping.
                    var wallpaperOriginPx by remember { mutableStateOf(IntOffset.Zero) }
                    var wallpaperSizePx   by remember { mutableStateOf(IntSize.Zero) }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned {
                                wallpaperOriginPx = it.positionInWindow().round()
                                wallpaperSizePx   = it.size
                            }
                            .padding(top = if (!settings.hideSystemStatusBar) statusBarHeightDp else 0.dp)
                            // Conditional on our own setting, unlike the status
                            // bar above — SYSTEM_UI_FLAG_LAYOUT_STABLE (always
                            // on, so hiding a bar doesn't jar the layout size)
                            // pins the nav bar's reported inset to its
                            // bars-shown value even once it's genuinely
                            // hidden (confirmed via dumpsys: Surface shown=
                            // false, isVisible=false, yet the window still
                            // reports a 60px inset) — so applying this
                            // unconditionally left a permanent blank gutter
                            // where the bar used to be, even after actually
                            // hiding it. Gating on the setting matches what we
                            // asked for instead of what got reported.
                            .then(
                                if (!settings.hideSystemNavBar)
                                    Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                                else Modifier
                            )
                            // Base layer under the wallpaper. Only ever visible
                            // if the wallpaper fails to decode, so it is a plain
                            // black backstop rather than a themed fill.
                            .background(Color.Black)
                    ) {
                        // Wallpaper layer. A wallpaper the user picked in Settings
                        // wins; with no override we fall back to the built-in
                        // asset for the current mode, so the launcher ships with
                        // a wallpaper rather than a flat fill.
                        //
                        // ContentScale.Crop (centre crop) is what makes one asset
                        // per mode enough: the images are 2670x1878 (1.42:1) but
                        // get shown on everything from a phone to a 16:9 head
                        // unit, so Crop scales to fill the shorter axis and trims
                        // the overflow evenly off both sides, always keeping the
                        // centre of the image. Fit would letterbox and FillBounds
                        // would distort.
                        //
                        // Routed through Coil rather than painterResource because
                        // Coil downsamples the decode to the layout size — decoded
                        // at full resolution each of these is ~20MB of
                        // ARGB_8888, and switching day/night would hold two.
                        val wallpaperModel: Any = if (settings.wallpaperUri.isNotEmpty()) {
                            android.net.Uri.parse(settings.wallpaperUri)
                        } else {
                            if (isDayMode) R.drawable.open_light else R.drawable.open_dark
                        }
                        AsyncImage(
                            model              = wallpaperModel,
                            contentDescription = null,
                            contentScale       = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier           = Modifier.fillMaxSize()
                        )
                        Box(modifier = Modifier.fillMaxSize()
                            .background(Color.Black.copy(alpha = settings.wallpaperDim)))

                        val isBottomBar    = settings.sidebarPosition == SidebarPosition.BOTTOM

                        val sidebarContent: @Composable () -> Unit = {
                            val sidebarDensity = Density(
                                density = baseDensity.density * (1.0f + (settings.uiScale - 1.0f) * 0.35f),
                                fontScale = baseDensity.fontScale
                            )
                            CompositionLocalProvider(LocalDensity provides sidebarDensity) {
                                Sidebar(
                                    currentDest   = nav,
                                    settings      = settings,
                                    isHorizontal  = isBottomBar,
                                    // The sidebar shows a *blurred* copy of the same
                                    // wallpaper. The built-ins ship a blurred twin;
                                    // a user-picked one has none, so it gets
                                    // blurred at decode time instead.
                                    backdropSource    = if (settings.wallpaperUri.isNotEmpty()) {
                                        BackdropSource.NeedsBlur(
                                            android.net.Uri.parse(settings.wallpaperUri)
                                        )
                                    } else {
                                        BackdropSource.PreBlurred(
                                            if (isDayMode) R.drawable.open_light_blur
                                            else           R.drawable.open_dark_blur
                                        )
                                    },
                                    wallpaperOriginPx = wallpaperOriginPx,
                                    wallpaperSizePx   = wallpaperSizePx,
                                    installedIconFor = { pkg ->
                                        apps.find { it.packageName == pkg }?.icon
                                    },
                                    onNavigate    = { dest ->
                                        vm.cancelShortcutPicker()
                                        vm.cancelCarPlayPicker()
                                        vm.exitRearrangeMode()
                                        vm.navigate(dest)
                                    },
                                    onShortcutClick = { slot ->
                                        val shortcut = settings.shortcuts[slot]
                                        if (shortcut.packageName.isNotEmpty()) {
                                            vm.launchApp(shortcut.packageName)
                                        }
                                    },
                                    onShortcutLongPress  = { slot -> vm.startShortcutPicker(slot) },
                                    onShortcutRemove     = { slot -> vm.removeShortcut(slot) },
                                    onShortcutSetIcon    = { slot, icon -> vm.setShortcutIcon(slot, icon) },
                                    onReorder            = { from, to -> vm.reorderShortcut(from, to) }
                                )
                            }
                        }

                        val homeContent: @Composable () -> Unit = {
                            HomeScreen(
                                settings            = settings,
                                weather             = weather,
                                nowPlaying          = nowPlaying,
                                location            = location,
                                bearing             = bearing,
                                isWifi              = isWifi,
                                isData              = isData,
                                isDayMode           = isDayMode,
                                isActive            = nav == NavDestination.HOME,
                                onPlayPause         = vm::playPause,
                                onNext              = vm::skipNext,
                                onPrev              = vm::skipPrev,
                                onLaunchCarPlay     = { vm.launchApp(settings.carPlayPackage) },
                                onLaunchAndroidAuto = { vm.launchApp(settings.androidAutoPackage) },
                                onAssignCarPlay     = { vm.startCarPlayPicker() },
                                onAssignAndroidAuto = { vm.startAndroidAutoPicker() },
                                onClearCarPlay      = { vm.clearCarPlayApp() },
                                onClearAndroidAuto  = { vm.clearAndroidAutoApp() },
                                onAssignPip         = { slot -> vm.startPipPicker(slot) },
                                onClearPip          = { slot -> vm.clearPipApp(slot) },
                                onSetPipSplit       = { vm.setPipSplit(it) },
                                onSetPipSplit2      = { vm.setPipSplit2(it) },
                                onSetPipAppCount    = { vm.setPipAppCount(it) },
                                onSwapPipApps       = { dividerIndex -> vm.swapPipApps(dividerIndex) },
                                onTapNowPlaying     = {
                                    val pkg = nowPlaying?.controller?.packageName
                                    if (!pkg.isNullOrEmpty()) vm.launchApp(pkg)
                                },
                                onUpdateWidget      = { id, sx, sy -> vm.updateWidgetConfig(id, sx, sy) },
                                onMoveWidget        = { id, gx, gy -> vm.moveWidgetConfig(id, gx, gy) },
                                onAddWidget         = { id -> vm.addWidget(id) },
                                onRemoveWidget      = { id -> vm.removeWidget(id) },
                                onSetClockStyle     = { style -> vm.updateSettings { copy(clockStyle = style) } },
                                onSetVitalsAsBars   = { asBars -> vm.updateSettings { copy(vitalsAsBars = asBars) } },
                                onSetSpeedometerDigitalOnly = { digital -> vm.updateSettings { copy(speedometerDigitalOnly = digital) } },
                                onUpdateSoundPad    = { idx, pad -> vm.updateSoundboardPad(idx, pad) },
                                hardwareRadio         = hardwareRadio,
                                onLaunchHardwareRadio = { vm.launchHardwareRadioApp() },
                                onStopHardwareRadio   = { vm.stopHardwareRadioApp() },
                                onRadioSeekUp         = { vm.radioSeekUp() },
                                onRadioSeekDown       = { vm.radioSeekDown() },
                                onRadioCycleFm        = { vm.radioCycleFm() },
                                onRadioSwitchAm       = { vm.radioSwitchAm() },
                                onRadioTune           = { band, freq -> vm.radioTune(band, freq) },
                                onAssignRadio         = { vm.startRadioPicker() }
                            )
                        }

                        val mainPane: @Composable (Modifier) -> Unit = { paneModifier ->
                            Box(modifier = paneModifier) {
                                // Home stays composed so its VirtualDisplays survive navigation.
                                homeContent()
                                AnimatedContent(
                                targetState   = nav,
                                transitionSpec = {
                                    fadeIn() + slideInHorizontally { it / 10 } togetherWith
                                    fadeOut() + slideOutHorizontally { -it / 10 }
                                },
                                modifier = Modifier.fillMaxSize(),
                                label    = "pane_transition"
                            ) { destination ->
                                when (destination) {
                                    NavDestination.HOME -> Box(Modifier.fillMaxSize())

                                    NavDestination.APP_LIBRARY -> AppLibraryScreen(
                                        apps                = apps,
                                        isLoading           = appsLoading,
                                        isPickerMode        = pickerSlot != null,
                                        pickerSlot          = pickerSlot,
                                        isCarPlayPickerMode = appPickerTarget != null,
                                        carPlayPickerLabel  = when (appPickerTarget) {
                                            com.openlauncher.app.viewmodel.LauncherViewModel.AppPickerTarget.ANDROID_AUTO -> stringResource(R.string.choose_android_auto_app)
                                            com.openlauncher.app.viewmodel.LauncherViewModel.AppPickerTarget.PIP          -> stringResource(R.string.choose_pip_app)
                                            com.openlauncher.app.viewmodel.LauncherViewModel.AppPickerTarget.RADIO        -> stringResource(R.string.choose_radio_app)
                                            com.openlauncher.app.viewmodel.LauncherViewModel.AppPickerTarget.AUTOSTART    -> stringResource(R.string.choose_autostart_app)
                                            else -> stringResource(R.string.choose_carplay_app)
                                        },
                                        accent              = accent,
                                        iconScale           = settings.appIconScale,
                                        gridColumns         = settings.appGridColumns,
                                        gridRows            = settings.appGridRows,
                                        favoriteApps        = settings.favoriteApps,
                                        onAppClick          = { app -> vm.launchApp(app.packageName) },
                                        onPickerSelect      = { slot, app -> vm.assignShortcut(slot, app) },
                                        onCarPlaySelect     = { app -> vm.assignPickerApp(app) },
                                        onToggleFavorite    = { pkg -> vm.toggleFavoriteApp(pkg) }
                                    )

                                    NavDestination.SETTINGS -> SettingsScreen(
                                        settings = settings,
                                        accent   = accent,
                                        onUpdate = { block -> vm.updateSettings(block) },
                                        onAssignPip = { slot ->
                                            vm.startPipPicker(slot, NavDestination.SETTINGS)
                                        },
                                        onClearPip = { slot -> vm.clearPipApp(slot) },
                                        onStartAutostartPicker = {
                                            vm.startAutostartPicker(NavDestination.SETTINGS)
                                        },
                                        onReset  = { vm.resetSettings() },
                                        onRestartLauncher = { restartLauncher() }
                                    )
                                }
                            }
                            }
                        }

                        // The sidebar's own edge that matches its position
                        // (left edge for LEFT, right edge for RIGHT, bottom
                        // edge for BOTTOM) sits flush against the true
                        // window edge — no gap, no radius there (handled
                        // inside Sidebar.kt). Every other edge — including
                        // the one facing the content pane — keeps the gap,
                        // so the sidebar still reads as floating on three
                        // sides.
                        if (isBottomBar) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                mainPane(Modifier.weight(1f).fillMaxWidth())
                                sidebarContent()
                            }
                        } else {
                            Row(modifier = Modifier.fillMaxSize()) {
                                if (settings.sidebarPosition == SidebarPosition.LEFT) {
                                    sidebarContent()
                                }
                                mainPane(Modifier.weight(1f).fillMaxHeight())
                                if (settings.sidebarPosition == SidebarPosition.RIGHT) {
                                    sidebarContent()
                                }
                            }
                        }
                    }
                }
            }
            } // CompositionLocalProvider
        }
    }

    override fun onResume() {
        super.onResume()
        setNavigationControlsDisabled(true)
        window.decorView.post(::hideSystemBars)
        vm.refreshConnectivity()
        vm.refreshMedia()
    }

    override fun onStop() {
        setNavigationControlsDisabled(false)
        super.onStop()
        vm.stopLocationUpdates()
    }

    override fun onStart() {
        super.onStart()
        vm.startLocationUpdates()
    }

    /**
     * "Restart Launcher" (Settings > Maintenance). Standard restart-the-whole-app
     * pattern: queue the relaunch, then release every embedded pane, then kill
     * this process so the already-queued request survives. exit() must follow
     * startActivity() with nothing in between — delaying it (e.g. to
     * onDestroy(), to let Compose dispose TaskEmbedder/VirtualDisplay state
     * gracefully via its own lifecycle first) killed the process mid-transaction
     * and dropped the relaunch entirely instead of making anything safer.
     * TaskEmbedder.releaseAll() below is the fix that actually worked: it runs
     * synchronously in this same call stack (not a delay to a later lifecycle
     * callback), so every VirtualDisplay gets to detach before the process
     * dies, instead of being abandoned mid-transaction — which otherwise left
     * WindowManager unable to signal the new activity's window as ready,
     * freezing its first frame. Confirmed stable across repeated restarts.
     */
    private fun restartLauncher() {
        val component = packageManager.getLaunchIntentForPackage(packageName)?.component
        if (component == null) {
            Log.e("OpenLauncher", "restartLauncher: no launch intent for $packageName")
            return
        }
        startActivity(Intent.makeRestartActivityTask(component))
        com.openlauncher.app.ui.widget.TaskEmbedder.releaseAll()
        Runtime.getRuntime().exit(0)
    }
}
