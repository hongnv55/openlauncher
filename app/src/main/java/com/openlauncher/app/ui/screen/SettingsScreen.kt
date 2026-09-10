package com.openlauncher.app.ui.screen

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.BuildConfig
import com.openlauncher.app.R
import com.openlauncher.app.data.AppFont
import com.openlauncher.app.data.AppLanguage
import com.openlauncher.app.data.AppSettings
import com.openlauncher.app.data.DayNightMode
import kotlin.math.roundToInt
import com.openlauncher.app.data.SidebarPosition
import com.openlauncher.app.data.ShortcutConfig
import com.openlauncher.app.data.UnitSystem
import com.openlauncher.app.ui.theme.LocalDayMode
import com.openlauncher.app.ui.theme.PaneInset
import com.openlauncher.app.ui.theme.contentPane
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.openlauncher.app.ui.components.ColorPickerDialog
import com.openlauncher.app.ui.components.ConfirmDialog

// Resolved at call site via LocalDayMode — see SettingsDivider / SettingsSection

// Temporarily hidden per request — flip back to true to restore. Sidebar
// Position moved into "Appearance" (still shown); these are the only rows
// actually removed from view.
private const val SHOW_VEHICLE_NAME_ROW      = false
private const val SHOW_UNIT_SYSTEM_ROW       = false
private const val SHOW_GPS_CALIBRATION_SECTION = false
private const val SHOW_UPDATES_SECTION       = false

@Composable
fun SettingsScreen(
    settings: AppSettings,
    accent: Color,
    onUpdate: (AppSettings.() -> AppSettings) -> Unit,
    onAssignPip: (slot: Int) -> Unit,
    onClearPip: (slot: Int) -> Unit,
    onStartAutostartPicker: () -> Unit,
    onReset: () -> Unit,
    onRestartLauncher: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showResetDialog       by remember { mutableStateOf(false) }
    var showRestartDialog     by remember { mutableStateOf(false) }
    var showAccentPicker      by remember { mutableStateOf(false) }
    var showSidebarColorPicker by remember { mutableStateOf(false) }
    var showFontColorPicker   by remember { mutableStateOf(false) }

    // OpenDocument (not GetContent): only SAF document URIs carry a persistable
    // grant, so this is what actually keeps the wallpaper readable after reboot
    val wallpaperPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            onUpdate { copy(wallpaperUri = it.toString()) }
        }
    }

    val isDayMode = LocalDayMode.current

    // Same content card as a PIP pane and the app list, at the same inset, so
    // all three destinations swap contents inside one persistent frame. Also
    // drops the old full-bleed colorScheme.background fill, which painted the
    // pre-wallpaper background colour straight over the wallpaper.
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(PaneInset)
            .contentPane(isDayMode, accent)
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── Title ────────────────────────────────────────────────────────────
        Text(
            text          = stringResource(R.string.settings).uppercase(),
            style         = MaterialTheme.typography.titleLarge,
            color         = if (isDayMode) Color(0xFF111111) else accent,
            letterSpacing = 3.sp,
            fontSize      = 14.sp
        )

        Spacer(Modifier.height(4.dp))

        // ── System ───────────────────────────────────────────────────────────
        SettingsSection(stringResource(R.string.system)) {
            val isMediaConnected by com.openlauncher.app.service.MediaListenerService.isConnected.collectAsState()

            // Bumped on ON_RESUME so statuses refresh when the user returns from
            // system settings (recomposition alone doesn't re-run these checks)
            var permissionRefresh by remember { mutableIntStateOf(0) }
            val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) permissionRefresh++
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            // canDrawOverlays requires API 23 — on Android 5.x the permission
            // model doesn't exist, so treat it as granted
            val canDrawOverlays = remember(permissionRefresh) {
                android.os.Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)
            }
            val hasLocation = remember(permissionRefresh) {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            val isDefaultLauncher = remember(permissionRefresh) {
                val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                context.packageManager.resolveActivity(
                    home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
                )?.activityInfo?.packageName == context.packageName
            }

            val homeRoleLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { permissionRefresh++ }

            val locationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { permissionRefresh++ }

            SettingsButton(
                label    = stringResource(R.string.set_default_launcher),
                sublabel = stringResource(if (isDefaultLauncher) R.string.default_launcher_active else R.string.default_launcher_required),
                icon     = Icons.Default.Home,
                accent   = if (isDefaultLauncher) accent else Color(0xFF993333),
                onClick  = {
                    // Preferred: the system home-role dialog (API 29+). Vendor ROMs
                    // sometimes ship without it, so fall through to the home-settings
                    // screen, then the default-apps screen.
                    var launched = false
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val rm = context.getSystemService(android.app.role.RoleManager::class.java)
                        if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) &&
                            !rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)
                        ) {
                            launched = runCatching {
                                homeRoleLauncher.launch(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME))
                            }.isSuccess
                        }
                    }
                    if (!launched) {
                        launched = runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.isSuccess
                    }
                    if (!launched) {
                        runCatching {
                            context.startActivity(
                                Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }
            )
            SettingsDivider()
            SettingsButton(
                label    = stringResource(R.string.notification_access),
                sublabel = stringResource(if (isMediaConnected) R.string.notification_granted else R.string.notification_required),
                icon     = if (isMediaConnected) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                accent   = if (isMediaConnected) accent else Color(0xFF993333),
                onClick  = {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            )
            SettingsDivider()
            SettingsButton(
                label    = stringResource(R.string.draw_over_apps),
                sublabel = stringResource(if (canDrawOverlays) R.string.overlay_granted else R.string.overlay_required),
                icon     = if (canDrawOverlays) Icons.Default.Layers else Icons.Default.LayersClear,
                accent   = if (canDrawOverlays) accent else Color(0xFF993333),
                onClick  = {
                    if (android.os.Build.VERSION.SDK_INT >= 23) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }
            )
            SettingsDivider()
            SettingsButton(
                label    = stringResource(R.string.location_access),
                sublabel = stringResource(if (hasLocation) R.string.location_granted else R.string.location_required),
                icon     = if (hasLocation) Icons.Default.LocationOn else Icons.Default.LocationOff,
                accent   = if (hasLocation) accent else Color(0xFF993333),
                onClick  = {
                    if (!hasLocation) {
                        // Ask in-app first — previously the only grant path was the
                        // onboarding flow; skipping it left GPS features dead forever
                        locationPermissionLauncher.launch(arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        ))
                    } else {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }
            )

            SettingsDivider()

            // Language — overrides the device's own locale for this app's UI
            // only (see LocaleHelper/MainActivity.attachBaseContext). Locale
            // is fixed at process-start time, so a change here only takes
            // effect after a full restart — hence reusing the same
            // showRestartDialog flow as the Maintenance section's button,
            // triggered automatically on an actual change. The two option
            // labels are fixed, native-language names ("English", "Tiếng
            // Việt") rather than translated strings — a language picker
            // conventionally names each language in itself, not in whatever
            // language the UI currently happens to be in.
            SettingsRow(
                label    = stringResource(R.string.language),
                sublabel = when (settings.appLanguage) {
                    AppLanguage.ENGLISH    -> "English"
                    AppLanguage.VIETNAMESE -> "Tiếng Việt"
                },
                icon = Icons.Default.Language
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppLanguage.entries.forEach { lang ->
                        FilterChip(
                            selected = settings.appLanguage == lang,
                            onClick  = {
                                if (settings.appLanguage != lang) {
                                    onUpdate { copy(appLanguage = lang) }
                                    showRestartDialog = true
                                }
                            },
                            label    = {
                                Text(
                                    text = when (lang) {
                                        AppLanguage.ENGLISH    -> "English"
                                        AppLanguage.VIETNAMESE -> "Tiếng Việt"
                                    },
                                    fontSize = 9.sp,
                                    letterSpacing = 0.5.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accent,
                                selectedLabelColor     = Color.Black
                            )
                        )
                    }
                }
            }
        }

        // ── Vehicle Name ─────────────────────────────────────────────────────
        if (SHOW_VEHICLE_NAME_ROW || SHOW_UNIT_SYSTEM_ROW) {
        SettingsSection(stringResource(R.string.vehicle)) {
            if (SHOW_VEHICLE_NAME_ROW) {
            var nameInput by remember(settings.vehicleName) { mutableStateOf(settings.vehicleName) }
            SettingsRow(
                label    = stringResource(R.string.vehicle_name),
                sublabel = "",
                icon     = Icons.Default.DirectionsCar
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value         = nameInput,
                        onValueChange = { nameInput = it },
                        placeholder   = { Text(stringResource(R.string.my_car), color = if (isDayMode) Color(0xFF999999) else Color(0xFF444444), fontSize = 12.sp) },
                        singleLine    = true,
                        textStyle     = LocalTextStyle.current.copy(fontSize = 12.sp, color = if (isDayMode) Color(0xFF111111) else Color.White),
                        colors        = outlinedFieldColors(accent),
                        modifier      = Modifier.width(140.dp)
                    )
                    if (nameInput != settings.vehicleName) {
                        IconButton(onClick = { onUpdate { copy(vehicleName = nameInput) } }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Check, stringResource(R.string.save), tint = accent, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            if (SHOW_UNIT_SYSTEM_ROW) SettingsDivider()
            }

            if (SHOW_UNIT_SYSTEM_ROW) {
            SettingsRow(label = stringResource(R.string.unit_system), sublabel = stringResource(if (settings.unitSystem == UnitSystem.METRIC) R.string.metric_summary else R.string.imperial_summary), icon = Icons.Default.Straighten) {
                Row {
                    FilterChip(
                        selected = settings.unitSystem == UnitSystem.METRIC,
                        onClick  = { onUpdate { copy(unitSystem = UnitSystem.METRIC) } },
                        label    = { Text(stringResource(R.string.metric), fontSize = 11.sp) },
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent,
                            selectedLabelColor     = Color.Black
                        )
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = settings.unitSystem == UnitSystem.IMPERIAL,
                        onClick  = { onUpdate { copy(unitSystem = UnitSystem.IMPERIAL) } },
                        label    = { Text(stringResource(R.string.imperial), fontSize = 11.sp) },
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent,
                            selectedLabelColor     = Color.Black
                        )
                    )
                }
            }
            }
        }
        }

        // ── Picture-in-Picture ───────────────────────────────────────────────
        SettingsSection(stringResource(R.string.picture_in_picture)) {
            fun appLabel(packageName: String): String {
                if (packageName.isEmpty()) return context.getString(R.string.pip_not_configured)
                return runCatching {
                    val info = context.packageManager.getApplicationInfo(packageName, 0)
                    context.packageManager.getApplicationLabel(info).toString()
                }.getOrDefault(packageName)
            }

            val firstPackage = settings.pipAppPackages.getOrElse(0) { "" }
            val secondPackage = settings.pipAppPackages.getOrElse(1) { "" }
            val thirdPackage = settings.pipAppPackages.getOrElse(2) { "" }

            SettingsButton(
                label = stringResource(R.string.first_pip),
                sublabel = appLabel(firstPackage),
                icon = Icons.Default.PictureInPicture,
                accent = accent,
                onClick = { onAssignPip(0) },
                trailingContent = {
                    if (firstPackage.isNotEmpty()) {
                        IconButton(onClick = { onClearPip(0) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, null, tint = Color(0xFF993333), modifier = Modifier.size(14.dp))
                        }
                    }
                }
            )

            SettingsDivider()

            SettingsButton(
                label = stringResource(R.string.second_pip),
                sublabel = appLabel(secondPackage),
                icon = Icons.Default.PictureInPictureAlt,
                accent = accent,
                onClick = { onAssignPip(1) },
                trailingContent = {
                    if (secondPackage.isNotEmpty()) {
                        IconButton(onClick = { onClearPip(1) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, null, tint = Color(0xFF993333), modifier = Modifier.size(14.dp))
                        }
                    }
                }
            )

            SettingsDivider()

            SettingsButton(
                label = stringResource(R.string.third_pip),
                sublabel = appLabel(thirdPackage),
                icon = Icons.Default.PictureInPictureAlt,
                accent = accent,
                onClick = { onAssignPip(2) },
                trailingContent = {
                    if (thirdPackage.isNotEmpty()) {
                        IconButton(onClick = { onClearPip(2) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, null, tint = Color(0xFF993333), modifier = Modifier.size(14.dp))
                        }
                    }
                }
            )
        }

        // ── Appearance ───────────────────────────────────────────────────────
        SettingsSection(stringResource(R.string.appearance)) {
            // Display Mode
            SettingsRow(
                label    = stringResource(R.string.display_mode),
                sublabel = when (settings.dayNightMode) {
                    DayNightMode.DARK   -> stringResource(R.string.always_dark)
                    DayNightMode.LIGHT  -> stringResource(R.string.always_light)
                    DayNightMode.AUTO   -> stringResource(R.string.sunrise_sunset)
                    DayNightMode.SYSTEM -> stringResource(R.string.follows_system_theme)
                },
                icon = when (settings.dayNightMode) {
                    DayNightMode.DARK   -> Icons.Default.NightlightRound
                    DayNightMode.LIGHT  -> Icons.Default.LightMode
                    DayNightMode.AUTO   -> Icons.Default.Brightness4
                    DayNightMode.SYSTEM -> Icons.Default.PhoneAndroid
                }
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DayNightMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.dayNightMode == mode,
                            onClick  = { onUpdate { copy(dayNightMode = mode) } },
                            label    = {
                                Text(
                                    text      = when (mode) {
                                        DayNightMode.DARK   -> stringResource(R.string.dark)
                                        DayNightMode.LIGHT  -> stringResource(R.string.light)
                                        DayNightMode.AUTO   -> stringResource(R.string.sunset)
                                        DayNightMode.SYSTEM -> stringResource(R.string.system)
                                    },
                                    fontSize  = 9.sp,
                                    letterSpacing = 0.5.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accent,
                                selectedLabelColor     = Color.Black
                            )
                        )
                    }
                }
            }

            SettingsDivider()

            // Sidebar Position — moved here from "Vehicle" so it lives with
            // the rest of the visual/layout controls.
            SettingsRow(
                label    = stringResource(R.string.sidebar_position),
                sublabel = when (settings.sidebarPosition) {
                    SidebarPosition.LEFT   -> stringResource(R.string.left_side)
                    SidebarPosition.RIGHT  -> stringResource(R.string.right_side)
                    SidebarPosition.BOTTOM -> stringResource(R.string.bottom)
                },
                icon     = Icons.Default.SwapHoriz
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SidebarPosition.entries.forEach { pos ->
                        FilterChip(
                            selected = settings.sidebarPosition == pos,
                            onClick  = { onUpdate { copy(sidebarPosition = pos) } },
                            label    = {
                                Text(
                                    when (pos) {
                                        SidebarPosition.LEFT   -> stringResource(R.string.left)
                                        SidebarPosition.RIGHT  -> stringResource(R.string.right)
                                        SidebarPosition.BOTTOM -> stringResource(R.string.bottom)
                                    },
                                    fontSize = 9.sp,
                                    letterSpacing = 0.5.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accent,
                                selectedLabelColor     = Color.Black
                            )
                        )
                    }
                }
            }

            if (settings.sidebarPosition == SidebarPosition.BOTTOM) {
                SettingsDivider()
                SettingsRow(
                    label    = stringResource(R.string.shortcuts_side),
                    sublabel = stringResource(if (settings.bottomBarShortcutsRight) R.string.shortcuts_right else R.string.shortcuts_left),
                    icon     = Icons.Default.FormatAlignRight
                ) {
                    Switch(
                        checked         = settings.bottomBarShortcutsRight,
                        onCheckedChange = { onUpdate { copy(bottomBarShortcutsRight = it) } },
                        colors          = switchColors(accent)
                    )
                }
            }

            SettingsDivider()

            // Accent color
            SettingsRow(
                label    = stringResource(R.string.accent_color),
                sublabel = stringResource(R.string.accent_color_description),
                icon     = Icons.Default.Palette
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(settings.accentColor))
                        .clickable { showAccentPicker = true }
                )
            }

            SettingsDivider()

            // Sidebar color — independent of Background: when off, the
            // sidebar auto-derives an "elevated card" tint from whatever
            // background is picked above (see Sidebar.kt); this lets that be
            // overridden with an exact color instead.
            SettingsRow(
                label    = stringResource(R.string.sidebar_color),
                sublabel = stringResource(if (settings.useCustomSidebarColor) R.string.custom else R.string.auto_from_background),
                icon     = Icons.Default.ViewSidebar
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(settings.sidebarColor))
                            .clickable { showSidebarColorPicker = true }
                    )
                    if (settings.useCustomSidebarColor) {
                        TextButton(
                            onClick = { onUpdate { copy(useCustomSidebarColor = false) } },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(stringResource(R.string.auto).uppercase(), color = accent, fontSize = 9.sp, letterSpacing = 1.sp)
                        }
                    }
                }
            }

            SettingsDivider()

            // Font Color row
            SettingsRow(
                label    = stringResource(R.string.font_color),
                sublabel = stringResource(R.string.font_color_description),
                icon     = Icons.Default.FormatSize
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(settings.fontColor))
                        .clickable { showFontColorPicker = true }
                )
            }

            SettingsDivider()

            // Wallpaper
            SettingsButton(
                label    = stringResource(R.string.set_wallpaper),
                sublabel = stringResource(if (settings.wallpaperUri.isNotEmpty()) R.string.custom_wallpaper_active else R.string.choose_gallery_image),
                icon     = Icons.Default.Wallpaper,
                accent   = accent,
                onClick  = { wallpaperPicker.launch(arrayOf("image/*")) }
            )
            if (settings.wallpaperUri.isNotEmpty()) {
                Column {
                    SettingsRow(
                        label    = stringResource(R.string.wallpaper_dim),
                        sublabel = stringResource(R.string.percent_value, settings.wallpaperDim * 100),
                        icon     = Icons.Default.BrightnessLow
                    ) {}
                    Slider(
                        value         = settings.wallpaperDim,
                        onValueChange = { onUpdate { copy(wallpaperDim = it) } },
                        valueRange    = 0f..0.95f,
                        steps         = 18,
                        colors        = sliderColors(accent),
                        modifier      = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick  = { onUpdate { copy(wallpaperUri = "") } },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(stringResource(R.string.remove_wallpaper).uppercase(), color = Color(0xFF993333), fontSize = 9.sp, letterSpacing = 1.sp)
                    }
                }
            }
        }

        // ── Typography ───────────────────────────────────────────────────────
        SettingsSection(stringResource(R.string.typography)) {
            SettingsRow(label = stringResource(R.string.font), sublabel = fontDisplayName(settings.appFont), icon = Icons.Default.FontDownload) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    com.openlauncher.app.data.AppFont.entries.forEach { font ->
                        FilterChip(
                            selected = settings.appFont == font,
                            onClick  = { onUpdate { copy(appFont = font) } },
                            label    = { Text(fontDisplayName(font), fontSize = 9.sp, letterSpacing = 0.5.sp) },
                            colors   = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accent,
                                selectedLabelColor     = Color.Black
                            )
                        )
                    }
                }
            }

            SettingsDivider()

            SettingsRow(label = stringResource(R.string.bold_font), sublabel = stringResource(R.string.bold_font_description), icon = Icons.Default.FormatBold) {
                Switch(
                    checked         = settings.fontBold,
                    onCheckedChange = { onUpdate { copy(fontBold = it) } },
                    colors          = switchColors(accent)
                )
            }

            SettingsDivider()

            Column {
                SettingsRow(
                    label    = stringResource(R.string.text_scale),
                    sublabel = stringResource(R.string.percent_value, settings.textScale * 100),
                    icon     = Icons.Default.TextFields
                ) {}
                Slider(
                    value         = settings.textScale,
                    onValueChange = { onUpdate { copy(textScale = it) } },
                    valueRange    = 0.8f..1.4f,
                    steps         = 5,
                    colors        = sliderColors(accent),
                    modifier      = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }

            SettingsDivider()

            Column {
                SettingsRow(
                    label    = stringResource(R.string.ui_scale),
                    sublabel = stringResource(R.string.scale_all_elements, settings.uiScale * 100),
                    icon     = Icons.Default.ZoomIn
                ) {}
                Slider(
                    value         = settings.uiScale,
                    onValueChange = { onUpdate { copy(uiScale = it) } },
                    valueRange    = 0.7f..1.5f,
                    steps         = 7,
                    colors        = sliderColors(accent),
                    modifier      = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }
        }

        // ── App Library ──────────────────────────────────────────────────────
        SettingsSection(stringResource(R.string.app_library)) {
            Column {
                SettingsRow(
                    label    = stringResource(R.string.app_icon_size),
                    sublabel = stringResource(R.string.percent_value, settings.appIconScale * 100),
                    icon     = Icons.Default.Apps
                ) {}
                Slider(
                    value         = settings.appIconScale,
                    onValueChange = { onUpdate { copy(appIconScale = it) } },
                    valueRange    = 1.0f..2.0f,
                    steps         = 9,
                    colors        = sliderColors(accent),
                    modifier      = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }

            SettingsDivider()

            Column {
                SettingsRow(
                    label    = stringResource(R.string.grid_columns),
                    sublabel = pluralStringResource(R.plurals.grid_columns_across, settings.appGridColumns, settings.appGridColumns),
                    icon     = Icons.Default.ViewColumn
                ) {}
                Slider(
                    value         = settings.appGridColumns.toFloat(),
                    onValueChange = { onUpdate { copy(appGridColumns = it.roundToInt()) } },
                    valueRange    = 4f..10f,
                    steps         = 5,
                    colors        = sliderColors(accent),
                    modifier      = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }

            SettingsDivider()

            Column {
                SettingsRow(
                    label    = stringResource(R.string.grid_rows),
                    sublabel = pluralStringResource(R.plurals.grid_rows_visible, settings.appGridRows, settings.appGridRows),
                    icon     = Icons.Default.ViewAgenda
                ) {}
                Slider(
                    value         = settings.appGridRows.toFloat(),
                    onValueChange = { onUpdate { copy(appGridRows = it.roundToInt()) } },
                    valueRange    = 2f..6f,
                    steps         = 3,
                    colors        = sliderColors(accent),
                    modifier      = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }
        }

        // ── GPS & Calibration ───────────────────────────────────────────────
        if (SHOW_GPS_CALIBRATION_SECTION) {
        SettingsSection(stringResource(R.string.gps_calibration)) {
            var calibrationStatus by remember { mutableStateOf<String?>(null) }
            val coroutineScope = rememberCoroutineScope()
            var isCalibratingCompass by remember { mutableStateOf(false) }
            var compassCountdown by remember { mutableIntStateOf(0) }

            // 1. Reset A-GPS Button
            SettingsButton(
                label    = stringResource(R.string.reset_agps),
                sublabel = calibrationStatus ?: stringResource(R.string.reset_agps_description),
                icon     = Icons.Default.MyLocation,
                accent   = accent,
                onClick  = {
                    calibrationStatus = context.getString(R.string.clearing_agps)
                    val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                    var success = false
                    try {
                        // "delete_aiding_data" is the command AOSP's GPS provider
                        // actually recognizes (requires ACCESS_LOCATION_EXTRA_COMMANDS)
                        success = lm.sendExtraCommand(android.location.LocationManager.GPS_PROVIDER, "delete_aiding_data", android.os.Bundle())
                        lm.sendExtraCommand(android.location.LocationManager.GPS_PROVIDER, "force_xtra_injection", null)
                        lm.sendExtraCommand(android.location.LocationManager.GPS_PROVIDER, "force_time_injection", null)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    calibrationStatus = if (success) {
                        context.getString(R.string.agps_cleared)
                    } else {
                        context.getString(R.string.agps_not_supported)
                    }
                }
            )
            
            SettingsDivider()
            
            // 2. Drive-in-circles magnetometer sweep. Android's sensor stack
            // self-calibrates the magnetometer continuously — the circles feed it
            // diverse readings. The timer guides the sweep; it does not (and
            // cannot) apply offsets itself, so the message must not claim it did.
            SettingsButton(
                label    = stringResource(R.string.magnetometer_sweep),
                sublabel = if (isCalibratingCompass) {
                    stringResource(R.string.sweep_active, compassCountdown)
                } else {
                    stringResource(R.string.sweep_description)
                },
                icon     = Icons.Default.Navigation,
                accent   = if (isCalibratingCompass) Color.Green else accent,
                onClick  = {
                    if (!isCalibratingCompass) {
                        isCalibratingCompass = true
                        compassCountdown = 30
                        coroutineScope.launch {
                            while (compassCountdown > 0) {
                                delay(1000)
                                compassCountdown--
                            }
                            isCalibratingCompass = false
                            calibrationStatus = context.getString(R.string.sweep_complete)
                        }
                    }
                }
            )

            SettingsDivider()

            // 4. Manual Compass Heading Offset Slider
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                SettingsRow(
                    label    = stringResource(R.string.compass_heading_offset),
                    sublabel = stringResource(R.string.manual_alignment, if (settings.compassOffset >= 0) "+" else "", settings.compassOffset.toInt()),
                    icon     = Icons.Default.Explore
                ) {}
                Slider(
                    value         = settings.compassOffset,
                    onValueChange = { onUpdate { copy(compassOffset = it) } },
                    valueRange    = -180f..180f,
                    steps         = 71, // 5 degree steps: 360 / 5 - 1 = 71 steps
                    colors        = sliderColors(accent),
                    modifier      = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }
        }
        }

        // ── Updates ──────────────────────────────────────────────────────────
        if (SHOW_UPDATES_SECTION) {
        SettingsSection(stringResource(R.string.updates)) {
            SettingsButton(
                label    = stringResource(R.string.check_for_updates),
                sublabel = stringResource(R.string.view_github_releases),
                icon     = Icons.Default.SystemUpdate,
                accent   = accent,
                onClick  = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/dw2lam/openlauncher/releases"))
                    context.startActivity(intent)
                }
            )
        }
        }

        // ── Autostart Apps ───────────────────────────────────────────────────
        SettingsSection(stringResource(R.string.autostart_apps)) {
            fun appLabel(packageName: String): String = runCatching {
                val info = context.packageManager.getApplicationInfo(packageName, 0)
                context.packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(packageName)

            if (settings.autostartPackages.isEmpty()) {
                SettingsRow(
                    label    = stringResource(R.string.no_autostart_apps),
                    sublabel = stringResource(R.string.autostart_description),
                    icon     = Icons.Default.Apps
                ) {}
            } else {
                settings.autostartPackages.forEachIndexed { index, pkg ->
                    if (index > 0) SettingsDivider()
                    SettingsRow(
                        label    = appLabel(pkg),
                        sublabel = stringResource(R.string.runs_in_background),
                        icon     = Icons.Default.Apps
                    ) {
                        IconButton(
                            onClick  = { onUpdate { copy(autostartPackages = autostartPackages - pkg) } },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Close, null, tint = Color(0xFF993333), modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            if (settings.autostartPackages.size < 3) {
                SettingsDivider()
                SettingsButton(
                    label    = stringResource(R.string.add_autostart_app),
                    sublabel = stringResource(R.string.add_autostart_description),
                    icon     = Icons.Default.Add,
                    accent   = accent,
                    onClick  = onStartAutostartPicker
                )
            }
        }

        // ── Status & Navigation ──────────────────────────────────────────────
        SettingsSection(stringResource(R.string.status_navigation)) {
            SettingsRow(
                label    = stringResource(R.string.hide_status_bar),
                sublabel = stringResource(R.string.hide_status_bar_description),
                icon     = Icons.Default.ViewHeadline
            ) {
                Switch(
                    checked         = settings.hideAppHeader,
                    onCheckedChange = { onUpdate { copy(hideAppHeader = it) } },
                    colors          = switchColors(accent)
                )
            }

            SettingsDivider()

            SettingsRow(
                label    = stringResource(R.string.hide_system_status_bar),
                sublabel = stringResource(R.string.hide_system_status_bar_description),
                icon     = Icons.Default.SignalCellularAlt
            ) {
                Switch(
                    checked         = settings.hideSystemStatusBar,
                    onCheckedChange = { onUpdate { copy(hideSystemStatusBar = it) } },
                    colors          = switchColors(accent)
                )
            }

            SettingsDivider()

            SettingsRow(
                label    = stringResource(R.string.hide_system_navigation_bar),
                sublabel = stringResource(R.string.hide_system_navigation_bar_description),
                icon     = Icons.Default.Web
            ) {
                Switch(
                    checked         = settings.hideSystemNavBar,
                    onCheckedChange = { onUpdate { copy(hideSystemNavBar = it) } },
                    colors          = switchColors(accent)
                )
            }
        }

        // ── Maintenance ──────────────────────────────────────────────────────
        SettingsSection(stringResource(R.string.maintenance)) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick  = { showRestartDialog = true },
                shape    = RoundedCornerShape(4.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = if (isDayMode) Color(0xFFE8E8E8) else Color(0xFF1A1A1A)),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Icon(Icons.Default.Refresh, null, tint = accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.restart_launcher), color = accent, fontSize = 13.sp, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick  = { showResetDialog = true },
                shape    = RoundedCornerShape(4.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A0000)),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Icon(Icons.Default.RestartAlt, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.reset_defaults), color = MaterialTheme.colorScheme.error, fontSize = 13.sp, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text          = stringResource(R.string.build_info, BuildConfig.VERSION_NAME, BuildConfig.BUILD_DATE),
            color         = if (isDayMode) Color(0xFFAAAAAA) else Color(0xFF2A2A2A),
            fontSize      = 10.sp,
            letterSpacing = 1.sp,
            modifier      = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 16.dp)
        )
    }
    } // end Box

    // ── Dialogs ──────────────────────────────────────────────────────────────
    if (showResetDialog) {
        ConfirmDialog(
            title        = stringResource(R.string.reset_settings),
            message      = stringResource(R.string.reset_settings_message),
            confirmLabel = stringResource(R.string.reset),
            onConfirm    = { onReset(); showResetDialog = false },
            onDismiss    = { showResetDialog = false }
        )
    }

    if (showRestartDialog) {
        ConfirmDialog(
            title        = stringResource(R.string.restart_launcher),
            message      = stringResource(R.string.restart_message),
            confirmLabel = stringResource(R.string.restart),
            onConfirm    = {
                showRestartDialog = false
                onRestartLauncher()
            },
            onDismiss    = { showRestartDialog = false }
        )
    }

    if (showAccentPicker) {
        ColorPickerDialog(
            title           = stringResource(R.string.accent_color),
            initialColor    = Color(settings.accentColor),
            onColorSelected = { c -> onUpdate { copy(accentColor = c.toArgb()) } },
            onDismiss       = { showAccentPicker = false }
        )
    }

    if (showSidebarColorPicker) {
        ColorPickerDialog(
            title           = stringResource(R.string.sidebar_color),
            initialColor    = Color(settings.sidebarColor),
            onColorSelected = { c ->
                onUpdate {
                    copy(
                        sidebarColor = c.toArgb(),
                        useCustomSidebarColor = true
                    )
                }
            },
            onDismiss       = { showSidebarColorPicker = false }
        )
    }

    if (showFontColorPicker) {
        ColorPickerDialog(
            title           = stringResource(R.string.font_color),
            initialColor    = Color(settings.fontColor),
            onColorSelected = { c -> onUpdate { copy(fontColor = c.toArgb()) } },
            onDismiss       = { showFontColorPicker = false }
        )
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDayMode     = LocalDayMode.current
    val sectionColor  = if (isDayMode) Color(0xFF888888) else Color(0xFF3A3A3A)
    val dividerColor  = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF1E1E1E)
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(
            text          = title.uppercase(),
            style         = MaterialTheme.typography.labelSmall,
            color         = sectionColor,
            letterSpacing = 2.sp,
            modifier      = Modifier.padding(top = 16.dp, bottom = 6.dp)
        )
        HorizontalDivider(color = dividerColor)
        Column(modifier = Modifier.fillMaxWidth(), content = content)
        HorizontalDivider(color = dividerColor)
    }
}

@Composable
private fun SettingsRow(
    label: String,
    sublabel: String = "",
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable RowScope.() -> Unit
) {
    val isDayMode   = LocalDayMode.current
    val labelColor  = if (isDayMode) Color(0xFF111111) else Color(0xFFDDDDDD)
    val subColor    = if (isDayMode) Color(0xFF888888) else Color(0xFF444444)
    val iconTint    = if (isDayMode) Color(0xFF777777) else MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = labelColor, fontSize = 13.sp)
            if (sublabel.isNotEmpty())
                Text(sublabel, style = MaterialTheme.typography.labelSmall, color = subColor, fontSize = 11.sp)
        }
        content()
    }
}

@Composable
private fun ColumnScope.SettingsButton(
    label: String,
    sublabel: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    onClick: () -> Unit,
    trailingContent: @Composable RowScope.() -> Unit = {}
) {
    val isDayMode  = LocalDayMode.current
    val labelColor = if (isDayMode) Color(0xFF111111) else Color(0xFFDDDDDD)
    val subColor   = if (isDayMode) Color(0xFF888888) else Color(0xFF444444)
    val chevronC   = if (isDayMode) Color(0xFFBBBBBB) else Color(0xFF2A2A2A)
    val iconTint   = if (isDayMode) Color(0xFF777777) else MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 0.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = labelColor, fontSize = 13.sp)
            if (sublabel.isNotEmpty())
                Text(sublabel, style = MaterialTheme.typography.labelSmall, color = subColor, fontSize = 11.sp)
        }
        trailingContent()
        Icon(Icons.Default.ChevronRight, null, tint = chevronC, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ColumnScope.SettingsDivider() {
    val isDayMode = LocalDayMode.current
    HorizontalDivider(color = if (isDayMode) Color(0xFFDDDDDD) else Color(0xFF141414))
}

@Composable
private fun outlinedFieldColors(accent: Color): androidx.compose.material3.TextFieldColors {
    val isDayMode = LocalDayMode.current
    val textColor = if (isDayMode) Color(0xFF111111) else Color.White
    val borderU   = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF2A2A2A)
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor   = accent,
        unfocusedBorderColor = borderU,
        focusedTextColor     = textColor,
        unfocusedTextColor   = textColor,
        cursorColor          = accent,
        focusedLabelColor    = accent,
        unfocusedLabelColor  = if (isDayMode) Color(0xFF888888) else Color(0xFF666666)
    )
}

@Composable
private fun switchColors(accent: Color): androidx.compose.material3.SwitchColors {
    val isDayMode = LocalDayMode.current
    return SwitchDefaults.colors(
        checkedThumbColor    = if (isDayMode) Color.White else Color.Black,
        checkedTrackColor    = accent,
        uncheckedThumbColor  = if (isDayMode) Color(0xFFBBBBBB) else Color(0xFF888888),
        uncheckedTrackColor  = if (isDayMode) Color(0xFFDDDDDD) else Color(0xFF1E1E1E),
        uncheckedBorderColor = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF3A3A3A)
    )
}

@Composable
private fun sliderColors(accent: Color): androidx.compose.material3.SliderColors {
    val isDayMode = LocalDayMode.current
    return SliderDefaults.colors(
        thumbColor         = accent,
        activeTrackColor   = accent,
        inactiveTrackColor = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF2A2A2A)
    )
}


@Composable
private fun fontDisplayName(font: AppFont): String = when (font) {
    AppFont.SYSTEM          -> stringResource(R.string.font_system)
    AppFont.JETBRAINS_MONO  -> "JetBrains Mono"
    AppFont.SOURCE_CODE_PRO -> "Source Code Pro"
}
