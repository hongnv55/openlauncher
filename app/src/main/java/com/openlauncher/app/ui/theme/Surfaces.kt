package com.openlauncher.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * The launcher's one content-surface language.
 *
 * Every destination now sits on the wallpaper rather than on a flat fill, which
 * splits the UI into two kinds of surface, and they are treated differently on
 * purpose:
 *
 *  - **Chrome** (the sidebar) is glass: translucent, letting the wallpaper show
 *    through, with an alpha rim as its edge highlight.
 *  - **Content** (a PIP pane, the app list, settings) is an opaque card. It has
 *    to carry dense text and arbitrary embedded app pixels, and the wallpaper
 *    behind it is not uniform — measured down one wallpaper the value behind the
 *    sidebar swings from ~(194,168,139) to ~(72,59,49). Text over that needs a
 *    veil so heavy it may as well be a solid card, so it is one.
 *
 * These live here, rather than privately in PipWidget where they started,
 * because three screens now share them. Copies would drift: the sidebar's
 * inactive-glyph tone had already forked into two disagreeing definitions
 * before it was consolidated.
 */

/** Corner radius for a content card. Kept in the same family as the sidebar's
 *  24dp without matching it: this shape clips a live embedded app's surface, so
 *  a large radius eats real UI at the corners, and the same radius reads far
 *  softer on a ~1800px-wide pane than on a 56dp bar. */
val PaneShape: Shape = RoundedCornerShape(12.dp)

/** Slightly heavier than the sidebar's 1dp rim — these surfaces are much larger,
 *  and this stroke is opaque rather than alpha-blended (see [paneOuterStroke]). */
val PaneBorderWidth = 1.5.dp

val PaneElevation = 6.dp

/** Inset from the window edge, matching the `gap` HomeScreen uses to inset its
 *  widget grid, so a pane and a full-screen destination line up exactly. */
val PaneInset = 10.dp

/** A content card's fill. */
fun paneSurface(isDayMode: Boolean): Color =
    if (isDayMode) Color(0xFFF7F8F9) else Color(0xFF08090A)

/**
 * The card's outer rim, so it still reads as a distinct, lifted panel once
 * content fills it edge-to-edge — at which point [paneSurface] is invisible and
 * this border is the only remaining separation from the wallpaper behind it.
 *
 * One even, opaque tone all the way around. Deliberately *not* the sidebar's
 * alpha rim: that works on glass because the wallpaper modulates it into a lit
 * edge, but over an embedded app's own pixels an alpha rim blends against
 * whatever the app happens to draw — invisible on a white app, harsh on a dark
 * one. A bright-to-dark fade was tried here too and read as an uneven smudge
 * rather than a lit edge. Native elevation shadow cannot stand in for it on
 * API 28 either, since ambient/spot shadow tinting only arrived in API 31.
 */
fun paneOuterStroke(accent: Color): Brush =
    SolidColor(lerp(Color.White, accent, 0.15f))

/**
 * The complete content-card treatment in one call, so the call sites are
 * identical rather than merely similar. Pass [shape] only when a surface needs
 * a different silhouette; pass a custom fill by applying `.background()` after
 * this and using [PaneShape] to clip.
 */
fun Modifier.contentPane(
    isDayMode: Boolean,
    accent: Color,
    shape: Shape = PaneShape
): Modifier = this
    .shadow(
        elevation    = PaneElevation,
        shape        = shape,
        ambientColor = Color.Black.copy(alpha = 0.4f),
        spotColor    = Color.Black.copy(alpha = 0.4f)
    )
    .clip(shape)
    .background(paneSurface(isDayMode))
    .border(PaneBorderWidth, paneOuterStroke(accent), shape)
