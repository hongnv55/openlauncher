package com.openlauncher.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.model.NowPlayingState
import java.util.Calendar
import kotlinx.coroutines.delay

// Shared by both island states so the pill's bottom edge lands in the same
// place regardless of which is showing. Sized so that, with the island top-
// anchored `gap` (10dp, HomeScreen's own constant) below the screen edge, its
// bottom sits `gap` above the status-bar strip's own bottom (APP_HEADER_
// HEIGHT, 59dp) — the same 10dp breathing room on both edges.
private val ISLAND_CONTENT_HEIGHT = 49.dp

/**
 * The status bar's clock pill. Sized entirely by its own content — a compact
 * time readout most of the time — rather than a fixed width, so it can widen
 * on its own into a minimal now-playing strip (album art, title, play/pause)
 * whenever [nowPlaying] actually has something loaded, with no layout slot
 * reserved for that wider state up front.
 */
@Composable
fun DynamicIsland(
    nowPlaying: NowPlayingState?,
    accent: Color,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Always true black, like the iPhone's own Dynamic Island — it never
    // tracks day/night mode, since the whole point is to read as a cutout
    // rather than a themed card.
    val pillBg     = Color.Black
    val pillBorder = Color(0x1FFFFFFF)
    val textColor  = Color(0xFFF2F2F2)

    // A paused (but not yet torn-down) media session would otherwise pin the
    // island on the now-playing view forever — plenty of apps hold their
    // session open indefinitely once paused. Give it a 3s grace period after
    // pausing before falling back to the clock, so a quick pause/resume
    // doesn't flicker, but an actually-abandoned session does settle back.
    val hasNowPlayingRaw = nowPlaying != null && nowPlaying.title.isNotEmpty()
    var showNowPlaying by remember { mutableStateOf(hasNowPlayingRaw) }
    LaunchedEffect(hasNowPlayingRaw, nowPlaying?.isPlaying) {
        when {
            !hasNowPlayingRaw          -> showNowPlaying = false
            nowPlaying?.isPlaying == true -> showNowPlaying = true
            else -> {
                delay(3_000)
                showNowPlaying = false
            }
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(pillBg)
            .border(Dp.Hairline, pillBorder, RoundedCornerShape(50))
    ) {
        if (showNowPlaying && nowPlaying != null) {
            NowPlayingIslandContent(
                state       = nowPlaying,
                accent      = accent,
                textColor   = textColor,
                onPlayPause = onPlayPause
            )
        } else {
            ClockIslandContent(textColor = textColor)
        }
    }
}

@Composable
private fun ClockIslandContent(textColor: Color) {
    var calendar by remember { mutableStateOf(Calendar.getInstance()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            calendar = Calendar.getInstance()
        }
    }
    val hour    = calendar.get(Calendar.HOUR_OF_DAY)
    val minute  = calendar.get(Calendar.MINUTE)
    val day     = calendar.get(Calendar.DAY_OF_MONTH)
    val month   = calendar.get(Calendar.MONTH) + 1

    Row(
        modifier          = Modifier.height(ISLAND_CONTENT_HEIGHT).padding(horizontal = 19.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text          = "%02d:%02d".format(hour, minute),
            color         = textColor,
            fontSize      = 20.sp,
            fontWeight    = FontWeight.SemiBold,
            letterSpacing = 0.2.sp
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text          = "%02d/%02d".format(day, month),
            color         = textColor.copy(alpha = 0.68f),
            fontSize      = 20.sp,
            fontWeight    = FontWeight.SemiBold,
            letterSpacing = 0.2.sp
        )
    }
}

@Composable
private fun NowPlayingIslandContent(
    state: NowPlayingState,
    accent: Color,
    textColor: Color,
    onPlayPause: () -> Unit
) {
    Row(
        modifier          = Modifier.height(ISLAND_CONTENT_HEIGHT).padding(start = 4.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            if (state.albumArt != null) {
                androidx.compose.foundation.Image(
                    painter            = BitmapPainter(state.albumArt.asImageBitmap()),
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.size(32.dp).clip(CircleShape)
                )
            } else {
                Icon(Icons.Default.MusicNote, null, tint = accent, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text       = state.title,
            color      = textColor,
            fontSize   = 17.sp,
            maxLines   = 1,
            // A fixed (rather than max-capped) width so the pill claims a
            // consistent, wider footprint in media mode — the viewport the
            // marquee scrolls the title through, wide enough to read several
            // words at a glance instead of just a fragment.
            modifier   = Modifier
                .width(190.dp)
                .basicMarquee(
                    iterations        = Int.MAX_VALUE,
                    // Pause on the title's start position for a beat before
                    // each pass — both the initial one and every repeat after
                    // a full loop — instead of scrolling continuously.
                    repeatDelayMillis = 1_200,
                    initialDelayMillis = 1_200
                )
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(41.dp)
                .clip(CircleShape)
                .background(accent)
                .clickable(onClick = onPlayPause),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector        = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
                tint               = if (accent.luminance() > 0.5f) Color.Black else Color.White,
                modifier           = Modifier.size(24.dp)
            )
        }
    }
}
