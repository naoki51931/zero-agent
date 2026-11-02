package com.zeroagent.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.io.File

/**
 * Save a crop of the rendered SeaArt result as a preview, not the original generated file.
 * The source remains a screen capture; user should review before posting.
 */
object SeaArtResultCapture {
    suspend fun capture(service: AccessibilityService, id: String, bounds: Rect): File? {
        if (Build.VERSION.SDK_INT < 30) return null
        return suspendCancellableCoroutine { continuation ->
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        var bitmap: Bitmap? = null
                        try {
                            val buffer = result.hardwareBuffer
                            try {
                                val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                    ?: error("Screenshot unavailable")
                                bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
                                hardware.recycle()
                            } finally { buffer.close() }
                            val frame = bitmap ?: error("Screenshot unavailable")
                            val left = bounds.left.coerceIn(0, frame.width - 1)
                            val top = bounds.top.coerceIn(0, frame.height - 1)
                            val width = (bounds.right - left).coerceAtMost(frame.width - left)
                            val height = (bounds.bottom - top).coerceAtMost(frame.height - top)
                            require(width >= 256 && height >= 256) { "Result image too small" }
                            val crop = Bitmap.createBitmap(frame, left, top, width, height)
                            val file = File(service.cacheDir, "seaart_preview_$id.png")
                            try {
                                file.outputStream().use {
                                    check(crop.compress(Bitmap.CompressFormat.PNG, 100, it))
                                }
                            } finally { crop.recycle() }
                            if (continuation.isActive) continuation.resume(file)
                        } catch (_: Exception) {
                            if (continuation.isActive) continuation.resume(null)
                        } finally { bitmap?.recycle() }
                    }
                })
        }
    }
}
