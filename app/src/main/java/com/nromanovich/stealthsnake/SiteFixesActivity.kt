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
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Material 3 Expressive Troubleshooting Guide matching [SettingsActivity]:
 * clean search bar, morphing filter chips, and airy expandable fix cards
 * highlighting symptoms and actionable fixes without visual clutter.
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
        val subtleText: Int,
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
    private lateinit var countLabel: TextView
    private lateinit var resultsRecycler: RecyclerView
    private val fixesAdapter = FixesAdapter()
    private lateinit var emptyTitleView: TextView
    private lateinit var emptyBodyView: TextView
    private lateinit var emptyResetBtn: TextView
    private lateinit var emptyStateView: LinearLayout

    private val categoryNavItems = mutableMapOf<FixCategory, TextView>()
    private val expandedIds = mutableSetOf<String>()
    private var selectedCategory: FixCategory = FixCategory.ALL
    private val searchHandler = Handler(Looper.getMainLooper())
    private val searchDebounceRunnable = Runnable { renderResults(animateList = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        SnakeTheme.applyDynamicActivityOverlay(this)
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

        // Top App Bar
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (64 * density).toInt()
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
            textSize = 21f
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

        // Material 3 52dp Search Pill
        searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), 0, (10 * density).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (52 * density).toInt()
            ).apply {
                setMargins((16 * density).toInt(), (2 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
            }
        }
        searchIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings_search)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams((20 * density).toInt(), (20 * density).toInt()).apply {
                marginEnd = (12 * density).toInt()
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
            setImageResource(R.drawable.ic_close)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
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

        // Horizontal M3 Expressive Filter Chips
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
                        resultsRecycler.scrollToPosition(0)
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

        // Quiet topic count caption
        countLabel = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding((20 * density).toInt(), (2 * density).toInt(), (20 * density).toInt(), (10 * density).toInt())
        }
        rootLayout.addView(countLabel)

        val hPad = (16 * density).toInt()
        resultsRecycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SiteFixesActivity)
            adapter = fixesAdapter
            clipToPadding = false
            setPadding(hPad, 0, hPad, (28 * density).toInt())
        }
        emptyStateView = buildEmptyStateView()
        rootLayout.addView(resultsRecycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        rootLayout.addView(emptyStateView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

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
        resultsRecycler.adapter = null
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
        val ripple = ColorUtils.setAlphaComponent(if (tokens.isLight) Color.BLACK else Color.WHITE, 28)
        return GuideUiColors(
            screenBg = browserColors.bg,
            cardSurface = tokens.cardSurface,
            innerContainer = if (tokens.isLight) {
                tokens.pillContainer
            } else {
                ColorUtils.blendARGB(tokens.screenBg, tokens.cardSurface, 0.48f)
            },
            backCircleBg = if (tokens.isLight) tokens.pillContainer else tokens.elevatedSurface,
            searchPillBg = if (tokens.isLight) tokens.cardSurface else tokens.elevatedSurface,
            accentHeader = tokens.categoryHeader,
            activeChipBg = tokens.primaryAccent,
            activeChipText = tokens.onPrimaryAccent,
            titleText = tokens.textPrimary,
            bodyText = tokens.textSecondary,
            subtleText = ColorUtils.setAlphaComponent(tokens.textSecondary, 190),
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

        // Tonal pill button for Settings so it stays calm and balanced in the Top App Bar
        btnOpenSettings.setTextColor(uiColors.titleText)
        btnOpenSettings.background = SnakeTheme.pillRippleSurface(uiColors.backCircleBg, uiColors.rippleColor, 18f, density)

        searchBox.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(uiColors.searchPillBg)
            cornerRadius = 26f * density
        }
        searchIcon.setColorFilter(uiColors.bodyText, PorterDuff.Mode.SRC_IN)
        searchInput.setTextColor(uiColors.titleText)
        searchInput.setHintTextColor(uiColors.bodyText)
        SnakeTheme.styleEditTextAccent(searchInput, palette, this)
        clearSearchBtn.setColorFilter(uiColors.bodyText, PorterDuff.Mode.SRC_IN)

        countLabel.setTextColor(uiColors.subtleText)
        emptyTitleView.setTextColor(uiColors.titleText)
        emptyBodyView.setTextColor(uiColors.bodyText)
        emptyResetBtn.setTextColor(uiColors.activeChipText)
        emptyResetBtn.background = SnakeTheme.pillRippleSurface(uiColors.activeChipBg, uiColors.rippleColor, 20f, density)
    }

    private fun updateCategoryRail() {
        for ((cat, item) in categoryNavItems) {
            val active = cat == selectedCategory
            val chipBg = if (active) uiColors.activeChipBg else uiColors.cardSurface
            // M3 Expressive shape morphing: 18dp pill -> 12dp rounded square when active
            item.background = SnakeTheme.pillRippleSurface(chipBg, uiColors.rippleColor, if (active) 12f else 18f, density)
            item.setTextColor(if (active) uiColors.activeChipText else uiColors.bodyText)
            item.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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

        if (matches.isEmpty()) {
            fixesAdapter.submitResults(emptyList(), query)
            resultsRecycler.visibility = View.GONE
            emptyStateView.visibility = View.VISIBLE
            emptyStateView.alpha = 0f
            emptyStateView.animate().alpha(1f).setDuration(180L).start()
            return
        }

        resultsRecycler.visibility = View.VISIBLE
        emptyStateView.visibility = View.GONE
        fixesAdapter.submitResults(matches, query)
        if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
            resultsRecycler.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST)
            )
            resultsRecycler.layout(0, 0, 1080, resultsRecycler.measuredHeight.coerceAtLeast(1920))
        }

        if (animateList) {
            resultsRecycler.animate().cancel()
            resultsRecycler.alpha = 0f
            resultsRecycler.translationY = 10f * density
            resultsRecycler.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            resultsRecycler.alpha = 1f
            resultsRecycler.translationY = 0f
        }
    }

    private class FixHolder(val slot: LinearLayout) : RecyclerView.ViewHolder(slot)

    private inner class FixesAdapter : RecyclerView.Adapter<FixHolder>() {
        private var items: List<FixGuideMatch> = emptyList()
        private var query: String = ""

        fun submitResults(newItems: List<FixGuideMatch>, searchQuery: String) {
            items = newItems
            query = searchQuery
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FixHolder {
            val slot = LinearLayout(this@SiteFixesActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            return FixHolder(slot)
        }

        override fun onBindViewHolder(holder: FixHolder, position: Int) {
            val gap = (10 * density).toInt()
            val lp = holder.slot.layoutParams as RecyclerView.LayoutParams
            lp.topMargin = if (position > 0) gap else 0
            holder.slot.layoutParams = lp
            holder.slot.removeAllViews()
            holder.slot.addView(buildExpressiveCard(items[position], query))
        }

        override fun onViewRecycled(holder: FixHolder) {
            holder.slot.removeAllViews()
            super.onViewRecycled(holder)
        }
    }

    private fun buildExpressiveCard(
        match: FixGuideMatch,
        query: String
    ): View {
        val queryMatchedCause = query.isNotBlank() &&
            match.cause.contains(query.trim(), ignoreCase = true)
        var isExpanded = expandedIds.contains(match.entry.id) || queryMatchedCause

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SnakeTheme.pillRippleSurface(uiColors.cardSurface, uiColors.rippleColor, 22f, density)
            val padH = (18 * density).toInt()
            val padV = (16 * density).toInt()
            setPadding(padH, padV, padH, padV)
            isClickable = true
            isFocusable = true
        }

        // Top row: Title (+ subtle category overline when viewing All) + Expand chevron
        val headerLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val titleStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (10 * density).toInt()
            }
        }
        if (selectedCategory == FixCategory.ALL) {
            titleStack.addView(TextView(this).apply {
                text = getString(match.entry.category.labelRes)
                textSize = 11.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(uiColors.accentHeader)
                setPadding(0, 0, 0, (2 * density).toInt())
            })
        }
        titleStack.addView(TextView(this).apply {
            text = SiteFixGuide.highlight(match.title, query, uiColors.accentHeader)
            textSize = 16.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(uiColors.titleText)
            setLineSpacing(2f * density, 1f)
        })

        val chevron = ImageView(this).apply {
            setImageResource(R.drawable.ic_expand_more)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(uiColors.subtleText, PorterDuff.Mode.SRC_IN)
            rotation = if (isExpanded) 180f else 0f
            layoutParams = LinearLayout.LayoutParams((22 * density).toInt(), (22 * density).toInt()).apply {
                topMargin = (2 * density).toInt()
            }
        }
        headerLine.addView(titleStack)
        headerLine.addView(chevron)
        card.addView(headerLine)

        // Direct symptoms description without redundant sub-heading
        card.addView(TextView(this).apply {
            text = SiteFixGuide.highlight(match.symptoms, query, uiColors.accentHeader)
            textSize = 13.5f
            setTextColor(uiColors.bodyText)
            setLineSpacing(2.5f * density, 1f)
            setPadding(0, (6 * density).toInt(), 0, 0)
        })

        // Highlighted M3 Tonal Fix Container (primary actionable answer)
        val fixBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SnakeTheme.roundedSurface(uiColors.innerContainer, Color.TRANSPARENT, 16f, density)
            val pH = (14 * density).toInt()
            val pV = (11 * density).toInt()
            setPadding(pH, pV, pH, pV)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (12 * density).toInt()
            }
        }
        fixBox.addView(TextView(this).apply {
            text = getString(R.string.fixes_label_fix)
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(uiColors.accentHeader)
            setPadding(0, 0, 0, (2 * density).toInt())
        })
        fixBox.addView(TextView(this).apply {
            text = SiteFixGuide.highlight(match.fixPath, query, uiColors.accentHeader)
            textSize = 13.5f
            setTextColor(uiColors.titleText)
            setLineSpacing(2.5f * density, 1f)
        })
        card.addView(fixBox)

        // Expandable technical cause ("Why it happens") — hidden by default to keep cards airy
        val causeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (isExpanded) View.VISIBLE else View.GONE
            setPadding((2 * density).toInt(), (10 * density).toInt(), (2 * density).toInt(), 0)
        }
        causeBox.addView(TextView(this).apply {
            text = getString(R.string.fixes_label_cause)
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(uiColors.accentHeader)
            setPadding(0, 0, 0, (2 * density).toInt())
        })
        causeBox.addView(TextView(this).apply {
            text = SiteFixGuide.highlight(match.cause, query, uiColors.accentHeader)
            textSize = 13f
            setTextColor(uiColors.bodyText)
            setLineSpacing(2.5f * density, 1f)
        })
        card.addView(causeBox)

        card.setOnClickListener {
            isExpanded = !isExpanded
            if (isExpanded) {
                expandedIds.add(match.entry.id)
                causeBox.visibility = View.VISIBLE
                causeBox.alpha = 0f
                causeBox.animate().alpha(1f).setDuration(160L).start()
            } else {
                expandedIds.remove(match.entry.id)
                causeBox.visibility = View.GONE
            }
            chevron.animate()
                .rotation(if (isExpanded) 180f else 0f)
                .setDuration(180L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        return card
    }

    private fun buildEmptyStateView(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding((24 * density).toInt(), (40 * density).toInt(), (24 * density).toInt(), (24 * density).toInt())
        }
        emptyTitleView = TextView(this).apply {
            text = getString(R.string.fixes_empty_title)
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (6 * density).toInt())
        }
        emptyBodyView = TextView(this).apply {
            text = getString(R.string.fixes_empty_body)
            textSize = 13.5f
            gravity = Gravity.CENTER
            setLineSpacing(2.5f * density, 1f)
            setPadding(0, 0, 0, (18 * density).toInt())
        }
        emptyResetBtn = TextView(this).apply {
            text = getString(R.string.fixes_reset_filters)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minHeight = (40 * density).toInt()
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
