package com.openlauncher.app.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import com.openlauncher.app.data.AppSettings
import com.openlauncher.app.data.DefaultShortcutIcon
import com.openlauncher.app.data.ShortcutConfig
import com.openlauncher.app.data.SidebarPosition
import com.openlauncher.app.model.NavDestination
import com.openlauncher.app.ui.theme.LocalDayMode
import kotlin.math.roundToInt

private val ICON_SIZE   = 30.dp
// Nav buttons (Home/Settings/Apps) no longer sit in a clipping chip, so this
// can run bigger than ICON_SIZE without getting cut off at the chip's edge.
private val NAV_ICON_SIZE = 46.dp
private val SLOT_SIZE   = 52.dp
private val SIDEBAR_CORNER  = 12.dp
private val NAV_CHIP_SIZE   = 46.dp
private val NAV_CHIP_RADIUS = RoundedCornerShape(14.dp)

@Composable
fun Sidebar(
    currentDest: NavDestination,
    settings: AppSettings,
    installedIconFor: (String) -> Drawable?,
    onNavigate: (NavDestination) -> Unit,
    onShortcutClick: (Int) -> Unit,
    onShortcutLongPress: (Int) -> Unit,
    onShortcutRemove: (Int) -> Unit,
    onShortcutSetIcon: (Int, DefaultShortcutIcon?) -> Unit,
    onReorder: (from: Int, to: Int) -> Unit,
    isHorizontal: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isDayMode    = LocalDayMode.current
    val accent       = Color(settings.accentColor)
    val sidebarBg    = settings.resolveSidebarColor(isDayMode)
    val iconInactive = if (isDayMode) Color(0xFF777777) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
    val density      = LocalDensity.current
    val slotSizePx   = with(density) { SLOT_SIZE.toPx() }

    var actionSheetSlot by remember { mutableStateOf<Int?>(null) }
    var iconPickerSlot  by remember { mutableStateOf<Int?>(null) }

    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetPx  by remember { mutableFloatStateOf(0f) }

    fun dragTargetIndex(): Int = if (draggingIndex < 0) -1 else
        (draggingIndex + (dragOffsetPx / slotSizePx).roundToInt())
            .coerceIn(0, settings.shortcuts.size - 1)

    fun slotTranslation(index: Int): Float {
        if (draggingIndex < 0 || index == draggingIndex) return 0f
        val from = draggingIndex
        val to   = dragTargetIndex()
        return when {
            from < to && index in (from + 1)..to -> -slotSizePx
            from > to && index in to until from  ->  slotSizePx
            else -> 0f
        }
    }

    val shortcutsContent: @Composable () -> Unit = {
        settings.shortcuts.forEachIndexed { index, shortcut ->
            val isDragging  = (index == draggingIndex)
            val translation = if (isDragging) dragOffsetPx else slotTranslation(index)

            ShortcutSlot(
                shortcut        = shortcut,
                accent          = accent,
                sidebarBg       = sidebarBg,
                resolvedIcon    = if (shortcut.packageName.isNotEmpty())
                                      installedIconFor(shortcut.packageName) else null,
                isDragging      = isDragging,
                dragTranslation = translation,
                isHorizontal    = isHorizontal,
                onClick         = { onShortcutClick(index) },
                onLongPress     = {
                    if (shortcut.packageName.isNotEmpty()) {
                        actionSheetSlot = index
                    } else {
                        onShortcutLongPress(index)
                    }
                },
                onDragStart = {
                    draggingIndex = index
                    dragOffsetPx  = 0f
                },
                onDragDelta = { d -> dragOffsetPx += d },
                onDragEnd   = {
                    val from = draggingIndex
                    val to   = dragTargetIndex()
                    if (from >= 0 && to != from) onReorder(from, to)
                    draggingIndex = -1
                    dragOffsetPx  = 0f
                }
            )
        }
    }

    val navButtons: @Composable () -> Unit = {
        NavButton(
            icon         = Icons.Default.Apps,
            label        = "Apps",
            isActive     = currentDest == NavDestination.APP_LIBRARY,
            accent       = accent,
            iconInactive = iconInactive,
            isHorizontal = isHorizontal,
            onClick      = { onNavigate(NavDestination.APP_LIBRARY) }
        )
        if (!isHorizontal) Spacer(Modifier.height(6.dp))
        NavButton(
            icon         = Icons.Default.Settings,
            label        = "Settings",
            isActive     = currentDest == NavDestination.SETTINGS,
            accent       = accent,
            iconInactive = iconInactive,
            isHorizontal = isHorizontal,
            onClick      = { onNavigate(NavDestination.SETTINGS) }
        )
        if (!isHorizontal) Spacer(Modifier.height(6.dp))
        NavButton(
            icon         = Icons.Default.Home,
            label        = "Home",
            isActive     = currentDest == NavDestination.HOME,
            accent       = accent,
            iconInactive = iconInactive,
            isHorizontal = isHorizontal,
            onClick      = { onNavigate(NavDestination.HOME) }
        )
    }

    if (isHorizontal) {
        // Bottom edge is flush with the window's own bottom edge — no gap,
        // no radius there — while the other three edges (top, facing the
        // content pane, and both sides) keep the floating-card treatment.
        val bottomBarShape = RoundedCornerShape(
            topStart = SIDEBAR_CORNER, topEnd = SIDEBAR_CORNER,
            bottomEnd = 0.dp, bottomStart = 0.dp
        )
        BoxWithConstraints(modifier = modifier) {
        // Inset proportionally (10% each side, i.e. 80% of the available
        // length) rather than a fixed dp amount, so the bar reads as its own
        // fixed, independent element noticeably shorter than the content it
        // sits beside — not just another same-sized card — at any width.
        val inset = maxWidth * 0.1f
        Box(
            modifier = Modifier
                .padding(horizontal = inset)
                .fillMaxWidth()
                .height(56.dp)
                .shadow(
                    elevation    = 6.dp,
                    shape        = bottomBarShape,
                    ambientColor = Color.Black.copy(alpha = 0.4f),
                    spotColor    = Color.Black.copy(alpha = 0.4f)
                )
                .clip(bottomBarShape)
                .background(sidebarBg)
        ) {
            // Shortcuts centred, inset past the edge-pinned nav buttons and
            // scrollable — an unbounded row ran beneath the nav buttons and off
            // both screen edges once enough slots were added
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .padding(horizontal = 150.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                shortcutsContent()
            }

            // Nav buttons pinned to one edge, Home always outermost
            Row(
                modifier = Modifier
                    .align(if (settings.bottomBarShortcutsRight) Alignment.CenterEnd else Alignment.CenterStart)
                    .fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!settings.bottomBarShortcutsRight) {
                    NavButton(Icons.Default.Home,     "Home",     currentDest == NavDestination.HOME,        accent, iconInactive, true) { onNavigate(NavDestination.HOME) }
                    NavButton(Icons.Default.Settings, "Settings", currentDest == NavDestination.SETTINGS,    accent, iconInactive, true) { onNavigate(NavDestination.SETTINGS) }
                    NavButton(Icons.Default.Apps,     "Apps",     currentDest == NavDestination.APP_LIBRARY, accent, iconInactive, true) { onNavigate(NavDestination.APP_LIBRARY) }
                } else {
                    NavButton(Icons.Default.Apps,     "Apps",     currentDest == NavDestination.APP_LIBRARY, accent, iconInactive, true) { onNavigate(NavDestination.APP_LIBRARY) }
                    NavButton(Icons.Default.Settings, "Settings", currentDest == NavDestination.SETTINGS,    accent, iconInactive, true) { onNavigate(NavDestination.SETTINGS) }
                    NavButton(Icons.Default.Home,     "Home",     currentDest == NavDestination.HOME,        accent, iconInactive, true) { onNavigate(NavDestination.HOME) }
                }
            }
        }
        }
    } else {
        // The edge touching the window (left edge for a LEFT sidebar, right
        // edge for RIGHT) is flush — no gap, no radius there — while the
        // other three edges keep the floating-card treatment.
        val sidebarShape = if (settings.sidebarPosition == SidebarPosition.RIGHT) {
            RoundedCornerShape(topStart = SIDEBAR_CORNER, topEnd = 0.dp, bottomEnd = 0.dp, bottomStart = SIDEBAR_CORNER)
        } else {
            RoundedCornerShape(topStart = 0.dp, topEnd = SIDEBAR_CORNER, bottomEnd = SIDEBAR_CORNER, bottomStart = 0.dp)
        }
        BoxWithConstraints(modifier = modifier) {
        // Inset proportionally (10% each side, i.e. 80% of the available
        // height) rather than a fixed dp amount, so the bar reads as its own
        // fixed, independent element noticeably shorter than the content it
        // sits beside — not just another same-sized card — at any height.
        val inset = maxHeight * 0.1f
        Column(
            modifier = Modifier
                .padding(vertical = inset)
                .width(56.dp)
                .fillMaxHeight()
                .shadow(
                    elevation    = 6.dp,
                    shape        = sidebarShape,
                    ambientColor = Color.Black.copy(alpha = 0.4f),
                    spotColor    = Color.Black.copy(alpha = 0.4f)
                )
                .clip(sidebarShape)
                .background(sidebarBg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 6.dp, bottom = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                shortcutsContent()
            }

            Spacer(Modifier.height(8.dp))
            // Faint boundary between the shortcut slots and the fixed nav
            // actions below — inset from both edges rather than spanning
            // full-width, so it reads as a soft zone marker rather than a
            // hard seam that'd cut across the card's rounded silhouette.
            HorizontalDivider(
                modifier  = Modifier
                    .padding(horizontal = 12.dp)
                    .width(32.dp),
                thickness = 1.dp,
                color     = Color.White.copy(alpha = 0.35f)
            )
            Spacer(Modifier.height(8.dp))
            navButtons()
            Spacer(Modifier.height(4.dp))
        }
        }
    }

    // ── Action sheet dialog ──────────────────────────────────────────────────
    actionSheetSlot?.let { slot ->
        ShortcutActionDialog(
            accent      = accent,
            onChangeApp = {
                actionSheetSlot = null
                onShortcutLongPress(slot)
            },
            onCustomizeIcon = {
                actionSheetSlot = null
                iconPickerSlot = slot
            },
            onRemove = {
                actionSheetSlot = null
                onShortcutRemove(slot)
            },
            onDismiss = { actionSheetSlot = null }
        )
    }

    // ── Icon picker dialog ───────────────────────────────────────────────────
    iconPickerSlot?.let { slot ->
        IconPickerDialog(
            accent          = accent,
            hasNativeIcon   = settings.shortcuts.getOrNull(slot)?.packageName?.isNotEmpty() == true,
            currentOverride = settings.shortcuts.getOrNull(slot)?.customIconOverride,
            onPick  = { icon ->
                iconPickerSlot = null
                onShortcutSetIcon(slot, icon)
            },
            onDismiss = { iconPickerSlot = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutSlot(
    shortcut: ShortcutConfig,
    accent: Color,
    sidebarBg: Color,
    resolvedIcon: Drawable?,
    isDragging: Boolean,
    dragTranslation: Float,
    isHorizontal: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val currentOnClick      by rememberUpdatedState(onClick)
    val currentOnLongPress  by rememberUpdatedState(onLongPress)
    val currentOnDragStart  by rememberUpdatedState(onDragStart)
    val currentOnDragDelta  by rememberUpdatedState(onDragDelta)
    val currentOnDragEnd    by rememberUpdatedState(onDragEnd)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .then(
                if (isHorizontal) Modifier.fillMaxHeight().width(SLOT_SIZE)
                else              Modifier.fillMaxWidth().height(SLOT_SIZE)
            )
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                if (isHorizontal) translationX = dragTranslation else translationY = dragTranslation
                alpha = if (isDragging) 0.55f else 1f
            }
            .combinedClickable(
                onClick     = { currentOnClick() },
                onLongClick = { }
            )
            .pointerInput(isHorizontal) {
                var longPressTriggered = false
                var hasSignificantDrag = false
                var totalDrag          = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = { _ ->
                        longPressTriggered = true
                        hasSignificantDrag = false
                        totalDrag          = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val delta = if (isHorizontal) dragAmount.x else dragAmount.y
                        totalDrag += delta
                        if (!hasSignificantDrag && kotlin.math.abs(totalDrag) > viewConfiguration.touchSlop) {
                            hasSignificantDrag = true
                            currentOnDragStart()
                        }
                        if (hasSignificantDrag) currentOnDragDelta(delta)
                    },
                    onDragEnd = {
                        if (longPressTriggered && !hasSignificantDrag) currentOnLongPress()
                        if (hasSignificantDrag) currentOnDragEnd()
                        longPressTriggered = false
                        hasSignificantDrag = false
                        totalDrag          = 0f
                    },
                    onDragCancel = {
                        if (hasSignificantDrag) currentOnDragEnd()
                        longPressTriggered = false
                        hasSignificantDrag = false
                        totalDrag          = 0f
                    }
                )
            }
    ) {
        val isDayMode    = LocalDayMode.current
        val iconInactive = if (isDayMode) Color(0xFF777777) else Color(0xFF3A3A3A)
        // Same chip footprint/radius as NavButton below, always visible (not
        // just on an "active" state — shortcuts don't have one) so a bare
        // 30dp glyph doesn't float alone in a 52dp slot, reading noticeably
        // sparser/smaller than the nav icons right below it, which sit
        // inside their own chip. A subtle lighter step off the sidebar's own
        // color (not a contrasting swatch) — close enough in tone to still
        // read as part of the same sidebar, just a hair "raised" toward a
        // light source, the way a bevel/highlight implies elevation.
        val chipBg = lerp(sidebarBg, Color.White, 0.12f)
        Box(
            modifier = Modifier
                .size(NAV_CHIP_SIZE)
                .clip(NAV_CHIP_RADIUS)
                .background(chipBg),
            contentAlignment = Alignment.Center
        ) {
        val override = shortcut.customIconOverride
        when {
            override != null && override != DefaultShortcutIcon.NONE -> {
                Icon(
                    imageVector        = override.toIcon(),
                    contentDescription = shortcut.label,
                    tint               = iconInactive,
                    modifier           = Modifier.size(ICON_SIZE)
                )
            }
            resolvedIcon != null -> {
                // Cache per icon — every slot recomposes each drag frame, and an
                // un-remembered toBitmap allocated a fresh bitmap per slot per frame
                val bmp = remember(resolvedIcon) { resolvedIcon.toBitmap(60, 60) }
                Icon(
                    painter            = BitmapPainter(bmp.asImageBitmap()),
                    contentDescription = shortcut.label,
                    tint               = Color.Unspecified,
                    modifier           = Modifier.size(30.dp)
                )
            }
            shortcut.isDefault -> {
                Icon(
                    imageVector        = shortcut.defaultIcon.toIcon(),
                    contentDescription = shortcut.label,
                    tint               = iconInactive,
                    modifier           = Modifier.size(ICON_SIZE)
                )
            }
            else -> {
                Icon(
                    imageVector        = Icons.Default.Add,
                    contentDescription = "Add shortcut",
                    tint               = if (isDayMode) Color(0xFFBBBBBB) else Color(0xFF252525),
                    modifier           = Modifier.size(ICON_SIZE)
                )
            }
        }
        }
    }
}

@Composable
private fun ShortcutActionDialog(
    accent: Color,
    onChangeApp: () -> Unit,
    onCustomizeIcon: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF111111))
                .border(1.dp, Color(0xFF1E1E1E), RoundedCornerShape(4.dp))
                .padding(vertical = 4.dp)
                .width(180.dp)
        ) {
            ActionRow("CHANGE APP",     Icons.Default.SwapHoriz, accent, onChangeApp)
            HorizontalDivider(color = Color(0xFF1A1A1A))
            ActionRow("CUSTOMIZE ICON", Icons.Default.Palette,   accent, onCustomizeIcon)
            HorizontalDivider(color = Color(0xFF1A1A1A))
            ActionRow("REMOVE",         Icons.Default.Delete,     Color(0xFF993333), onRemove)
        }
    }
}

@Composable
private fun ActionRow(label: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Text(label, color = tint, fontSize = 10.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun IconPickerDialog(
    accent: Color,
    hasNativeIcon: Boolean,
    currentOverride: DefaultShortcutIcon?,
    onPick: (DefaultShortcutIcon?) -> Unit,
    onDismiss: () -> Unit
) {
    val vectorOptions = DefaultShortcutIcon.entries.filter { it != DefaultShortcutIcon.NONE }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF111111))
                .border(1.dp, Color(0xFF1E1E1E), RoundedCornerShape(4.dp))
                .padding(12.dp)
        ) {
            Text(
                "CHOOSE ICON",
                color         = Color(0xFF888888),
                fontSize      = 9.sp,
                letterSpacing = 2.sp,
                modifier      = Modifier.padding(bottom = 10.dp)
            )

            if (hasNativeIcon) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (currentOverride == null) accent.copy(alpha = 0.15f) else Color.Transparent)
                        .clickable { onPick(null) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.Apps, null, tint = if (currentOverride == null) accent else Color(0xFF666666), modifier = Modifier.size(18.dp))
                    Text(
                        "NATIVE APP ICON",
                        color         = if (currentOverride == null) accent else Color(0xFF888888),
                        fontSize      = 9.sp,
                        letterSpacing = 1.sp
                    )
                }
                HorizontalDivider(color = Color(0xFF1A1A1A), modifier = Modifier.padding(vertical = 6.dp))
            }

            LazyVerticalGrid(
                columns               = GridCells.Fixed(5),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement   = Arrangement.spacedBy(5.dp),
                modifier              = Modifier.heightIn(max = 300.dp)
            ) {
                items(vectorOptions) { iconOption ->
                    val isSelected = currentOverride == iconOption
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSelected) accent.copy(alpha = 0.18f) else Color(0xFF1A1A1A))
                            .clickable { onPick(iconOption) }
                    ) {
                        Icon(
                            imageVector        = iconOption.toIcon(),
                            contentDescription = iconOption.name,
                            tint               = if (isSelected) accent else Color(0xFF888888),
                            modifier           = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    accent: Color,
    iconInactive: Color,
    isHorizontal: Boolean = false,
    onClick: () -> Unit
) {
    // No chip/box around the icon — nothing to clip it to, so it can be
    // sized up freely — active state instead reads off a small bar under the
    // icon (reserved at fixed size always, just transparent when inactive,
    // so toggling active never shifts the icon's position).
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .then(
                if (isHorizontal) Modifier.fillMaxHeight().width(SLOT_SIZE)
                else              Modifier.fillMaxWidth().height(SLOT_SIZE)
            )
            .clickable(onClick = onClick)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = label,
                tint               = if (isActive) accent else iconInactive,
                modifier           = Modifier.size(NAV_ICON_SIZE)
            )
            Spacer(Modifier.height(0.dp))
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(if (isActive) accent else Color.Transparent)
            )
        }
    }
}

fun DefaultShortcutIcon.toIcon(): ImageVector = when (this) {
    // Navigation & vehicle
    DefaultShortcutIcon.RADIO       -> Icons.Default.Radio
    DefaultShortcutIcon.CAMERA      -> Icons.Default.CameraAlt
    DefaultShortcutIcon.PHONE       -> Icons.Default.Phone
    DefaultShortcutIcon.MAP         -> Icons.Default.Map
    DefaultShortcutIcon.NAVIGATION  -> Icons.Default.Navigation
    DefaultShortcutIcon.CAR         -> Icons.Default.DirectionsCar
    DefaultShortcutIcon.GAS_STATION -> Icons.Default.LocalGasStation
    DefaultShortcutIcon.DASHBOARD   -> Icons.Default.Speed
    // Audio & media
    DefaultShortcutIcon.MUSIC       -> Icons.Default.MusicNote
    DefaultShortcutIcon.SPEAKER     -> Icons.Default.Speaker
    DefaultShortcutIcon.HEADSET     -> Icons.Default.Headset
    DefaultShortcutIcon.EQUALIZER   -> Icons.Default.Equalizer
    DefaultShortcutIcon.VOLUME_UP   -> Icons.Default.VolumeUp
    // Connectivity
    DefaultShortcutIcon.BLUETOOTH   -> Icons.Default.Bluetooth
    DefaultShortcutIcon.WIFI        -> Icons.Default.Wifi
    // Lighting & climate
    DefaultShortcutIcon.LIGHTBULB   -> Icons.Default.Lightbulb
    DefaultShortcutIcon.BRIGHTNESS  -> Icons.Default.BrightnessHigh
    DefaultShortcutIcon.AC          -> Icons.Default.AcUnit
    DefaultShortcutIcon.THERMOSTAT  -> Icons.Default.Thermostat
    // General utility
    DefaultShortcutIcon.TV          -> Icons.Default.Tv
    DefaultShortcutIcon.VIDEOCAM    -> Icons.Default.Videocam
    DefaultShortcutIcon.STAR        -> Icons.Default.Star
    DefaultShortcutIcon.MESSAGE     -> Icons.Default.Message
    DefaultShortcutIcon.TIMER       -> Icons.Default.Timer
    DefaultShortcutIcon.LOCK        -> Icons.Default.Lock
    DefaultShortcutIcon.SETTINGS    -> Icons.Default.Settings
    DefaultShortcutIcon.FAVORITE    -> Icons.Default.Favorite
    // Web / location
    DefaultShortcutIcon.GLOBE       -> Icons.Default.Language
    DefaultShortcutIcon.NONE        -> Icons.Default.Apps
}

// Shared with PipWidget's divider grip (see HomeScreen.kt/PipWidget.kt) so
// that grip always matches whatever color the sidebar itself actually ends
// up rendering — auto-derived from the background, or the exact color when
// useCustomSidebarColor overrides it — rather than tracking it separately.
fun AppSettings.resolveSidebarColor(isDayMode: Boolean): Color {
    // Derived from the launcher's own background, not a fixed gray — a
    // lighter tint of it (elevation via tone, not a hard-coded color), so
    // the sidebar stays in the same palette as whatever background the user
    // picks. How much lighter scales with how dark the background already
    // is, rather than a fixed day/night split: a near-black background only
    // needs a small lift to read as an elevated card (lifting it 80% of the
    // way to white — right for the original pale default — would blow a dark
    // background out to a stark near-white slab instead). An already-light
    // background still gets pushed further toward white so it stays visibly
    // lighter than its own backdrop.
    return if (useCustomSidebarColor) {
        Color(sidebarColor)
    } else if (useCustomBackgroundColor) {
        val customBg = Color(backgroundColor)
        val liftFraction = 0.18f + (0.8f - 0.18f) * customBg.luminance().coerceIn(0f, 1f)
        lerp(customBg, Color.White, liftFraction)
    } else {
        if (isDayMode) Color(0xFFE0E0E0) else Color.Black.copy(alpha = 0.4f)
    }
}
