package com.openlauncher.app.ui.widget

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.model.WeatherState
import com.openlauncher.app.R

@Composable
fun WeatherWidget(
    state: WeatherState?,
    accent: Color,
    metric: Boolean,
    isDayMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val contentColor = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
    val subColor     = if (isDayMode) Color(0xFF888888) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)

    Box(modifier = modifier) {
        if (state != null) {
            Column(
                modifier            = Modifier.fillMaxSize().padding(start = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text     = state.conditionIcon,
                    fontSize = 34.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text       = state.temperatureDisplay(metric),
                    color      = contentColor,
                    fontSize   = 32.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 1.sp
                )
                Text(
                    text          = stringResource(when (state.weatherCode) {
                        0 -> R.string.weather_clear
                        1, 2, 3 -> R.string.weather_cloudy
                        45, 48 -> R.string.weather_foggy
                        51, 53, 55 -> R.string.weather_drizzle
                        61, 63, 65 -> R.string.weather_rain
                        71, 73, 75 -> R.string.weather_snow
                        80, 81, 82 -> R.string.weather_showers
                        95 -> R.string.weather_thunderstorm
                        96, 99 -> R.string.weather_hail
                        else -> R.string.weather_unknown
                    }).uppercase(),
                    color         = subColor,
                    fontSize      = 9.sp,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}
