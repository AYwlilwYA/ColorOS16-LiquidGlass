package com.dreamglass.cos17

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import com.coloros16.liquidglass.hook.LockWallpaperBackdrop
import org.json.JSONObject
import java.io.File

/** Owned synthetic wallpapers test production asynchronous GPU preparation.
 * Does not claim OEM wallpaper acquisition or physical lockscreen visual acceptance. */
class LockWallpaperProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var label: TextView
    private val checks = linkedMapOf<String, Boolean>()
    private val output by lazy { File(filesDir, "lock-wallpaper-probe").apply { mkdirs() } }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        label = TextView(this).apply { text = "Wallpaper GPU input probe" }
        setContentView(label)
        label.post {
            LockWallpaperBackdrop.changed()
            val first = wallpaper(Color.GREEN)
            LockWallpaperBackdrop.accept(first)
            awaitWallpaper("first-lock-wallpaper", Color.GREEN) { firstOutput ->
                checks["cached-source-reused"] = LockWallpaperBackdrop.get(this) === firstOutput
                // A new native wallpaper must replace the prepared source, not reuse the old scene.
                LockWallpaperBackdrop.accept(wallpaper(Color.BLUE))
                checks["old-scene-immediately-invalidated"] = LockWallpaperBackdrop.get(this) == null
                awaitWallpaper("new-lock-wallpaper", Color.BLUE) { secondOutput ->
                    checks["distinct-generation-output"] = firstOutput !== secondOutput
                    LockWallpaperBackdrop.changed()
                    // No privileged wallpaper read is performed here after invalidation.
                    checks["changed-event-clears-prepared-image"] = preparedImage() == null
                    finishReport()
                }
            }
        }
    }
    private fun wallpaper(color: Int) = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).drawColor(color)
    }
    private fun preparedImage(): Any? = LockWallpaperBackdrop.javaClass.getDeclaredField("image").apply {
        isAccessible = true
    }.get(LockWallpaperBackdrop)
    private fun awaitWallpaper(name: String, expected: Int, ready: (Bitmap) -> Unit) {
        val deadline = SystemClock.uptimeMillis() + 8_000L
        fun poll() {
            try {
                val bitmap = LockWallpaperBackdrop.get(this)
                if (bitmap != null) {
                    checks["$name-hardware-output"] = bitmap.config == Bitmap.Config.HARDWARE
                    val pixels = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: error("GPU readback failed")
                    val color = pixels.getPixel(pixels.width / 2, pixels.height / 2)
                    checks["$name-correct-source"] = kotlin.math.abs(Color.red(color) - Color.red(expected)) <= 2 &&
                        kotlin.math.abs(Color.green(color) - Color.green(expected)) <= 2 &&
                        kotlin.math.abs(Color.blue(color) - Color.blue(expected)) <= 2
                    File(output, "$name.png").outputStream().use { pixels.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    pixels.recycle()
                    ready(bitmap)
                } else if (SystemClock.uptimeMillis() < deadline) main.postDelayed({ poll() }, 50L)
                else { checks["$name-ready"] = false; finishReport() }
            } catch (error: Throwable) { finishReport(error.toString()) }
        }
        poll()
    }
    private fun finishReport(error: String? = null) {
        val report = JSONObject().put("checks", JSONObject(checks as Map<*, *>))
            .put("passed", error == null && checks.isNotEmpty() && checks.values.all { it })
            .put("physicalVisualVerified", false).put("oemWallpaperAcquisitionVerified", false)
            .put("displayCaptureUsed", false)
        if (error != null) report.put("error", error)
        File(output, "report.json").writeText(report.toString(2))
        label.text = report.toString(2)
    }
}
