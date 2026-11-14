package com.nromanovich.stealthsnake

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureManagerTest {

    @Test
    fun `capture writes PNG without EXIF in private sandbox and preserves View visibility`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = View(activity)
        activity.setContentView(view)
        view.layout(0, 0, 120, 80)

        val executor = Executors.newSingleThreadExecutor()
        val fakeRequester = CaptureManager.PixelCopyRequester { _, _, destBitmap, listener, handler ->
            destBitmap.eraseColor(Color.rgb(18, 52, 86))
            handler.post { listener.onPixelCopyFinished(PixelCopy.SUCCESS) }
        }
        val manager = CaptureManager(
            context = activity,
            mainHandler = Handler(Looper.getMainLooper()),
            ioExecutor = executor,
            pixelCopyRequester = fakeRequester
        )

        var result: Result<File>? = null
        manager.capture(activity.window, view) { result = it }

        shadowOf(Looper.getMainLooper()).idle()
        executor.shutdown()
        while (!executor.isTerminated) {
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()

        val file = result!!.getOrThrow()
        assertTrue(file.exists())
        assertTrue(file.parentFile!!.absolutePath.startsWith(activity.filesDir.absolutePath))
        assertEquals(View.VISIBLE, view.visibility)
        assertTrue(view.isAttachedToWindow)

        val bytes = file.readBytes()
        val pngMagic = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        assertArrayEquals(pngMagic, bytes.copyOfRange(0, 8))
        assertFalse("PNG must not contain EXIF chunk", bytes.decodeToString().contains("eXIf"))

        val decoded = BitmapFactory.decodeFile(file.absolutePath)
        assertEquals(120, decoded.width)
        assertEquals(80, decoded.height)
        assertEquals(Color.rgb(18, 52, 86), decoded.getPixel(10, 10))

        assertEquals(1, manager.listCaptures().size)
        assertEquals(1, manager.clearCaptures())
        assertEquals(0, manager.listCaptures().size)
    }

    @Test
    fun `capture reports failure cleanly when PixelCopy fails or view is not laid out`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val detachedView = View(activity)
        val manager = CaptureManager(activity)

        var detachedResult: Result<File>? = null
        manager.capture(activity.window, detachedView) { detachedResult = it }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(detachedResult!!.isFailure)

        val attachedView = View(activity)
        activity.setContentView(attachedView)
        attachedView.layout(0, 0, 100, 100)

        val failingManager = CaptureManager(
            context = activity,
            pixelCopyRequester = { _, _, _, listener, handler ->
                handler.post { listener.onPixelCopyFinished(PixelCopy.ERROR_UNKNOWN) }
            }
        )
        var failResult: Result<File>? = null
        failingManager.capture(activity.window, attachedView) { failResult = it }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(failResult!!.isFailure)

        // Calling capture after shutdown must fail cleanly without throwing RejectedExecutionException.
        var shutdownResult: Result<File>? = null
        val shutManager = CaptureManager(
            context = activity,
            mainHandler = Handler(Looper.getMainLooper()),
            pixelCopyRequester = { _, _, destBitmap, listener, handler ->
                destBitmap.eraseColor(Color.BLACK)
                handler.post { listener.onPixelCopyFinished(PixelCopy.SUCCESS) }
            }
        )
        shutManager.shutdown()
        shutManager.capture(activity.window, attachedView) { shutdownResult = it }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shutdownResult!!.isFailure)

        // deleteCapture must refuse files outside private capturesDir.
        val outsideFile = File(activity.filesDir, "outside.png").apply { writeText("keep") }
        assertFalse(manager.deleteCapture(outsideFile))
        assertTrue(outsideFile.exists())
        outsideFile.delete()

        manager.shutdown()
        failingManager.shutdown()
    }
}
