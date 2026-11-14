package com.nromanovich.stealthsnake

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils

/**
 * Android 16 Material 3 Expressive Troubleshooting Guide matching [SettingsActivity]:
 * circular back navigation, pill search bar, morphing filter chips, and
 * asymmetric grouped fix cards with highlighted resolution blocks.
 */
class SiteFixesActivity : AppCompatActivity() {

    private data class GuideUiColors(
        val screenBg: Int,
        val cardSurface: Int,
        val innerContainer: Int,
        val backCircleBg: Int,
        val searchPillBg: Int,
        val accentHeader: Int,
        val activeChipBg: Int,
        val activeChipText: Int,
        val titleText: Int,
        val bodyText: Int,
        val rippleColor: Int
    )

    private lateinit var palette: ThemePalette
    private var themeSignature: String = ""
    private lateinit var uiColors: GuideUiColors
    private var density: Float = 1f

    private lateinit var rootLayout: LinearLayout
    private lateinit var backBtn: ImageButton
    private lateinit var titleView: TextView
    private lateinit var btnOpenSettings: TextView
    private lateinit var searchBox: LinearLayout
    private lateinit var searchIcon: ImageView
    private lateinit var searchInput: EditText
    private lateinit var clearSearchBtn: ImageButton
    private lateinit var chipStrip: HorizontalScrollView
    private lateinit var chipContainer: LinearLayout
    private lateinit var introView: TextView
    private lateinit var countLabel: TextView
    private lateinit var resultsList: LinearLayout
    private lateinit var resultsScroll: ScrollView
    private lateinit var emptyTitleView: TextView
    private lateinit var emptyBodyView: TextView
    private lateinit var emptyResetBtn: TextView
    private lateinit var emptyStateView: LinearLayout

    private val categoryNavItems = mutableMapOf<FixCategory, TextView>()
    private var selectedCategory: FixCategory = FixCategory.ALL
    private val searchHandler = Handler(Looper.getMainLooper())
    private val searchDebounceRunnable = Runnable { renderResults(animateList = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = SnakePreferences(this).themePalette
        themeSignature = SnakeTheme.resolveThemeSignature(this)
        density = resources.displayMetrics.density
        uiColors = resolveGuideColors(palette)
        SnakeTheme.applySystemBars(this, palette, uiColors.screenBg)

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(uiColors.screenBg)
        }

        // Top Header Bar matching SettingsActivity
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
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
            setOnClickListener { finish() }
        }
        titleView = TextView(this).apply {
            text = getString(R.string.fixes_title)
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (14 * density).toInt()
                marginEnd = (8 * density).toInt()
            }
        }
        btnOpenSettings = TextView(this).apply {
            text = getString(R.string.fixes_open_settings)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minHeight = (36 * density).toInt()
            val hPad = (14 * density).toInt()
            val vPad = (6 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            SnakeTheme.attachExpressivePressAnimation(this, scaleDown = 0.94f)
            setOnClickListener {
                startActivity(
                    Intent(this@SiteFixesActivity, SettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        }
        headerRow.addView(backBtn)
        headerRow.addView(titleView)
        headerRow.addView(btnOpenSettings)
        rootLayout.addView(headerRow)

        // Android 16 48dp Pill Search Bar
        searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * density).toInt(), 0, (8 * density).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (48 * density).toInt()
            ).apply {
                setMargins((16 * density).toInt(), (2 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
            }
        }
        searchIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings_search)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams((20 * density).toInt(), (20 * density).toInt()).apply {
                marginEnd = (10 * density).toInt()
            }
        }
        searchInput = EditText(this).apply {
            hint = getString(R.string.fixes_search_hint)
            textSize = 15f
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        }
        clearSearchBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = null
            contentDescription = getString(R.string.fixes_clear_search)
            visibility = View.GONE
            SnakeTheme.attachExpressivePressAnimation(this, scaleDown = 0.88f)
            setOnClickListener { searchInput.setText("") }
        }
        searchBox.addView(searchIcon)
        searchBox.addView(searchInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchBox.addView(clearSearchBtn, LinearLayout.LayoutParams((38 * density).toInt(), (38 * density).toInt()))
        rootLayout.addView(searchBox)

        // Horizontal M3 Expressive Filter Chips Bar
        chipContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), (2 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
        }
        for ((index, cat) in FixCategory.entries.withIndex()) {
            val chip = TextView(this).apply {
                text = getString(cat.labelRes)
                textSize = 13f
                maxLines = 1
                gravity = Gravity.CENTER
                minHeight = (36 * density).toInt()
                val hPad = (16 * density).toInt()
                val vPad = (8 * density).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index > 0) marginStart = (8 * density).toInt()
                }
                SnakeTheme.attachExpressivePressAnimation(this, scaleDown = 0.94f)
                setOnClickListener {
                    if (selectedCategory != cat) {
                        selectedCategory = cat
                        updateCategoryRail()
                        renderResults(animateList = true)
                        resultsScroll.scrollTo(0, 0)
                    }
                }
            }
            categoryNavItems[cat] = chip
            chipContainer.addView(chip)
        }
        chipStrip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipContainer)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        rootLayout.addView(chipStrip)

        // Main Scrollable Content Area
        val contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (4 * density).toInt(), (16 * density).toInt(), (28 * density).toInt())
        }

        introView = TextView(this).apply {
            text = getString(R.string.fixes_intro)
            textSize = 13f
            setLineSpacing(2f * density, 1f)
            setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
        }
        contentColumn.addView(introView)

        countLabel = TextView(this).apply {
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (10 * density).toInt())
        }
        contentColumn.addView(countLabel)

        resultsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        emptyStateView = buildEmptyStateView()
        contentColumn.addView(resultsList)
        contentColumn.addView(emptyStateView)

        resultsScroll = ScrollView(this).apply {
            addView(contentColumn)
        }
        rootLayout.addView(resultsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(rootLayout)

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                clearSearchBtn.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                searchHandler.removeCallbacks(searchDebounceRunnable)
                if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
                    renderResults(animateList = false)
                } else {
                    searchHandler.postDelayed(searchDebounceRunnable, 120L)
                }
            }
        })

        applyHeaderAndChromeColors()
        updateCategoryRail()
        renderResults(animateList = false)
    }

    override fun onDestroy() {
        searchHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val latestPalette = SnakePreferences(this).themePalette
        val latestSignature = SnakeTheme.resolveThemeSignature(this)
        if (latestPalette != palette || latestSignature != themeSignature) {
            palette = latestPalette
            themeSignature = latestSignature
            uiColors = resolveGuideColors(palette)
            applyHeaderAndChromeColors()
            updateCategoryRail()
            renderResults(animateList = false)
        }
        SnakeTheme.applySystemBars(this, palette, uiColors.screenBg)
    }

    private fun resolveGuideColors(p: ThemePalette): GuideUiColors {
        val tokens = SnakeTheme.resolveTokens(p, this)
        val browserColors = SnakeTheme.resolveBrowserColors(this, p)
        val ripple = ColorUtils.setAlphaComponent(if (tokens.isLight) Color.BLACK else Color.WHITE, 30)
        return GuideUiColors(
            screenBg = browserColors.bg,
            cardSurface = tokens.cardSurface,
            innerContainer = if (tokens.isLight) {
                tokens.pillContainer
            } else {
                ColorUtils.blendARGB(tokens.screenBg, tokens.cardSurface, 0.45f)
            },
            backCircleBg = if (tokens.isLight) tokens.pillContainer else tokens.elevatedSurface,
            searchPillBg = if (tokens.isLight) tokens.cardSurface else tokens.elevatedSurface,
            accentHeader = tokens.categoryHeader,
            activeChipBg = tokens.primaryAccent,
            activeChipText = tokens.onPrimaryAccent,
            titleText = tokens.textPrimary,
            bodyText = tokens.textSecondary,
            rippleColor = ripple
        )
    }

    private fun applyHeaderAndChromeColors() {
        rootLayout.setBackgroundColor(uiColors.screenBg)
        val backCircle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(uiColors.backCircleBg)
        }
        val backMask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        backBtn.background = RippleDrawable(ColorStateList.valueOf(uiColors.rippleColor), backCircle, backMask)
        backBtn.setColorFilter(uiColors.titleText, PorterDuff.Mode.SRC_IN)
        titleView.setTextColor(uiColors.titleText)

        btnOpenSettings.setTextColor(uiColors.activeChipText)
        val settingsShape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(uiColors.activeChipBg)
            cornerRadius = 18f * density
        }
        val settingsMask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.WHITE)
            cornerRadius = 18f * density
        }
        btnOpenSettings.background = RippleDrawable(ColorStateList.valueOf(uiColors.rippleColor), settingsShape, settingsMask)

        searchBox.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(uiColors.searchPillBg)
            cornerRadius = 24f * density
        }
        searchIcon.setColorFilter(uiColors.bodyText, PorterDuff.Mode.SRC_IN)
        searchInput.setTextColor(uiColors.titleText)
        searchInput.setHintTextColor(uiColors.bodyText)
        clearSearchBtn.setColorFilter(uiColors.bodyText, PorterDuff.Mode.SRC_IN)

        introView.setTextColor(uiColors.bodyText)
        countLabel.setTextColor(uiColors.accentHeader)
        emptyTitleView.setTextColor(uiColors.titleText)
        emptyBodyView.setTextColor(uiColors.bodyText)
        emptyResetBtn.setTextColor(uiColors.activeChipText)
        emptyResetBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(uiColors.activeChipBg)
            cornerRadius = 20f * density
        }
    }

    private fun updateCategoryRail() {
        for ((cat, item) in categoryNavItems) {
            val active = cat == selectedCategory
            val chipBg = if (active) uiColors.activeChipBg else uiColors.cardSurface
            // Active chips morph from 18dp pill toward 12dp rounded square (M3 Expressive shape change)
            item.background = SnakeTheme.pillRippleSurface(chipBg, uiColors.rippleColor, if (active) 12f else 18f, density)
            item.setTextColor(if (active) uiColors.activeChipText else uiColors.bodyText)
            item.typeface = Typeface.create("sans-serif-medium", if (active) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun renderResults(animateList: Boolean) {
        val query = searchInput.text?.toString().orEmpty()
        val matches = SiteFixGuide.search(this, query, selectedCategory)

        if (query.isBlank() && selectedCategory == FixCategory.ALL) {
            countLabel.text = getString(R.string.fixes_count_all, matches.size)
        } else {
            countLabel.text = getString(R.string.fixes_count_filtered, matches.size, SiteFixGuide.entries.size)
        }

        resultsList.removeAllViews()
        if (matches.isEmpty()) {
            resultsList.visibility = View.GONE
            emptyStateView.visibility = View.VISIBLE
            emptyStateView.alpha = 0f
            emptyStateView.animate().alpha(1f).setDuration(180L).start()
            return
        }

        resultsList.visibility = View.VISIBLE
        emptyStateView.visibility = View.GONE
        val seam = (2f * density).toInt().coerceAtLeast(1)
        matches.forEachIndexed { index, match ->
            val card = buildExpressiveCard(match, query, index, matches.size)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index > 0) topMargin = seam
            }
            resultsList.addView(card, lp)
        }

        if (animateList) {
            resultsList.animate().cancel()
            resultsList.alpha = 0f
            resultsList.translationY = 10f * density
            resultsList.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            resultsList.alpha = 1f
            resultsList.translationY = 0f
        }
    }

    private fun buildExpressiveCard(
        match: FixGuideMatch,
        query: String,
        index: Int,
        count: Int
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SnakeTheme.asymmetricCardSurface(
                fillColor = uiColors.cardSurface,
                rippleColor = Color.TRANSPARENT,
                index = index,
                count = count,
                density = density
            )
            val padH = (18 * density).toInt()
            val padV = (16 * density).toInt()
            setPadding(padH, padV, padH, padV)
        }

        val headerLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        val title = TextView(this).apply {
            text = SiteFixGuide.highlight(match.title, query, palette.accent)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(uiColors.titleText)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (8 * density).toInt()
            }
        }
        val badge = TextView(this).apply {
            text = getString(match.entry.category.labelRes)
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(uiColors.accentHeader)
            background = SnakeTheme.roundedSurface(uiColors.innerContainer, Color.TRANSPARENT, 10f, density)
            setPadding((8 * density).toInt(), (3 * density).toInt(), (8 * density).toInt(), (3 * density).toInt())
        }
        headerLine.addView(title)
        headerLine.addView(badge)
        card.addView(headerLine)

        card.addView(buildInlineLine(getString(R.string.fixes_label_symptoms), match.symptoms, query, uiColors.bodyText, uiColors.accentHeader))
        card.addView(buildInlineLine(getString(R.string.fixes_label_cause), match.cause, query, uiColors.bodyText, uiColors.accentHeader))

        // Highlighted Resolution Pill Box
        val fixBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SnakeTheme.roundedSurface(uiColors.innerContainer, Color.TRANSPARENT, 14f, density)
            val pH = (14 * density).toInt()
            val pV = (10 * density).toInt()
            setPadding(pH, pV, pH, pV)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (6 * density).toInt()
            }
        }
        fixBox.addView(TextView(this).apply {
            text = getString(R.string.fixes_label_fix)
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(uiColors.accentHeader)
            setPadding(0, 0, 0, (2 * density).toInt())
        })
        fixBox.addView(TextView(this).apply {
            text = SiteFixGuide.highlight(match.fixPath, query, palette.accent)
            textSize = 13.5f
            setTextColor(uiColors.titleText)
        })
        card.addView(fixBox)
        return card
    }

    private fun buildInlineLine(
        label: String,
        body: String,
        query: String,
        bodyColor: Int,
        labelColor: Int
    ): View {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (2 * density).toInt(), 0, (6 * density).toInt())
        }
        val heading = TextView(this).apply {
            text = label
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(labelColor)
        }
        val text = TextView(this).apply {
            text = SiteFixGuide.highlight(body, query, palette.accent)
            textSize = 13.5f
            setTextColor(bodyColor)
            setPadding(0, (1 * density).toInt(), 0, 0)
        }
        block.addView(heading)
        block.addView(text)
        return block
    }

    private fun buildEmptyStateView(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding((16 * density).toInt(), (32 * density).toInt(), (16 * density).toInt(), (24 * density).toInt())
        }
        emptyTitleView = TextView(this).apply {
            text = getString(R.string.fixes_empty_title)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (6 * density).toInt())
        }
        emptyBodyView = TextView(this).apply {
            text = getString(R.string.fixes_empty_body)
            textSize = 13.5f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (16 * density).toInt())
        }
        emptyResetBtn = TextView(this).apply {
            text = getString(R.string.fixes_reset_filters)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            val hPad = (20 * density).toInt()
            val vPad = (10 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            SnakeTheme.attachExpressivePressAnimation(this, scaleDown = 0.94f)
            setOnClickListener {
                selectedCategory = FixCategory.ALL
                updateCategoryRail()
                searchInput.setText("")
                renderResults(animateList = true)
            }
        }
        box.addView(emptyTitleView)
        box.addView(emptyBodyView)
        box.addView(emptyResetBtn)
        return box
    }
}
