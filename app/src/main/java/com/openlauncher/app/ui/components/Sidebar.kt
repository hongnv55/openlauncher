package com.openlauncher.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.annotation.DrawableRes
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

private val ICON_SIZE   = 30.dp
// Nav buttons (Home/Settings/Apps) no longer sit in a clipping chip, so this
// can run bigger than ICON_SIZE without getting cut off at the chip's edge.
private val NAV_ICON_SIZE = 46.dp
private val SLOT_SIZE   = 52.dp
// Big enough that the two non-flush corners read as a half-capsule against
// the 56dp bar width, rather than a merely "slightly softened" rectangle.
private val SIDEBAR_CORNER  = 24.dp
private val NAV_CHIP_SIZE   = 46.dp
private val NAV_CHIP_RADIUS = RoundedCornerShape(14.dp)

// ── Tones derived from the surface the sidebar actually presents ─────────────
// These used to be hard-coded for a dark sidebar, which broke once the sidebar
// color became configurable. They are all keyed off one question — does the
// panel read light or dark — answered by [readsLight].

/**
 * Whether the panel reads as light to the eye.
 *
 * `Color.luminance()` ignores alpha, so it cannot answer this for glass: a
 * 10%-white veil and a 30%-white veil both report luminance 1.0 while looking
 * nothing alike. A translucent panel takes most of its apparent lightness from
 * the wallpaper behind it, and that tracks day/night — so alpha picks which
 * signal to trust: an opaque custom color is judged on its own luminance, a
 * glass panel on the mode.
 */
private fun readsLight(sidebarBg: Color, isDayMode: Boolean): Boolean =
    if (sidebarBg.alpha >= 0.9f) sidebarBg.luminance() > 0.5f else isDayMode

/**
 * Which edge sits flush against the window, and so gets no rim at all: the
 * panel is meant to read as continuous with the screen edge there, and a
 * highlight traced along that seam turns it into a drawn outline instead.
 */
private enum class FlushEdge { LEFT, RIGHT, BOTTOM }

/**
 * Draws [rimOn]'s highlight along only the edges that face the content, as an
 * open path. `Modifier.border` has no per-side option and would trace all four,
 * including the flush one.
 */
private fun Modifier.glassRim(color: Color, radius: Dp, flush: FlushEdge): Modifier =
    this.drawWithContent {
        drawContent()
        val sw = 1.dp.toPx()
        val i  = sw / 2   // half the stroke, so the line lands wholly inside the clip
        val r  = radius.toPx()
        val w  = size.width
        val h  = size.height
        val p  = Path()
        when (flush) {
            FlushEdge.LEFT -> {
                p.moveTo(0f, i)
                p.lineTo(w - i - r, i)
                p.arcTo(Rect(w - i - 2 * r, i, w - i, i + 2 * r), -90f, 90f, false)
                p.lineTo(w - i, h - i - r)
                p.arcTo(Rect(w - i - 2 * r, h - i - 2 * r, w - i, h - i), 0f, 90f, false)
                p.lineTo(0f, h - i)
            }
            FlushEdge.RIGHT -> {
                p.moveTo(w, i)
                p.lineTo(i + r, i)
                p.arcTo(Rect(i, i, i + 2 * r, i + 2 * r), -90f, -90f, false)
                p.lineTo(i, h - i - r)
                p.arcTo(Rect(i, h - i - 2 * r, i + 2 * r, h - i), 180f, -90f, false)
                p.lineTo(w, h - i)
            }
            FlushEdge.BOTTOM -> {
                p.moveTo(i, h)
                p.lineTo(i, i + r)
                p.arcTo(Rect(i, i, i + 2 * r, i + 2 * r), 180f, 90f, false)
                p.lineTo(w - i - r, i)
                p.arcTo(Rect(w - i - 2 * r, i, w - i, i + 2 * r), -90f, 90f, false)
                p.lineTo(w - i, h)
            }
        }
        drawPath(p, color, style = Stroke(width = sw))
    }

/**
 * The panel's backdrop: the same wallpaper, at the same crop geometry and size
 * as the fullscreen layer, translated back by this panel's own position so the
 * fragment showing through lines up with the image around it. Misalignment
 * would show a different part of the scene inside the panel than outside, which
 * is why this works in pixels rather than dp.
 *
 * Drawn in the draw phase with an explicit translate rather than laid out as an
 * oversized child with Modifier.offset. That approach depended on how the parent
 * placed a child bigger than itself, and measurement showed this Box *centres*
 * such a child (a 1920px node in a 70px panel landed 925px left of the panel
 * origin) rather than pinning it top-left, so the offset that should have
 * aligned it pushed it off-panel entirely. Translating at draw time has no such
 * dependency.
 */
/**
 * Where the panel's blurred backdrop comes from. The two cases are genuinely
 * different work, so they are named rather than sniffed from an `Any`:
 *
 *  - [PreBlurred] is one of the built-in wallpapers, shipped alongside a
 *    blurred twin (445x313, gaussian). Best quality and near-free to decode.
 *  - [NeedsBlur] is a wallpaper the user picked, which has no twin, so the
 *    blur has to be produced here.
 */
sealed interface BackdropSource {
    data class PreBlurred(@DrawableRes val resId: Int) : BackdropSource
    data class NeedsBlur(val uri: Uri) : BackdropSource
}

/**
 * Roughly the width the blurred backdrop is decoded to, matching the built-in
 * blurred assets. Detail below the resulting upscale factor is gone, which is
 * the whole point — the magnification is itself a box blur.
 */
private const val BACKDROP_DECODE_WIDTH = 445

/**
 * Decodes [source] small.
 *
 * `inSampleSize` rather than Coil: a Coil request with an explicit `.size()`
 * was measured to come back at full resolution here (1/8 and 1/64 requests
 * produced pixel-identical output), so the downsample never happened. This goes
 * through BitmapFactory, where the decoder itself drops the pixels and the size
 * is not advisory.
 */
private fun decodeBackdrop(context: Context, source: BackdropSource): Bitmap? = runCatching {
    when (source) {
        is BackdropSource.PreBlurred ->
            BitmapFactory.decodeResource(context.resources, source.resId)

        is BackdropSource.NeedsBlur -> {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(source.uri)
                ?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return@runCatching null
            // Largest power of two that still leaves us at or above the target
            // width — BitmapFactory rounds inSampleSize down to a power of two
            // anyway, so computing it that way avoids a surprise.
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= BACKDROP_DECODE_WIDTH) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(source.uri)
                ?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    }
}.getOrNull()

/**
 * The panel's frosted backdrop: a blurred copy of the wallpaper, drawn at the
 * same crop geometry and size as the fullscreen layer and translated back by
 * this panel's own position, so the fragment showing through lines up with the
 * sharp image around it. The sidebar silhouette comes from the parent's
 * `.clip(sidebarShape)`; nothing here is shaped like a sidebar.
 *
 * Drawn in the draw phase rather than laid out as an oversized child with
 * Modifier.offset. That approach depended on how the parent placed a child
 * bigger than itself, and measurement showed this Box *centres* such a child (a
 * 1920px node in a 70px panel landed 925px left of the panel origin) rather
 * than pinning it top-left, so the offset that should have aligned it pushed it
 * off-panel entirely. Drawing has no such dependency.
 */
@Composable
private fun GlassBackdrop(
    source: BackdropSource?,
    wallpaperOriginPx: IntOffset,
    wallpaperSizePx: IntSize,
    dim: Float,
    panelOriginPx: IntOffset
) {
    // Zero on the very first pass, before onGloballyPositioned has reported.
    if (source == null || wallpaperSizePx.width <= 0 || wallpaperSizePx.height <= 0) return
    val context = LocalContext.current
    // Off the main thread: a user-picked wallpaper can be a 12MP photo, and even
    // subsampled the decoder still reads the whole file.
    val backdrop by produceState<ImageBitmap?>(null, source) {
        value = withContext(Dispatchers.IO) { decodeBackdrop(context, source)?.asImageBitmap() }
    }
    val dx  = wallpaperOriginPx.x - panelOriginPx.x
    val dy  = wallpaperOriginPx.y - panelOriginPx.y
    val wpW = wallpaperSizePx.width
    val wpH = wallpaperSizePx.height

    Box(
        Modifier.fillMaxSize().drawWithContent {
            backdrop?.let { image ->
                // Replicate ContentScale.Crop over the wallpaper's own rect, so
                // the fragment under this panel is the same region the
                // fullscreen layer shows around it.
                val scale = max(wpW.toFloat() / image.width, wpH.toFloat() / image.height)
                val dw = (image.width * scale).roundToInt()
                val dh = (image.height * scale).roundToInt()
                drawImage(
                    image     = image,
                    dstOffset = IntOffset(dx + (wpW - dw) / 2, dy + (wpH - dh) / 2),
                    dstSize   = IntSize(dw, dh)
                )
            }
            drawContent()
        }
    )
    // The same veil the fullscreen layer gets — without it the panel's backdrop
    // would sit brighter than the wallpaper it is supposed to continue.
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
}

/**
 * The panel's outer rim — always a white highlight, never a dark line, because
 * this is the edge-lit sheen that makes glass look like glass. It only changes
 * strength: 0.70 on light glass, where it is the main thing separating the
 * panel from a bright wallpaper (0.55 sat too close to the panel's own 0.50
 * veil to register as a separate highlight), and a restrained 0.22 on dark
 * glass, where more turns it into a drawn outline.
 */
private fun rimOn(sidebarBg: Color, isDayMode: Boolean): Color =
    Color.White.copy(alpha = if (readsLight(sidebarBg, isDayMode)) 0.70f else 0.22f)

/**
 * Interior hairline (the divider above the nav group). Unlike the rim this has
 * to read *against* the panel, so it goes dark on light glass.
 */
private fun hairlineOn(sidebarBg: Color, isDayMode: Boolean, onLight: Float, onDark: Float): Color =
    if (readsLight(sidebarBg, isDayMode)) Color.Black.copy(alpha = onLight)
    else Color.White.copy(alpha = onDark)

/**
 * Single inactive-glyph tone for the whole sidebar — nav buttons and shortcut
 * slots previously each computed their own, and disagreed. [faint] is for a
 * placeholder (an empty slot's "+"), which should read as absent rather than
 * as a real icon that happens to be dim.
 */
private fun inactiveIconOn(sidebarBg: Color, isDayMode: Boolean, faint: Boolean = false): Color =
    if (readsLight(sidebarBg, isDayMode)) {
        if (faint) Color(0x669A9A9A) else Color(0xE6404040)
    } else {
        Color.White.copy(alpha = if (faint) 0.28f else 0.62f)
    }

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
    backdropSource: BackdropSource? = null,
    wallpaperOriginPx: IntOffset = IntOffset.Zero,
    wallpaperSizePx: IntSize = IntSize.Zero,
    modifier: Modifier = Modifier
) {
    val isDayMode    = LocalDayMode.current
    // Captured from the panel itself rather than derived from sidebarPosition,
    // so the backdrop's alignment holds for LEFT/RIGHT/BOTTOM alike and stays
    // correct when the status-bar or nav-bar padding above changes.
    var panelOriginPx by remember { mutableStateOf(IntOffset.Zero) }
    val accent       = Color(settings.accentColor)
    val sidebarBg    = settings.resolveSidebarColor(isDayMode)
    val iconInactive = inactiveIconOn(sidebarBg, isDayMode)
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
                .onGloballyPositioned { panelOriginPx = it.positionInWindow().round() }
                .clip(bottomBarShape)
                // Edge-lit highlight along the silhouette — what separates the
                // bar from the backdrop behind it. Skips the bottom edge, which
                // is flush with the window.
                .glassRim(rimOn(sidebarBg, isDayMode), SIDEBAR_CORNER, FlushEdge.BOTTOM)
        ) {
            GlassBackdrop(
                source            = backdropSource,
                wallpaperOriginPx = wallpaperOriginPx,
                wallpaperSizePx   = wallpaperSizePx,
                dim               = settings.wallpaperDim,
                panelOriginPx     = panelOriginPx
            )
            Box(Modifier.fillMaxSize().background(sidebarBg))

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
        Box(
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
                .onGloballyPositioned { panelOriginPx = it.positionInWindow().round() }
                .clip(sidebarShape)
                // See the bottom-bar variant above. Skips whichever vertical
                // edge is flush with the window for this sidebar position.
                .glassRim(
                    rimOn(sidebarBg, isDayMode),
                    SIDEBAR_CORNER,
                    if (settings.sidebarPosition == SidebarPosition.RIGHT) FlushEdge.RIGHT
                    else FlushEdge.LEFT
                )
        ) {
        GlassBackdrop(
            source            = backdropSource,
            wallpaperOriginPx = wallpaperOriginPx,
            wallpaperSizePx   = wallpaperSizePx,
            dim               = settings.wallpaperDim,
            panelOriginPx     = panelOriginPx
        )
        Box(Modifier.fillMaxSize().background(sidebarBg))
        Column(
            modifier = Modifier.fillMaxSize(),
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
                color     = hairlineOn(sidebarBg, isDayMode, 0.18f, 0.35f)
            )
            Spacer(Modifier.height(8.dp))
            navButtons()
            // Matches the 13.6dp the first shortcut slot gets at the top. At the
            // old 4dp the Home glyph and its active-state bar ended up 4.8dp off
            // the panel's bottom edge — under a third of the top inset, and
            // tighter still to the eye because the 24dp bottom corner is curving
            // inward right there.
            Spacer(Modifier.height(13.dp))
        }
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
        val iconInactive = inactiveIconOn(sidebarBg, isDayMode)
        val override     = shortcut.customIconOverride
        // An explicit override still wins over the installed icon, matching
        // the ordering of the `when` this replaced.
        val nativeIcon   = resolvedIcon
            ?.takeIf { override == null || override == DefaultShortcutIcon.NONE }

        if (nativeIcon != null) {
            // Full-bleed at the chip's own footprint, with no chip underneath:
            // an app icon is already a rounded square carrying its own
            // background, so nesting it inside another one read muddy and cost
            // the artwork a third of its size. Still clipped, so a legacy
            // square bitmap takes the same silhouette as an adaptive icon.
            val sizePx = with(LocalDensity.current) { NAV_CHIP_SIZE.roundToPx() }
            // Cached per icon *and* per resolved pixel size — every slot
            // recomposes each drag frame, and an un-remembered toBitmap
            // allocated a fresh bitmap per slot per frame. Rasterising at the
            // real pixel size instead of a fixed 60px is what removes the
            // upscale blur on 3x-density screens.
            val bmp = remember(nativeIcon, sizePx) { nativeIcon.toBitmap(sizePx, sizePx) }
            Icon(
                painter            = BitmapPainter(bmp.asImageBitmap()),
                contentDescription = shortcut.label,
                tint               = Color.Unspecified,
                modifier           = Modifier
                    .size(NAV_CHIP_SIZE)
                    .clip(NAV_CHIP_RADIUS)
            )
        } else {
            // Vector glyphs keep the chip: a bare 30dp glyph floating alone in
            // a 52dp slot read noticeably sparser/smaller than the nav icons
            // right below it. A subtle lighter step off the sidebar's own color
            // (not a contrasting swatch) — close enough in tone to still read
            // as part of the same sidebar, just a hair "raised" toward a light
            // source, the way a bevel/highlight implies elevation.
            Box(
                modifier = Modifier
                    .size(NAV_CHIP_SIZE)
                    .clip(NAV_CHIP_RADIUS)
                    .background(lerp(sidebarBg, Color.White, 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                when {
                    override != null && override != DefaultShortcutIcon.NONE -> {
                        Icon(
                            imageVector        = override.toIcon(),
                            contentDescription = shortcut.label,
                            tint               = iconInactive,
                            modifier           = Modifier.size(ICON_SIZE)
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
                            tint               = inactiveIconOn(sidebarBg, isDayMode, faint = true),
                            modifier           = Modifier.size(ICON_SIZE)
                        )
                    }
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

// Also feeds PipWidget's divider grip (see HomeScreen.kt/PipWidget.kt) so the
// grip is defined from the sidebar rather than tracked separately. Note the
// grip composites this over pane content, not over the wallpaper, so with the
// default glass value the two match in tone rather than pixel-for-pixel.
fun AppSettings.resolveSidebarColor(isDayMode: Boolean): Color {
    // Frosted glass, not a solid slab. The sidebar now always sits on the
    // wallpaper (see the wallpaper layer in MainActivity), so it should let the
    // image through and take its tone from it — which is what makes it read as
    // a panel floating over the scene rather than a bar bolted beside it.
    //
    // This replaces a lift derived from `backgroundColor`. That derivation made
    // sense while the sidebar sat on a flat fill of that color, but the
    // wallpaper hides it now, so the sidebar was tracking a color nobody sees
    // and came out an opaque mid-gray no matter how bright the wallpaper was.
    //
    // A white veil in both modes rather than white/day + black/night: the glass
    // reads as the same material at two strengths, and over a dark wallpaper a
    // faint white lift still says "translucent panel", where a black one just
    // deepens the image. `useCustomSidebarColor` still wins outright for anyone
    // who wants a specific opaque color.
    return if (useCustomSidebarColor) {
        Color(sidebarColor)
    } else {
        // 0.50 rather than a lighter veil because the panel has to carry the
        // monochrome nav glyphs, and what sits behind it is not uniform: down
        // this wallpaper the value swings from ~(194,168,139) at the top to
        // ~(72,59,49) in the dune shadow, which is exactly where the nav group
        // sits. Measured against a #404040 glyph, 0.30 gave 2.33:1 over that
        // dark end — under the 3:1 floor for large graphics — while 0.50 gives
        // 3.88:1 and still reads as glass, with the gradient clearly visible
        // through it.
        if (isDayMode) Color.White.copy(alpha = 0.50f)
        else           Color.White.copy(alpha = 0.10f)
    }
}
