package com.openlauncher.app.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.openlauncher.app.ui.widget.PipWidget

class PipDividerProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val leftPackage = intent.getStringExtra(EXTRA_LEFT_PACKAGE) ?: DEFAULT_LEFT_PACKAGE
        val rightPackage = intent.getStringExtra(EXTRA_RIGHT_PACKAGE) ?: DEFAULT_RIGHT_PACKAGE

        setContent {
            var split by remember { mutableFloatStateOf(0.5f) }
            PipWidget(
                packageNames = listOf(leftPackage, rightPackage),
                appCount = 2,
                splitFraction = split,
                accent = Color(0xFFFFCC33),
                isDayMode = false,
                isEditing = false,
                onAssign = {},
                onSplitChange = { split = it },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    companion object {
        const val EXTRA_LEFT_PACKAGE = "left_package"
        const val EXTRA_RIGHT_PACKAGE = "right_package"
        private const val DEFAULT_LEFT_PACKAGE = "com.openlauncher.pipprobe"
        private const val DEFAULT_RIGHT_PACKAGE = "app.morphe.android.apps.maps"
    }
}
