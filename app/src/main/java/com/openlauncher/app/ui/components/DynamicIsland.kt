package com.openlauncher.app.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.model.NowPlayingState
import java.util.Calendar
import kotlinx.coroutines.delay

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

    val hasNowPlaying = nowPlaying != null && nowPlaying.title.isNotEmpty()

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(pillBg)
            .border(Dp.Hairline, pillBorder, RoundedCornerShape(50))
    ) {
        if (hasNowPlaying) {
            NowPlayingIslandContent(
                state       = nowPlaying!!,
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
        modifier          = Modifier.height(45.dp).padding(horizontal = 19.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text          = "%02d:%02d".format(hour, minute),
            color         = textColor,
            fontSize      = 20.sp,
            fontWeight    = FontWeight.SemiBold,
            fontFamily    = FontFamily.Monospace,
            letterSpacing = 0.2.sp
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text          = "%02d/%02d".format(day, month),
            color         = textColor.copy(alpha = 0.68f),
            fontSize      = 20.sp,
            fontWeight    = FontWeight.SemiBold,
            fontFamily    = FontFamily.Monospace,
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
        modifier          = Modifier.height(51.dp).padding(start = 4.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(43.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            if (state.albumArt != null) {
                androidx.compose.foundation.Image(
                    painter            = BitmapPainter(state.albumArt.asImageBitmap()),
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.size(43.dp).clip(RoundedCornerShape(12.dp))
                )
            } else {
                Icon(Icons.Default.MusicNote, null, tint = accent, modifier = Modifier.size(23.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text       = state.title,
            color      = textColor,
            fontSize   = 17.sp,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis,
            modifier   = Modifier.widthIn(max = 120.dp)
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
