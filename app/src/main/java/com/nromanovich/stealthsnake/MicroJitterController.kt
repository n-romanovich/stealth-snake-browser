package com.nromanovich.stealthsnake

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.security.SecureRandom

/**
 * Generates subtle native Android [MotionEvent] touch sequences (`isTrusted === true` in DOM)
 * when the user is idle for 12-20 seconds, neutralizing idle-freeze behavioral heuristics
 * while keeping the browser always-focused.
 */
class MicroJitterController(
    private val targetView: View,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    @Volatile private var minIdleMs: Long = DEFAULT_MIN_IDLE_MS,
    @Volatile private var maxIdleMs: Long = DEFAULT_MAX_IDLE_MS,
    private val stepDelayMs: Long = DEFAULT_STEP_DELAY_MS,
    private val uptimeProvider: () -> Long = SystemClock::uptimeMillis,
    private val random: SecureRandom = SecureRandom()
) {
    init {
        require(minIdleMs > 0 && maxIdleMs >= minIdleMs) {
            "Invalid idle range [$minIdleMs, $maxIdleMs]"
        }
        require(stepDelayMs >= 0) { "stepDelayMs must be non-negative" }
    }

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var isDispatchingSynthetic: Boolean = false
        private set

    var syntheticCount: Int = 0
        private set

    private val triggerRunnable = Runnable { performJitterCycle() }
    private var pendingMoveRunnable: Runnable? = null
    private var pendingUpRunnable: Runnable? = null
    private var hasOpenGesture: Boolean = false
    private var openDownTime: Long = 0L
    private var openX: Float = 0f
    private var openY: Float = 0f

    fun start() {
        if (isRunning) return
        isRunning = true
        scheduleNext()
    }

    fun stop() {
        isRunning = false
        cancelPending()
    }

    fun onRealUserInteraction() {
        if (!isRunning) return
        cancelPending()
        scheduleNext()
    }

    fun updateIdleRange(newMinMs: Long, newMaxMs: Long) {
        require(newMinMs > 0 && newMaxMs >= newMinMs) {
            "Invalid idle range [$newMinMs, $newMaxMs]"
        }
        minIdleMs = newMinMs
        maxIdleMs = newMaxMs
        if (isRunning) {
            cancelPending()
            scheduleNext()
        }
    }

    internal fun nextIdleDelayMs(): Long {
        if (maxIdleMs == minIdleMs) return minIdleMs
        val span = maxIdleMs - minIdleMs + 1
        return minIdleMs + (random.nextLong().ushr(1) % span)
    }

    private fun scheduleNext() {
        if (!isRunning) return
        handler.postDelayed(triggerRunnable, nextIdleDelayMs())
    }

    private fun cancelPending() {
        handler.removeCallbacks(triggerRunnable)
        pendingMoveRunnable?.let { handler.removeCallbacks(it) }
        pendingUpRunnable?.let { handler.removeCallbacks(it) }
        pendingMoveRunnable = null
        pendingUpRunnable = null
        if (hasOpenGesture) {
            hasOpenGesture = false
            if (targetView.isAttachedToWindow) {
                dispatchSyntheticTouch(openDownTime, uptimeProvider(), MotionEvent.ACTION_CANCEL, openX, openY)
            }
        }
    }

    private fun performJitterCycle() {
        if (!isRunning) return
        if (!targetView.isAttachedToWindow) {
            stop()
            return
        }
        val touchSlop = runCatching {
            ViewConfiguration.get(targetView.context).scaledTouchSlop.toFloat()
        }.getOrDefault(8f).coerceAtLeast(8f)
        val deltaY = touchSlop + 6f
        if (targetView.width <= 4 || targetView.height <= deltaY + 8f) {
            scheduleNext()
            return
        }

        // Neutral edge coordinates + displacement exceeding touchSlop so WebView treats it as
        // a micro-drag/scroll rather than a tap/click on page elements.
        val x = 12f.coerceAtMost((targetView.width - 4).toFloat())
        val baseY = (targetView.height * 0.5f) + (random.nextInt(21) - 10)
        val y = baseY.coerceIn(4f, targetView.height - deltaY - 4f)
        val downTime = uptimeProvider()
        val pressure = 0.40f + random.nextFloat() * 0.25f

        hasOpenGesture = true
        openDownTime = downTime
        openX = x
        openY = y
        dispatchSyntheticTouch(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, pressure)

        val moveRunnable = Runnable {
            if (!isRunning || !hasOpenGesture) return@Runnable
            openY = y + deltaY
            dispatchSyntheticTouch(downTime, uptimeProvider(), MotionEvent.ACTION_MOVE, x, openY, pressure)

            val upRunnable = Runnable {
                if (!isRunning || !hasOpenGesture) return@Runnable
                hasOpenGesture = false
                dispatchSyntheticTouch(downTime, uptimeProvider(), MotionEvent.ACTION_CANCEL, x, openY, 0f)
                syntheticCount++
                pendingMoveRunnable = null
                pendingUpRunnable = null
                scheduleNext()
            }
            pendingUpRunnable = upRunnable
            handler.postDelayed(upRunnable, stepDelayMs)
        }
        pendingMoveRunnable = moveRunnable
        handler.postDelayed(moveRunnable, stepDelayMs)
    }

    private fun dispatchSyntheticTouch(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
        pressure: Float = 0.45f
    ) {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            this.pressure = pressure
            this.size = 0.08f
        })
        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
        isDispatchingSynthetic = true
        try {
            targetView.dispatchTouchEvent(event)
        } finally {
            isDispatchingSynthetic = false
            event.recycle()
        }
    }

    companion object {
        const val DEFAULT_MIN_IDLE_MS = 12_000L
        const val DEFAULT_MAX_IDLE_MS = 20_000L
        const val DEFAULT_STEP_DELAY_MS = 40L
    }
}
