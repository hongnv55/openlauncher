package com.openlauncher.app.debug

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.widget.LinearLayout
import android.widget.TextView
import com.openlauncher.app.ui.widget.EmbeddedSurfaceView
import com.openlauncher.app.ui.widget.TaskEmbedder

class PipPlacementProbeActivity : Activity() {
    private lateinit var embedder: TaskEmbedder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
            ?: DEFAULT_TARGET_PACKAGE
        embedder = TaskEmbedder(this)

        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(24, 24, 24))
            textSize = 16f
            setPadding(24, 16, 24, 16)
            text = "PIP placement probe\ntarget=$targetPackage\nwaiting for Surface"
        }
        val surface = EmbeddedSurfaceView(this).apply {
            this.embedder = this@PipPlacementProbeActivity.embedder
            inputEnabled = true
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    val frame = holder.surfaceFrame
                    val densityDpi = resources.displayMetrics.densityDpi
                    status.text = "PIP placement probe\ntarget=$targetPackage\n" +
                        "surface=${frame.width()}x${frame.height()} density=$densityDpi"
                    this@PipPlacementProbeActivity.embedder.attach(
                        targetPackage,
                        holder.surface,
                        frame.width(),
                        frame.height(),
                        densityDpi
                    ) { success ->
                        status.post {
                            status.append("\nlaunchAccepted=$success\nSee TaskEmbedder logs for actual display")
                        }
                    }
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    this@PipPlacementProbeActivity.embedder.resize(
                        width,
                        height,
                        resources.displayMetrics.densityDpi
                    )
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    this@PipPlacementProbeActivity.embedder.detach()
                }
            })
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(status, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(surface, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
        })
        Log.i(TAG, "started target=$targetPackage")
    }

    override fun onDestroy() {
        embedder.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TARGET_PACKAGE = "target_package"
        private const val DEFAULT_TARGET_PACKAGE = "com.openlauncher.pipprobe"
        private const val TAG = "PipPlacementProbe"
    }
}
