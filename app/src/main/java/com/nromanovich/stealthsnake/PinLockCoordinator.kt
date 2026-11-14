package com.nromanovich.stealthsnake

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.VisibleForTesting
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import java.util.Collections
import java.util.WeakHashMap

/**
 * Coordinates PIN lock state across every Activity in the app.
 *
 * Tracks started Activities so intra-app navigation (e.g. MainActivity -> SettingsActivity -> Back)
 * never triggers false timeouts, while leaving the app to background from ANY Activity arms the
 * background lock timer and protects whichever Activity returns to the foreground.
 */
object PinLockCoordinator : Application.ActivityLifecycleCallbacks {

    interface PinProtectedHost {
        fun onPinLockRequired()
        fun onPinUnlocked()
        fun onPinAutoWipeRequested()
    }

    private data class SecondaryLockState(
        val overlay: View,
        val contentChild: View?,
        val prevVisibility: Int,
        val prevImportanceForA11y: Int,
        val backCallback: OnBackPressedCallback?
    )

    private val registeredApps = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Application, Boolean>())
    )
    private val startedActivityIds = mutableSetOf<Int>()
    private val secondaryLocks = mutableMapOf<Int, SecondaryLockState>()

    @Volatile
    private var unlockedForSession: Boolean = false

    @Volatile
    private var lastBackgroundAtMs: Long = 0L

    @Volatile
    private var clockProvider: () -> Long = { SystemClock.elapsedRealtime() }

    fun ensureRegistered(context: Context) {
        val app = context.applicationContext as? Application ?: return
        if (registeredApps.add(app)) {
            app.registerActivityLifecycleCallbacks(this)
        }
    }

    fun isLocked(prefs: SnakePreferences): Boolean =
        prefs.pinLockEnabled && !unlockedForSession

    fun markUnlocked() {
        unlockedForSession = true
        lastBackgroundAtMs = 0L
    }

    fun onPinConfigured() {
        unlockedForSession = false
        lastBackgroundAtMs = 0L
    }

    fun onPinCleared() {
        unlockedForSession = false
        lastBackgroundAtMs = 0L
    }

    @VisibleForTesting
    fun resetForTests(clock: () -> Long = { SystemClock.elapsedRealtime() }) {
        startedActivityIds.clear()
        secondaryLocks.clear()
        unlockedForSession = false
        lastBackgroundAtMs = 0L
        clockProvider = clock
    }

    @VisibleForTesting
    fun setClockForTests(clock: () -> Long) {
        clockProvider = clock
    }

    internal enum class PinDotState {
        FILLED_DOT
    }

    internal class PinStepIndicatorView(
        context: Context,
        private val density: Float,
        state: PinDotState,
        private val dotColor: Int
    ) : View(context) {

        var state: PinDotState = state
            private set

        private var popScale: Float = 1f
        private var popAnim: ValueAnimator? = null
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        fun triggerPopAnimation() {
            popAnim?.cancel()
            popScale = 0.45f
            popAnim = ValueAnimator.ofFloat(0.45f, 1f).apply {
                duration = 180L
                interpolator = OvershootInterpolator(1.5f)
                addUpdateListener {
                    popScale = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = (24 * density + 0.5f).toInt()
            setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            fillPaint.color = dotColor
            canvas.drawCircle(w / 2f, h / 2f, 5.8f * density * popScale, fillPaint)
        }

        override fun onDetachedFromWindow() {
            popAnim?.cancel()
            popAnim = null
            super.onDetachedFromWindow()
        }
    }

    internal fun renderPinStepRow(
        row: LinearLayout,
        enteredLength: Int,
        prevLength: Int,
        dotColor: Int,
        density: Float
    ) {
        val totalSlots = enteredLength.coerceAtLeast(0)
        while (row.childCount > totalSlots) {
            row.removeViewAt(row.childCount - 1)
        }
        while (row.childCount < totalSlots) {
            val dotView = PinStepIndicatorView(row.context, density, PinDotState.FILLED_DOT, dotColor).apply {
                val marginH = (4.5f * density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    (24 * density).toInt(),
                    (24 * density).toInt()
                ).apply {
                    setMargins(marginH, 0, marginH, 0)
                }
            }
            row.addView(dotView)
        }
        if (enteredLength > prevLength && totalSlots > 0) {
            (row.getChildAt(totalSlots - 1) as? PinStepIndicatorView)?.triggerPopAnimation()
        }
    }

    private class PinDigitKeyView(
        context: Context,
        digit: String,
        private val density: Float,
        private val normalBg: Int,
        private val pressedBg: Int,
        private val normalText: Int,
        private val pressedText: Int
    ) : AppCompatTextView(context) {

        private var pressProgress = 0f
        private var pressAnim: ValueAnimator? = null
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val rect = RectF()

        init {
            text = digit
            textSize = 28f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(normalText)
            background = null
        }

        private fun animateDown() {
            pressAnim?.cancel()
            pressAnim = ValueAnimator.ofFloat(pressProgress, 1f).apply {
                duration = 75L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    pressProgress = it.animatedValue as Float
                    setTextColor(ColorUtils.blendARGB(normalText, pressedText, pressProgress))
                    invalidate()
                }
                start()
            }
        }

        private fun animateUp() {
            val current = pressProgress
            pressAnim?.cancel()
            pressAnim = if (current < 0.85f) {
                ValueAnimator.ofFloat(current, 1f, 1f, 0f).apply {
                    duration = 260L
                    interpolator = DecelerateInterpolator()
                    addUpdateListener {
                        pressProgress = it.animatedValue as Float
                        setTextColor(ColorUtils.blendARGB(normalText, pressedText, pressProgress))
                        invalidate()
                    }
                    start()
                }
            } else {
                ValueAnimator.ofFloat(current, 0f).apply {
                    duration = 190L
                    interpolator = DecelerateInterpolator()
                    addUpdateListener {
                        pressProgress = it.animatedValue as Float
                        setTextColor(ColorUtils.blendARGB(normalText, pressedText, pressProgress))
                        invalidate()
                    }
                    start()
                }
            }
        }

        private fun pulseClick() {
            pressAnim?.cancel()
            pressAnim = ValueAnimator.ofFloat(0f, 1f, 1f, 0f).apply {
                duration = 260L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    pressProgress = it.animatedValue as Float
                    setTextColor(ColorUtils.blendARGB(normalText, pressedText, pressProgress))
                    invalidate()
                }
                start()
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> animateDown()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animateUp()
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            if (pressAnim?.isRunning != true && pressProgress < 0.2f) pulseClick()
            return super.performClick()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w > 0f && h > 0f) {
                val circleR = minOf(w, h) / 2f
                val squareR = 15f * density
                val radius = circleR + (squareR - circleR) * pressProgress

                rect.set(0f, 0f, w, h)
                fillPaint.color = ColorUtils.blendARGB(normalBg, pressedBg, pressProgress)
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
            }
            super.onDraw(canvas)
        }

        override fun onDetachedFromWindow() {
            pressAnim?.cancel()
            pressAnim = null
            super.onDetachedFromWindow()
        }
    }

    private class PinBackspaceKeyView(
        context: Context,
        private val density: Float,
        private val iconTint: Int
    ) : AppCompatTextView(context) {

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val tagPath = Path()

        init {
            text = "⌫"
            setTextColor(Color.TRANSPARENT)
            gravity = Gravity.CENTER
            background = null
        }

        private fun animateScale(dur: Long, scale: Float) {
            animate().cancel()
            animate().scaleX(scale).scaleY(scale).setDuration(dur).start()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> animateScale(90L, 0.9f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animateScale(180L, 1f)
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cx = w / 2f
            val cy = h / 2f

            // Material You backspace pentagon tag with centered X
            strokePaint.color = iconTint
            val halfH = 8.2f * density
            val leftX = cx - 12.2f * density
            val bodyLeftX = cx - 5.2f * density
            val rightX = cx + 11.2f * density
            val cr = 3.2f * density

            tagPath.reset()
            tagPath.moveTo(leftX, cy)
            tagPath.lineTo(bodyLeftX, cy - halfH)
            tagPath.lineTo(rightX - cr, cy - halfH)
            tagPath.quadTo(rightX, cy - halfH, rightX, cy - halfH + cr)
            tagPath.lineTo(rightX, cy + halfH - cr)
            tagPath.quadTo(rightX, cy + halfH, rightX - cr, cy + halfH)
            tagPath.lineTo(bodyLeftX, cy + halfH)
            tagPath.close()
            canvas.drawPath(tagPath, strokePaint)

            val xCenter = cx + 2.4f * density
            val xHalf = 3.1f * density
            canvas.drawLine(xCenter - xHalf, cy - xHalf, xCenter + xHalf, cy + xHalf, strokePaint)
            canvas.drawLine(xCenter + xHalf, cy - xHalf, xCenter - xHalf, cy + xHalf, strokePaint)
        }

        override fun onDetachedFromWindow() {
            animate().cancel()
            super.onDetachedFromWindow()
        }
    }

    /**
     * Builds the Material You full-screen PIN prompt view shared by
     * [MainActivity] and secondary Activities. Runs verification via [PinManager.verifyPinAsync].
     */
    fun createPinOverlayView(
        activity: Activity,
        prefs: SnakePreferences,
        onUnlocked: () -> Unit,
        onAutoWipe: () -> Unit
    ): View {
        val palette = prefs.themePalette
        val tokens = SnakeTheme.resolveTokens(palette, activity)
        val density = activity.resources.displayMetrics.density

        val screenBg = tokens.screenBg
        val titleColor = tokens.textPrimary
        val indicatorColor = if (tokens.isLight) tokens.textPrimary else tokens.primaryAccent
        val keyNormalBg = if (tokens.isLight) tokens.pillContainer else tokens.cardSurface
        val keyPressedBg = tokens.primaryAccent
        val keyTextNormal = tokens.textPrimary
        val keyTextPressed = tokens.onPrimaryAccent
        val actionTint = tokens.textSecondary
        val pillDisabledBg = keyNormalBg
        val pillDisabledText = ColorUtils.setAlphaComponent(tokens.textSecondary, 150)
        val pillEnabledBg = tokens.primaryAccent
        val pillEnabledText = tokens.onPrimaryAccent

        val enteredPin = StringBuilder()
        var previousIndicatorLen = 0
        var verifying = false

        val rootScroll = ScrollView(activity).apply {
            isFillViewport = true
            setBackgroundColor(screenBg)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            val padH = (20 * density).toInt()
            val padTop = (40 * density).toInt()
            val padBottom = (28 * density).toInt()
            setPadding(padH, padTop, padH, padBottom)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val titleTv = TextView(activity).apply {
            text = activity.getString(R.string.pin_title)
            textSize = 16.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(titleColor)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (44 * density).toInt())
        }

        val dotsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (34 * density).toInt()
            )
        }

        val errorTv = TextView(activity).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#EF4444"))
            gravity = Gravity.CENTER
            minHeight = (26 * density).toInt()
            setPadding(0, (8 * density).toInt(), 0, (28 * density).toInt())
        }

        lateinit var clearKeyTv: AppCompatTextView
        val unlockBtn = MaterialButton(activity).apply {
            text = activity.getString(R.string.pin_unlock)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isAllCaps = false
            cornerRadius = (22 * density).toInt()
            elevation = 0f
            stateListAnimator = null
            setBackgroundColor(pillDisabledBg)
            setTextColor(pillDisabledText)
            isEnabled = false
            minWidth = (140 * density).toInt()
            minimumWidth = (140 * density).toInt()
            minHeight = (44 * density).toInt()
            minimumHeight = (44 * density).toInt()
            val hPad = (28 * density).toInt()
            setPadding(hPad, 0, hPad, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (44 * density).toInt()
            ).apply {
                topMargin = (28 * density).toInt()
            }
            SnakeTheme.attachExpressivePressAnimation(this, scaleDown = 0.95f)
        }

        fun syncIndicator() {
            val len = enteredPin.length
            renderPinStepRow(
                row = dotsRow,
                enteredLength = len,
                prevLength = previousIndicatorLen,
                dotColor = indicatorColor,
                density = density
            )
            previousIndicatorLen = len

            clearKeyTv.isEnabled = !verifying && len > 0
            clearKeyTv.animate().alpha(if (len > 0) 0.85f else 0f).setDuration(150L).start()

            val canSubmit = !verifying && len in PinManager.MIN_PIN_LENGTH..PinManager.MAX_PIN_LENGTH
            unlockBtn.isEnabled = canSubmit
            unlockBtn.setBackgroundColor(if (canSubmit) pillEnabledBg else pillDisabledBg)
            unlockBtn.setTextColor(if (canSubmit) pillEnabledText else pillDisabledText)
            unlockBtn.alpha = if (canSubmit) 1.0f else 0.72f
        }

        val submitPin = {
            if (!verifying && enteredPin.length in PinManager.MIN_PIN_LENGTH..PinManager.MAX_PIN_LENGTH) {
                verifying = true
                syncIndicator()
                val candidate = enteredPin.toString()
                PinManager.verifyPinAsync(activity, prefs, candidate) { res ->
                    if (activity.isFinishing || activity.isDestroyed) return@verifyPinAsync
                    verifying = false
                    when (res) {
                        is PinManager.VerifyResult.Success -> {
                            markUnlocked()
                            enteredPin.clear()
                            syncIndicator()
                            hideKeyboard(activity, rootScroll)
                            onUnlocked()
                        }
                        is PinManager.VerifyResult.Failed -> {
                            enteredPin.clear()
                            syncIndicator()
                            val shake = 10f * density
                            ObjectAnimator.ofFloat(
                                dotsRow,
                                View.TRANSLATION_X,
                                0f, -shake, shake, -shake * 0.7f, shake * 0.7f, 0f
                            ).apply {
                                duration = 280L
                                start()
                            }
                            errorTv.text = if (res.lockoutRemainingMs > 0L) {
                                activity.getString(R.string.pin_locked_out, ((res.lockoutRemainingMs + 999L) / 1000L).toInt())
                            } else if (prefs.pinAutoWipeOnFail) {
                                activity.getString(R.string.pin_wrong, res.remaining)
                            } else {
                                activity.getString(R.string.pin_wrong_simple)
                            }
                        }
                        is PinManager.VerifyResult.TriggerAutoWipe -> {
                            markUnlocked()
                            enteredPin.clear()
                            syncIndicator()
                            hideKeyboard(activity, rootScroll)
                            onAutoWipe()
                        }
                    }
                }
            }
        }

        fun appendDigit(d: Char) {
            if (verifying || enteredPin.length >= PinManager.MAX_PIN_LENGTH) return
            if (errorTv.text.isNotEmpty()) errorTv.text = ""
            enteredPin.append(d)
            syncIndicator()
        }

        fun backspace() {
            if (verifying || enteredPin.isEmpty()) return
            enteredPin.deleteCharAt(enteredPin.length - 1)
            syncIndicator()
        }

        fun clearPinInput() {
            if (verifying || enteredPin.isEmpty()) return
            enteredPin.clear()
            syncIndicator()
        }

        unlockBtn.setOnClickListener { submitPin() }
        rootScroll.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                    appendDigit(('0'.code + (keyCode - KeyEvent.KEYCODE_0)).toChar())
                    true
                }
                KeyEvent.KEYCODE_DEL -> {
                    backspace()
                    true
                }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    submitPin()
                    true
                }
                else -> false
            }
        }

        val keypadRows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("DEL", "0", "CLR")
        )
        val screenWidthPx = activity.resources.displayMetrics.widthPixels
        val keyGap = (12 * density).toInt()
        val keySize = minOf((82 * density).toInt(), maxOf((62 * density).toInt(), (screenWidthPx - (84 * density).toInt()) / 3))

        val keypadContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        clearKeyTv = AppCompatTextView(activity).apply {
            text = activity.getString(R.string.pin_clear_key)
            contentDescription = activity.getString(R.string.pin_clear_key)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(actionTint)
            background = null
            alpha = 0f
            isEnabled = false
            SnakeTheme.attachExpressivePressAnimation(this, scaleDown = 0.9f)
            setOnClickListener { clearPinInput() }
        }

        keypadRows.forEachIndexed { rowIdx, keys ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                clipChildren = false
                clipToPadding = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (rowIdx > 0) topMargin = keyGap
                }
            }
            keys.forEachIndexed { colIdx, key ->
                val lp = LinearLayout.LayoutParams(keySize, keySize).apply {
                    if (colIdx > 0) marginStart = keyGap
                }
                val cellView: View = when (key) {
                    "DEL" -> PinBackspaceKeyView(activity, density, actionTint).apply {
                        contentDescription = activity.getString(R.string.pin_backspace_desc)
                        setOnClickListener { backspace() }
                    }
                    "CLR" -> clearKeyTv
                    else -> PinDigitKeyView(
                        context = activity,
                        digit = key,
                        density = density,
                        normalBg = keyNormalBg,
                        pressedBg = keyPressedBg,
                        normalText = keyTextNormal,
                        pressedText = keyTextPressed
                    ).apply {
                        contentDescription = key
                        setOnClickListener { appendDigit(key[0]) }
                    }
                }
                row.addView(cellView, lp)
            }
            keypadContainer.addView(row)
        }

        content.addView(titleTv)
        content.addView(dotsRow)
        content.addView(errorTv)
        content.addView(keypadContainer)
        content.addView(unlockBtn)

        rootScroll.addView(content)
        syncIndicator()
        return rootScroll
    }

    /**
     * Erases all persistent user artifacts across files, WebView storage, and preferences
     * upon failed-PIN auto-wipe triggered outside [MainActivity].
     */
    fun wipeAllSensitiveDataFromSecondary(context: Context) {
        val appCtx = context.applicationContext
        CaptureManager(appCtx).clearCaptures()
        OfflinePagesManager(appCtx).clearAll()
        DownloadHistoryStore(appCtx).clear()
        runCatching { WebStorage.getInstance().deleteAllData() }
        runCatching { WebViewDatabase.getInstance(appCtx).clearHttpAuthUsernamePassword() }
        runCatching {
            // HTTP cache / history / form data / SSL decisions are process-wide, so a throwaway WebView
            // clears what MainActivity.wipeSession() would otherwise clear. Main thread only (all callers are).
            WebView(appCtx).apply {
                clearCache(true)
                clearHistory()
                clearFormData()
                clearSslPreferences()
                destroy()
            }
        }
        runCatching {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
        }
        SnakePreferences(appCtx).scrubSensitiveDataForPanicWipe()
    }

    fun hideKeyboard(activity: Activity, focusView: View? = null) {
        val target = focusView ?: activity.currentFocus ?: activity.window?.decorView
        target?.clearFocus()
        val token = target?.windowToken ?: return
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(token, 0)
    }

    fun enforceLockIfNeeded(activity: Activity) {
        val prefs = SnakePreferences(activity)
        if (!prefs.pinLockEnabled) {
            unlockedForSession = true
            removeLockFromActivity(activity)
            return
        }
        if (unlockedForSession) {
            removeLockFromActivity(activity)
            return
        }
        if (activity is PinProtectedHost) {
            activity.onPinLockRequired()
            return
        }
        attachSecondaryLock(activity, prefs)
    }

    private fun attachSecondaryLock(activity: Activity, prefs: SnakePreferences) {
        val id = System.identityHashCode(activity)
        if (secondaryLocks.containsKey(id)) return
        val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val primaryChild = contentRoot.getChildAt(0)
        val prevVis = primaryChild?.visibility ?: View.VISIBLE
        val prevA11y = primaryChild?.importantForAccessibility ?: View.IMPORTANT_FOR_ACCESSIBILITY_AUTO

        primaryChild?.visibility = View.INVISIBLE
        primaryChild?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        hideKeyboard(activity)

        val backCallback = (activity as? ComponentActivity)?.let { componentActivity ->
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Intentionally swallow Back presses while PIN overlay is active.
                }
            }.also { componentActivity.onBackPressedDispatcher.addCallback(it) }
        }

        val overlay = createPinOverlayView(
            activity = activity,
            prefs = prefs,
            onUnlocked = { removeLockFromActivity(activity) },
            onAutoWipe = {
                wipeAllSensitiveDataFromSecondary(activity)
                removeLockFromActivity(activity)
                Toast.makeText(activity, R.string.pin_wiped, Toast.LENGTH_LONG).show()
                val restartIntent = Intent(activity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                activity.startActivity(restartIntent)
                activity.finish()
            }
        )
        overlay.alpha = 0f
        contentRoot.addView(
            overlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        overlay.animate().alpha(1f).setDuration(180L).setInterpolator(DecelerateInterpolator()).start()
        secondaryLocks[id] = SecondaryLockState(overlay, primaryChild, prevVis, prevA11y, backCallback)
    }

    private fun removeLockFromActivity(activity: Activity) {
        if (activity is PinProtectedHost) {
            activity.onPinUnlocked()
            return
        }
        val id = System.identityHashCode(activity)
        val state = secondaryLocks.remove(id) ?: return
        state.backCallback?.isEnabled = false
        state.backCallback?.remove()
        state.contentChild?.visibility = state.prevVisibility
        state.contentChild?.importantForAccessibility = state.prevImportanceForA11y
        (state.overlay.parent as? ViewGroup)?.removeView(state.overlay)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // Overlay enforcement runs in onActivityResumed (and MainActivity.onCreate defers loading).
    }

    override fun onActivityStarted(activity: Activity) {
        val id = System.identityHashCode(activity)
        val wasInBackground = startedActivityIds.isEmpty()
        startedActivityIds.add(id)
        if (wasInBackground && lastBackgroundAtMs > 0L) {
            val prefs = SnakePreferences(activity)
            if (PinManager.shouldLockAfterBackground(prefs.pinLockEnabled, lastBackgroundAtMs, clockProvider())) {
                unlockedForSession = false
            }
            lastBackgroundAtMs = 0L
        }
    }

    override fun onActivityResumed(activity: Activity) {
        enforceLockIfNeeded(activity)
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) {
        val id = System.identityHashCode(activity)
        startedActivityIds.remove(id)
        if (startedActivityIds.isEmpty() && !activity.isChangingConfigurations) {
            val prefs = SnakePreferences(activity)
            if (prefs.pinLockEnabled) {
                lastBackgroundAtMs = clockProvider()
            }
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        val id = System.identityHashCode(activity)
        startedActivityIds.remove(id)
        secondaryLocks.remove(id)?.let { state ->
            state.backCallback?.isEnabled = false
            state.backCallback?.remove()
        }
    }
}
