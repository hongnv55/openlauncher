package com.openlauncher.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.openlauncher.app.model.AppInfo
import com.openlauncher.app.R
import com.openlauncher.app.ui.theme.LocalDayMode
import com.openlauncher.app.ui.theme.PaneInset
import com.openlauncher.app.ui.theme.contentPane

// FAVORITED first — it's the default-selected/focused tab on open.
private enum class AppFilter { FAVORITED, USER, SYSTEM, ALL }

// Matches WIDGET_RADIUS/SIDEBAR_RADIUS — one shared corner-radius language
// across Home's sidebar/PIP panel and the App Library grid.
private val TILE_RADIUS = RoundedCornerShape(12.dp)

@Composable
fun AppLibraryScreen(
    apps: List<AppInfo>,
    isLoading: Boolean,
    isPickerMode: Boolean,
    pickerSlot: Int?,
    isCarPlayPickerMode: Boolean,
    carPlayPickerLabel: String? = null,
    accent: Color,
    iconScale: Float = 1.4f,
    gridColumns: Int = 6,
    gridRows: Int = 3,
    favoriteApps: List<String> = emptyList(),
    onAppClick: (AppInfo) -> Unit,
    onPickerSelect: (Int, AppInfo) -> Unit,
    onCarPlaySelect: (AppInfo) -> Unit,
    onToggleFavorite: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isDayMode     = LocalDayMode.current
    val headerColor   = MaterialTheme.colorScheme.onBackground
    val placeholderC  = if (isDayMode) Color(0xFF999999) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
    val dividerColor  = if (isDayMode) Color(0xFFCCCCCC) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f)
    val emptyColor    = if (isDayMode) Color(0xFF888888) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
    val fieldTextC    = MaterialTheme.colorScheme.onBackground
    val fieldBorderU  = if (isDayMode) Color(0xFFCCCCCC) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f)

    val anyPickerMode = isPickerMode || isCarPlayPickerMode
    var query     by remember { mutableStateOf("") }
    // Favorited is the tab that's focused first on open.
    var appFilter by remember { mutableStateOf(AppFilter.FAVORITED) }

    val filtered = remember(apps, query, appFilter, anyPickerMode, favoriteApps) {
        val byName = if (query.isBlank()) apps
                     else apps.filter { it.appName.contains(query, ignoreCase = true) }
        // In picker mode always show everything so shortcuts can be set to any app
        if (anyPickerMode) byName
        else when (appFilter) {
            AppFilter.FAVORITED -> byName.filter { it.packageName in favoriteApps }
            AppFilter.USER   -> byName.filter { !it.isSystemApp }
            AppFilter.SYSTEM -> byName.filter { it.isSystemApp }
            AppFilter.ALL    -> byName
        }
    }

    // The same content card a PIP pane gets, at the same inset, so navigating
    // Home -> Apps swaps what is inside one persistent frame instead of
    // replacing the whole screen. It also drops the old full-bleed
    // colorScheme.background fill, which was painting the pre-wallpaper
    // background colour straight over the wallpaper.
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(PaneInset)
            .contentPane(isDayMode, accent)
    ) {
        // ── Header ─────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text          = when {
                    isCarPlayPickerMode -> carPlayPickerLabel ?: stringResource(R.string.choose_carplay_app)
                    anyPickerMode       -> stringResource(R.string.choose_app)
                    else                -> stringResource(R.string.apps)
                },
                style         = MaterialTheme.typography.titleLarge,
                color         = if (anyPickerMode) accent else headerColor,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontSize      = 20.sp
            )
            if (!anyPickerMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = appFilter == filter,
                            onClick  = { appFilter = filter },
                            label    = {
                                Text(
                                    when (filter) {
                                        AppFilter.FAVORITED -> stringResource(R.string.filter_favorited)
                                        AppFilter.USER   -> stringResource(R.string.filter_installed)
                                        AppFilter.SYSTEM -> stringResource(R.string.filter_system)
                                        AppFilter.ALL    -> stringResource(R.string.filter_all)
                                    },
                                    fontSize = 13.sp,
                                    letterSpacing = 0.5.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accent,
                                selectedLabelColor     = Color.Black,
                                labelColor             = placeholderC
                            )
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            var searchFocused by remember { mutableStateOf(false) }
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .width(200.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, if (searchFocused) accent else fieldBorderU, RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Search, null, tint = placeholderC, modifier = Modifier.size(14.dp))
                    BasicTextField(
                        value         = query,
                        onValueChange = { query = it },
                        singleLine    = true,
                        textStyle     = TextStyle(color = fieldTextC, fontSize = 13.sp),
                        cursorBrush   = SolidColor(accent),
                        modifier      = Modifier
                            .weight(1f)
                            .onFocusChanged { searchFocused = it.isFocused },
                        decorationBox = { inner ->
                            Box {
                                if (query.isEmpty()) Text(stringResource(R.string.search_hint), color = placeholderC, fontSize = 13.sp)
                                inner()
                            }
                        }
                    )
                }
            }
        }

        HorizontalDivider(color = dividerColor)

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = accent, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            return@Column
        }

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (appFilter == AppFilter.FAVORITED && !anyPickerMode)
                        stringResource(R.string.no_favorited_apps)
                    else stringResource(R.string.no_apps_found),
                    color = emptyColor, letterSpacing = 1.sp, fontSize = 12.sp
                )
            }
            return@Column
        }

        // ── App grid ────────────────────────────────────────────────────────────
        // Columns come from GridCells.Fixed (Compose divides width evenly on its
        // own); row height doesn't have an equivalent built-in, so it's computed
        // here from the measured available height / gridRows, keeping the grid
        // fully filled instead of the tile size just following icon scale.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val rows = gridRows.coerceAtLeast(1)
            val tileHeight = ((maxHeight - 12.dp - 4.dp * (rows - 1)) / rows).coerceAtLeast(40.dp)

            LazyVerticalGrid(
                columns               = GridCells.Fixed(gridColumns.coerceAtLeast(1)),
                contentPadding        = PaddingValues(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement   = Arrangement.spacedBy(4.dp),
                modifier              = Modifier.fillMaxSize()
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    AppTile(
                        app        = app,
                        accent     = accent,
                        iconScale  = iconScale,
                        tileHeight = tileHeight,
                        isFavorite = app.packageName in favoriteApps,
                        // Star toggle only in normal browse mode — in picker
                        // mode a tap is meant to pick the app for assignment,
                        // and the filter chips (Favorited included) are
                        // already hidden there too.
                        onToggleFavorite = if (anyPickerMode) null else {
                            { onToggleFavorite(app.packageName) }
                        },
                        onClick = {
                            when {
                                isCarPlayPickerMode            -> onCarPlaySelect(app)
                                isPickerMode && pickerSlot != null -> onPickerSelect(pickerSlot, app)
                                else                           -> onAppClick(app)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppTile(
    app: AppInfo,
    accent: Color,
    iconScale: Float,
    tileHeight: Dp,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val isDayMode  = LocalDayMode.current
    val tileBg     = if (isDayMode) Color(0xFFFFFFFF) else Color(0xFF0B0B0B)
    val tileBorder = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF1A1A1A)
    val iconSize   = 40.dp * iconScale
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(tileHeight)
            .clip(TILE_RADIUS)
            .background(tileBg)
            .border(1.dp, tileBorder, TILE_RADIUS)
            .clickable(onClick = onClick)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(7.dp)
        ) {
            val bmp = remember(app.packageName, iconScale) {
                val px = (80 * iconScale).toInt()
                try { app.icon.toBitmap(px, px) } catch (_: Exception) { null }
            }
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    painter            = BitmapPainter(bmp.asImageBitmap()),
                    contentDescription = app.appName,
                    modifier           = Modifier.size(iconSize)
                )
            } else {
                Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
                    Text(app.appName.take(1).uppercase(), color = accent, fontSize = 18.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text          = app.appName.uppercase(),
                style         = MaterialTheme.typography.labelSmall,
                color         = if (isDayMode) Color(0xFF666666) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                maxLines      = 2,
                overflow      = TextOverflow.Clip,
                textAlign     = TextAlign.Center,
                letterSpacing = 0.5.sp,
                lineHeight    = 12.sp,
                fontSize      = 11.sp
            )
        }

        if (onToggleFavorite != null) {
            val starTint = if (isFavorite) Color(0xFFFFC107) else if (isDayMode) Color(0xFFBBBBBB) else Color(0xFF444444)
            Icon(
                imageVector        = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = stringResource(if (isFavorite) R.string.unfavorite else R.string.favorite),
                tint               = starTint,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(32.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication        = null,
                        onClick           = onToggleFavorite
                    )
                    .padding(2.dp)
            )
        }
    }
}
