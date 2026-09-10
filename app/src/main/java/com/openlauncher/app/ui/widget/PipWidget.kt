package com.openlauncher.app.ui.widget

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import com.openlauncher.app.ui.theme.PaneBorderWidth
import com.openlauncher.app.ui.theme.paneDividerGrip
import com.openlauncher.app.ui.theme.PaneShape
import com.openlauncher.app.ui.theme.paneOuterStroke
import com.openlauncher.app.ui.theme.paneSurface
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PANE_SPLIT_RANGE = 0.15f..0.85f
private val DIVIDER_LAYOUT_WIDTH = 10.dp
private val DIVIDER_TOUCH_WIDTH = 24.dp
private val DIVIDER_GRIP_WIDTH = 14.dp
private val DIVIDER_GRIP_HEIGHT = 64.dp
private const val DIVIDER_INPUT_SETTLE_MS = 180L
private const val SECOND_PANE_LAUNCH_DELAY_MS = 1_500L
private const val THIRD_PANE_LAUNCH_DELAY_MS = 3_000L
private const val APP_REVEAL_DELAY_MS = 1_200L

/**
 * [appCount] 1, 2, or 3 apps side-by-side. With 2, panes are split at
 * [splitFraction] (fraction of the pane area, excluding the divider, given to
 * the first visual position). With 3, a second divider further splits
 * whatever's left over at [splitFraction2] (fraction of the *remaining* area
 * given to the second visual position — the third gets what's left). Drag
 * either divider to resize; tap one to swap its two adjacent panes'
 * positions. [paneOrder] maps visual position -> slot index (default
 * `[0, 1, 2]`, i.e. slot N renders in position N); each slot is
 * independently assignable/clearable regardless of where it currently
 * displays.
 *
 * Apps render via true embedding — a private [VirtualDisplay] piped into a
 * plain [SurfaceView] inside this same view hierarchy — matching how the
 * commercial "lecoauto" launcher does the identical feature (confirmed live,
 * via dumpsys, on our own test device: both embedded apps show up as
 * `mWindowingMode=fullscreen` on their own small virtual display, not
 * freeform). Because the embedded app is just a normal View clipped to
 * whatever bounds Compose gives it — not a separate top-level window —
 * there's no caption to mask, no window-overlap or drag-lock problem.
 *
 * Requires UID 1000 (sharedUserId="android.uid.system", platform-signed).
 * On API 27/28 the target stack is explicitly moved to the VirtualDisplay,
 * matching LeCoAuto without depending on the secondary-display feature flag.
 *
 * Placement, ActivityView-style pointer forwarding, and debounced dual-pane
 * resize are verified on API 28 for the probe app, Maps, and YouTube. Focus,
 * back routing, and task lifecycle remain separate release-hardening work.
 */
@Composable
fun PipWidget(
    packageNames: List<String>,
    appCount: Int,
    splitFraction: Float,
    splitFraction2: Float = 0.5f,
    accent: Color,
    launcherBackground: Color = Color.Black,
    isDayMode: Boolean,
    isActive: Boolean = true,
    isEditing: Boolean,
    onAssign: (slot: Int) -> Unit,
    onSplitChange: (Float) -> Unit,
    onSplitChange2: (Float) -> Unit = {},
    onSwap: (dividerIndex: Int) -> Unit = {},
    paneOrder: List<Int> = listOf(0, 1, 2),
    modifier: Modifier = Modifier
) {
    val pkg0 = packageNames.getOrElse(0) { "" }

    if (appCount <= 1) {
        Box(modifier) {
            PipPane(
                packageName = pkg0,
                inputEnabled = isActive && !isEditing,
                accent = accent,
                isDayMode = isDayMode,
                isEditing = isEditing,
                launchDelayMs = 0L,
                onAssign = { onAssign(0) },
                modifier = Modifier.fillMaxSize()
            )
        }
        return
    }

    val pkg1 = packageNames.getOrElse(1) { "" }
    val pkg2 = packageNames.getOrElse(2) { "" }
    val showThird = appCount >= 3
    val density = LocalDensity.current

    // Live position while a divider is being dragged; null when neither is.
    // Both dividers share this single pair — only one can be dragged at a
    // time — tagged with which divider (0 or 1) owns the current drag so
    // each divider's own preview grip only lights up for its own gesture.
    var previewFraction by remember { mutableStateOf<Float?>(null) }
    var previewDividerIndex by remember { mutableIntStateOf(0) }
    var localCommittedFraction by remember {
        mutableFloatStateOf(splitFraction.coerceIn(PANE_SPLIT_RANGE))
    }
    var localCommittedFraction2 by remember {
        mutableFloatStateOf(splitFraction2.coerceIn(PANE_SPLIT_RANGE))
    }
    var paneInputLocked by remember { mutableStateOf(false) }
    var unlockInputJob by remember { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(splitFraction) {
        if (!(previewFraction != null && previewDividerIndex == 0)) {
            localCommittedFraction = splitFraction.coerceIn(PANE_SPLIT_RANGE)
        }
    }
    LaunchedEffect(splitFraction2) {
        if (!(previewFraction != null && previewDividerIndex == 1)) {
            localCommittedFraction2 = splitFraction2.coerceIn(PANE_SPLIT_RANGE)
        }
    }

    fun beginDrag(dividerIndex: Int, startFraction: Float) {
        unlockInputJob?.cancel()
        paneInputLocked = true
        previewDividerIndex = dividerIndex
        previewFraction = startFraction
    }

    fun endDrag() {
        previewFraction = null
        unlockInputJob = coroutineScope.launch {
            delay(DIVIDER_INPUT_SETTLE_MS)
            paneInputLocked = false
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val dividerCount = if (showThird) 2 else 1
        val paneAreaWidth = maxWidth - DIVIDER_LAYOUT_WIDTH * dividerCount
        val paneAreaWidthPx = with(density) { paneAreaWidth.toPx() }

        val fraction1 = localCommittedFraction.coerceIn(PANE_SPLIT_RANGE)
        val width0 = paneAreaWidth * fraction1
        val remainingAfter0 = paneAreaWidth - width0
        val remainingAfter0Px = with(density) { remainingAfter0.toPx() }

        val fraction2 = localCommittedFraction2.coerceIn(PANE_SPLIT_RANGE)
        val width1 = if (showThird) remainingAfter0 * fraction2 else remainingAfter0
        val width2 = if (showThird) remainingAfter0 - width1 else 0.dp

        val offset0 = 0.dp
        val offset1 = width0 + DIVIDER_LAYOUT_WIDTH
        val offset2 = offset1 + width1 + DIVIDER_LAYOUT_WIDTH

        val positionOffsets = listOf(offset0, offset1, offset2)
        val positionWidths = listOf(width0, width1, width2)

        // slot0/slot1/slot2 are declared here in fixed order, always — never
        // reordered — and only ever positioned via offset()/width(). Reordering
        // which one comes first in a Row (even wrapped in a matching key())
        // still moved the underlying SurfaceView within its real parent
        // ViewGroup, which detaches and reattaches it — triggering a real
        // surfaceDestroyed/surfaceCreated cycle (seen as the embedded app
        // briefly reloading) despite Compose-level state surviving. Swapping
        // only the offset/width — a slot's position and size, not its place
        // in the tree — never touches the View's attachment at all, so
        // paneOrder can freely rearrange all three sides and every app stays
        // on screen the whole time. Adding/removing the third pane (showThird
        // flipping) only ever appends/removes slot2 at the end — it never
        // reorders slot0/slot1 relative to each other either.
        fun positionOf(slot: Int): Int = paneOrder.indexOf(slot).let { if (it >= 0) it else slot }

        Box(Modifier.fillMaxSize()) {
            val pos0 = positionOf(0)
            val pos1 = positionOf(1)

            PipPane(
                packageName = pkg0,
                inputEnabled = isActive && !isEditing && !paneInputLocked,
                accent = accent,
                isDayMode = isDayMode,
                isEditing = isEditing,
                launchDelayMs = 0L,
                onAssign = { onAssign(0) },
                modifier = Modifier
                    .offset(x = positionOffsets[pos0])
                    .width(positionWidths[pos0])
                    .fillMaxHeight()
            )
            PipPane(
                packageName = pkg1,
                inputEnabled = isActive && !isEditing && !paneInputLocked,
                accent = accent,
                isDayMode = isDayMode,
                isEditing = isEditing,
                launchDelayMs = SECOND_PANE_LAUNCH_DELAY_MS,
                onAssign = { onAssign(1) },
                modifier = Modifier
                    .offset(x = positionOffsets[pos1])
                    .width(positionWidths[pos1])
                    .fillMaxHeight()
            )
            if (showThird) {
                val pos2 = positionOf(2)
                PipPane(
                    packageName = pkg2,
                    inputEnabled = isActive && !isEditing && !paneInputLocked,
                    accent = accent,
                    isDayMode = isDayMode,
                    isEditing = isEditing,
                    launchDelayMs = THIRD_PANE_LAUNCH_DELAY_MS,
                    onAssign = { onAssign(2) },
                    modifier = Modifier
                        .offset(x = positionOffsets[pos2])
                        .width(positionWidths[pos2])
                        .fillMaxHeight()
                )
            }

            // Gap fillers — purely visual placeholders, matching the touch
            // boxes below in position, no content of their own.
            Box(
                modifier = Modifier
                    .offset(x = width0)
                    .width(DIVIDER_LAYOUT_WIDTH)
                    .fillMaxHeight()
            )
            if (showThird) {
                Box(
                    modifier = Modifier
                        .offset(x = width0 + DIVIDER_LAYOUT_WIDTH + width1)
                        .width(DIVIDER_LAYOUT_WIDTH)
                        .fillMaxHeight()
                )
            }

            PaneDivider(
                gapStartX = width0,
                committedFraction = fraction1,
                referenceWidthPx = paneAreaWidthPx,
                isDragging = previewFraction != null && previewDividerIndex == 0,
                onDragStart = { beginDrag(0, fraction1) },
                onPreviewChange = { previewFraction = it },
                onCommit = { committed ->
                    localCommittedFraction = committed
                    onSplitChange(committed)
                },
                onDragEnd = ::endDrag,
                onTap = { onSwap(0) },
                accent = accent,
                gripColor = paneDividerGrip(isDayMode)
            )
            if (showThird) {
                PaneDivider(
                    gapStartX = width0 + DIVIDER_LAYOUT_WIDTH + width1,
                    committedFraction = fraction2,
                    referenceWidthPx = remainingAfter0Px,
                    isDragging = previewFraction != null && previewDividerIndex == 1,
                    onDragStart = { beginDrag(1, fraction2) },
                    onPreviewChange = { previewFraction = it },
                    onCommit = { committed ->
                        localCommittedFraction2 = committed
                        onSplitChange2(committed)
                    },
                    onDragEnd = ::endDrag,
                    onTap = { onSwap(1) },
                    accent = accent,
                    gripColor = paneDividerGrip(isDayMode)
                )
            }

            // Live-drag preview grip for whichever divider is currently being
            // dragged — positioned in the same coordinate space as that
            // divider's own gap (position0/1 for divider 0, the remaining
            // area after pane0 for divider 1).
            previewFraction?.let { preview ->
                val previewGripWidth = DIVIDER_GRIP_WIDTH
                val previewBase = if (previewDividerIndex == 0) 0.dp else offset1
                val previewAreaWidth = if (previewDividerIndex == 0) paneAreaWidth else remainingAfter0
                val previewOffsetX = previewBase + previewAreaWidth * preview +
                    DIVIDER_LAYOUT_WIDTH / 2 - previewGripWidth / 2
                Box(
                    Modifier
                        .offset(x = previewOffsetX)
                        .align(Alignment.CenterStart)
                        .width(previewGripWidth)
                        .height(DIVIDER_GRIP_HEIGHT)
                        .clip(RoundedCornerShape(previewGripWidth / 2))
                        .background(lerp(Color.White, accent, 0.15f))
                )
            }
        }
    }
}

@Composable
private fun PaneDivider(
    gapStartX: Dp,
    committedFraction: Float,
    referenceWidthPx: Float,
    isDragging: Boolean,
    onDragStart: () -> Unit,
    onPreviewChange: (Float?) -> Unit,
    onCommit: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onTap: () -> Unit,
    accent: Color,
    gripColor: Color
) {
    Box(
        modifier = Modifier
            .offset(x = gapStartX + DIVIDER_LAYOUT_WIDTH / 2 - DIVIDER_TOUCH_WIDTH / 2)
            .width(DIVIDER_TOUCH_WIDTH)
            .fillMaxHeight()
            .pointerInput(referenceWidthPx, committedFraction) {
                var dragStartFraction = committedFraction
                var totalDragPx = 0f
                detectDragGestures(
                    onDragStart = {
                        dragStartFraction = committedFraction
                        totalDragPx = 0f
                        onDragStart()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (referenceWidthPx > 0f) {
                            totalDragPx += dragAmount.x
                            onPreviewChange(
                                (dragStartFraction + totalDragPx / referenceWidthPx)
                                    .coerceIn(PANE_SPLIT_RANGE)
                            )
                        }
                    },
                    onDragEnd = {
                        onCommit((dragStartFraction + totalDragPx / referenceWidthPx.coerceAtLeast(1f))
                            .coerceIn(PANE_SPLIT_RANGE))
                        onPreviewChange(null)
                        onDragEnd()
                    },
                    onDragCancel = {
                        onPreviewChange(null)
                        onDragEnd()
                    }
                )
            }
            // Separate from the drag detector above: a plain tap (no
            // touch-slop movement) never triggers detectDragGestures'
            // callbacks, so it's free to swap this divider's two adjacent
            // apps' slots instead — a quick way to re-order without a drag.
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            },
        contentAlignment = Alignment.Center
    ) {
        // Fill is a fixed per-mode tone (see paneDividerGrip) rather than the
        // sidebar's colour: that one is translucent glass tuned for the
        // wallpaper, and composited here over the pane gap it came out washed.
        // No border — the shadow alone lifts it off whatever is underneath.
        val gripShape = RoundedCornerShape(DIVIDER_GRIP_WIDTH / 2)
        Box(
            Modifier
                .width(DIVIDER_GRIP_WIDTH)
                .height(DIVIDER_GRIP_HEIGHT)
                .then(
                    if (isDragging) Modifier
                    else Modifier.shadow(
                        elevation   = 2.dp,
                        shape       = gripShape,
                        ambientColor = Color.Black.copy(alpha = 0.2f),
                        spotColor    = Color.Black.copy(alpha = 0.2f)
                    )
                )
                .clip(gripShape)
                .background(
                    if (isDragging) SolidColor(Color.Transparent)
                    else SolidColor(gripColor)
                )
        )
    }
}

@Composable
private fun PipPane(
    packageName: String,
    inputEnabled: Boolean,
    accent: Color,
    isDayMode: Boolean,
    isEditing: Boolean,
    launchDelayMs: Long,
    onAssign: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tileBg = paneSurface(isDayMode)
    val dimColor = if (isDayMode) Color(0xFF6C737A) else Color(0xFF737A82)
    val paneStroke = if (isDayMode) Color.Black.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.10f)
    val paneRim = paneOuterStroke(isDayMode, accent)
    val context  = LocalContext.current

    if (packageName.isEmpty()) {
        Column(
            modifier = modifier
                .shadow(
                    elevation    = 6.dp,
                    shape        = PaneShape,
                    ambientColor = Color.Black.copy(alpha = 0.4f),
                    spotColor    = Color.Black.copy(alpha = 0.4f)
                )
                .clip(PaneShape)
                .background(
                    Brush.linearGradient(
                        listOf(accent.copy(alpha = if (isDayMode) 0.10f else 0.08f), tileBg, tileBg)
                    )
                )
                .border(PaneBorderWidth, paneRim, PaneShape)
                .then(if (!isEditing) Modifier.clickable(onClick = onAssign) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = if (isDayMode) 0.10f else 0.12f))
                    .border(1.dp, accent.copy(alpha = 0.42f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Add, null, tint = accent, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.height(13.dp))
            Text(
                "ADD APPLICATION",
                color = if (isDayMode) Color(0xFF25282B) else Color(0xFFD8DDE2),
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.6.sp,
                textAlign     = TextAlign.Center
            )
            Spacer(Modifier.height(5.dp))
            Text(
                "CHOOSE WHAT RUNS IN THIS PANE",
                color = dimColor.copy(alpha = 0.72f),
                fontSize = 7.sp,
                letterSpacing = 0.7.sp,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    val pm    = context.packageManager
    val icon  = remember(packageName) {
        runCatching { pm.getApplicationIcon(packageName).toBitmap(96, 96) }.getOrNull()
    }
    val label = remember(packageName) {
        runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }

    var embedFailed by remember(packageName) { mutableStateOf(false) }
    var placementReady by remember(packageName) { mutableStateOf(false) }
    var appRevealed by remember(packageName) { mutableStateOf(false) }

    LaunchedEffect(placementReady) {
        if (placementReady) {
            delay(APP_REVEAL_DELAY_MS)
            appRevealed = true
        }
    }

    Box(
        modifier = modifier
            .shadow(
                elevation    = 6.dp,
                shape        = PaneShape,
                ambientColor = Color.Black.copy(alpha = 0.4f),
                spotColor    = Color.Black.copy(alpha = 0.4f)
            )
            .clip(PaneShape)
            .background(tileBg)
            .border(PaneBorderWidth, paneRim, PaneShape),
        contentAlignment = Alignment.Center
    ) {
        if (embedFailed) {
            Column(
                modifier = Modifier
                    .then(if (!isEditing) Modifier.clickable { embedFailed = false } else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(if (isDayMode) Color.White else Color(0xFF141618))
                        .border(1.dp, paneStroke, RoundedCornerShape(15.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (icon != null) {
                        androidx.compose.foundation.Image(
                            painter = BitmapPainter(icon.asImageBitmap()),
                            contentDescription = label,
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                        )
                    } else {
                        Icon(Icons.Default.PlayArrow, null, tint = accent, modifier = Modifier.size(32.dp))
                    }
                }
                Text(
                    text          = label.uppercase(),
                    color = if (isDayMode) Color(0xFF25282B) else Color(0xFFD8DDE2),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                    textAlign     = TextAlign.Center,
                    maxLines      = 1
                )
                Text(
                    text = "TAP TO RETRY",
                    color = accent,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.4.sp
                )
            }
        } else {
            EmbeddedAppView(
                packageName = packageName,
                inputEnabled = inputEnabled,
                launchDelayMs = launchDelayMs,
                onPlacementReady = { placementReady = true },
                onFailure = { embedFailed = true },
                modifier = Modifier.fillMaxSize()
            )
            AnimatedVisibility(
                visible = !appRevealed,
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().background(tileBg),
                    contentAlignment = Alignment.Center
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = accent,
                            trackColor = dimColor.copy(alpha = 0.18f),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(64.dp)
                        )
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(if (isDayMode) Color.White else Color(0xFF111315)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (icon != null) {
                                androidx.compose.foundation.Image(
                                    painter = BitmapPainter(icon.asImageBitmap()),
                                    contentDescription = label,
                                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
                                )
                            } else {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = label,
                                    tint = accent,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmbeddedAppView(
    packageName: String,
    inputEnabled: Boolean,
    launchDelayMs: Long,
    onPlacementReady: () -> Unit,
    onFailure: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val embedder = remember(packageName) { TaskEmbedder(context) }

    DisposableEffect(packageName) {
        onDispose { embedder.release() }
    }

    LaunchedEffect(inputEnabled) {
        if (!inputEnabled) embedder.focusHost()
    }

    // AndroidView's factory — and so this SurfaceHolder.Callback — runs only
    // once for this slot's lifetime: Home stays composed across navigation
    // (its VirtualDisplays must survive), so reassigning this slot to a
    // different package never tears down or recreates the SurfaceView itself,
    // only the remembered TaskEmbedder above (old one released, new one
    // constructed). surfaceCreated — the only place attach() was called —
    // then never fires again, so the new embedder's attach() was simply never
    // invoked: the new app silently never launched. Tracking the current
    // surface here and re-attaching whenever the package changes (with a
    // surface already available) fixes that, independent of real surface
    // lifecycle events.
    var surfaceInfo by remember { mutableStateOf<PipSurfaceInfo?>(null) }
    val hasSurface = surfaceInfo != null

    LaunchedEffect(packageName, hasSurface) {
        val info = surfaceInfo ?: return@LaunchedEffect
        embedder.attach(
            packageName,
            info.surface,
            info.width,
            info.height,
            info.densityDpi,
            launchDelayMs
        ) { ok ->
            if (ok) onPlacementReady() else onFailure()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            val view = EmbeddedSurfaceView(viewContext)
            view.embedder = embedder
            view.inputEnabled = inputEnabled
            view.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    val frame = holder.surfaceFrame
                    surfaceInfo = PipSurfaceInfo(
                        holder.surface,
                        frame.width(),
                        frame.height(),
                        viewContext.resources.displayMetrics.densityDpi
                    )
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    // Current property, not the factory-time closure value —
                    // otherwise a resize after reassignment would land on the
                    // already-released old embedder instead of the new one.
                    view.embedder?.resize(width, height, viewContext.resources.displayMetrics.densityDpi)
                    surfaceInfo = PipSurfaceInfo(
                        holder.surface, width, height, viewContext.resources.displayMetrics.densityDpi
                    )
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    surfaceInfo = null
                    view.embedder?.detach()
                }
            })
            view
        },
        update = { surfaceView ->
            surfaceView.embedder = embedder
            surfaceView.inputEnabled = inputEnabled
        }
    )
}

private data class PipSurfaceInfo(
    val surface: Surface,
    val width: Int,
    val height: Int,
    val densityDpi: Int
)

internal class EmbeddedSurfaceView(context: Context) : SurfaceView(context) {
    var embedder: TaskEmbedder? = null
    var inputEnabled: Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!inputEnabled) return true

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        embedder?.forwardInputEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
            parent?.requestDisallowInterceptTouchEvent(false)
        } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        // Keep ownership of the whole gesture even if the forwarder is not
        // ready for its first event yet.
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (inputEnabled && event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            if (embedder?.forwardInputEvent(event) == true) return true
        }
        return super.onGenericMotionEvent(event)
    }
}

internal class TaskEmbedder(private val context: Context) {
    private data class DisplaySize(val width: Int, val height: Int, val densityDpi: Int)
    private data class StackSnapshot(
        val stackId: Int,
        val displayId: Int,
        val topPackage: String?,
        val taskPackages: Set<String>
    ) {
        fun containsPackage(packageName: String): Boolean =
            topPackage == packageName || packageName in taskPackages
    }

    private var virtualDisplay: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())
    private var launched = false
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var orientationGuard: View? = null
    private var orientationGuardWindowManager: WindowManager? = null
    private var attachedSurface: Surface? = null
    @Volatile private var inputForwarder: Any? = null
    @Volatile private var forwardEventMethod: java.lang.reflect.Method? = null
    @Volatile private var activityManagerService: Any? = null
    @Volatile private var getAllStackInfosMethod: java.lang.reflect.Method? = null
    @Volatile private var moveStackToDisplayMethod: java.lang.reflect.Method? = null
    @Volatile private var setFocusedStackMethod: java.lang.reflect.Method? = null
    @Volatile private var registerTaskStackListenerMethod: java.lang.reflect.Method? = null
    @Volatile private var unregisterTaskStackListenerMethod: java.lang.reflect.Method? = null
    @Volatile private var embeddedStackId = -1
    private val generation = AtomicInteger()

    // Set once placement succeeds, so a later TaskStackListener callback (fired
    // long after attach()'s own call stack has returned) can relaunch into the
    // same VirtualDisplay without needing a fresh surfaceCreated() — which never
    // comes, since MainActivity keeps this composable's SurfaceView alive across
    // navigation (see MainActivity's "Home stays composed" comment).
    @Volatile private var attachedPackageName: String? = null
    @Volatile private var embeddedDisplayId = -1
    @Volatile private var lastOnResult: ((Boolean) -> Unit)? = null
    private var taskStackListenerRegistered = false
    private val reconcileRunnable = Runnable { Thread { reconcilePlacement() }.start() }

    /**
     * Detects an embedded app leaving its VirtualDisplay after the fact — e.g. the
     * user opened it from Recents, or it was bounced to the default display because
     * it doesn't support secondary displays — which otherwise leaves the pane
     * showing a permanently black, empty VirtualDisplay with no way to recover
     * short of restarting the whole launcher process.
     *
     * android.app.TaskStackListener is @hide (see framework-stubs/), so this is a
     * real subclass of the hidden runtime class, not a dynamic Proxy: registration
     * requires an actual ITaskStackListener Binder, and JVM method dispatch matches
     * on name+descriptor regardless of which class declared the compile-time type.
     */
    private val taskStackListener = object : android.app.TaskStackListener() {
        override fun onTaskStackChanged() {
            // Very chatty — fires for unrelated stack churn system-wide — so
            // debounce into a single reconcile rather than reacting per-event.
            handler.removeCallbacks(reconcileRunnable)
            handler.postDelayed(reconcileRunnable, STACK_CHANGED_DEBOUNCE_MS)
        }
    }
    private var appliedSize: DisplaySize? = null
    private var pendingSize: DisplaySize? = null
    private val applyPendingResize = Runnable {
        val requested = pendingSize ?: return@Runnable
        pendingSize = null
        val display = virtualDisplay ?: return@Runnable
        if (requested == appliedSize) return@Runnable
        runCatching {
            val surface = attachedSurface?.takeIf { it.isValid }
            if (surface != null) display.surface = null
            display.resize(requested.width, requested.height, requested.densityDpi)
            if (surface != null) display.surface = surface
            appliedSize = requested
            android.util.Log.d(
                "TaskEmbedder",
                "resize applied ${requested.width}x${requested.height}@${requested.densityDpi}"
            )
        }.onFailure { android.util.Log.e("TaskEmbedder", "resize failed", it) }
    }

    fun attach(
        packageName: String,
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
        launchDelayMs: Long = 0L,
        onResult: (Boolean) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < 27) {
            android.util.Log.e("TaskEmbedder", "Cannot embed $packageName: Android 8.1/API 27 is required")
            onResult(false)
            return
        }
        // Safety net for a stale TaskEmbedder surviving an unexpected Activity
        // recreation without its DisposableEffect.onDispose firing (so release()
        // never ran): without this, the old instance's TaskStackListener keeps
        // reacting and can win a race to reclaim the package onto its own
        // orphaned, no-longer-displayed VirtualDisplay instead of this new one.
        activeEmbeddersByPackage.put(packageName, this)?.let { previous ->
            if (previous !== this) {
                android.util.Log.w(
                    "TaskEmbedder",
                    "pre-empting a stale TaskEmbedder still registered for $packageName"
                )
                previous.release()
            }
        }
        val existing = virtualDisplay
        if (existing != null) {
            attachedSurface = surface
            runCatching { existing.surface = surface }
            resize(width, height, densityDpi)
            return
        }
        attachedPackageName = packageName
        lastOnResult = onResult
        runCatching {
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            this.displayManager = displayManager

            var targetDisplayId = -1
            val attachGeneration = generation.incrementAndGet()
            fun launchOnce() {
                if (launched || targetDisplayId < 0) return
                launched = true
                unregisterDisplayListener()
                Thread {
                    if (launchDelayMs > 0L) Thread.sleep(launchDelayMs)
                    if (generation.get() != attachGeneration) return@Thread
                    val ok = launchAndPlace(packageName, targetDisplayId, attachGeneration)
                    handler.post {
                        if (!ok) launched = false
                        onResult(ok)
                    }
                }.start()
            }
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {
                    if (displayId != targetDisplayId) return
                    android.util.Log.d("TaskEmbedder", "onDisplayAdded($displayId)")
                    launchOnce()
                }
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {}
            }
            displayListener = listener
            displayManager.registerDisplayListener(listener, handler)

            val name = "TaskView@" + System.identityHashCode(this)
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
            val vd = displayManager.createVirtualDisplay(name, width, height, densityDpi, surface, flags, null, handler)
            virtualDisplay = vd
            attachedSurface = surface
            appliedSize = DisplaySize(width, height, densityDpi)
            targetDisplayId = vd.display.displayId
            embeddedDisplayId = targetDisplayId
            dontOverrideDisplayInfo(targetDisplayId)
            installLandscapeOrientationGuard(vd)
            android.util.Log.d("TaskEmbedder", "createVirtualDisplay ok displayId=$targetDisplayId, waiting for onDisplayAdded")
            handler.postDelayed(::launchOnce, 400)
        }.onFailure {
            android.util.Log.e("TaskEmbedder", "createVirtualDisplay failed", it)
            onResult(false)
        }
    }

    /**
     * Shared launch+placement retry loop, used both by the initial [attach] and by
     * [reconcilePlacement] relaunching into an already-created VirtualDisplay after
     * the embedded app drifted off it. Must run off the main thread — it blocks on
     * launch/placement polling.
     */
    private fun launchAndPlace(packageName: String, targetDisplayId: Int, attachGeneration: Int): Boolean {
        ensureTaskStackListenerRegistered()
        val inputReady = createInputForwarder(targetDisplayId)
        if (!inputReady) return false
        for (attempt in 0..MAX_LAUNCH_RETRIES) {
            if (generation.get() != attachGeneration) return false
            val stacksBeforeLaunch = readStackSnapshots()
            val warmProcess = isPackageProcessRunning(packageName)
            android.util.Log.d(
                "TaskEmbedder",
                "launch attempt=${attempt + 1}/${MAX_LAUNCH_RETRIES + 1} " +
                    "package=$packageName display=$targetDisplayId " +
                    "warmProcess=$warmProcess " +
                    "moveDelayMs=${if (warmProcess) WARM_PROCESS_MOVE_DELAY_MS else 0L}"
            )
            val launchAccepted = launchPending(packageName, targetDisplayId)
            if (launchAccepted && warmProcess) {
                Thread.sleep(WARM_PROCESS_MOVE_DELAY_MS)
                if (generation.get() != attachGeneration) return false
            }
            val placed = launchAccepted && placeLaunchedStack(
                packageName,
                targetDisplayId,
                stacksBeforeLaunch.mapTo(mutableSetOf()) { it.stackId },
                attachGeneration
            )
            if (placed) {
                Thread.sleep(APP_STABILITY_CHECK_MS)
                if (generation.get() != attachGeneration) return false
                val ok = readStackSnapshots().any {
                    it.displayId == targetDisplayId && it.containsPackage(packageName)
                }
                if (ok) return true
            }

            embeddedStackId = -1
            if (attempt < MAX_LAUNCH_RETRIES) {
                android.util.Log.w(
                    "TaskEmbedder",
                    "launch failed; retry ${attempt + 1}/$MAX_LAUNCH_RETRIES " +
                        "package=$packageName display=$targetDisplayId"
                )
                Thread.sleep(LAUNCH_RETRY_DELAY_MS)
            } else {
                android.util.Log.e(
                    "TaskEmbedder",
                    "launch failed after ${MAX_LAUNCH_RETRIES + 1} attempts " +
                        "package=$packageName display=$targetDisplayId"
                )
            }
        }
        return false
    }

    /**
     * Runs off the main thread (posted from [taskStackListener]'s debounce). Checks
     * whether the embedded stack is still on its VirtualDisplay and, if it drifted
     * away or was removed, relaunches the same package into the same display —
     * otherwise the pane would stay a black, empty VirtualDisplay indefinitely,
     * since nothing else ever calls attach() again after the first surfaceCreated().
     */
    private fun reconcilePlacement() {
        val displayId = embeddedDisplayId
        val packageName = attachedPackageName
        val stackId = embeddedStackId
        if (displayId < 0 || packageName == null || stackId < 0) return

        val attachGeneration = generation.get()
        val snapshots = runCatching { readStackSnapshots() }.getOrNull()
            ?: return // read failure: don't false-positive a relaunch, next tick re-checks
        val stillPlaced = snapshots.any { it.stackId == stackId && it.displayId == displayId }
        if (stillPlaced) return

        // Also fires when the SAME package is deliberately opened as its own
        // window (e.g. from the app list) — that reuses this exact task/stack,
        // which is indistinguishable at the stack level from an unwanted drift.
        // Only reclaim once nobody is actually looking at it fullscreen anymore;
        // this listener fires again on its own the moment that changes (e.g. the
        // user backing out to the launcher is itself a stack change), so no
        // separate "pending reclaim" flag or lifecycle hook is needed here.
        if (isVisibleOnDefaultDisplay(packageName, snapshots)) {
            android.util.Log.d(
                "TaskEmbedder",
                "stack $stackId ($packageName) left display $displayId but is now on the default " +
                    "display where the user can see/touch it directly — deferring reclaim until it isn't"
            )
            return
        }

        android.util.Log.w(
            "TaskEmbedder",
            "embedded stack $stackId ($packageName) drifted off display $displayId — relaunching"
        )
        embeddedStackId = -1
        val ok = launchAndPlace(packageName, displayId, attachGeneration)
        handler.post { lastOnResult?.invoke(ok) }
    }

    /**
     * Whether [packageName] is the TOPMOST stack on the default display (0) —
     * the one real physical screen the user directly looks at/touches. A PIP
     * pane's VirtualDisplay is just texture-composited into our own surface, so
     * this is a robust proxy for "can the user see/interact with it outside my
     * tiny pane right now" — without needing AMS's focused-stack bookkeeping,
     * which [setFocusedStackMethod] itself pollutes for input routing between
     * panes and so cannot be used to answer this question.
     *
     * Must be the FIRST displayId==0 entry, not merely present anywhere in it:
     * getAllStackInfos() returns stacks ordered front-to-back per display (matching
     * `dumpsys activity activities`'s own "top to bottom" listing), and a package
     * backgrounded behind the launcher is still technically "on display 0" —
     * checking mere presence would defer forever once the user has already
     * navigated back away from it.
     */
    private fun isVisibleOnDefaultDisplay(packageName: String, snapshots: List<StackSnapshot>): Boolean {
        val topOnDefaultDisplay = snapshots.firstOrNull { it.displayId == 0 } ?: return false
        return topOnDefaultDisplay.containsPackage(packageName)
    }

    private fun ensureTaskStackListenerRegistered() {
        if (taskStackListenerRegistered) return
        runCatching {
            ensureActivityManagerMethods()
            registerTaskStackListenerMethod!!.invoke(activityManagerService, taskStackListener)
            taskStackListenerRegistered = true
            android.util.Log.d("TaskEmbedder", "TaskStackListener registered")
        }.onFailure {
            android.util.Log.w(
                "TaskEmbedder",
                "TaskStackListener registration failed; pane will not auto-recover if the embedded app leaves its display",
                it
            )
        }
    }

    private fun unregisterTaskStackListenerIfNeeded() {
        if (!taskStackListenerRegistered) return
        taskStackListenerRegistered = false
        runCatching { unregisterTaskStackListenerMethod!!.invoke(activityManagerService, taskStackListener) }
            .onFailure { android.util.Log.w("TaskEmbedder", "TaskStackListener unregister failed", it) }
    }

    @SuppressLint("NewApi") // attach() rejects API levels below 27.
    private fun launchPending(packageName: String, displayId: Int): Boolean {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(packageName),
                0
            )
                .firstOrNull()?.activityInfo?.let { ai ->
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(ai.packageName, ai.name)
                }
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching {
            val options = ActivityOptions.makeCustomAnimation(context, 0, 0)
            ActivityOptions::class.java.getMethod("setLaunchDisplayId", Int::class.javaPrimitiveType)
                .invoke(options, displayId)
            val requestCode = NEXT_REQUEST_CODE.incrementAndGet()
            val pendingIntent = PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            android.util.Log.d(
                "TaskEmbedder",
                "launch request package=$packageName component=${intent.component} " +
                    "flags=0x${intent.flags.toString(16)} display=$displayId requestCode=$requestCode"
            )
            // Match ActivityView: the immutable token owns the base Intent;
            // only the launch options are supplied when it is sent.
            pendingIntent.send(null, 0, null, null, null, null, options.toBundle())
            android.util.Log.d("TaskEmbedder", "launch accepted package=$packageName requestedDisplay=$displayId")
            true
        }.onFailure {
            android.util.Log.e("TaskEmbedder", "launch $packageName onto display=$displayId failed", it)
        }.getOrDefault(false)
    }

    private fun dontOverrideDisplayInfo(displayId: Int) {
        runCatching {
            val windowManagerGlobal = Class.forName("android.view.WindowManagerGlobal")
            val iwm = windowManagerGlobal.getMethod("getWindowManagerService").invoke(null)
            iwm.javaClass.getMethod("dontOverrideDisplayInfo", Int::class.javaPrimitiveType)
                .invoke(iwm, displayId)
            android.util.Log.d("TaskEmbedder", "dontOverrideDisplayInfo($displayId) ok")
        }.onFailure {
            android.util.Log.e("TaskEmbedder", "dontOverrideDisplayInfo failed", it)
        }
    }

    private fun installLandscapeOrientationGuard(display: VirtualDisplay) {
        runCatching {
            val displayContext = context.createDisplayContext(display.display)
            val windowManager = displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val guard = View(displayContext)
            val params = WindowManager.LayoutParams(
                1,
                1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                alpha = 0f
                screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                title = "EmbeddedLandscapeOrientationGuard"
            }
            windowManager.addView(guard, params)
            orientationGuard = guard
            orientationGuardWindowManager = windowManager
            android.util.Log.d(
                "TaskEmbedder",
                "landscape orientation guard installed displayId=${display.display.displayId}"
            )
        }.onFailure {
            android.util.Log.e("TaskEmbedder", "landscape orientation guard failed", it)
        }
    }

    private fun removeLandscapeOrientationGuard() {
        val guard = orientationGuard
        val windowManager = orientationGuardWindowManager
        orientationGuard = null
        orientationGuardWindowManager = null
        if (guard != null && windowManager != null) {
            runCatching { windowManager.removeViewImmediate(guard) }
                .onFailure { android.util.Log.w("TaskEmbedder", "remove orientation guard failed", it) }
        }
    }

    fun resize(width: Int, height: Int, densityDpi: Int) {
        if (width <= 0 || height <= 0 || densityDpi <= 0) return
        val requested = DisplaySize(width, height, densityDpi)
        if (requested == appliedSize || requested == pendingSize) return
        pendingSize = requested
        handler.removeCallbacks(applyPendingResize)
        handler.postDelayed(applyPendingResize, RESIZE_DEBOUNCE_MS)
    }

    fun forwardInputEvent(event: InputEvent): Boolean {
        if (event is MotionEvent && event.actionMasked == MotionEvent.ACTION_DOWN) {
            focusEmbeddedStack()
        }
        val forwarder = inputForwarder ?: return false
        val method = forwardEventMethod ?: return false
        return runCatching { method.invoke(forwarder, event) as? Boolean ?: false }
            .onFailure { android.util.Log.e("TaskEmbedder", "input forwarding failed", it) }
            .getOrDefault(false)
    }

    fun detach() {
        attachedSurface = null
        runCatching { virtualDisplay?.surface = null }
    }

    fun focusHost() {
        focusHostStack()
    }

    fun release() {
        generation.incrementAndGet()
        unregisterDisplayListener()
        unregisterTaskStackListenerIfNeeded()
        handler.removeCallbacksAndMessages(null)
        focusHostStack()
        inputForwarder = null
        forwardEventMethod = null
        embeddedStackId = -1
        // Only remove our own registration — if a newer instance already
        // pre-empted us (see attach()), it has since overwritten this entry.
        attachedPackageName?.let { activeEmbeddersByPackage.remove(it, this) }
        attachedPackageName = null
        embeddedDisplayId = -1
        lastOnResult = null
        pendingSize = null
        appliedSize = null
        attachedSurface = null
        removeLandscapeOrientationGuard()
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        displayManager = null
        launched = false
    }

    private fun unregisterDisplayListener() {
        val manager = displayManager
        val listener = displayListener
        if (manager != null && listener != null) {
            runCatching { manager.unregisterDisplayListener(listener) }
        }
        displayListener = null
    }

    private fun isPackageProcessRunning(packageName: String): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.runningAppProcesses.orEmpty().any { process ->
            process.processName == packageName || process.processName.startsWith("$packageName:")
        }
    }

    private fun createInputForwarder(displayId: Int): Boolean {
        return runCatching {
            val inputManagerClass = Class.forName("android.hardware.input.InputManager")
            val inputManager = inputManagerClass.getMethod("getInstance").invoke(null)
            val createMethod = inputManagerClass.getMethod(
                "createInputForwarder",
                Int::class.javaPrimitiveType
            )
            val forwarder = createMethod.invoke(inputManager, displayId)
                ?: error("createInputForwarder returned null")
            val method = forwarder.javaClass.getMethod("forwardEvent", InputEvent::class.java)
                .apply { isAccessible = true }
            inputForwarder = forwarder
            forwardEventMethod = method
            android.util.Log.d("TaskEmbedder", "createInputForwarder($displayId) ok")
            true
        }.onFailure {
            inputForwarder = null
            forwardEventMethod = null
            android.util.Log.e("TaskEmbedder", "createInputForwarder($displayId) failed", it)
        }.getOrDefault(false)
    }

    private fun placeLaunchedStack(
        packageName: String,
        targetDisplayId: Int,
        previousStackIds: Set<Int>,
        attachGeneration: Int
    ): Boolean {
        for (attempt in 0 until PLACEMENT_ATTEMPTS) {
            if (generation.get() != attachGeneration || virtualDisplay?.display?.displayId != targetDisplayId) {
                return false
            }

            val matches = readStackSnapshots().filter { it.containsPackage(packageName) }
            val stack = matches.firstOrNull { it.displayId == targetDisplayId }
                ?: matches.firstOrNull { it.stackId !in previousStackIds && it.topPackage == packageName }
                ?: matches.firstOrNull { it.topPackage == packageName }

            if (stack != null) {
                if (stack.displayId != targetDisplayId && !moveStack(stack.stackId, targetDisplayId)) {
                    return false
                }
                val moved = waitForStackDisplay(stack.stackId, targetDisplayId, attachGeneration)
                if (moved) {
                    embeddedStackId = stack.stackId
                    focusEmbeddedStack()
                    android.util.Log.d(
                        "TaskEmbedder",
                        "placement complete package=$packageName stackId=${stack.stackId} displayId=$targetDisplayId"
                    )
                    return true
                }
            }

            Thread.sleep(PLACEMENT_POLL_MS)
        }
        android.util.Log.e(
            "TaskEmbedder",
            "placement timed out package=$packageName requestedDisplay=$targetDisplayId"
        )
        return false
    }

    private fun moveStack(stackId: Int, displayId: Int): Boolean {
        return runCatching {
            ensureActivityManagerMethods()
            moveStackToDisplayMethod!!.invoke(activityManagerService, stackId, displayId)
            android.util.Log.d("TaskEmbedder", "moveStackToDisplay($stackId, $displayId) ok")
            true
        }.onFailure {
            android.util.Log.e("TaskEmbedder", "moveStackToDisplay($stackId, $displayId) failed", it)
        }.getOrDefault(false)
    }

    private fun waitForStackDisplay(stackId: Int, displayId: Int, attachGeneration: Int): Boolean {
        repeat(MOVE_CONFIRM_ATTEMPTS) {
            if (generation.get() != attachGeneration) return false
            if (readStackSnapshots().any { it.stackId == stackId && it.displayId == displayId }) return true
            Thread.sleep(PLACEMENT_POLL_MS)
        }
        return false
    }

    private fun focusEmbeddedStack() {
        val stackId = embeddedStackId
        if (stackId < 0) return
        focusStack(stackId)
    }

    private fun focusHostStack() {
        val hostStack = readStackSnapshots()
            .filter { it.displayId == 0 && it.containsPackage(context.packageName) }
            .maxByOrNull { it.stackId }
            ?: return
        focusStack(hostStack.stackId)
    }

    private fun focusStack(stackId: Int) {
        // Re-issuing setFocusedStack for a stack that's already focused is at
        // best wasted work — a single logical tap/drag on an embedded pane can
        // arrive as many separate ACTION_DOWN events, so skip the redundant
        // reassignment when nothing would actually change. (A separate, since
        // confirmed, source of the real system nav bar flickering back in is
        // MainActivity's own edge-swipe gesture handling — this guard alone
        // isn't sufficient for that.)
        if (stackId == lastFocusedStackId) return
        runCatching {
            ensureActivityManagerMethods()
            setFocusedStackMethod!!.invoke(activityManagerService, stackId)
            lastFocusedStackId = stackId
        }.onFailure {
            android.util.Log.w("TaskEmbedder", "setFocusedStack($stackId) failed", it)
        }
    }

    private fun readStackSnapshots(): List<StackSnapshot> {
        return runCatching {
            ensureActivityManagerMethods()
            val stacks = getAllStackInfosMethod!!.invoke(activityManagerService) as? Iterable<*>
                ?: return@runCatching emptyList()
            stacks.mapNotNull { stack ->
                stack ?: return@mapNotNull null
                val type = stack.javaClass
                fun field(name: String): Any? = type.getDeclaredField(name).run {
                    isAccessible = true
                    get(stack)
                }
                val topPackage = (field("topActivity") as? ComponentName)?.packageName
                val taskPackages = (field("taskNames") as? Array<*>)
                    ?.mapNotNullTo(mutableSetOf()) { taskName ->
                        ComponentName.unflattenFromString(taskName?.toString().orEmpty())?.packageName
                    }
                    ?: emptySet()
                StackSnapshot(
                    stackId = field("stackId") as Int,
                    displayId = field("displayId") as Int,
                    topPackage = topPackage,
                    taskPackages = taskPackages
                )
            }
        }.onFailure {
            android.util.Log.w("TaskEmbedder", "getAllStackInfos failed", it)
        }.getOrDefault(emptyList())
    }

    private fun ensureActivityManagerMethods() {
        if (activityManagerService != null) return
        synchronized(this) {
            if (activityManagerService != null) return
            val getService = Class.forName("android.app.ActivityManager").getDeclaredMethod("getService")
                .apply { isAccessible = true }
            val service = getService.invoke(null)
            getAllStackInfosMethod = service.javaClass.getMethod("getAllStackInfos")
            moveStackToDisplayMethod = service.javaClass.getMethod(
                "moveStackToDisplay",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            setFocusedStackMethod = service.javaClass.getMethod(
                "setFocusedStack",
                Int::class.javaPrimitiveType
            )
            activityManagerService = service

            // Isolated from the block above: if this fails on some build, the
            // core placement path (already working) must keep working — the
            // pane just won't auto-recover from a drifted stack.
            runCatching {
                val iTaskStackListener = Class.forName("android.app.ITaskStackListener")
                registerTaskStackListenerMethod = service.javaClass.getMethod(
                    "registerTaskStackListener", iTaskStackListener
                )
                unregisterTaskStackListenerMethod = service.javaClass.getMethod(
                    "unregisterTaskStackListener", iTaskStackListener
                )
            }.onFailure {
                android.util.Log.w("TaskEmbedder", "TaskStackListener methods unavailable", it)
            }
        }
    }

    companion object {
        // System API on Android 9; omitted from the public SDK stub used to compile the app.
        const val VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        const val MAX_LAUNCH_RETRIES = 3
        const val APP_STABILITY_CHECK_MS = 800L
        const val LAUNCH_RETRY_DELAY_MS = 250L
        const val WARM_PROCESS_MOVE_DELAY_MS = 800L
        const val RESIZE_DEBOUNCE_MS = 80L
        const val PLACEMENT_ATTEMPTS = 50
        const val MOVE_CONFIRM_ATTEMPTS = 20
        const val PLACEMENT_POLL_MS = 100L
        const val STACK_CHANGED_DEBOUNCE_MS = 150L
        val NEXT_REQUEST_CODE = AtomicInteger(10_000)

        // Cross-instance guard against two TaskEmbedders for the same package
        // being alive at once (see the comment in attach()).
        val activeEmbeddersByPackage = java.util.concurrent.ConcurrentHashMap<String, TaskEmbedder>()

        // setFocusedStack is a single system-wide value, not per-embedder, so the
        // "already focused, skip the call" guard in focusStack() must be shared
        // across every TaskEmbedder instance too (see the comment there).
        @Volatile
        var lastFocusedStackId = -1

        // Called right before a deliberate process kill (see MainActivity's
        // restartLauncher()) so every VirtualDisplay gets a chance to detach
        // instead of being abandoned mid-transaction — DisposableEffect's
        // onDispose never runs when the process exits via Runtime.exit().
        fun releaseAll() {
            activeEmbeddersByPackage.values.toList().forEach { embedder ->
                runCatching { embedder.release() }
            }
        }
    }
}
