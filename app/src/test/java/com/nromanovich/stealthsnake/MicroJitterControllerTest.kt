package com.nromanovich.stealthsnake

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MicroJitterControllerTest {

    @Test
    fun `dispatches DOWN MOVE UP sequence with touchscreen source after idle timeout`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val touchSlop = android.view.ViewConfiguration.get(activity).scaledTouchSlop.toFloat()
        val recorded = mutableListOf<Triple<Int, Float, Float>>()
        val recordedPressures = mutableListOf<Float>()
        val syntheticFlagsInsideDispatch = mutableListOf<Boolean>()
        lateinit var controller: MicroJitterController

        val spyView = object : View(activity) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                recorded.add(Triple(event.actionMasked, event.x, event.y))
                recordedPressures.add(event.pressure)
                syntheticFlagsInsideDispatch.add(controller.isDispatchingSynthetic)
                assertEquals(InputDevice.SOURCE_TOUCHSCREEN, event.source)
                return true
            }
        }
        activity.setContentView(spyView)
        spyView.layout(0, 0, 400, 800)

        controller = MicroJitterController(
            targetView = spyView,
            handler = Handler(Looper.getMainLooper()),
            minIdleMs = 100L,
            maxIdleMs = 100L,
            stepDelayMs = 20L
        )

        controller.start()
        assertTrue(controller.isRunning)

        // Before idle timeout elapses, no synthetic events may fire.
        shadowOf(Looper.getMainLooper()).idleFor(80, TimeUnit.MILLISECONDS)
        assertTrue(recorded.isEmpty())

        // Cross idle timeout -> ACTION_DOWN with realistic finger pressure.
        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.MILLISECONDS)
        assertEquals(1, recorded.size)
        assertEquals(MotionEvent.ACTION_DOWN, recorded[0].first)
        assertTrue("Expected positive touch pressure", recordedPressures[0] > 0f)
        assertEquals(listOf(true), syntheticFlagsInsideDispatch)
        assertFalse(controller.isDispatchingSynthetic)

        // Advance past MOVE (> touchSlop so WebView does not treat it as a tap) and CANCEL delays.
        shadowOf(Looper.getMainLooper()).idleFor(25, TimeUnit.MILLISECONDS)
        assertEquals(2, recorded.size)
        assertEquals(MotionEvent.ACTION_MOVE, recorded[1].first)
        assertEquals(recorded[0].second, recorded[1].second, 0.001f)
        val dy = kotlin.math.abs(recorded[1].third - recorded[0].third)
        assertTrue("Expected drag displacement ($dy) > touchSlop ($touchSlop)", dy > touchSlop)
        assertTrue("Expected positive MOVE pressure", recordedPressures[1] > 0f)

        shadowOf(Looper.getMainLooper()).idleFor(25, TimeUnit.MILLISECONDS)
        assertEquals(3, recorded.size)
        assertEquals(MotionEvent.ACTION_CANCEL, recorded[2].first)
        assertEquals(listOf(true, true, true), syntheticFlagsInsideDispatch)
        assertFalse(controller.isDispatchingSynthetic)
        assertEquals(1, controller.syntheticCount)

        controller.stop()
        assertFalse(controller.isRunning)
    }

    @Test
    fun `real user interaction postpones synthetic jitter and nextIdleDelay stays in range`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val recorded = mutableListOf<Int>()
        val spyView = object : View(activity) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                recorded.add(event.actionMasked)
                return true
            }
        }
        activity.setContentView(spyView)
        spyView.layout(0, 0, 300, 600)

        val defaultController = MicroJitterController(spyView)
        repeat(50) {
            val d = defaultController.nextIdleDelayMs()
            assertTrue("Delay $d out of bounds", d in 12_000L..20_000L)
        }

        val controller = MicroJitterController(
            targetView = spyView,
            handler = Handler(Looper.getMainLooper()),
            minIdleMs = 100L,
            maxIdleMs = 100L,
            stepDelayMs = 20L
        )
        controller.start()

        shadowOf(Looper.getMainLooper()).idleFor(70, TimeUnit.MILLISECONDS)
        controller.onRealUserInteraction()
        shadowOf(Looper.getMainLooper()).idleFor(70, TimeUnit.MILLISECONDS)
        assertTrue("Timer should have reset on real interaction", recorded.isEmpty())

        shadowOf(Looper.getMainLooper()).idleFor(40, TimeUnit.MILLISECONDS)
        assertEquals(listOf(MotionEvent.ACTION_DOWN), recorded)
        // Stopping mid-gesture must emit ACTION_CANCEL so WebView does not see a stuck pointer.
        controller.stop()
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), recorded)
    }
}
