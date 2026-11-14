package com.nromanovich.stealthsnake

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton

/**
 * Android 15/16 Material 3 Expressive hierarchical Settings screen:
 * Top-level Settings Hub -> Category sub-screens -> Back to Hub,
 * with asymmetric grouped cards, custom outlined/badged switches,
 * and flat aligned global search.
 */
class SettingsActivity : AppCompatActivity() {

    private data class SettingsUiColors(
        val screenBg: Int,
        val cardSurface: Int,
        val backCircleBg: Int,
        val categoryHeader: Int,
        val titleText: Int,
        val subtitleText: Int,
        val iconTint: Int,
        val searchBg: Int,
        val searchBarBg: Int,
        val inputFieldBg: Int,
        val switchTrackOn: Int,
        val switchThumbOn: Int,
        val switchTrackOff: Int,
        val switchStrokeOff: Int,
        val switchThumbOff: Int,
        val switchCrossOff: Int,
        val rippleColor: Int
    )

    private data class SearchEntry(
        val title: String,
        val category: String,
        val keywords: String,
        @DrawableRes val iconRes: Int?,
        val categoryId: String,
        val targetView: View
    )

    internal class Android16Switch(
        context: Context,
        private val density: Float
    ) : CompoundButton(context) {

        private var trackOn = Color.GRAY
        private var thumbOn = Color.DKGRAY
        private var trackOff = Color.DKGRAY
        private var strokeOff = Color.GRAY
        private var thumbOff = Color.GRAY
        private var crossOff = Color.DKGRAY

        private var progress = 0f
        private var pressProgress = 0f
        private var thumbAnimator: ValueAnimator? = null
        private var pressAnimator: ValueAnimator? = null
        private val colorEval = ArgbEvaluator()
        private val m3Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val trackRect = RectF()
        private val thumbRect = RectF()
        private val glyphPath = Path()

        init {
            // Let the parent card handle touch clicks cleanly while CompoundButton.performClick() still works in tests
            isClickable = false
            isFocusable = false
            background = null
        }

        override fun setChecked(checked: Boolean) {
            val changed = isChecked != checked
            super.setChecked(checked)
            val target = if (checked) 1f else 0f
            if (!isAttachedToWindow || width <= 0 || !changed) {
                thumbAnimator?.cancel()
                progress = target
                invalidate()
                return
            }
            thumbAnimator?.cancel()
            thumbAnimator = ValueAnimator.ofFloat(progress, target).apply {
                duration = 220L
                interpolator = m3Interpolator
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun setPressedState(pressed: Boolean) {
            val target = if (pressed) 1f else 0f
            if (!isAttachedToWindow || width <= 0) {
                pressAnimator?.cancel()
                pressProgress = target
                invalidate()
                return
            }
            pressAnimator?.cancel()
            pressAnimator = ValueAnimator.ofFloat(pressProgress, target).apply {
                duration = 140L
                interpolator = m3Interpolator
                addUpdateListener {
                    pressProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun applyColors(
            trackOn: Int,
            thumbOn: Int,
            trackOff: Int,
            strokeOff: Int,
            thumbOff: Int,
            crossOff: Int
        ) {
            this.trackOn = trackOn
            this.thumbOn = thumbOn
            this.trackOff = trackOff
            this.strokeOff = strokeOff
            this.thumbOff = thumbOff
            this.crossOff = crossOff
            invalidate()
        }

        override fun onDetachedFromWindow() {
            // Rebuilding the settings UI removes switches mid-animation; stop the animators so they
            // don't keep ticking against a detached view that still references the Activity context.
            thumbAnimator?.cancel()
            pressAnimator?.cancel()
            super.onDetachedFromWindow()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = (SWITCH_WIDTH_DP * density + 0.5f).toInt()
            val h = (SWITCH_HEIGHT_DP * density + 0.5f).toInt()
            setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val p = progress.coerceIn(0f, 1f)
            val radius = h / 2f

            val border = 2f * density
            val halfBorder = border / 2f
            val inset = halfBorder * (1f - p)
            val trackCorner = radius - inset
            trackRect.set(inset, inset, w - inset, h - inset)
            fillPaint.color = colorEval.evaluate(p, trackOff, trackOn) as Int
            canvas.drawRoundRect(trackRect, trackCorner, trackCorner, fillPaint)

            val strokeAlpha = ((1f - p) * Color.alpha(strokeOff)).toInt().coerceIn(0, 255)
            if (strokeAlpha > 2) {
                strokePaint.color = ColorUtils.setAlphaComponent(strokeOff, strokeAlpha)
                strokePaint.strokeWidth = border
                canvas.drawRoundRect(trackRect, trackCorner, trackCorner, strokePaint)
            }

            val cx = (h / 2f) + (w - h) * p
            val cy = h / 2f
            val baseR = (10f + 2f * p) * density
            val stretchX = pressProgress * 3.5f * density
            val slideStretch = kotlin.math.sin(p * Math.PI).toFloat() * 1.6f * density
            val rx = (baseR + stretchX + slideStretch).coerceAtMost(w * 0.42f)
            val ry = baseR + (pressProgress * 0.8f * density)
            thumbRect.set(cx - rx, cy - ry, cx + rx, cy + ry)
            fillPaint.color = colorEval.evaluate(p, thumbOff, thumbOn) as Int
            canvas.drawRoundRect(thumbRect, ry, ry, fillPaint)

            val offAlpha = ((1f - p / 0.6f).coerceIn(0f, 1f) * 255).toInt()
            if (offAlpha > 4) {
                val s = 0.65f + 0.35f * (1f - p)
                val halfX = 3.2f * density * s
                glyphPaint.color = ColorUtils.setAlphaComponent(crossOff, offAlpha)
                glyphPaint.strokeWidth = 1.8f * density
                canvas.drawLine(cx - halfX, cy - halfX, cx + halfX, cy + halfX, glyphPaint)
                canvas.drawLine(cx + halfX, cy - halfX, cx - halfX, cy + halfX, glyphPaint)
            }

            val onAlpha = (((p - 0.35f) / 0.65f).coerceIn(0f, 1f) * 255).toInt()
            if (onAlpha > 4) {
                val s = 0.65f + 0.35f * p
                glyphPaint.color = ColorUtils.setAlphaComponent(trackOn, onAlpha)
                glyphPaint.strokeWidth = 2f * density
                glyphPath.reset()
                glyphPath.moveTo(cx - 4.2f * density * s, cy + 0.3f * density * s)
                glyphPath.lineTo(cx - 1.2f * density * s, cy + 3.4f * density * s)
                glyphPath.lineTo(cx + 4.5f * density * s, cy - 3.0f * density * s)
                canvas.drawPath(glyphPath, glyphPaint)
            }
        }
    }

    private class SettlingScrollView(context: Context) : ScrollView(context) {
        var onUserTouchStart: (() -> Unit)? = null

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_MOVE) {
                onUserTouchStart?.invoke()
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    private lateinit var prefs: SnakePreferences
    private lateinit var rootFrame: FrameLayout
    private lateinit var mainLayout: LinearLayout
    private lateinit var headerBar: LinearLayout
    private lateinit var backBtn: ImageButton
    private lateinit var titleView: TextView
    private lateinit var searchBtn: ImageButton
    private lateinit var navRailContainer: LinearLayout
    private lateinit var bodyFrame: FrameLayout
    private lateinit var contentScroll: ScrollView
    private lateinit var container: LinearLayout
    private lateinit var hubScroll: NestedScrollView
    private lateinit var hubContainer: LinearLayout
    private var searchOverlay: LinearLayout? = null

    private var activeCategoryId: String? = null
    private val categoryContainers = mutableMapOf<String, LinearLayout>()
    private val categoryTitles = mutableMapOf<String, String>()

    // Values the text inputs were initialised from; lets a rebuild persist un-blurred edits without
    // clobbering pref changes made by presets/profiles/reset in the meantime.
    private var builtHomeUrl: String? = null
    private var builtSearchTemplate: String? = null
    private var builtBlocklistRaw: String? = null
    private val sectionCategoryByKey = mutableMapOf<String, String>()
    private var homeUrlInput: EditText? = null
    private var customSearchInput: EditText? = null
    private var customRulesInput: EditText? = null
    private var activeHighlightAnimator: ValueAnimator? = null
    private var highlightedView: View? = null
    private val navItems = mutableListOf<TextView>()
    private val sectionViewsByKey = mutableMapOf<String, Pair<TextView, LinearLayout>>()
    private val searchEntries = mutableListOf<SearchEntry>()
    private var selectedNavItem: TextView? = null
    private var selectedSectionView: View? = null
    private var programmaticScrollActive: Boolean = false
    private var settledScrollY: Int = -1
    private var settleRunnable: Runnable? = null
    private var hasScrolledInitialTarget: Boolean = false
    private val activeDialogs = mutableListOf<AlertDialog>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SnakePreferences(this)

        val density = resources.displayMetrics.density
        rootFrame = FrameLayout(this)

        mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Top Header Bar: Back circle + Title + Search button
        headerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (62 * density).toInt()
            )
        }

        backBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings_back)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = getString(R.string.action_cancel)
            layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt())
            SnakeTheme.attachExpressivePressAnimation(this, scaleDown = 0.90f)
            setOnClickListener { handleBackNavigation() }
        }
        headerBar.addView(backBtn)

        titleView = TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (14 * density).toInt()
            }
        }
        headerBar.addView(titleView)

        searchBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings_search)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = getString(R.string.settings_search_hint)
            layoutParams = LinearLayout.LayoutParams((42 * density).toInt(), (42 * density).toInt())
            SnakeTheme.attachExpressivePressAnimation(this, scaleDown = 0.90f)
            setOnClickListener { openSearchOverlay() }
        }
        headerBar.addView(searchBtn)
        mainLayout.addView(headerBar)

        val bodyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        // Hidden compatibility nav rail (keeps existing ScrollView ordering and nav assertions intact)
        navRailContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val navScroll = ScrollView(this).apply {
            visibility = View.GONE
            addView(navRailContainer)
        }
        bodyRow.addView(navScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))

        bodyFrame = FrameLayout(this)

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, (24 * density).toInt())
        }
        val settlingScroll = SettlingScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            addView(container)
            onUserTouchStart = {
                if (programmaticScrollActive) {
                    settleRunnable?.let { removeCallbacks(it) }
                    programmaticScrollActive = false
                    settledScrollY = scrollY
                }
            }
        }
        contentScroll = settlingScroll
        contentScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val section = selectedSectionView ?: return@setOnScrollChangeListener
            if (selectedNavItem == null || programmaticScrollActive) return@setOnScrollChangeListener
            val threshold = (36 * density).toInt()
            val scrolledAway = settledScrollY >= 0 && kotlin.math.abs(scrollY - settledScrollY) > threshold
            val outOfViewport = contentScroll.height > 0 &&
                (section.bottom <= scrollY || section.top >= scrollY + contentScroll.height)
            if (scrolledAway || outOfViewport) {
                clearNavSelection(prefs.themePalette)
            }
        }
        bodyFrame.addView(
            contentScroll,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // NestedScrollView (not a ScrollView subclass) renders the Settings Hub on top when activeCategoryId == null
        hubContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, (24 * density).toInt())
        }
        hubScroll = NestedScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            isClickable = true
            isFocusable = true
            addView(hubContainer)
        }
        bodyFrame.addView(
            hubScroll,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        bodyRow.addView(bodyFrame, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        mainLayout.addView(bodyRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        rootFrame.addView(
            mainLayout,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setContentView(rootFrame)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackNavigation()
            }
        })

        // Survive process/config recreation without dumping the user back on the hub.
        activeCategoryId = savedInstanceState?.getString(STATE_ACTIVE_CATEGORY)
        buildSettingsUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        flushTextInputs()
        activeCategoryId?.let { outState.putString(STATE_ACTIVE_CATEGORY, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        flushTextInputs()
        super.onPause()
    }

    private fun trackDialog(dialog: AlertDialog): AlertDialog {
        activeDialogs.removeAll { !it.isShowing }
        activeDialogs.add(dialog)
        dialog.setOnDismissListener { activeDialogs.remove(dialog) }
        return dialog
    }

    override fun onDestroy() {
        settleRunnable?.let { contentScroll.removeCallbacks(it) }
        activeHighlightAnimator?.cancel()
        activeDialogs.toList().forEach { runCatching { it.dismiss() } }
        activeDialogs.clear()
        super.onDestroy()
    }

    private fun handleBackNavigation() {
        when {
            searchOverlay != null -> closeSearchOverlay()
            activeCategoryId != null -> closeCategoryToHub()
            else -> finish()
        }
    }

    private fun openCategory(categoryId: String, targetView: View? = null) {
        if (categoryId == CAT_ABOUT) {
            if (activeCategoryId != null) closeCategoryToHub()
            if (targetView != null) {
                val density = resources.displayMetrics.density
                hubScroll.post {
                    val y = computeRelativeTop(targetView, hubContainer)
                    hubScroll.smoothScrollTo(0, maxOf(0, y - (16 * density).toInt()))
                    pulseViewHighlight(targetView, prefs.themePalette)
                }
            }
            return
        }
        categoryContainers[categoryId] ?: return
        activeCategoryId = categoryId
        hubScroll.visibility = View.GONE
        contentScroll.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO

        for ((id, catContainer) in categoryContainers) {
            catContainer.visibility = if (id == categoryId) View.VISIBLE else View.GONE
        }
        titleView.text = categoryTitles[categoryId] ?: getString(R.string.settings_title)

        val density = resources.displayMetrics.density
        contentScroll.animate().cancel()
        contentScroll.alpha = 0f
        contentScroll.translationX = 24f * density
        contentScroll.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        titleView.animate().cancel()
        titleView.alpha = 0.35f
        titleView.translationX = 10f * density
        titleView.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(180L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        contentScroll.post {
            if (targetView != null) {
                val y = computeRelativeTop(targetView, container)
                contentScroll.smoothScrollTo(0, maxOf(0, y - (16 * density).toInt()))
                pulseViewHighlight(targetView, prefs.themePalette)
            } else {
                contentScroll.scrollTo(0, 0)
            }
        }
    }

    private fun closeCategoryToHub() {
        flushTextInputs()
        activeCategoryId = null
        for ((_, catContainer) in categoryContainers) {
            catContainer.visibility = View.VISIBLE
        }
        val colors = resolveColors(prefs.themePalette)
        val density = resources.displayMetrics.density
        buildHubCards(colors, density)
        hubScroll.visibility = View.VISIBLE
        hubScroll.scrollTo(0, 0)
        contentScroll.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        contentScroll.alpha = 1f
        contentScroll.translationX = 0f
        titleView.text = getString(R.string.settings_title)

        hubScroll.animate().cancel()
        hubScroll.alpha = 0f
        hubScroll.translationX = -20f * density
        hubScroll.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(200L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        titleView.animate().cancel()
        titleView.alpha = 0.35f
        titleView.translationX = -10f * density
        titleView.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(180L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun flushTextInputs() {
        homeUrlInput?.text?.toString()?.let { prefs.homeUrl = it }
        customSearchInput?.text?.toString()?.let { prefs.customSearchTemplate = it }
        customRulesInput?.text?.toString()?.let { prefs.customBlocklistRaw = it }
    }

    /** Persists un-blurred edits before the inputs are destroyed by a rebuild, unless a pref changed under them. */
    private fun flushTextInputsBeforeRebuild() {
        homeUrlInput?.text?.toString()?.let { if (prefs.homeUrl == builtHomeUrl) prefs.homeUrl = it }
        customSearchInput?.text?.toString()?.let { if (prefs.customSearchTemplate == builtSearchTemplate) prefs.customSearchTemplate = it }
        customRulesInput?.text?.toString()?.let { if (prefs.customBlocklistRaw == builtBlocklistRaw) prefs.customBlocklistRaw = it }
    }

    private fun resolveColors(palette: ThemePalette): SettingsUiColors {
        val tokens = SnakeTheme.resolveTokens(palette, this)
        val ripple = ColorUtils.setAlphaComponent(
            if (tokens.isLight) Color.BLACK else Color.WHITE,
            if (tokens.isLight) 30 else 40
        )
        return SettingsUiColors(
            screenBg = tokens.screenBg,
            cardSurface = tokens.cardSurface,
            backCircleBg = if (tokens.isLight) tokens.pillContainer else tokens.elevatedSurface,
            categoryHeader = tokens.categoryHeader,
            titleText = tokens.textPrimary,
            subtitleText = tokens.textSecondary,
            iconTint = if (tokens.isLight) tokens.textSecondary else ColorUtils.blendARGB(tokens.textPrimary, tokens.textSecondary, 0.45f),
            searchBg = tokens.screenBg,
            searchBarBg = if (tokens.isLight) tokens.cardSurface else tokens.elevatedSurface,
            inputFieldBg = tokens.pillContainer,
            switchTrackOn = tokens.primaryAccent,
            switchThumbOn = tokens.onPrimaryAccent,
            switchTrackOff = tokens.switchTrackOff,
            switchStrokeOff = tokens.switchThumbOff,
            switchThumbOff = tokens.switchThumbOff,
            switchCrossOff = tokens.switchTrackOff,
            rippleColor = ripple
        )
    }

    private fun buildSettingsUi(preserveScroll: Boolean = false, flushInputs: Boolean = true) {
        if (flushInputs) {
            flushTextInputsBeforeRebuild()
        }
        builtHomeUrl = prefs.homeUrl
        builtSearchTemplate = prefs.customSearchTemplate
        builtBlocklistRaw = prefs.customBlocklistRaw
        val previousScrollY = if (preserveScroll) contentScroll.scrollY else 0
        val palette = prefs.themePalette
        val colors = resolveColors(palette)
        val density = resources.displayMetrics.density

        SnakeTheme.applySystemBars(this, palette, colors.screenBg)
        rootFrame.setBackgroundColor(colors.screenBg)
        mainLayout.setBackgroundColor(colors.screenBg)
        bodyFrame.setBackgroundColor(colors.screenBg)
        hubScroll.setBackgroundColor(colors.screenBg)
        titleView.setTextColor(colors.titleText)

        val backCircle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colors.backCircleBg)
        }
        backBtn.background = RippleDrawable(ColorStateList.valueOf(colors.rippleColor), backCircle, null)
        backBtn.setColorFilter(colors.titleText, PorterDuff.Mode.SRC_IN)

        val searchMask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        searchBtn.background = RippleDrawable(ColorStateList.valueOf(colors.rippleColor), null, searchMask)
        searchBtn.setColorFilter(colors.iconTint, PorterDuff.Mode.SRC_IN)

        settleRunnable?.let { contentScroll.removeCallbacks(it) }
        settleRunnable = null
        selectedNavItem = null
        selectedSectionView = null
        programmaticScrollActive = false
        settledScrollY = -1
        activeHighlightAnimator?.cancel()
        highlightedView = null
        navRailContainer.removeAllViews()
        navItems.clear()
        sectionViewsByKey.clear()
        sectionCategoryByKey.clear()
        categoryContainers.clear()
        categoryTitles.clear()
        searchEntries.clear()
        container.removeAllViews()
        homeUrlInput = null
        customSearchInput = null
        customRulesInput = null

        // Build all 10 category sub-screens in logical first-run onboarding order

        // Keep homeUrlInput as the first EditText in the container (Presets only has MaterialButtons).
        // Wait: in top-to-bottom layout we want Presets first AND homeUrlInput still first EditText!
        // Presets only has MaterialButtons (no EditText!), so homeUrlInput IS STILL the very first EditText in container!

        // CAT 1: Protection & Profiles
        val cat1Title = getString(R.string.cat_hub_presets)
        buildCategoryContainer(CAT_PRESETS, cat1Title) { section ->
            val group = createCardGroup(section, topGapDp = 6f)
            val presetCard = createBaseCardContainer(colors, density, hasIcon = true)
            presetCard.addView(createLeadingIcon(R.drawable.ic_settings_shield, colors, density))

            val rightCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            rightCol.addView(TextView(this).apply {
                text = getString(R.string.settings_presets)
                textSize = 16f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setTextColor(colors.titleText)
            })
            rightCol.addView(TextView(this).apply {
                text = getString(R.string.desc_presets)
                textSize = 13f
                setTextColor(colors.subtitleText)
                setPadding(0, (3 * density).toInt(), 0, (10 * density).toInt())
            })

            val currentPreset = prefs.preset
            val activeProfileId = prefs.activeProfileId
            val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val maxBtn = createPresetButton(
                label = getString(R.string.preset_max_stealth),
                active = activeProfileId == null && currentPreset == ProtectionPreset.MAX_STEALTH,
                colors = colors,
                density = density
            ) {
                prefs.applyPreset(ProtectionPreset.MAX_STEALTH)
                Toast.makeText(this, getString(R.string.preset_max_stealth), Toast.LENGTH_SHORT).show()
                buildSettingsUi()
            }
            val compatBtn = createPresetButton(
                label = getString(R.string.preset_compatibility),
                active = activeProfileId == null && currentPreset == ProtectionPreset.COMPATIBILITY,
                colors = colors,
                density = density
            ) {
                if (prefs.activeProfileId == null && prefs.preset == ProtectionPreset.COMPATIBILITY) {
                    Toast.makeText(this, getString(R.string.preset_compatibility), Toast.LENGTH_SHORT).show()
                } else {
                    val d = AlertDialog.Builder(this)
                        .setTitle(R.string.compat_warn_title)
                        .setMessage(R.string.compat_warn_body)
                        .setPositiveButton(R.string.compat_warn_enable) { _, _ ->
                            prefs.applyPreset(ProtectionPreset.COMPATIBILITY)
                            Toast.makeText(this, getString(R.string.preset_compatibility), Toast.LENGTH_SHORT).show()
                            buildSettingsUi()
                        }
                        .setNeutralButton(R.string.compat_warn_guide) { _, _ ->
                            startActivity(Intent(this, SiteFixesActivity::class.java))
                        }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show()
                    trackDialog(d)
                    SnakeTheme.styleExpressiveDialog(d, palette)
                }
            }
            val addProfileBtn = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "+"
                textSize = 20f
                isAllCaps = false
                cornerRadius = 0
                strokeWidth = 0
                setTextColor(colors.categoryHeader)
                contentDescription = getString(R.string.profile_add_desc)
                setPadding(0, 0, 0, 0)
                minWidth = 0
                minimumWidth = 0
                layoutParams = LinearLayout.LayoutParams(
                    (36 * density).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (4 * density).toInt()
                }
                SnakeTheme.attachExpressivePressAnimation(this, scaleDown = 0.90f)
                setOnClickListener { showCreateProfileDialog(palette, density) }
            }
            (compatBtn.layoutParams as LinearLayout.LayoutParams).marginStart = (6 * density).toInt()
            presetRow.addView(maxBtn)
            presetRow.addView(compatBtn)
            presetRow.addView(addProfileBtn)
            rightCol.addView(presetRow)

            val profiles = prefs.listProfiles()
            if (profiles.isNotEmpty()) {
                rightCol.addView(TextView(this).apply {
                    text = getString(R.string.profiles_section_title)
                    textSize = 12f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(colors.categoryHeader)
                    setPadding(0, (12 * density).toInt(), 0, (4 * density).toInt())
                })
                for (chunk in profiles.chunked(2)) {
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, (3 * density).toInt(), 0, (3 * density).toInt())
                    }
                    chunk.forEachIndexed { idx, profile ->
                        val pBtn = createPresetButton(
                            label = profile.name,
                            active = activeProfileId == profile.id,
                            colors = colors,
                            density = density
                        ) {
                            prefs.applyProfile(profile.id)
                            Toast.makeText(this, profile.name, Toast.LENGTH_SHORT).show()
                            buildSettingsUi()
                        }.apply {
                            setOnLongClickListener {
                                val d = AlertDialog.Builder(this@SettingsActivity)
                                    .setTitle(getString(R.string.profile_delete_title, profile.name))
                                    .setPositiveButton(R.string.profile_delete_btn) { _, _ ->
                                        prefs.deleteProfile(profile.id)
                                        buildSettingsUi()
                                    }
                                    .setNegativeButton(R.string.action_cancel, null)
                                    .show()
                                trackDialog(d)
                                SnakeTheme.styleExpressiveDialog(d, palette, destructivePositive = true)
                                true
                            }
                        }
                        if (idx > 0) {
                            (pBtn.layoutParams as LinearLayout.LayoutParams).marginStart = (6 * density).toInt()
                        }
                        row.addView(pBtn)
                    }
                    if (chunk.size == 1) {
                        row.addView(View(this), LinearLayout.LayoutParams(0, (40 * density).toInt(), 1f).apply {
                            marginStart = (6 * density).toInt()
                        })
                    }
                    rightCol.addView(row)
                }
                rightCol.addView(TextView(this).apply {
                    text = getString(R.string.profiles_hint)
                    textSize = 12f
                    setTextColor(colors.subtitleText)
                    setPadding(0, (4 * density).toInt(), 0, 0)
                })
            }

            presetCard.addView(rightCol)
            group.addView(presetCard)
            registerSearchEntry(getString(R.string.settings_presets), cat1Title, R.drawable.ic_settings_shield, CAT_PRESETS, presetCard)

            val fixesBtn = addActionCard(group, colors, density, getString(R.string.pref_site_fixes_guide)) {
                startActivity(Intent(this, SiteFixesActivity::class.java))
            }
            registerSearchEntry(getString(R.string.pref_site_fixes_guide), cat1Title, null, CAT_PRESETS, fixesBtn)
            refreshGroupShapes(group, colors, density)
        }.also { section ->
            registerCompatNavItem(getString(R.string.nav_presets), CAT_PRESETS, section, palette)
        }

        // CAT 2: Search & Start Page
        val cat2Title = getString(R.string.cat_hub_search_home)
        buildCategoryContainer(CAT_SEARCH_HOME, cat2Title) { section ->
            val group = createCardGroup(section, topGapDp = 6f)
            val (homeCard, homeEt) = addTextFieldCard(
                parent = group,
                colors = colors,
                density = density,
                label = getString(R.string.pref_home_url),
                initial = prefs.homeUrl,
                description = getString(R.string.desc_home_url),
                inputType = InputType.TYPE_TEXT_VARIATION_URI,
                iconRes = R.drawable.ic_settings_home
            ) { prefs.homeUrl = it }
            homeUrlInput = homeEt
            registerSearchEntry(getString(R.string.pref_home_url), cat2Title, R.drawable.ic_settings_home, CAT_SEARCH_HOME, homeCard)

            lateinit var customSearchCard: View
            val searchChoiceCard = addChoiceCard(
                parent = group,
                colors = colors,
                density = density,
                label = getString(R.string.pref_search_engine),
                options = listOf(
                    SearchEngine.DUCKDUCKGO to getString(R.string.search_ddg),
                    SearchEngine.STARTPAGE to getString(R.string.search_startpage),
                    SearchEngine.BRAVE to getString(R.string.search_brave),
                    SearchEngine.CUSTOM to getString(R.string.search_custom),
                    SearchEngine.OFF to getString(R.string.search_off)
                ),
                selected = prefs.searchEngine
            ) { chosen ->
                prefs.searchEngine = chosen
                customSearchCard.visibility = if (chosen == SearchEngine.CUSTOM) View.VISIBLE else View.GONE
                refreshGroupShapes(group, colors, density)
            }
            registerSearchEntry(getString(R.string.pref_search_engine), cat2Title, null, CAT_SEARCH_HOME, searchChoiceCard)

            val (customCard, customEt) = addTextFieldCard(
                parent = group,
                colors = colors,
                density = density,
                label = getString(R.string.pref_custom_search_template),
                initial = prefs.customSearchTemplate,
                description = getString(R.string.desc_custom_search_template),
                inputType = InputType.TYPE_TEXT_VARIATION_URI
            ) { prefs.customSearchTemplate = it }
            customSearchCard = customCard
            customSearchInput = customEt
            customSearchCard.visibility = if (prefs.searchEngine == SearchEngine.CUSTOM) View.VISIBLE else View.GONE

            refreshGroupShapes(group, colors, density)
        }

        // CAT 3: Appearance & Display
        val cat3Title = getString(R.string.cat_hub_appearance)
        lateinit var cat3Nav: TextView
        val cat3Section = buildCategoryContainer(CAT_APPEARANCE, cat3Title) { section ->
            val groupInterface = createCardGroup(section, topGapDp = 6f)
            val dynamicAvailable = SnakeTheme.isDynamicColorAvailable()
            val themeOptions = buildList<Pair<ThemePalette?, String>> {
                if (dynamicAvailable) {
                    add(null to getString(R.string.theme_system_dynamic))
                }
                add(ThemePalette.OBSIDIAN to getString(R.string.theme_obsidian))
                add(ThemePalette.AMOLED to getString(R.string.theme_amoled))
                add(ThemePalette.MIDNIGHT to getString(R.string.theme_midnight))
                add(ThemePalette.EMERALD to getString(R.string.theme_emerald))
                add(ThemePalette.SLATE_LIGHT to getString(R.string.theme_slate_light))
            }
            val currentThemeSelection: ThemePalette? =
                if (SnakeTheme.isDynamicThemeActive(this)) null else prefs.themePalette
            val themeCard = addChoiceCard(
                parent = groupInterface,
                colors = colors,
                density = density,
                label = getString(R.string.pref_theme_palette),
                options = themeOptions,
                selected = currentThemeSelection,
                iconRes = R.drawable.ic_settings_palette
            ) { chosen ->
                if (chosen == null) {
                    if (!prefs.useDynamicTheme) {
                        flushTextInputs()
                        prefs.enableDynamicTheme()
                        buildSettingsUi(preserveScroll = true)
                    }
                } else if (prefs.useDynamicTheme || prefs.themePalette != chosen) {
                    flushTextInputs()
                    prefs.themePalette = chosen
                    buildSettingsUi(preserveScroll = true)
                }
            }
            registerSearchEntry(getString(R.string.pref_theme_palette), cat3Title, R.drawable.ic_settings_palette, CAT_APPEARANCE, themeCard)

            val langCard = addChoiceCard(
                parent = groupInterface,
                colors = colors,
                density = density,
                label = getString(R.string.pref_language),
                options = listOf(
                    SupportedLocale.SYSTEM to getString(R.string.lang_system),
                    SupportedLocale.EN to getString(R.string.lang_en),
                    SupportedLocale.RU to getString(R.string.lang_ru)
                ),
                selected = prefs.locale
            ) { chosen ->
                if (prefs.locale != chosen) {
                    flushTextInputs()
                    prefs.locale = chosen
                }
            }
            registerSearchEntry(getString(R.string.pref_language), cat3Title, null, CAT_APPEARANCE, langCard)

            val statusBarCard = addSwitchCard(groupInterface, colors, density, getString(R.string.pref_show_status_bar), prefs.showStatusBar) {
                prefs.showStatusBar = it
            }
            registerSearchEntry(getString(R.string.pref_show_status_bar), cat3Title, null, CAT_APPEARANCE, statusBarCard)
            refreshGroupShapes(groupInterface, colors, density)

            addSubheader(section, colors, density, getString(R.string.subcat_display))
            val groupDisplay = createCardGroup(section)
            val forceDarkCard = addSwitchCard(groupDisplay, colors, density, getString(R.string.pref_force_dark), prefs.forceDarkPages) {
                prefs.forceDarkPages = it
            }
            registerSearchEntry(getString(R.string.pref_force_dark), cat3Title, null, CAT_APPEARANCE, forceDarkCard)

            val zoomCard = addChoiceCard(
                parent = groupDisplay,
                colors = colors,
                density = density,
                label = getString(R.string.pref_text_zoom),
                options = listOf(85 to "85%", 100 to "100%", 115 to "115%", 130 to "130%"),
                selected = prefs.textZoomPercent
            ) { prefs.textZoomPercent = it }
            registerSearchEntry(getString(R.string.pref_text_zoom), cat3Title, null, CAT_APPEARANCE, zoomCard)

            val screenCard = addSwitchCard(groupDisplay, colors, density, getString(R.string.pref_keep_screen_on), prefs.keepScreenOn) {
                prefs.keepScreenOn = it
            }
            registerSearchEntry(getString(R.string.pref_keep_screen_on), cat3Title, null, CAT_APPEARANCE, screenCard)

            val hapticCard = addSwitchCard(groupDisplay, colors, density, getString(R.string.pref_haptic), prefs.hapticFeedbackEnabled) {
                prefs.hapticFeedbackEnabled = it
            }
            registerSearchEntry(getString(R.string.pref_haptic), cat3Title, null, CAT_APPEARANCE, hapticCard)
            refreshGroupShapes(groupDisplay, colors, density)
        }
        cat3Nav = registerCompatNavItem(getString(R.string.nav_appearance), CAT_APPEARANCE, cat3Section, palette)

        // CAT 4: PIN Lock
        val cat4Title = getString(R.string.cat_hub_pin)
        val cat4Section = buildCategoryContainer(CAT_PIN, cat4Title) { section ->
            val pinGroup = createCardGroup(section, topGapDp = 6f)
            val pinEnabled = prefs.pinLockEnabled
            val pinBtn = addActionCard(
                parent = pinGroup,
                colors = colors,
                density = density,
                label = getString(if (pinEnabled) R.string.pref_pin_change else R.string.pref_pin_set),
                subtitle = getString(if (pinEnabled) R.string.pin_status_enabled else R.string.pin_status_disabled),
                iconRes = R.drawable.ic_settings_lock
            ) {
                if (prefs.pinLockEnabled) {
                    showVerifyCurrentPinDialog(
                        titleRes = R.string.pin_verify_current_title,
                        palette = palette,
                        density = density
                    ) {
                        showSetPinDialog(palette, density)
                    }
                } else {
                    showSetPinDialog(palette, density)
                }
            }
            registerSearchEntry(getString(if (pinEnabled) R.string.pref_pin_change else R.string.pref_pin_set), cat4Title, R.drawable.ic_settings_lock, CAT_PIN, pinBtn)

            if (pinEnabled) {
                val removePinBtn = addActionCard(pinGroup, colors, density, getString(R.string.pref_pin_remove)) {
                    showVerifyCurrentPinDialog(
                        titleRes = R.string.pin_verify_remove_title,
                        palette = palette,
                        density = density
                    ) {
                        PinManager.clearPin(prefs)
                        PinLockCoordinator.markUnlocked()
                        Toast.makeText(this, R.string.pin_removed, Toast.LENGTH_SHORT).show()
                        buildSettingsUi()
                    }
                }
                registerSearchEntry(getString(R.string.pref_pin_remove), cat4Title, null, CAT_PIN, removePinBtn)

                val autoWipeCard = addPinAutoWipeSwitchCard(pinGroup, colors, density)
                registerSearchEntry(getString(R.string.pref_pin_auto_wipe), cat4Title, null, CAT_PIN, autoWipeCard)
            }
            refreshGroupShapes(pinGroup, colors, density)
        }
        registerCompatNavItem(getString(R.string.nav_security), CAT_PIN, cat4Section, palette)

        // CAT 5: Session & Data Wipe
        val cat5Title = getString(R.string.cat_hub_wipe)
        buildCategoryContainer(CAT_WIPE, cat5Title) { section ->
            val wipeGroup1 = createCardGroup(section, topGapDp = 6f)
            val exitCard = addSwitchCard(wipeGroup1, colors, density, getString(R.string.pref_auto_wipe_exit), prefs.autoWipeOnExit, getString(R.string.pref_auto_wipe_hint), iconRes = R.drawable.ic_menu_wipe) {
                prefs.autoWipeOnExit = it
            }
            registerSearchEntry(getString(R.string.pref_auto_wipe_exit), cat5Title, R.drawable.ic_menu_wipe, CAT_WIPE, exitCard)

            val confirmCard = addSwitchCard(wipeGroup1, colors, density, getString(R.string.pref_confirm_wipe), prefs.confirmBeforeWipe) {
                prefs.confirmBeforeWipe = it
            }
            registerSearchEntry(getString(R.string.pref_confirm_wipe), cat5Title, null, CAT_WIPE, confirmCard)
            refreshGroupShapes(wipeGroup1, colors, density)

            val wipeGroup2 = createCardGroup(section, topGapDp = 16f)
            val offlineCard = addSwitchCard(wipeGroup2, colors, density, getString(R.string.pref_auto_clear_offline), prefs.autoClearOfflineOnWipe) {
                prefs.autoClearOfflineOnWipe = it
            }
            registerSearchEntry(getString(R.string.pref_auto_clear_offline), cat5Title, null, CAT_WIPE, offlineCard)

            val clearCapCard = addSwitchCard(wipeGroup2, colors, density, getString(R.string.pref_auto_clear_captures), prefs.autoClearCapturesOnWipe) {
                prefs.autoClearCapturesOnWipe = it
            }
            registerSearchEntry(getString(R.string.pref_auto_clear_captures), cat5Title, null, CAT_WIPE, clearCapCard)
            refreshGroupShapes(wipeGroup2, colors, density)
        }

        // CAT 6: Downloads & Screenshots (SECTION_DOWNLOADS deep-link target)
        val cat6Title = getString(R.string.cat_hub_downloads)
        val cat6Section = buildCategoryContainer(CAT_DOWNLOADS, cat6Title) { section ->
            val dlGroup = createCardGroup(section, topGapDp = 6f)
            val dlCard = addSwitchCard(dlGroup, colors, density, getString(R.string.pref_downloads_enabled), prefs.downloadsEnabled, getString(R.string.pref_downloads_hint), iconRes = R.drawable.ic_settings_downloads) {
                prefs.downloadsEnabled = it
            }
            registerSearchEntry(getString(R.string.pref_downloads_enabled), cat6Title, R.drawable.ic_settings_downloads, CAT_DOWNLOADS, dlCard)
            refreshGroupShapes(dlGroup, colors, density)

            addSubheader(section, colors, density, getString(R.string.subcat_captures))
            val capGroup = createCardGroup(section)
            val blockSecCard = addSwitchCard(capGroup, colors, density, getString(R.string.pref_block_screenshots), prefs.blockScreenshots, getString(R.string.desc_block_screenshots)) {
                prefs.blockScreenshots = it
                SnakeTheme.applySystemBars(this, prefs.themePalette, colors.screenBg, blockScreenshots = it)
            }
            registerSearchEntry(getString(R.string.pref_block_screenshots), cat6Title, null, CAT_DOWNLOADS, blockSecCard)

            val capModeCard = addChoiceCard(
                parent = capGroup,
                colors = colors,
                density = density,
                label = getString(R.string.pref_capture_mode),
                options = listOf(
                    CaptureMode.ASK to getString(R.string.capture_mode_ask),
                    CaptureMode.PRIVATE to getString(R.string.capture_mode_private),
                    CaptureMode.GALLERY to getString(R.string.capture_mode_gallery)
                ),
                selected = prefs.captureMode
            ) { prefs.captureMode = it }
            registerSearchEntry(getString(R.string.pref_capture_mode), cat6Title, null, CAT_DOWNLOADS, capModeCard)

            val whyCapBtn = addActionCard(capGroup, colors, density, getString(R.string.pref_why_hidden_storage)) {
                val d = AlertDialog.Builder(this)
                    .setTitle(R.string.capture_info_title)
                    .setMessage(R.string.capture_info_body)
                    .setPositiveButton(R.string.action_ok, null)
                    .show()
                trackDialog(d)
                SnakeTheme.styleExpressiveDialog(d, palette)
            }
            registerSearchEntry(getString(R.string.pref_why_hidden_storage), cat6Title, null, CAT_DOWNLOADS, whyCapBtn)
            refreshGroupShapes(capGroup, colors, density)
        }
        val dlNav = registerCompatNavItem(getString(R.string.nav_navigation), CAT_DOWNLOADS, cat6Section, palette)
        sectionViewsByKey[SECTION_DOWNLOADS] = dlNav to cat6Section
        sectionCategoryByKey[SECTION_DOWNLOADS] = CAT_DOWNLOADS

        // CAT 7: VPN & Network Guard
        val cat7Title = getString(R.string.cat_hub_vpn)
        val cat7Section = buildCategoryContainer(CAT_VPN, cat7Title) { section ->
            val vpnGroup = createCardGroup(section, topGapDp = 6f)
            lateinit var bypassCard: View
            val vpnCard = addSwitchCard(
                parent = vpnGroup,
                colors = colors,
                density = density,
                label = getString(R.string.pref_vpn_kill_switch),
                checked = prefs.vpnKillSwitchEnabled,
                description = getString(R.string.desc_vpn_kill_switch),
                iconRes = R.drawable.ic_settings_vpn
            ) { enabled ->
                prefs.vpnKillSwitchEnabled = enabled
                bypassCard.visibility = if (enabled) View.VISIBLE else View.GONE
                refreshGroupShapes(vpnGroup, colors, density)
            }
            registerSearchEntry(getString(R.string.pref_vpn_kill_switch), cat7Title, R.drawable.ic_settings_vpn, CAT_VPN, vpnCard)

            bypassCard = addSwitchCard(vpnGroup, colors, density, getString(R.string.pref_vpn_bypass_start), prefs.vpnBypassStartCheck, getString(R.string.desc_vpn_bypass_start)) {
                prefs.vpnBypassStartCheck = it
            }
            bypassCard.visibility = if (prefs.vpnKillSwitchEnabled) View.VISIBLE else View.GONE
            registerSearchEntry(getString(R.string.pref_vpn_bypass_start), cat7Title, null, CAT_VPN, bypassCard)
            refreshGroupShapes(vpnGroup, colors, density)

            addSubheader(section, colors, density, getString(R.string.cat_network), topDp = 18f)
            val netExtraGroup = createCardGroup(section, topGapDp = 0f)
            val webrtcCard = addSwitchCard(netExtraGroup, colors, density, getString(R.string.pref_block_webrtc), prefs.blockWebRtcIce, getString(R.string.desc_block_webrtc)) {
                prefs.blockWebRtcIce = it
            }
            registerSearchEntry(getString(R.string.pref_block_webrtc), cat7Title, null, CAT_VPN, webrtcCard)

            val schemesCard = addSwitchCard(netExtraGroup, colors, density, getString(R.string.pref_block_schemes), prefs.blockExternalSchemes) {
                prefs.blockExternalSchemes = it
            }
            registerSearchEntry(getString(R.string.pref_block_schemes), cat7Title, null, CAT_VPN, schemesCard)
            refreshGroupShapes(netExtraGroup, colors, density)
        }
        registerCompatNavItem(getString(R.string.nav_network), CAT_VPN, cat7Section, palette)

        // CAT 8: Tracker & Ad Blocking
        val cat8Title = getString(R.string.cat_hub_blocking)
        buildCategoryContainer(CAT_BLOCKING, cat8Title) { section ->
            val blockGroup = createCardGroup(section, topGapDp = 6f)
            val builtinCard = addSwitchCard(blockGroup, colors, density, getString(R.string.pref_builtin_blocklist), prefs.builtinBlocklistEnabled, iconRes = R.drawable.ic_chrome_tune) {
                prefs.builtinBlocklistEnabled = it
            }
            registerSearchEntry(getString(R.string.pref_builtin_blocklist), cat8Title, R.drawable.ic_chrome_tune, CAT_BLOCKING, builtinCard)

            val beaconsCard = addSwitchCard(blockGroup, colors, density, getString(R.string.pref_block_beacons), prefs.blockBeacons, getString(R.string.desc_block_beacons)) {
                prefs.blockBeacons = it
            }
            registerSearchEntry(getString(R.string.pref_block_beacons), cat8Title, null, CAT_BLOCKING, beaconsCard)
            refreshGroupShapes(blockGroup, colors, density)

            val rulesGroup = createCardGroup(section, topGapDp = 16f)
            val (rulesCard, rulesEt) = addTextFieldCard(
                parent = rulesGroup,
                colors = colors,
                density = density,
                label = getString(R.string.pref_custom_blocklist),
                initial = prefs.customBlocklistRaw,
                description = null,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            ) { prefs.customBlocklistRaw = it }
            customRulesInput = rulesEt
            registerSearchEntry(getString(R.string.pref_custom_blocklist), cat8Title, null, CAT_BLOCKING, rulesCard)
            refreshGroupShapes(rulesGroup, colors, density)
        }

        // CAT 9: Anti-Fingerprinting & Stealth
        val cat9Title = getString(R.string.cat_hub_antifp)
        val cat9Section = buildCategoryContainer(CAT_ANTIFP, cat9Title) { section ->
            val fpGroup = createCardGroup(section, topGapDp = 6f)
            val canvasCard = addSwitchCard(fpGroup, colors, density, getString(R.string.pref_noise_canvas), prefs.noiseCanvasWebgl, getString(R.string.desc_noise_canvas), iconRes = R.drawable.ic_snake_minimal) { prefs.noiseCanvasWebgl = it }
            val audioCard = addSwitchCard(fpGroup, colors, density, getString(R.string.pref_noise_audio), prefs.noiseAudio, getString(R.string.desc_noise_audio)) { prefs.noiseAudio = it }
            val vpCard = addSwitchCard(fpGroup, colors, density, getString(R.string.pref_lock_viewport), prefs.lockViewport, getString(R.string.desc_lock_viewport)) { prefs.lockViewport = it }
            val sensorCard = addSwitchCard(fpGroup, colors, density, getString(R.string.pref_sensor_fallback), prefs.sensorFallback, getString(R.string.desc_sensor_fallback)) { prefs.sensorFallback = it }
            val perfCard = addSwitchCard(fpGroup, colors, density, getString(R.string.pref_fuzz_perf), prefs.fuzzPerformanceNow, getString(R.string.desc_fuzz_perf)) { prefs.fuzzPerformanceNow = it }
            registerSearchEntry(getString(R.string.pref_noise_canvas), cat9Title, R.drawable.ic_snake_minimal, CAT_ANTIFP, canvasCard)
            registerSearchEntry(getString(R.string.pref_noise_audio), cat9Title, null, CAT_ANTIFP, audioCard)
            registerSearchEntry(getString(R.string.pref_lock_viewport), cat9Title, null, CAT_ANTIFP, vpCard)
            registerSearchEntry(getString(R.string.pref_sensor_fallback), cat9Title, null, CAT_ANTIFP, sensorCard)
            registerSearchEntry(getString(R.string.pref_fuzz_perf), cat9Title, null, CAT_ANTIFP, perfCard)
            refreshGroupShapes(fpGroup, colors, density)

            addSubheader(section, colors, density, getString(R.string.subcat_behavior))
            val behGroup = createCardGroup(section)
            val visCard = addSwitchCard(behGroup, colors, density, getString(R.string.pref_hide_visibility), prefs.hideVisibilityFocus, getString(R.string.desc_hide_visibility)) { prefs.hideVisibilityFocus = it }
            val dialogsCard = addSwitchCard(behGroup, colors, density, getString(R.string.pref_suppress_dialogs), prefs.suppressJsDialogs) { prefs.suppressJsDialogs = it }
            lateinit var minJitterCard: View
            lateinit var maxJitterCard: View
            val jitterCard = addSwitchCard(behGroup, colors, density, getString(R.string.pref_jitter_enabled), prefs.microJitterEnabled, getString(R.string.desc_jitter_enabled)) { enabled ->
                prefs.microJitterEnabled = enabled
                val vis = if (enabled) View.VISIBLE else View.GONE
                minJitterCard.visibility = vis
                maxJitterCard.visibility = vis
                refreshGroupShapes(behGroup, colors, density)
            }
            var maxValueView: TextView? = null
            val (minCard, _) = addDelayStepperCard(
                parent = behGroup,
                colors = colors,
                density = density,
                label = getString(R.string.pref_jitter_min),
                initialSec = prefs.microJitterMinSec,
                minLimit = 5,
                maxLimit = 60,
                valueProvider = { prefs.microJitterMinSec },
                onChanged = { newMin ->
                    prefs.microJitterMinSec = newMin
                    maxValueView?.text = getString(R.string.jitter_seconds_format, prefs.microJitterMaxSec)
                }
            )
            minJitterCard = minCard
            val (maxCard, maxTv) = addDelayStepperCard(
                parent = behGroup,
                colors = colors,
                density = density,
                label = getString(R.string.pref_jitter_max),
                initialSec = prefs.microJitterMaxSec,
                minLimit = 5,
                maxLimit = 90,
                valueProvider = { prefs.microJitterMaxSec },
                onChanged = { newMax -> prefs.microJitterMaxSec = newMax }
            )
            maxJitterCard = maxCard
            maxValueView = maxTv
            val jVis = if (prefs.microJitterEnabled) View.VISIBLE else View.GONE
            minJitterCard.visibility = jVis
            maxJitterCard.visibility = jVis
            registerSearchEntry(getString(R.string.pref_hide_visibility), cat9Title, null, CAT_ANTIFP, visCard)
            registerSearchEntry(getString(R.string.pref_suppress_dialogs), cat9Title, null, CAT_ANTIFP, dialogsCard)
            registerSearchEntry(getString(R.string.pref_jitter_enabled), cat9Title, null, CAT_ANTIFP, jitterCard)
            refreshGroupShapes(behGroup, colors, density)
        }
        registerCompatNavItem(getString(R.string.nav_antifp), CAT_ANTIFP, cat9Section, palette)

        // Build top-level Hub cards
        buildHubCards(colors, density)

        val targetSectionKey = if (!hasScrolledInitialTarget) intent.getStringExtra(EXTRA_SCROLL_TO_SECTION) else null
        val targetPair = targetSectionKey?.let { sectionViewsByKey[it] }
        if (targetPair != null) {
            hasScrolledInitialTarget = true
            val (navItem, section) = targetPair
            selectNavItem(navItem, section, palette)
            openCategory(sectionCategoryByKey[targetSectionKey] ?: CAT_DOWNLOADS)
        } else {
            hasScrolledInitialTarget = true
            val currentCat = activeCategoryId
            if (currentCat != null && currentCat in categoryContainers) {
                openCategory(currentCat)
                if (preserveScroll) {
                    contentScroll.post { contentScroll.scrollTo(0, previousScrollY) }
                }
            } else {
                activeCategoryId = null
                hubScroll.visibility = View.VISIBLE
                contentScroll.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                titleView.text = getString(R.string.settings_title)
                if (preserveScroll) {
                    contentScroll.post { contentScroll.scrollTo(0, previousScrollY) }
                }
                if (navItems.isNotEmpty() && container.childCount > 0) {
                    selectNavItem(navItems.first(), container.getChildAt(0), palette, programmatic = false)
                }
            }
        }
        // Keep compiler clean on cat3Nav
        cat3Nav.id
    }

    private fun buildHubCards(colors: SettingsUiColors, density: Float) {
        hubContainer.removeAllViews()

        val onShort = getString(R.string.state_on_short)
        val offShort = getString(R.string.state_off_short)

        // Block 1: Everyday Setup
        addSubheader(hubContainer, colors, density, getString(R.string.hub_group_everyday), topDp = 6f)
        val everydayGroup = createCardGroup(hubContainer)

        val activePresetSummary = prefs.activeProfileId?.let { id ->
            prefs.listProfiles().firstOrNull { it.id == id }?.name
        } ?: when (prefs.preset) {
            ProtectionPreset.MAX_STEALTH -> getString(R.string.preset_max_stealth)
            ProtectionPreset.COMPATIBILITY -> getString(R.string.preset_compatibility)
            ProtectionPreset.CUSTOM -> getString(R.string.preset_custom)
        }
        addHubCategoryCard(everydayGroup, colors, density, getString(R.string.cat_hub_presets), activePresetSummary, R.drawable.ic_settings_shield, CAT_PRESETS)

        val searchName = when (prefs.searchEngine) {
            SearchEngine.DUCKDUCKGO -> getString(R.string.search_ddg)
            SearchEngine.STARTPAGE -> getString(R.string.search_startpage)
            SearchEngine.BRAVE -> getString(R.string.search_brave)
            SearchEngine.CUSTOM -> getString(R.string.search_custom)
            SearchEngine.OFF -> getString(R.string.search_off)
        }
        addHubCategoryCard(everydayGroup, colors, density, getString(R.string.cat_hub_search_home), "$searchName · ${prefs.homeUrl}", R.drawable.ic_settings_home, CAT_SEARCH_HOME)

        val themeName = if (SnakeTheme.isDynamicThemeActive(this)) {
            getString(R.string.theme_system_dynamic)
        } else {
            when (prefs.themePalette) {
                ThemePalette.OBSIDIAN -> getString(R.string.theme_obsidian)
                ThemePalette.AMOLED -> getString(R.string.theme_amoled)
                ThemePalette.MIDNIGHT -> getString(R.string.theme_midnight)
                ThemePalette.EMERALD -> getString(R.string.theme_emerald)
                ThemePalette.SLATE_LIGHT -> getString(R.string.theme_slate_light)
            }
        }
        addHubCategoryCard(everydayGroup, colors, density, getString(R.string.cat_hub_appearance), "$themeName · ${prefs.textZoomPercent}%", R.drawable.ic_settings_palette, CAT_APPEARANCE)
        refreshGroupShapes(everydayGroup, colors, density)

        // Block 2: Privacy, Access & Files
        addSubheader(hubContainer, colors, density, getString(R.string.hub_group_privacy))
        val privacyGroup = createCardGroup(hubContainer)

        val pinSub = getString(if (prefs.pinLockEnabled) R.string.hub_sub_pin_on else R.string.hub_sub_pin_off)
        addHubCategoryCard(privacyGroup, colors, density, getString(R.string.cat_hub_pin), pinSub, R.drawable.ic_settings_lock, CAT_PIN)

        val wipeSub = getString(R.string.hub_sub_wipe, if (prefs.autoWipeOnExit) onShort else offShort)
        addHubCategoryCard(privacyGroup, colors, density, getString(R.string.cat_hub_wipe), wipeSub, R.drawable.ic_menu_wipe, CAT_WIPE)

        val dlSub = getString(
            R.string.hub_sub_downloads,
            if (prefs.downloadsEnabled) onShort else offShort,
            if (prefs.blockScreenshots) onShort else offShort
        )
        addHubCategoryCard(privacyGroup, colors, density, getString(R.string.cat_hub_downloads), dlSub, R.drawable.ic_settings_downloads, CAT_DOWNLOADS)
        refreshGroupShapes(privacyGroup, colors, density)

        // Block 3: Network & Anti-Tracking
        addSubheader(hubContainer, colors, density, getString(R.string.hub_group_network_snake))
        val netSnakeGroup = createCardGroup(hubContainer)

        val vpnSub = getString(
            R.string.hub_sub_vpn,
            if (prefs.vpnKillSwitchEnabled) onShort else offShort,
            if (prefs.blockWebRtcIce) onShort else offShort
        )
        addHubCategoryCard(netSnakeGroup, colors, density, getString(R.string.cat_hub_vpn), vpnSub, R.drawable.ic_settings_vpn, CAT_VPN)

        val blockingSub = getString(
            R.string.hub_sub_blocking,
            if (prefs.builtinBlocklistEnabled) onShort else offShort,
            prefs.parsedCustomRules().size
        )
        addHubCategoryCard(netSnakeGroup, colors, density, getString(R.string.cat_hub_blocking), blockingSub, R.drawable.ic_chrome_tune, CAT_BLOCKING)

        val activeShields = listOf(
            prefs.noiseCanvasWebgl,
            prefs.noiseAudio,
            prefs.lockViewport,
            prefs.sensorFallback,
            prefs.fuzzPerformanceNow,
            prefs.hideVisibilityFocus,
            prefs.suppressJsDialogs
        ).count { it }
        val antiFpSub = getString(
            R.string.hub_sub_antifp,
            activeShields,
            if (prefs.microJitterEnabled) onShort else offShort
        )
        addHubCategoryCard(netSnakeGroup, colors, density, getString(R.string.cat_hub_antifp), antiFpSub, R.drawable.ic_snake_minimal, CAT_ANTIFP)
        refreshGroupShapes(netSnakeGroup, colors, density)

        // Block 4: System (About & Reset inline in main settings)
        addSubheader(hubContainer, colors, density, getString(R.string.hub_group_about))
        val systemGroup = createCardGroup(hubContainer)
        val cat10Title = getString(R.string.cat_hub_about)
        searchEntries.removeAll { it.categoryId == CAT_ABOUT }

        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        }.getOrDefault("1.0.0")
        val infoCard = createBaseCardContainer(colors, density, hasIcon = true)
        infoCard.addView(createLeadingIcon(R.drawable.ic_settings_info, colors, density))
        val infoCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        infoCol.addView(TextView(this).apply {
            text = getString(R.string.about_version_format, versionName)
            textSize = 16f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(colors.titleText)
        })
        infoCol.addView(TextView(this).apply {
            text = getString(R.string.about_author)
            textSize = 13.5f
            setTextColor(colors.subtitleText)
            setPadding(0, (3 * density).toInt(), 0, 0)
        })
        infoCard.addView(infoCol)
        systemGroup.addView(infoCard)

        val githubBtn = addActionCard(systemGroup, colors, density, getString(R.string.about_github_label), getString(R.string.about_github_url)) {
            openGithubProject()
        }
        registerSearchEntry(getString(R.string.about_github_label), cat10Title, null, CAT_ABOUT, githubBtn)

        val onboardingBtn = addActionCard(systemGroup, colors, density, getString(R.string.pref_show_onboarding)) {
            prefs.onboardingCompleted = false
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            finish()
        }
        registerSearchEntry(getString(R.string.pref_show_onboarding), cat10Title, null, CAT_ABOUT, onboardingBtn)

        val resetBtn = addActionCard(systemGroup, colors, density, getString(R.string.pref_reset_defaults)) {
            showResetConfirmationDialog()
        }
        registerSearchEntry(getString(R.string.pref_reset_defaults), cat10Title, null, CAT_ABOUT, resetBtn)
        refreshGroupShapes(systemGroup, colors, density)
    }
    private fun addHubCategoryCard(
        group: LinearLayout,
        colors: SettingsUiColors,
        density: Float,
        title: String,
        subtitle: String,
        @DrawableRes iconRes: Int,
        categoryId: String
    ) {
        val card = createBaseCardContainer(colors, density, hasIcon = true)
        card.addView(createLeadingIcon(iconRes, colors, density))
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = title
            textSize = 16f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(colors.titleText)
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            textSize = 13.5f
            setTextColor(colors.subtitleText)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, (2 * density).toInt(), 0, 0)
        })
        card.addView(textCol)
        SnakeTheme.attachExpressivePressAnimation(card, scaleDown = 0.975f)
        card.setOnClickListener { openCategory(categoryId) }
        group.addView(card)
    }

    private fun buildCategoryContainer(
        categoryId: String,
        title: String,
        populate: (LinearLayout) -> Unit
    ): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        populate(section)
        container.addView(section)
        categoryContainers[categoryId] = section
        categoryTitles[categoryId] = title
        return section
    }

    private fun registerCompatNavItem(
        navLabel: String,
        categoryId: String,
        section: View,
        palette: ThemePalette
    ): TextView {
        val navItem = TextView(this).apply {
            text = navLabel
            textSize = 12f
            maxLines = 2
            setTextColor(palette.muted)
        }
        navItem.setOnClickListener {
            selectNavItem(navItem, section, palette)
            hubScroll.visibility = View.GONE
            for ((_, catContainer) in categoryContainers) {
                catContainer.visibility = View.VISIBLE
            }
            contentScroll.post {
                contentScroll.smoothScrollTo(0, section.top)
                pulseViewHighlight(section, palette)
            }
        }
        navItems.add(navItem)
        navRailContainer.addView(navItem)
        return navItem
    }

    private fun openSearchOverlay() {
        closeSearchOverlay()
        val colors = resolveColors(prefs.themePalette)
        val density = resources.displayMetrics.density

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.searchBg)
            isClickable = true
            isFocusable = true
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(colors.searchBarBg)
                cornerRadius = 24f * density
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (48 * density).toInt()
            ).apply {
                val hm = (8 * density).toInt()
                setMargins(hm, (10 * density).toInt(), hm, (6 * density).toInt())
            }
        }

        val rippleMask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        val closeSearchBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings_back)
            setColorFilter(colors.titleText, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.CENTER
            background = RippleDrawable(ColorStateList.valueOf(colors.rippleColor), null, rippleMask)
            contentDescription = getString(R.string.action_cancel)
            layoutParams = LinearLayout.LayoutParams((46 * density).toInt(), (48 * density).toInt())
            SnakeTheme.attachExpressivePressAnimation(this, scaleDown = 0.90f)
            setOnClickListener { closeSearchOverlay() }
        }
        bar.addView(closeSearchBtn)

        val searchInput = EditText(this).apply {
            hint = getString(R.string.settings_search_hint)
            textSize = 16f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(colors.titleText)
            setHintTextColor(colors.subtitleText)
            background = null
            setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        bar.addView(searchInput)

        val clearBtn = TextView(this).apply {
            text = "✕"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(colors.titleText)
            visibility = View.GONE
            background = RippleDrawable(ColorStateList.valueOf(colors.rippleColor), null, rippleMask)
            layoutParams = LinearLayout.LayoutParams((46 * density).toInt(), (48 * density).toInt())
            setOnClickListener { searchInput.setText("") }
        }
        bar.addView(clearBtn)
        overlay.addView(bar)

        val resultsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * density).toInt(), 0, (24 * density).toInt())
        }
        val resultsScroll = ScrollView(this).apply { addView(resultsList) }
        overlay.addView(resultsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        fun renderResults(query: String) {
            resultsList.removeAllViews()
            val q = query.trim().lowercase()
            val matches = if (q.isEmpty()) searchEntries else searchEntries.filter { it.keywords.contains(q) }
            if (matches.isEmpty()) {
                resultsList.addView(TextView(this).apply {
                    text = getString(R.string.settings_search_empty)
                    textSize = 14.5f
                    setTextColor(colors.subtitleText)
                    setPadding((68 * density).toInt(), (24 * density).toInt(), (24 * density).toInt(), (24 * density).toInt())
                })
                return
            }
            for (entry in matches) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = (68 * density).toInt()
                    setPadding((22 * density).toInt(), (12 * density).toInt(), (24 * density).toInt(), (12 * density).toInt())
                    val mask = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(Color.WHITE)
                    }
                    background = RippleDrawable(ColorStateList.valueOf(colors.rippleColor), null, mask)
                    SnakeTheme.attachExpressivePressAnimation(this, scaleDown = 0.98f)
                    setOnClickListener {
                        closeSearchOverlay()
                        openCategory(entry.categoryId, entry.targetView)
                    }
                }

                // Fixed 46dp leading slot (24dp icon + 22dp gap) so all search items align at x = 68dp
                val leadingSlot = if (entry.iconRes != null) {
                    ImageView(this).apply {
                        setImageResource(entry.iconRes)
                        setColorFilter(colors.iconTint, PorterDuff.Mode.SRC_IN)
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt()).apply {
                            marginEnd = (22 * density).toInt()
                        }
                    }
                } else {
                    View(this).apply {
                        layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt()).apply {
                            marginEnd = (22 * density).toInt()
                        }
                    }
                }
                row.addView(leadingSlot)

                val textCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                textCol.addView(TextView(this).apply {
                    text = entry.title
                    textSize = 16f
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    setTextColor(colors.titleText)
                })
                textCol.addView(TextView(this).apply {
                    text = entry.category
                    textSize = 13.5f
                    setTextColor(colors.subtitleText)
                    setPadding(0, (2 * density).toInt(), 0, 0)
                })
                row.addView(textCol)
                resultsList.addView(row)
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                clearBtn.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
                renderResults(text)
            }
        })

        renderResults("")
        searchOverlay = overlay
        overlay.alpha = 0f
        bar.translationY = -12f * density
        rootFrame.addView(
            overlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        overlay.animate().alpha(1f).setDuration(180L).setInterpolator(DecelerateInterpolator()).start()
        bar.animate().translationY(0f).setDuration(200L).setInterpolator(DecelerateInterpolator()).start()
        searchInput.requestFocus()
    }

    private fun closeSearchOverlay() {
        searchOverlay?.let {
            rootFrame.removeView(it)
            searchOverlay = null
        }
    }

    private fun registerSearchEntry(
        title: String,
        category: String,
        @DrawableRes iconRes: Int?,
        categoryId: String,
        targetView: View
    ) {
        searchEntries.add(
            SearchEntry(
                title = title,
                category = category,
                keywords = "${title.lowercase()} ${category.lowercase()}",
                iconRes = iconRes,
                categoryId = categoryId,
                targetView = targetView
            )
        )
    }

    private fun computeRelativeTop(child: View, ancestor: ViewGroup): Int {
        var top = child.top
        var current = child.parent
        while (current is View && current !== ancestor) {
            top += current.top
            current = current.parent
        }
        return top
    }

    private fun openGithubProject() {
        val url = getString(R.string.about_github_url)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val launched = runCatching {
            startActivity(intent)
            true
        }.getOrDefault(false)
        if (!launched) {
            val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("github", url))
            Toast.makeText(this, R.string.about_github_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun createCardGroup(parent: LinearLayout, topGapDp: Float = 0f): LinearLayout {
        val density = resources.displayMetrics.density
        val hm = (16 * density).toInt()
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutTransition = LayoutTransition().apply {
                setDuration(180L)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(hm, (topGapDp * density).toInt(), hm, 0)
            }
        }
        parent.addView(group)
        return group
    }

    private fun createBaseCardContainer(
        colors: SettingsUiColors,
        density: Float,
        hasIcon: Boolean
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = (68 * density).toInt()
        val padStart = ((if (hasIcon) 20 else 18) * density).toInt()
        val padEnd = (16 * density).toInt()
        val padV = (14 * density).toInt()
        setPadding(padStart, padV, padEnd, padV)
    }

    private fun createLeadingIcon(
        @DrawableRes iconRes: Int,
        colors: SettingsUiColors,
        density: Float
    ): ImageView = ImageView(this).apply {
        setImageResource(iconRes)
        setColorFilter(colors.iconTint, PorterDuff.Mode.SRC_IN)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt()).apply {
            marginEnd = (20 * density).toInt()
        }
    }

    private fun refreshGroupShapes(
        group: LinearLayout,
        colors: SettingsUiColors,
        density: Float
    ) {
        val visible = mutableListOf<View>()
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.visibility == View.VISIBLE) {
                visible.add(child)
            }
        }
        val outerR = OUTER_CORNER_DP * density
        val innerR = INNER_CORNER_DP * density
        val seam = (CARD_SEAM_DP * density).toInt().coerceAtLeast(1)

        visible.forEachIndexed { idx, card ->
            val topR = if (idx == 0) outerR else innerR
            val botR = if (idx == visible.lastIndex) outerR else innerR
            val shape = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                setColor(colors.cardSurface)
                cornerRadii = floatArrayOf(topR, topR, topR, topR, botR, botR, botR, botR)
            }
            val mask = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(topR, topR, topR, topR, botR, botR, botR, botR)
            }
            card.background = RippleDrawable(ColorStateList.valueOf(colors.rippleColor), shape, mask)

            val lp = (card.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = if (idx == 0) 0 else seam
            card.layoutParams = lp
        }
    }

    private fun addSubheader(
        parent: LinearLayout,
        colors: SettingsUiColors,
        density: Float,
        title: String,
        topDp: Float = 18f
    ) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(colors.categoryHeader)
            setPadding((24 * density).toInt(), (topDp * density).toInt(), (24 * density).toInt(), (8 * density).toInt())
        })
    }

    private fun selectNavItem(
        selected: TextView,
        section: View,
        palette: ThemePalette,
        programmatic: Boolean = true
    ) {
        val browserColors = SnakeTheme.resolveBrowserColors(this, palette)
        settleRunnable?.let { contentScroll.removeCallbacks(it) }
        selectedNavItem = selected
        selectedSectionView = section
        for (item in navItems) {
            val isSelected = item === selected
            item.setTextColor(if (isSelected) browserColors.accent else browserColors.muted)
            item.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        }
        if (!programmatic) {
            programmaticScrollActive = false
            settledScrollY = contentScroll.scrollY
            return
        }
        programmaticScrollActive = true
        settledScrollY = -1
        val runnable = Runnable {
            programmaticScrollActive = false
            settledScrollY = contentScroll.scrollY
        }
        settleRunnable = runnable
        contentScroll.postDelayed(runnable, 450L)
    }

    private fun clearNavSelection(palette: ThemePalette) {
        val browserColors = SnakeTheme.resolveBrowserColors(this, palette)
        settleRunnable?.let { contentScroll.removeCallbacks(it) }
        settleRunnable = null
        selectedNavItem = null
        selectedSectionView = null
        programmaticScrollActive = false
        settledScrollY = -1
        for (item in navItems) {
            item.setTextColor(browserColors.muted)
            item.setTypeface(null, Typeface.NORMAL)
        }
    }

    private fun pulseViewHighlight(target: View, palette: ThemePalette) {
        val browserColors = SnakeTheme.resolveBrowserColors(this, palette)
        activeHighlightAnimator?.cancel()
        highlightedView = target
        val originalBg = target.background
        val startColor = ColorUtils.setAlphaComponent(browserColors.accent, 36)
        activeHighlightAnimator = ValueAnimator.ofObject(ArgbEvaluator(), startColor, Color.TRANSPARENT).apply {
            duration = 1200L
            addUpdateListener { animator ->
                if (target === highlightedView) {
                    val c = animator.animatedValue as Int
                    if (originalBg == null) {
                        target.setBackgroundColor(c)
                    } else {
                        target.alpha = 0.82f + 0.18f * (1f - animator.animatedFraction)
                    }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    if (originalBg == null) target.setBackgroundColor(Color.TRANSPARENT) else target.alpha = 1f
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (originalBg == null) target.setBackgroundColor(Color.TRANSPARENT) else target.alpha = 1f
                    if (highlightedView === target) highlightedView = null
                }
            })
            start()
        }
    }

    private fun addPinAutoWipeSwitchCard(
        parent: LinearLayout,
        colors: SettingsUiColors,
        density: Float
    ): View {
        val label = getString(R.string.pref_pin_auto_wipe)
        val card = createBaseCardContainer(colors, density, hasIcon = false)
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (12 * density).toInt()
            }
        }
        textCol.addView(TextView(this).apply {
            text = label
            textSize = 16f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(colors.titleText)
        })

        lateinit var sw: Android16Switch
        var ignoreChange = false
        sw = Android16Switch(this, density).apply {
            text = label
            isChecked = prefs.pinAutoWipeOnFail
            applySwitchColors(this, colors)
            setOnCheckedChangeListener { _, isChecked ->
                if (ignoreChange) return@setOnCheckedChangeListener
                if (!isChecked && prefs.pinLockEnabled) {
                    ignoreChange = true
                    sw.isChecked = true
                    ignoreChange = false
                    showVerifyCurrentPinDialog(
                        titleRes = R.string.pin_verify_disable_autowipe_title,
                        palette = prefs.themePalette,
                        density = density
                    ) {
                        prefs.pinAutoWipeOnFail = false
                        ignoreChange = true
                        sw.isChecked = false
                        ignoreChange = false
                    }
                } else {
                    prefs.pinAutoWipeOnFail = isChecked
                }
            }
        }
        card.addView(textCol)
        card.addView(sw)
        SnakeTheme.attachExpressivePressAnimation(card, scaleDown = 0.98f) { pressed ->
            sw.setPressedState(pressed)
        }
        card.setOnClickListener { sw.performClick() }
        parent.addView(card)
        return card
    }

    private fun showResetConfirmationDialog() {
        val d = AlertDialog.Builder(this)
            .setTitle(R.string.reset_confirm_title)
            .setMessage(R.string.reset_confirm_body)
            .setPositiveButton(R.string.action_reset) { _, _ ->
                prefs.resetToDefaults(preservePin = true)
                Toast.makeText(this, R.string.settings_reset_done, Toast.LENGTH_SHORT).show()
                // Skip flushing stale/un-blurred text fields so reset defaults are not overwritten.
                buildSettingsUi(preserveScroll = false, flushInputs = false)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
        trackDialog(d)
        SnakeTheme.styleExpressiveDialog(d, prefs.themePalette, destructivePositive = true)
    }

    private fun showVerifyCurrentPinDialog(
        titleRes: Int,
        palette: ThemePalette,
        density: Float,
        onVerified: () -> Unit
    ) {
        trackDialog(PinDialogHelper.showVerifyCurrentPinDialog(this, prefs, titleRes, onVerified))
    }

    private fun showSetPinDialog(palette: ThemePalette, density: Float) {
        trackDialog(
            PinDialogHelper.showSetPinDialog(this, prefs, includeBody = true) {
                buildSettingsUi(preserveScroll = true)
            }
        )
    }

    private fun showCreateProfileDialog(palette: ThemePalette, density: Float) {
        val dialogColors = SnakeTheme.resolveDialogColors(palette, this)
        val pad = (22 * density).toInt()
        val input = EditText(this).apply {
            hint = getString(R.string.profile_create_hint)
            setSingleLine(true)
            textSize = 15.5f
            setTextColor(dialogColors.title)
            setHintTextColor(dialogColors.body)
            background = SnakeTheme.roundedSurface(dialogColors.surfaceContainer, dialogColors.stroke, 16f, density)
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (12 * density).toInt(), pad, (4 * density).toInt())
            addView(input)
        }
        val d = AlertDialog.Builder(this)
            .setTitle(R.string.profile_create_title)
            .setView(wrapper)
            .setPositiveButton(R.string.profile_create_btn) { _, _ ->
                val raw = input.text?.toString().orEmpty().trim()
                if (raw.isNotEmpty()) {
                    val created = prefs.createProfileFromCurrent(raw)
                    Toast.makeText(this, getString(R.string.profile_created, created.name), Toast.LENGTH_SHORT).show()
                    buildSettingsUi()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
        trackDialog(d)
        SnakeTheme.styleExpressiveDialog(d, palette)
    }

    private fun createPresetButton(
        label: String,
        active: Boolean,
        colors: SettingsUiColors,
        density: Float,
        onClick: () -> Unit
    ): MaterialButton {
        val activeBg = colors.switchTrackOn
        val activeText = colors.switchThumbOn
        val inactiveBg = colors.inputFieldBg
        return MaterialButton(this).apply {
            text = label
            textSize = 13f
            maxLines = 1
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            isAllCaps = false
            cornerRadius = 0
            elevation = 0f
            stateListAnimator = null
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            insetTop = (2 * density).toInt()
            insetBottom = (2 * density).toInt()
            minHeight = (40 * density).toInt()
            minimumHeight = (40 * density).toInt()
            if (active) {
                backgroundTintList = ColorStateList.valueOf(activeBg)
                setTextColor(activeText)
                strokeWidth = 0
            } else {
                backgroundTintList = ColorStateList.valueOf(inactiveBg)
                setTextColor(colors.titleText)
                strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(colors.subtitleText, 70))
                strokeWidth = (1 * density).toInt()
            }
            layoutParams = LinearLayout.LayoutParams(0, (40 * density).toInt(), 1f)
            SnakeTheme.attachExpressivePressAnimation(this, scaleDown = 0.95f)
            setOnClickListener { onClick() }
        }
    }

    private fun applySwitchColors(sw: Android16Switch, colors: SettingsUiColors) {
        sw.applyColors(
            trackOn = colors.switchTrackOn,
            thumbOn = colors.switchThumbOn,
            trackOff = colors.switchTrackOff,
            strokeOff = colors.switchStrokeOff,
            thumbOff = colors.switchThumbOff,
            crossOff = colors.switchCrossOff
        )
    }

    private fun addSwitchCard(
        parent: LinearLayout,
        colors: SettingsUiColors,
        density: Float,
        label: String,
        checked: Boolean,
        description: String? = null,
        @DrawableRes iconRes: Int? = null,
        onChanged: (Boolean) -> Unit
    ): View {
        val card = createBaseCardContainer(colors, density, hasIcon = iconRes != null)
        if (iconRes != null) {
            card.addView(createLeadingIcon(iconRes, colors, density))
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (12 * density).toInt()
            }
        }
        textCol.addView(TextView(this).apply {
            text = label
            textSize = 16f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(colors.titleText)
        })
        if (!description.isNullOrBlank()) {
            textCol.addView(TextView(this).apply {
                text = description
                textSize = 13.5f
                setTextColor(colors.subtitleText)
                setPadding(0, (2 * density).toInt(), 0, 0)
            })
        }

        val sw = Android16Switch(this, density).apply {
            text = label
            isChecked = checked
            applySwitchColors(this, colors)
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }
        card.addView(textCol)
        card.addView(sw)
        SnakeTheme.attachExpressivePressAnimation(card, scaleDown = 0.98f) { pressed ->
            sw.setPressedState(pressed)
        }
        card.setOnClickListener { sw.performClick() }
        parent.addView(card)
        return card
    }

    private fun <T> addChoiceCard(
        parent: LinearLayout,
        colors: SettingsUiColors,
        density: Float,
        label: String,
        options: List<Pair<T, String>>,
        selected: T,
        @DrawableRes iconRes: Int? = null,
        onSelected: (T) -> Unit
    ): View {
        var currentSelected = selected
        val selectedTitle = options.firstOrNull { it.first == selected }?.second
            ?: options.firstOrNull()?.second.orEmpty()

        val card = createBaseCardContainer(colors, density, hasIcon = iconRes != null)
        if (iconRes != null) {
            card.addView(createLeadingIcon(iconRes, colors, density))
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = label
            textSize = 16f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(colors.titleText)
        })
        val summaryTv = TextView(this).apply {
            text = selectedTitle
            textSize = 13.5f
            setTextColor(colors.subtitleText)
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        textCol.addView(summaryTv)
        card.addView(textCol)

        val group = RadioGroup(this).apply {
            visibility = View.GONE
            isSaveEnabled = false
        }
        val buttonIds = options.map { View.generateViewId() }
        val buttons = mutableListOf<RadioButton>()
        options.forEachIndexed { idx, (value, title) ->
            val rb = RadioButton(this).apply {
                id = buttonIds[idx]
                isSaveEnabled = false
                text = title
                isChecked = (value == selected)
            }
            buttons.add(rb)
            group.addView(rb)
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val index = buttonIds.indexOf(checkedId)
            if (index in options.indices) {
                currentSelected = options[index].first
                summaryTv.text = options[index].second
                onSelected(currentSelected)
            }
        }
        card.addView(group)

        SnakeTheme.attachExpressivePressAnimation(card, scaleDown = 0.975f)
        card.setOnClickListener {
            val paletteNow = prefs.themePalette
            val dialogColors = SnakeTheme.resolveDialogColors(paletteNow, this)
            val labels = options.map { it.second }.toTypedArray()
            val curIdx = options.indexOfFirst { it.first == currentSelected }.coerceAtLeast(0)
            val adapter = object : ArrayAdapter<String>(
                this,
                androidx.appcompat.R.layout.select_dialog_singlechoice_material,
                labels
            ) {
                override fun getView(position: Int, convertView: View?, parentGroup: ViewGroup): View {
                    val v = super.getView(position, convertView, parentGroup)
                    (v as? CheckedTextView)?.apply {
                        setTextColor(dialogColors.title)
                        checkMarkTintList = ColorStateList.valueOf(dialogColors.positiveBg)
                    }
                    return v
                }
            }
            val d = AlertDialog.Builder(this)
                .setTitle(label)
                .setSingleChoiceItems(adapter, curIdx) { dialog, which ->
                    if (which in buttons.indices) {
                        buttons[which].isChecked = true
                    }
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
            trackDialog(d)
            SnakeTheme.styleExpressiveDialog(d, paletteNow)
        }

        parent.addView(card)
        return card
    }

    private fun addTextFieldCard(
        parent: LinearLayout,
        colors: SettingsUiColors,
        density: Float,
        label: String,
        initial: String,
        description: String?,
        inputType: Int,
        @DrawableRes iconRes: Int? = null,
        onSave: (String) -> Unit
    ): Pair<View, EditText> {
        val card = createBaseCardContainer(colors, density, hasIcon = iconRes != null)
        if (iconRes != null) {
            card.addView(createLeadingIcon(iconRes, colors, density))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = label
            textSize = 16f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(colors.titleText)
        })
        if (!description.isNullOrBlank()) {
            col.addView(TextView(this).apply {
                text = description
                textSize = 13f
                setTextColor(colors.subtitleText)
                setPadding(0, (2 * density).toInt(), 0, (6 * density).toInt())
            })
        } else {
            col.addView(View(this), LinearLayout.LayoutParams(0, (8 * density).toInt()))
        }

        val isMultiLine = (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val et = EditText(this).apply {
            setText(initial)
            this.inputType = inputType
            if (!isMultiLine) {
                isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_DONE
            }
            textSize = 14.5f
            setTextColor(colors.titleText)
            setHintTextColor(colors.subtitleText)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(colors.inputFieldBg)
                cornerRadius = 12f * density
            }
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    onSave(text?.toString().orEmpty())
                }
            }
            if (!isMultiLine) {
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                        onSave(text?.toString().orEmpty())
                        clearFocus()
                        true
                    } else {
                        false
                    }
                }
            }
        }
        col.addView(et)
        card.addView(col)
        parent.addView(card)
        return card to et
    }

    private fun addDelayStepperCard(
        parent: LinearLayout,
        colors: SettingsUiColors,
        density: Float,
        label: String,
        initialSec: Int,
        minLimit: Int,
        maxLimit: Int,
        valueProvider: (() -> Int)? = null,
        onChanged: (Int) -> Unit
    ): Pair<View, TextView> {
        val card = createBaseCardContainer(colors, density, hasIcon = false)
        val labelTv = TextView(this).apply {
            text = label
            textSize = 16f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(colors.titleText)
        }
        var currentVal = initialSec
        val valueTv = TextView(this).apply {
            text = getString(R.string.jitter_seconds_format, currentVal)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colors.categoryHeader)
            gravity = Gravity.CENTER
            minWidth = (44 * density).toInt()
        }
        val createStepBtn = { sign: String, delta: Int ->
            MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = sign
                isAllCaps = false
                cornerRadius = 0
                setTextColor(colors.titleText)
                contentDescription = "$label $sign"
                minWidth = 0
                minimumWidth = 0
                setPadding((12 * density).toInt(), (4 * density).toInt(), (12 * density).toInt(), (4 * density).toInt())
                SnakeTheme.attachExpressivePressAnimation(this, scaleDown = 0.90f)
                setOnClickListener {
                    val base = valueProvider?.invoke() ?: currentVal
                    val next = (base + delta).coerceIn(minLimit, maxLimit)
                    onChanged(next)
                    currentVal = valueProvider?.invoke() ?: next
                    valueTv.text = getString(R.string.jitter_seconds_format, currentVal)
                }
            }
        }
        card.addView(labelTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(createStepBtn("-", -1))
        card.addView(valueTv)
        card.addView(createStepBtn("+", +1))
        parent.addView(card)
        return card to valueTv
    }

    private fun addActionCard(
        parent: LinearLayout,
        colors: SettingsUiColors,
        density: Float,
        label: String,
        subtitle: String? = null,
        @DrawableRes iconRes: Int? = null,
        onClick: () -> Unit
    ): Button {
        val padStart = ((if (iconRes != null) 20 else 18) * density).toInt()
        val padEnd = (16 * density).toInt()
        val padV = (14 * density).toInt()

        if (subtitle.isNullOrBlank()) {
            val btn = AppCompatButton(this).apply {
                text = label
                isAllCaps = false
                textSize = 16f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(colors.titleText)
                stateListAnimator = null
                elevation = 0f
                minimumHeight = (68 * density).toInt()
                minHeight = (68 * density).toInt()
                setPadding(padStart, padV, padEnd, padV)
                if (iconRes != null) {
                    val drawable = ContextCompat.getDrawable(this@SettingsActivity, iconRes)?.mutate()
                    drawable?.setTint(colors.iconTint)
                    val size = (24 * density).toInt()
                    drawable?.setBounds(0, 0, size, size)
                    setCompoundDrawablesRelative(drawable, null, null, null)
                    compoundDrawablePadding = (20 * density).toInt()
                }
                SnakeTheme.attachExpressivePressAnimation(this, scaleDown = 0.975f)
                setOnClickListener { onClick() }
            }
            parent.addView(btn)
            return btn
        }

        val wrapper = createBaseCardContainer(colors, density, hasIcon = iconRes != null)
        if (iconRes != null) {
            wrapper.addView(createLeadingIcon(iconRes, colors, density))
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val innerBtn = AppCompatButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(colors.titleText)
            background = null
            stateListAnimator = null
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClick() }
        }
        textCol.addView(innerBtn)
        textCol.addView(TextView(this).apply {
            text = subtitle
            textSize = 13.5f
            setTextColor(colors.subtitleText)
            setPadding(0, (2 * density).toInt(), 0, 0)
        })
        wrapper.addView(textCol)
        SnakeTheme.attachExpressivePressAnimation(wrapper, scaleDown = 0.975f)
        wrapper.setOnClickListener { innerBtn.performClick() }
        parent.addView(wrapper)
        return innerBtn
    }

    companion object {
        const val EXTRA_SCROLL_TO_SECTION = "extra_scroll_to_section"
        const val SECTION_DOWNLOADS = "section_downloads"
        private const val STATE_ACTIVE_CATEGORY = "state_active_category"

        internal const val CAT_PRESETS = "cat_presets"
        internal const val CAT_SEARCH_HOME = "cat_search_home"
        internal const val CAT_APPEARANCE = "cat_appearance"
        internal const val CAT_PIN = "cat_pin"
        internal const val CAT_WIPE = "cat_wipe"
        internal const val CAT_DOWNLOADS = "cat_downloads"
        internal const val CAT_VPN = "cat_vpn"
        internal const val CAT_BLOCKING = "cat_blocking"
        internal const val CAT_ANTIFP = "cat_antifp"
        internal const val CAT_ABOUT = "cat_about"

        internal const val OUTER_CORNER_DP = 24f
        internal const val INNER_CORNER_DP = 4f
        internal const val CARD_SEAM_DP = 2f
        internal const val SWITCH_WIDTH_DP = 52f
        internal const val SWITCH_HEIGHT_DP = 32f
    }
}
