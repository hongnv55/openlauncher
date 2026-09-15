package com.openlauncher.app.ui.screen

import android.content.res.Configuration
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import com.openlauncher.app.data.AppSettings
import com.openlauncher.app.R
import com.openlauncher.app.data.ClockStyle
import com.openlauncher.app.data.computeWidgetMove
import com.openlauncher.app.data.GRID_COLS
import com.openlauncher.app.data.GRID_ROWS
import com.openlauncher.app.data.WidgetConfig
import com.openlauncher.app.model.NowPlayingState
import com.openlauncher.app.model.WeatherState
import com.openlauncher.app.ui.theme.LocalDayMode
import com.openlauncher.app.ui.widget.*
import java.util.Calendar
import com.openlauncher.app.util.LocationData

private val WIDGET_RADIUS = RoundedCornerShape(12.dp)

private enum class ResizeEdge { RIGHT, BOTTOM, CORNER }

private data class WidgetTypeInfo(
    val id: String,
    val labelRes: Int,
    val icon: ImageVector,
    val descriptionRes: Int
)

private val ALL_WIDGET_TYPES = listOf(
    WidgetTypeInfo("CLOCK", R.string.widget_clock, Icons.Default.AccessTime, R.string.widget_clock_description),
    WidgetTypeInfo("WEATHER", R.string.widget_weather, Icons.Default.Cloud, R.string.widget_weather_description),
    WidgetTypeInfo("NOW_PLAYING", R.string.widget_now_playing, Icons.Default.MusicNote, R.string.widget_now_playing_description),
    WidgetTypeInfo("TELEMETRY", R.string.widget_compass, Icons.Default.Explore, R.string.widget_compass_description),
    WidgetTypeInfo("ALTIMETER", R.string.widget_altimeter, Icons.Default.FlightTakeoff, R.string.widget_altimeter_description),
    WidgetTypeInfo("SPEEDOMETER", R.string.widget_speed, Icons.Default.Speed, R.string.widget_speed_description),
    WidgetTypeInfo("VITALS", R.string.widget_vitals, Icons.Default.Dns, R.string.widget_vitals_description),
    WidgetTypeInfo("TRIP_TRACKER", R.string.widget_trip_tracker, Icons.Default.Map, R.string.widget_trip_tracker_description),
    WidgetTypeInfo("SOUNDBOARD", R.string.widget_soundboard, Icons.Default.Piano, R.string.widget_soundboard_description),
    WidgetTypeInfo("PIP", R.string.widget_pip, Icons.Default.PictureInPicture, R.string.widget_pip_description)
)

private fun canAddWidget(settings: com.openlauncher.app.data.AppSettings): Boolean {
    val visibleIds = buildSet {
        if (settings.showClock) add("CLOCK")
        if (settings.showWeather) add("WEATHER")
        if (settings.showNowPlaying) add("NOW_PLAYING")
        if (settings.showTelemetry) add("TELEMETRY")
        if (settings.showAltimeter) add("ALTIMETER")
        if (settings.showSpeedometer) add("SPEEDOMETER")
        if (settings.showVitals) add("VITALS")
        if (settings.showTripTracker) add("TRIP_TRACKER")
        if (settings.showSoundboard) add("SOUNDBOARD")
        if (settings.showPip) add("PIP")
    }
    val activeWidgets = settings.widgetLayout.filter { it.enabled && it.id in visibleIds }
    val occupied = buildSet<Pair<Int, Int>> {
        activeWidgets.forEach { w ->
            for (dx in 0 until w.spanX) for (dy in 0 until w.spanY) add(w.gridX + dx to w.gridY + dy)
        }
    }
    val hasFreeCell = (0 until com.openlauncher.app.data.GRID_ROWS).any { r ->
        (0 until com.openlauncher.app.data.GRID_COLS).any { c -> (c to r) !in occupied }
    }
    // Also true if any active widget spans >1 cell and can be shrunk to make room
    val hasShrinkable = activeWidgets.any { it.spanX * it.spanY > 1 }
    return hasFreeCell || hasShrinkable
}

@Composable
fun HomeScreen(
    settings: AppSettings,
    weather: WeatherState?,
    nowPlaying: NowPlayingState?,
    location: LocationData?,
    bearing: Float,
    isWifi: Boolean,
    isData: Boolean,
    isDayMode: Boolean = false,
    isActive: Boolean = true,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onLaunchCarPlay: () -> Unit,
    onLaunchAndroidAuto: () -> Unit,
    onAssignCarPlay: () -> Unit,
    onAssignAndroidAuto: () -> Unit,
    onClearCarPlay: () -> Unit,
    onClearAndroidAuto: () -> Unit,
    onAssignPip: (slot: Int) -> Unit,
    onClearPip: (slot: Int) -> Unit,
    onSetPipSplit: (Float) -> Unit,
    onSetPipSplit2: (Float) -> Unit = {},
    onSetPipAppCount: (Int) -> Unit,
    onSwapPipApps: (dividerIndex: Int) -> Unit = {},
    onTapNowPlaying: () -> Unit,
    onUpdateWidget: (id: String, spanX: Int, spanY: Int) -> Unit,
    onMoveWidget: (id: String, gridX: Int, gridY: Int) -> Unit,
    onAddWidget: (id: String) -> Unit,
    onRemoveWidget: (id: String) -> Unit,
    onSetClockStyle: (ClockStyle) -> Unit,
    onSetVitalsAsBars: (Boolean) -> Unit = {},
    onSetSpeedometerDigitalOnly: (Boolean) -> Unit = {},
    onUpdateSoundPad: (index: Int, pad: com.openlauncher.app.data.SoundPadConfig) -> Unit = { _, _ -> },
    hardwareRadio: com.openlauncher.app.viewmodel.LauncherViewModel.HardwareRadioState? = null,
    onLaunchHardwareRadio: () -> Unit = {},
    onStopHardwareRadio: () -> Unit = {},
    onRadioSeekUp: () -> Unit = {},
    onRadioSeekDown: () -> Unit = {},
    onRadioCycleFm: () -> Unit = {},
    onRadioSwitchAm: () -> Unit = {},
    onRadioTune: (band: String, freq: Float) -> Unit = { _, _ -> },
    onAssignRadio: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val accent       = Color(settings.accentColor)
    val gap          = 10.dp
    // There is now always a wallpaper behind the launcher — the one the user
    // picked in Settings when set, otherwise the built-in per-mode asset (see
    // the wallpaper layer in MainActivity). So the treatments below that used
    // to be gated on "did the user pick a wallpaper" apply unconditionally:
    // a widget's near-opaque backing and hairline border are what keep it
    // legible over photographic content, and the 0.35-alpha / theme-derived
    // variants only ever made sense over a flat fill, which no longer occurs.
    val widgetBg     = if (isDayMode) Color(0xFFFFFFFF) else Color(0xCC000000)
    val widgetBorder = if (isDayMode) Color(0xFFCCCCCC) else Color(0x22FFFFFF)
    // Sits behind the embedded PIP surface rather than filling the screen, so
    // it does not occlude the wallpaper.
    val launcherBackground = Color.Black
    val headerTextColor   = if (isDayMode) Color(0xFF111111) else accent
    val statusIconColor   = if (isDayMode) Color(0xFF444444) else Color(0xFF666666)
    val controlIconColor  = if (isDayMode) Color(0xFF666666) else Color(0xFF444444)

    var resizingId    by remember { mutableStateOf<String?>(null) }
    var contextMenuId by remember { mutableStateOf<String?>(null) }

    val configuration    = LocalConfiguration.current
    val isLandscape      = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var editMode         by remember { mutableStateOf(false) }
    var widgetLibraryOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {

        // ── Header ──────────────────────────────────────────────────────────
        if (!settings.hideAppHeader) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text          = settings.vehicleName.uppercase(),
                    style         = MaterialTheme.typography.titleLarge,
                    color         = headerTextColor,
                    letterSpacing = 3.sp,
                    fontSize      = 14.sp
                )
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(visible = isWifi, enter = fadeIn(), exit = fadeOut()) {
                    Icon(Icons.Default.Wifi, stringResource(R.string.wifi), tint = statusIconColor, modifier = Modifier.size(16.dp))
                }
                if (isWifi) Spacer(Modifier.width(6.dp))
                AnimatedVisibility(visible = isData, enter = fadeIn(), exit = fadeOut()) {
                    Icon(Icons.Default.SignalCellularAlt, stringResource(R.string.mobile_data), tint = statusIconColor, modifier = Modifier.size(16.dp))
                }
                if (isLandscape) {
                    Spacer(Modifier.width(8.dp))
                    if (editMode) {
                        IconButton(
                            onClick  = { widgetLibraryOpen = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector        = Icons.Default.Dashboard,
                                contentDescription = stringResource(R.string.widget_library),
                                tint               = controlIconColor,
                                modifier           = Modifier.size(15.dp)
                            )
                        }
                        Spacer(Modifier.width(2.dp))
                    }
                    IconButton(
                        onClick  = { editMode = !editMode },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector        = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.edit_widgets),
                            tint               = if (editMode) accent else controlIconColor,
                            modifier           = Modifier.size(15.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF141414))
        }

        // ── Widget Grid ─────────────────────────────────────────────────────
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(gap)
        ) {
            val cellW = (maxWidth  - gap * (GRID_COLS - 1)) / GRID_COLS
            val cellH = (maxHeight - gap * (GRID_ROWS - 1)) / GRID_ROWS
            val density = LocalDensity.current
            val cellStepXPx = with(density) { (cellW + gap).toPx() }
            val cellStepYPx = with(density) { (cellH + gap).toPx() }

            // WEATHER stays in the set even with no data: the commit path
            // (LauncherViewModel.moveWidgetConfig) computes against settings flags
            // only, so dropping it here would make the drop ghost and the committed
            // layout disagree. With no data the cell renders fully transparent.
            val visibleIds = buildSet {
                if (settings.showClock) add("CLOCK")
                if (settings.showWeather) add("WEATHER")
                if (settings.showNowPlaying) add("NOW_PLAYING")
                if (settings.showTelemetry) add("TELEMETRY")
                if (settings.showAltimeter) add("ALTIMETER")
                if (settings.showSpeedometer) add("SPEEDOMETER")
                if (settings.showVitals) add("VITALS")
                if (settings.showTripTracker) add("TRIP_TRACKER")
                if (settings.showSoundboard) add("SOUNDBOARD")
                if (settings.showPip) add("PIP")
            }

            // Keep only visible widgets exactly as configured in settings, allowing explicit resizing to dictate layout
            val visible = settings.widgetLayout.filter { it.enabled && it.id in visibleIds }
            val rendered = visible

            // ── Drag state ───────────────────────────────────────────────────
            var draggingId   by remember { mutableStateOf<String?>(null) }
            var dragOffsetPx by remember { mutableStateOf(Offset.Zero) }

            // ── Edge-resize state (drag a widget's own handles instead of the dialog) ──
            var resizeDragId       by remember { mutableStateOf<String?>(null) }
            var resizeDragOffsetPx by remember { mutableStateOf(Offset.Zero) }
            var resizeDragEdge     by remember { mutableStateOf<ResizeEdge?>(null) }

            // Compute snap target for the widget being dragged (uses original spanX)
            val draggingOriginal = if (draggingId != null) visible.find { it.id == draggingId } else null
            val targetGridX = draggingOriginal?.let {
                (it.gridX + (dragOffsetPx.x / cellStepXPx).roundToInt()).coerceIn(0, GRID_COLS - it.spanX)
            }
            val targetGridY = draggingOriginal?.let {
                (it.gridY + (dragOffsetPx.y / cellStepYPx).roundToInt()).coerceIn(0, GRID_ROWS - it.spanY)
            }

            // Compute proposed layout (push preview) while dragging
            val proposedLayout = if (draggingOriginal != null && targetGridX != null && targetGridY != null)
                computeWidgetMove(visible, draggingOriginal.id, targetGridX, targetGridY)
            else null

            // Live target span for whichever widget is being edge-resized
            val resizingOriginal = if (resizeDragId != null) visible.find { it.id == resizeDragId } else null
            val liveResizeSpanX = resizingOriginal?.let {
                val deltaCells = if (resizeDragEdge == ResizeEdge.BOTTOM) 0
                    else (resizeDragOffsetPx.x / cellStepXPx).roundToInt()
                (it.spanX + deltaCells).coerceIn(1, GRID_COLS - it.gridX)
            }
            val liveResizeSpanY = resizingOriginal?.let {
                val deltaCells = if (resizeDragEdge == ResizeEdge.RIGHT) 0
                    else (resizeDragOffsetPx.y / cellStepYPx).roundToInt()
                (it.spanY + deltaCells).coerceIn(1, GRID_ROWS - it.gridY)
            }

            // Grid overlay — shows every cell while dragging/resizing so the
            // user can see exactly where a widget will land or how big it'll be
            if (draggingId != null || resizeDragId != null) {
                val cellWPx  = with(density) { cellW.toPx() }
                val cellHPx  = with(density) { cellH.toPx() }
                val stepXPx  = cellStepXPx
                val stepYPx  = cellStepYPx
                val cornerPx = with(density) { 12.dp.toPx() }
                val strokePx = with(density) { 1.dp.toPx() }
                Canvas(modifier = Modifier.matchParentSize()) {
                    for (r in 0 until GRID_ROWS) for (c in 0 until GRID_COLS) {
                        drawRoundRect(
                            color      = Color.White.copy(alpha = 0.10f),
                            topLeft    = Offset(stepXPx * c, stepYPx * r),
                            size       = Size(cellWPx, cellHPx),
                            cornerRadius = CornerRadius(cornerPx),
                            style      = Stroke(width = strokePx)
                        )
                    }
                }
            }

            // Resize ghost — target footprint while dragging an edge/corner handle
            if (resizingOriginal != null && liveResizeSpanX != null && liveResizeSpanY != null) {
                val rX = (cellW + gap) * resizingOriginal.gridX
                val rY = (cellH + gap) * resizingOriginal.gridY
                val rW = cellW * liveResizeSpanX + gap * (liveResizeSpanX - 1)
                val rH = cellH * liveResizeSpanY + gap * (liveResizeSpanY - 1)
                Box(
                    modifier = Modifier
                        .absoluteOffset(x = rX, y = rY)
                        .size(rW, rH)
                        .background(accent.copy(alpha = 0.08f))
                        .border(1.dp, accent.copy(alpha = 0.5f), WIDGET_RADIUS)
                )
            }

            // Drop ghost — rendered before widgets so it appears beneath them
            if (draggingOriginal != null && targetGridX != null && targetGridY != null) {
                val gX = (cellW + gap) * targetGridX
                val gY = (cellH + gap) * targetGridY
                val gW = cellW * draggingOriginal.spanX + gap * (draggingOriginal.spanX - 1)
                val gH = cellH * draggingOriginal.spanY + gap * (draggingOriginal.spanY - 1)
                Box(
                    modifier = Modifier
                        .absoluteOffset(x = gX, y = gY)
                        .size(gW, gH)
                        .background(accent.copy(alpha = 0.08f))
                        .border(1.dp, accent.copy(alpha = 0.5f), WIDGET_RADIUS)
                )
            }

            // Displacement ghosts — show where pushed widgets will land
            if (proposedLayout != null && draggingOriginal != null) {
                proposedLayout
                    .filter { it.id != draggingOriginal.id }
                    .forEach { proposed ->
                        val original = visible.find { it.id == proposed.id } ?: return@forEach
                        if (proposed.gridX != original.gridX || proposed.gridY != original.gridY) {
                            val dX = (cellW + gap) * proposed.gridX
                            val dY = (cellH + gap) * proposed.gridY
                            val dW = cellW * proposed.spanX + gap * (proposed.spanX - 1)
                            val dH = cellH * proposed.spanY + gap * (proposed.spanY - 1)
                            Box(
                                modifier = Modifier
                                    .absoluteOffset(x = dX, y = dY)
                                    .size(dW, dH)
                                    .border(1.dp, Color.White.copy(alpha = 0.25f), WIDGET_RADIUS)
                            )
                        }
                    }
            }

            rendered.forEach { w ->
              // Without an explicit key, Compose tracks each item by its
              // position in this forEach rather than by widget id. If the
              // underlying list order changes between recompositions (e.g.
              // after a move/resize re-sorts widgetLayout), a Box — along with
              // its remembered pointerInput/gesture state — can get silently
              // reused for a DIFFERENT widget than the one it was built for.
              // That's exactly how dragging WEATHER's resize handle could end
              // up mutating NOW_PLAYING: the handle's onDragEnd closure still
              // captured the widget that used to occupy this slot.
              key(w.id) {
                val xOff   = (cellW + gap) * w.gridX
                val yOff   = (cellH + gap) * w.gridY
                val width  = cellW * w.spanX + gap * (w.spanX - 1)
                val height = cellH * w.spanY + gap * (w.spanY - 1)

                val label = when (w.id) {
                    "CLOCK"       -> clockTimeLabel(Calendar.getInstance())
                    "WEATHER"     -> stringResource(R.string.widget_weather)
                    "NOW_PLAYING" -> stringResource(R.string.widget_now_playing)
                    "TELEMETRY"   -> stringResource(R.string.widget_compass)
                    "ALTIMETER"   -> stringResource(R.string.widget_altimeter)
                    "SPEEDOMETER" -> stringResource(R.string.widget_speed)
                    "TRIP_TRACKER" -> stringResource(R.string.widget_trip)
                    "SOUNDBOARD"  -> stringResource(R.string.widget_sound)
                    "PIP"         -> stringResource(R.string.widget_pip)
                    else          -> w.id
                }

                // Original (pre-auto-expand) spanX needed for drag boundary clamping
                val origSpanX  = visible.find { it.id == w.id }?.spanX ?: 1
                val isDragging = draggingId == w.id
                // Weather with no data reserves its cell but draws nothing
                // (still visible in edit mode so it can be moved/removed)
                val isGhost    = w.id == "WEATHER" && weather == null && !editMode
                val isPip      = w.id == "PIP"
                val dragDpX    = if (isDragging) with(density) { dragOffsetPx.x.toDp() } else 0.dp
                val dragDpY    = if (isDragging) with(density) { dragOffsetPx.y.toDp() } else 0.dp

                @OptIn(ExperimentalFoundationApi::class)
                Box(
                    modifier = Modifier
                        .absoluteOffset(x = xOff + dragDpX, y = yOff + dragDpY)
                        .size(width, height)
                        .zIndex(if (isDragging) 1f else 0f)
                        .clip(WIDGET_RADIUS)
                        .background(if (isGhost || isPip) Color.Transparent else widgetBg)
                        .border(
                            width = if (editMode) 1.5.dp else 1.dp,
                            color = when {
                                editMode -> accent.copy(alpha = 0.45f)
                                isPip    -> Color.Transparent
                                isGhost  -> Color.Transparent
                                else     -> widgetBorder
                            },
                            shape = WIDGET_RADIUS
                        )
                        .combinedClickable(
                            indication        = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick           = { if (editMode) contextMenuId = w.id },
                            onLongClick       = { if (!editMode) contextMenuId = w.id }
                        )
                        .then(
                            if (editMode) Modifier.pointerInput(editMode, w.id, w.gridX, w.gridY) {
                                var hasSignificantDrag = false
                                // Touch-slop gate: without it, sub-pixel jitter during a
                                // long-press counts as a drag and the context menu never opens
                                val slop = viewConfiguration.touchSlop
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { _ ->
                                        draggingId         = w.id
                                        dragOffsetPx       = Offset.Zero
                                        hasSignificantDrag = false
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffsetPx      += dragAmount
                                        if (!hasSignificantDrag && dragOffsetPx.getDistance() > slop) {
                                            hasSignificantDrag = true
                                        }
                                    },
                                    onDragEnd = {
                                        if (hasSignificantDrag) {
                                            val newX = (w.gridX + (dragOffsetPx.x / cellStepXPx).roundToInt())
                                                .coerceIn(0, GRID_COLS - origSpanX)
                                            val newY = (w.gridY + (dragOffsetPx.y / cellStepYPx).roundToInt())
                                                .coerceIn(0, GRID_ROWS - w.spanY)
                                            onMoveWidget(w.id, newX, newY)
                                        } else {
                                            contextMenuId = w.id
                                        }
                                        draggingId   = null
                                        dragOffsetPx = Offset.Zero
                                    },
                                    onDragCancel = {
                                        draggingId   = null
                                        dragOffsetPx = Offset.Zero
                                    }
                                )
                            } else Modifier
                        )
                ) {
                    when (w.id) {
                        "CLOCK" -> ClockWidget(
                            style      = settings.clockStyle,
                            accent     = accent,
                            isDayMode  = isDayMode,
                            modifier   = Modifier.fillMaxSize()
                        )
                        "WEATHER" -> WeatherWidget(
                            state      = weather,
                            accent     = accent,
                            metric     = settings.unitSystem.name == "METRIC",
                            isDayMode  = isDayMode,
                            modifier   = Modifier.fillMaxSize()
                        )
                        "NOW_PLAYING" -> NowPlayingWidget(
                            state               = nowPlaying,
                            accent              = accent,
                            carPlayPackage      = settings.carPlayPackage,
                            androidAutoPackage  = settings.androidAutoPackage,
                            onPlayPause         = onPlayPause,
                            onNext              = onNext,
                            onPrev              = onPrev,
                            onLaunchCarPlay     = onLaunchCarPlay,
                            onLaunchAndroidAuto = onLaunchAndroidAuto,
                            onTapToOpenApp      = onTapNowPlaying,
                            modifier            = Modifier.fillMaxSize(),
                            isEditing           = editMode,
                            isDayMode           = isDayMode,
                            hardwareRadio         = hardwareRadio,
                            onLaunchHardwareRadio = onLaunchHardwareRadio,
                            onStopHardwareRadio   = onStopHardwareRadio,
                            onRadioSeekUp         = onRadioSeekUp,
                            onRadioSeekDown       = onRadioSeekDown,
                            onRadioCycleFm        = onRadioCycleFm,
                            onRadioSwitchAm       = onRadioSwitchAm,
                            onRadioTune           = onRadioTune,
                            onAssignRadio         = onAssignRadio
                        )
                        "TELEMETRY" -> TelemetryWidget(
                            location  = location,
                            bearing   = (bearing + settings.compassOffset + 360f) % 360f,
                            accent    = accent,
                            isDayMode = isDayMode,
                            modifier  = Modifier.fillMaxSize()
                        )
                        "ALTIMETER" -> AltimeterWidget(
                            location  = location,
                            isMetric  = settings.unitSystem == com.openlauncher.app.data.UnitSystem.METRIC,
                            accent    = accent,
                            isDayMode = isDayMode,
                            modifier  = Modifier.fillMaxSize()
                        )
                        "SPEEDOMETER" -> SpeedometerWidget(
                            location  = location,
                            isMetric  = settings.unitSystem == com.openlauncher.app.data.UnitSystem.METRIC,
                            accent    = accent,
                            isDayMode = isDayMode,
                            digitalOnly = settings.speedometerDigitalOnly,
                            modifier  = Modifier.fillMaxSize()
                        )
                        "VITALS" -> VitalsWidget(
                            accent    = accent,
                            isDayMode = isDayMode,
                            asBars    = settings.vitalsAsBars,
                            modifier  = Modifier.fillMaxSize()
                        )
                        "TRIP_TRACKER" -> TripTrackerWidget(
                            location  = location,
                            isMetric  = settings.unitSystem == com.openlauncher.app.data.UnitSystem.METRIC,
                            accent    = accent,
                            isDayMode = isDayMode,
                            modifier  = Modifier.fillMaxSize()
                        )
                        "SOUNDBOARD" -> SoundboardWidget(
                            pads      = settings.soundboardPads,
                            accent    = accent,
                            isDayMode = isDayMode,
                            isEditing = editMode,
                            onUpdatePad = onUpdateSoundPad,
                            modifier  = Modifier.fillMaxSize()
                        )
                        "PIP" -> PipWidget(
                            packageNames   = settings.pipAppPackages,
                            appCount       = settings.pipAppCount,
                            splitFraction  = settings.pipPaneSplit,
                            splitFraction2 = settings.pipPaneSplit2,
                            accent         = accent,
                            launcherBackground = launcherBackground,
                            isDayMode      = isDayMode,
                            isActive       = isActive,
                            isEditing      = editMode,
                            onAssign       = onAssignPip,
                            onSplitChange  = onSetPipSplit,
                            onSplitChange2 = onSetPipSplit2,
                            onSwap         = onSwapPipApps,
                            paneOrder      = settings.pipPaneOrder,
                            modifier       = Modifier.fillMaxSize()
                        )
                    }

                    // Label — hide when album art fills the widget background,
                    // and always for PIP (the embedded apps' own UI already
                    // fills the tile; a "PIP" caption in the corner is just noise).
                    val labelColor = when {
                        isGhost -> Color.Transparent
                        isPip -> Color.Transparent
                        w.id == "NOW_PLAYING" && nowPlaying?.albumArt != null && nowPlaying.title.isNotEmpty() -> Color.Transparent
                        isDayMode -> Color(0xFF999999)
                        else      -> Color(0xFF3A3A3A)
                    }
                    Text(
                        text          = label,
                        style         = MaterialTheme.typography.labelSmall,
                        color         = labelColor,
                        letterSpacing = 2.sp,
                        fontSize      = 8.sp,
                        modifier      = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 10.dp, top = 7.dp)
                    )

                    // Edge/corner resize handles — drag directly instead of the +/- dialog
                    if (editMode && !isDragging) {
                        fun commitResize(dx: Boolean, dy: Boolean) {
                            val newSpanX = if (dx)
                                (w.spanX + (resizeDragOffsetPx.x / cellStepXPx).roundToInt())
                                    .coerceIn(1, GRID_COLS - w.gridX)
                            else w.spanX
                            val newSpanY = if (dy)
                                (w.spanY + (resizeDragOffsetPx.y / cellStepYPx).roundToInt())
                                    .coerceIn(1, GRID_ROWS - w.gridY)
                            else w.spanY
                            android.util.Log.d(
                                "WidgetResize",
                                "commit id=${w.id} activeDragId=$resizeDragId edge=$resizeDragEdge " +
                                    "from=(${w.spanX},${w.spanY}) to=($newSpanX,$newSpanY) offsetPx=$resizeDragOffsetPx"
                            )
                            onUpdateWidget(w.id, newSpanX, newSpanY)
                            resizeDragId     = null
                            resizeDragOffsetPx = Offset.Zero
                            resizeDragEdge   = null
                        }
                        ResizeHandle(
                            edge     = ResizeEdge.RIGHT,
                            accent   = accent,
                            modifier = Modifier.align(Alignment.CenterEnd),
                            onDragStart = {
                                android.util.Log.d("WidgetResize", "start id=${w.id} edge=RIGHT")
                                resizeDragId = w.id; resizeDragOffsetPx = Offset.Zero; resizeDragEdge = ResizeEdge.RIGHT
                            },
                            onDrag      = { resizeDragOffsetPx += it },
                            onDragEnd   = { commitResize(dx = true, dy = false) }
                        )
                        ResizeHandle(
                            edge     = ResizeEdge.BOTTOM,
                            accent   = accent,
                            modifier = Modifier.align(Alignment.BottomCenter),
                            onDragStart = {
                                android.util.Log.d("WidgetResize", "start id=${w.id} edge=BOTTOM")
                                resizeDragId = w.id; resizeDragOffsetPx = Offset.Zero; resizeDragEdge = ResizeEdge.BOTTOM
                            },
                            onDrag      = { resizeDragOffsetPx += it },
                            onDragEnd   = { commitResize(dx = false, dy = true) }
                        )
                        ResizeHandle(
                            edge     = ResizeEdge.CORNER,
                            accent   = accent,
                            modifier = Modifier.align(Alignment.BottomEnd),
                            onDragStart = {
                                android.util.Log.d("WidgetResize", "start id=${w.id} edge=CORNER")
                                resizeDragId = w.id; resizeDragOffsetPx = Offset.Zero; resizeDragEdge = ResizeEdge.CORNER
                            },
                            onDrag      = { resizeDragOffsetPx += it },
                            onDragEnd   = { commitResize(dx = true, dy = true) }
                        )
                    }
                }
              }
            }
        }
    }

    // ── Widget context menu (long-press any cell) ────────────────────────────
    contextMenuId?.let { id ->
        WidgetContextMenu(
            widgetId            = id,
            accent              = accent,
            clockStyle          = settings.clockStyle,
            vitalsAsBars        = settings.vitalsAsBars,
            speedometerDigitalOnly = settings.speedometerDigitalOnly,
            carPlayPackage      = settings.carPlayPackage,
            androidAutoPackage  = settings.androidAutoPackage,
            pipAppPackages      = settings.pipAppPackages,
            pipAppCount         = settings.pipAppCount,
            isDayMode           = isDayMode,
            onResize            = { contextMenuId = null; resizingId = id },
            onAssignCarPlay     = { contextMenuId = null; onAssignCarPlay() },
            onAssignAndroidAuto = { contextMenuId = null; onAssignAndroidAuto() },
            onClearCarPlay      = { contextMenuId = null; onClearCarPlay() },
            onClearAndroidAuto  = { contextMenuId = null; onClearAndroidAuto() },
            onAssignPip         = { slot -> contextMenuId = null; onAssignPip(slot) },
            onClearPip          = { slot -> contextMenuId = null; onClearPip(slot) },
            onSetPipAppCount    = { onSetPipAppCount(it) },
            onSetClockStyle     = { onSetClockStyle(it) },
            onSetVitalsAsBars   = { onSetVitalsAsBars(it) },
            onSetSpeedometerDigitalOnly = { onSetSpeedometerDigitalOnly(it) },
            onDismiss           = { contextMenuId = null }
        )
    }

    // ── Resize dialog ────────────────────────────────────────────────────────
    resizingId?.let { id ->
        val config = settings.widgetLayout.find { it.id == id }
        if (config != null) {
            WidgetResizeDialog(
                config    = config,
                accent    = accent,
                isDayMode = isDayMode,
                onDismiss = { resizingId = null },
                onConfirm = { sx, sy ->
                    onUpdateWidget(id, sx, sy)
                    resizingId = null
                }
            )
        }
    }

    // ── Widget library ────────────────────────────────────────────────────────
    if (widgetLibraryOpen) {
        WidgetLibraryDialog(
            settings  = settings,
            accent    = accent,
            isDayMode = isDayMode,
            onAdd     = { id -> onAddWidget(id) },
            onRemove  = { id -> onRemoveWidget(id) },
            onDismiss = { widgetLibraryOpen = false }
        )
    }
}

@Composable
private fun WidgetContextMenu(
    widgetId: String,
    accent: Color,
    clockStyle: ClockStyle,
    vitalsAsBars: Boolean,
    speedometerDigitalOnly: Boolean,
    carPlayPackage: String = "",
    androidAutoPackage: String = "",
    pipAppPackages: List<String> = listOf("", ""),
    pipAppCount: Int = 1,
    isDayMode: Boolean,
    onResize: () -> Unit,
    onAssignCarPlay: () -> Unit,
    onAssignAndroidAuto: () -> Unit,
    onClearCarPlay: () -> Unit,
    onClearAndroidAuto: () -> Unit,
    onAssignPip: (slot: Int) -> Unit,
    onClearPip: (slot: Int) -> Unit,
    onSetPipAppCount: (Int) -> Unit,
    onSetClockStyle: (ClockStyle) -> Unit,
    onSetVitalsAsBars: (Boolean) -> Unit,
    onSetSpeedometerDigitalOnly: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val menuBg    = if (isDayMode) Color(0xFFFFFFFF) else Color(0xFF111111)
    val menuBorder = if (isDayMode) Color(0xFFDDE1E5) else Color(0xFF1E1E1E)
    val menuDivider = if (isDayMode) Color(0xFFF1F3F5) else Color(0xFF1A1A1A)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(menuBg)
                .border(1.dp, menuBorder, RoundedCornerShape(4.dp))
                .padding(vertical = 4.dp)
                .width(200.dp)
        ) {
            val inactiveMenuTint = if (isDayMode) Color(0xFF777777) else Color(0xFF555555)
            ContextRow(stringResource(R.string.resize), Icons.Default.OpenWith, accent, onResize, isDayMode = isDayMode)
            if (widgetId == "CLOCK") {
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = stringResource(R.string.digital),
                    icon    = Icons.Default.Schedule,
                    tint    = if (clockStyle == ClockStyle.DIGITAL) accent else inactiveMenuTint,
                    onClick = { onSetClockStyle(ClockStyle.DIGITAL); onDismiss() },
                    isDayMode = isDayMode
                )
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = stringResource(R.string.analog),
                    icon    = Icons.Default.Watch,
                    tint    = if (clockStyle == ClockStyle.ANALOG) accent else inactiveMenuTint,
                    onClick = { onSetClockStyle(ClockStyle.ANALOG); onDismiss() },
                    isDayMode = isDayMode
                )
            }
            if (widgetId == "VITALS") {
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = stringResource(R.string.dial_gauges),
                    icon    = Icons.Default.Adjust,
                    tint    = if (!vitalsAsBars) accent else inactiveMenuTint,
                    onClick = { onSetVitalsAsBars(false); onDismiss() },
                    isDayMode = isDayMode
                )
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = stringResource(R.string.bars_view),
                    icon    = Icons.Default.FormatAlignLeft,
                    tint    = if (vitalsAsBars) accent else inactiveMenuTint,
                    onClick = { onSetVitalsAsBars(true); onDismiss() },
                    isDayMode = isDayMode
                )
            }
            if (widgetId == "SPEEDOMETER") {
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = stringResource(R.string.dial_track),
                    icon    = Icons.Default.Speed,
                    tint    = if (!speedometerDigitalOnly) accent else inactiveMenuTint,
                    onClick = { onSetSpeedometerDigitalOnly(false); onDismiss() },
                    isDayMode = isDayMode
                )
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = stringResource(R.string.digital_only),
                    icon    = Icons.Default.Dialpad,
                    tint    = if (speedometerDigitalOnly) accent else inactiveMenuTint,
                    onClick = { onSetSpeedometerDigitalOnly(true); onDismiss() },
                    isDayMode = isDayMode
                )
            }
            if (widgetId == "NOW_PLAYING") {
                HorizontalDivider(color = menuDivider)
                ContextRow(stringResource(R.string.assign_carplay_app), Icons.Default.PhoneAndroid, accent, onAssignCarPlay, isDayMode = isDayMode)
                if (carPlayPackage.isNotEmpty()) {
                    HorizontalDivider(color = menuDivider)
                    ContextRow(stringResource(R.string.clear_carplay_app), Icons.Default.PhoneAndroid, Color(0xFF884444), onClearCarPlay, isDayMode = isDayMode)
                }
                HorizontalDivider(color = menuDivider)
                ContextRow(stringResource(R.string.assign_android_auto_app), Icons.Default.DirectionsCar, accent, onAssignAndroidAuto, isDayMode = isDayMode)
                if (androidAutoPackage.isNotEmpty()) {
                    HorizontalDivider(color = menuDivider)
                    ContextRow(stringResource(R.string.clear_android_auto_app), Icons.Default.DirectionsCar, Color(0xFF884444), onClearAndroidAuto, isDayMode = isDayMode)
                }
            }
            if (widgetId == "PIP") {
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = pluralStringResource(R.plurals.app_count, 1, 1),
                    icon    = Icons.Default.PictureInPicture,
                    tint    = if (pipAppCount == 1) accent else inactiveMenuTint,
                    onClick = { onSetPipAppCount(1) },
                    isDayMode = isDayMode
                )
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = pluralStringResource(R.plurals.app_count, 2, 2),
                    icon    = Icons.Default.PictureInPicture,
                    tint    = if (pipAppCount == 2) accent else inactiveMenuTint,
                    onClick = { onSetPipAppCount(2) },
                    isDayMode = isDayMode
                )
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = pluralStringResource(R.plurals.app_count, 3, 3),
                    icon    = Icons.Default.PictureInPicture,
                    tint    = if (pipAppCount == 3) accent else inactiveMenuTint,
                    onClick = { onSetPipAppCount(3) },
                    isDayMode = isDayMode
                )
                HorizontalDivider(color = menuDivider)
                ContextRow(stringResource(R.string.assign_pip_app, 1), Icons.Default.PictureInPicture, accent, { onAssignPip(0) }, isDayMode = isDayMode)
                if (pipAppPackages.getOrElse(0) { "" }.isNotEmpty()) {
                    HorizontalDivider(color = menuDivider)
                    ContextRow(stringResource(R.string.clear_pip_app, 1), Icons.Default.PictureInPicture, Color(0xFF884444), { onClearPip(0) }, isDayMode = isDayMode)
                }
                if (pipAppCount >= 2) {
                    HorizontalDivider(color = menuDivider)
                    ContextRow(stringResource(R.string.assign_pip_app, 2), Icons.Default.PictureInPicture, accent, { onAssignPip(1) }, isDayMode = isDayMode)
                    if (pipAppPackages.getOrElse(1) { "" }.isNotEmpty()) {
                        HorizontalDivider(color = menuDivider)
                        ContextRow(stringResource(R.string.clear_pip_app, 2), Icons.Default.PictureInPicture, Color(0xFF884444), { onClearPip(1) }, isDayMode = isDayMode)
                    }
                }
                if (pipAppCount >= 3) {
                    HorizontalDivider(color = menuDivider)
                    ContextRow(stringResource(R.string.assign_pip_app, 3), Icons.Default.PictureInPicture, accent, { onAssignPip(2) }, isDayMode = isDayMode)
                    if (pipAppPackages.getOrElse(2) { "" }.isNotEmpty()) {
                        HorizontalDivider(color = menuDivider)
                        ContextRow(stringResource(R.string.clear_pip_app, 3), Icons.Default.PictureInPicture, Color(0xFF884444), { onClearPip(2) }, isDayMode = isDayMode)
                    }
                }
            }

        }
    }
}

@Composable
private fun ContextRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
    isDayMode: Boolean = false
) {
    val finalTint = if (isDayMode) {
        if (tint == Color(0xFF884444)) {
            tint
        } else if (tint == Color(0xFF777777)) {
            Color(0xFF888888)
        } else {
            Color(0xFF111111)
        }
    } else {
        tint
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = finalTint, modifier = Modifier.size(16.dp))
        Text(label, color = finalTint, fontSize = 10.sp, letterSpacing = 1.sp)
    }
}

/** A small drag grip on a widget's edge/corner, for direct resize-by-drag in edit mode. */
@Composable
private fun ResizeHandle(
    edge: ResizeEdge,
    accent: Color,
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit
) {
    val gripColor = accent.copy(alpha = 0.75f)
    val hitTarget = modifier
        .pointerInput(edge) {
            detectDragGestures(
                onDragStart = { onDragStart() },
                onDrag      = { change, dragAmount -> change.consume(); onDrag(dragAmount) },
                onDragEnd   = { onDragEnd() },
                onDragCancel = { onDragEnd() }
            )
        }
    when (edge) {
        ResizeEdge.RIGHT -> Box(
            modifier = hitTarget.size(width = 24.dp, height = 44.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(width = 4.dp, height = 28.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(gripColor)
            )
        }
        ResizeEdge.BOTTOM -> Box(
            modifier = hitTarget.size(width = 44.dp, height = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(width = 28.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(gripColor)
            )
        }
        ResizeEdge.CORNER -> Box(
            modifier = hitTarget.size(28.dp).padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(11.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(gripColor)
                    .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
            )
        }
    }
}

@Composable
private fun WidgetResizeDialog(
    config: WidgetConfig,
    accent: Color,
    isDayMode: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (spanX: Int, spanY: Int) -> Unit
) {
    var spanX by remember { mutableStateOf(config.spanX) }
    var spanY by remember { mutableStateOf(config.spanY) }

    val maxSpanX = GRID_COLS - config.gridX
    val maxSpanY = GRID_ROWS - config.gridY

    val dialogBg     = if (isDayMode) Color(0xFFFFFFFF) else MaterialTheme.colorScheme.background
    val dialogText   = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
    val cancelColor  = if (isDayMode) Color(0xFF6C757D) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text          = config.id.replace('_', ' '),
                color         = dialogText,
                fontSize      = 11.sp,
                letterSpacing = 2.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                SpanRow(label = stringResource(R.string.width), value = spanX, min = 1, max = maxSpanX, accent = accent, isDayMode = isDayMode) { spanX = it }
                SpanRow(label = stringResource(R.string.height), value = spanY, min = 1, max = maxSpanY, accent = accent, isDayMode = isDayMode) { spanY = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(spanX, spanY) }) {
                Text(stringResource(R.string.apply), color = accent, fontSize = 11.sp, letterSpacing = 1.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = cancelColor, fontSize = 11.sp, letterSpacing = 1.sp)
            }
        },
        containerColor    = dialogBg,
        titleContentColor = dialogText,
        textContentColor  = dialogText
    )
}

@Composable
private fun SpanRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    accent: Color,
    isDayMode: Boolean,
    onChange: (Int) -> Unit
) {
    val textColor   = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
    val dimColor    = if (isDayMode) Color(0xFF495057) else Color(0xFF666666)
    val disabledC   = if (isDayMode) Color(0xFFCED4DA) else Color(0xFF333333)
    val inactiveBg  = if (isDayMode) Color(0xFFE9ECEF) else Color(0xFF2A2A2A)
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text          = label,
            color         = dimColor,
            fontSize      = 10.sp,
            letterSpacing = 1.sp,
            modifier      = Modifier.width(52.dp)
        )
        IconButton(
            onClick  = { if (value > min) onChange(value - 1) },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.Remove, null,
                tint     = if (value > min) textColor else disabledC,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text      = stringResource(R.string.integer_value, value),
            color     = textColor,
            fontSize  = 16.sp,
            textAlign = TextAlign.Center,
            modifier  = Modifier.width(24.dp)
        )
        IconButton(
            onClick  = { if (value < max) onChange(value + 1) },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.Add, null,
                tint     = if (value < max) accent else disabledC,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(max) { i ->
                Box(
                    modifier = Modifier
                        .size(width = 14.dp, height = 10.dp)
                        .background(
                            if (i < value) accent.copy(alpha = 0.7f) else inactiveBg,
                            RoundedCornerShape(1.dp)
                        )
                )
            }
        }
    }
}

// ── Widget Library ────────────────────────────────────────────────────────────

@Composable
private fun WidgetLibraryDialog(
    settings: AppSettings,
    accent: Color,
    isDayMode: Boolean,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val dialogBg    = if (isDayMode) Color(0xFFEEEEEE) else Color(0xFF0C0C0C)
    val dialogBorder = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF1E1E1E)
    val titleColor  = if (isDayMode) Color(0xFF495057) else Color(0xFF555555)
    val closeColor  = if (isDayMode) Color(0xFF495057) else Color(0xFF444444)

    val activeIds = buildSet {
        if (settings.showClock) add("CLOCK")
        if (settings.showWeather) add("WEATHER")
        if (settings.showNowPlaying) add("NOW_PLAYING")
        if (settings.showTelemetry) add("TELEMETRY")
        if (settings.showAltimeter) add("ALTIMETER")
        if (settings.showSpeedometer) add("SPEEDOMETER")
        if (settings.showVitals) add("VITALS")
        if (settings.showTripTracker) add("TRIP_TRACKER")
        if (settings.showSoundboard) add("SOUNDBOARD")
        if (settings.showPip) add("PIP")
    }
    val canAdd = canAddWidget(settings)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(dialogBg)
                .border(1.dp, dialogBorder, RoundedCornerShape(4.dp))
                .padding(16.dp)
                .widthIn(min = 320.dp, max = 520.dp)
        ) {
            Row(
                modifier          = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text          = stringResource(R.string.widget_library),
                    color         = titleColor,
                    fontSize      = 9.sp,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, null, tint = closeColor, modifier = Modifier.size(14.dp))
                }
            }

            LazyVerticalGrid(
                columns               = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement   = Arrangement.spacedBy(6.dp),
                modifier              = Modifier.fillMaxWidth()
            ) {
                items(ALL_WIDGET_TYPES) { info ->
                    val isActive = info.id in activeIds
                    WidgetLibraryCard(
                        info     = info,
                        isActive = isActive,
                        canAdd   = canAdd,
                        accent   = accent,
                        isDayMode = isDayMode,
                        onToggle = { if (isActive) onRemove(info.id) else onAdd(info.id) }
                    )
                }
            }

            if (!canAdd) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text          = pluralStringResource(
                        R.plurals.all_widget_cells_occupied,
                        GRID_COLS * GRID_ROWS,
                        GRID_COLS * GRID_ROWS
                    ),
                    color         = if (isDayMode) Color(0xFFE03131) else Color(0xFF3A3A3A),
                    fontSize      = 8.sp,
                    letterSpacing = 1.sp,
                    modifier      = Modifier.fillMaxWidth(),
                    textAlign     = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun WidgetLibraryCard(
    info: WidgetTypeInfo,
    isActive: Boolean,
    canAdd: Boolean,
    accent: Color,
    isDayMode: Boolean,
    onToggle: () -> Unit
) {
    val enabled    = isActive || canAdd
    val cardBorder = if (isActive) accent else if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF1A1A1A)
    val cardBg     = if (isActive) accent.copy(alpha = 0.15f) else if (isDayMode) Color(0xFFFFFFFF) else Color(0xFF0E0E0E)
    val iconTint   = if (isActive) accent else if (isDayMode) Color(0xFF495057) else Color(0xFF333333)
    val labelColor = if (isActive) accent else if (isDayMode) Color(0xFF212529) else Color(0xFF3A3A3A)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(info.icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(5.dp))
        Text(
            text          = stringResource(info.labelRes),
            color         = labelColor,
            fontSize      = 7.sp,
            letterSpacing = 1.sp,
            textAlign     = TextAlign.Center,
            maxLines      = 2,
            lineHeight    = 9.sp
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text          = when {
                isActive -> stringResource(R.string.active)
                !canAdd  -> stringResource(R.string.full)
                else     -> stringResource(R.string.add)
            },
            color         = when {
                isActive -> accent.copy(alpha = 0.75f)
                !canAdd  -> if (isDayMode) Color(0xFFADB5BD) else Color(0xFF282828)
                else     -> if (isDayMode) Color(0xFF495057) else Color(0xFF3A3A3A)
            },
            fontSize      = 6.sp,
            letterSpacing = 1.sp,
            textAlign     = TextAlign.Center
        )
    }
}
