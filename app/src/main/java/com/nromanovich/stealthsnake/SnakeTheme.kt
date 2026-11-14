package com.nromanovich.stealthsnake

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat

enum class ThemePalette(
    val key: String,
    val bg: Int,
    val surface: Int,
    val card: Int,
    val toolbar: Int,
    val pill: Int,
    val accent: Int,
    val text: Int,
    val muted: Int,
    val stroke: Int,
    val isLight: Boolean
) {
    OBSIDIAN(
        key = "obsidian",
        bg = Color.parseColor("#131313"),
        surface = Color.parseColor("#1A1A1A"),
        card = Color.parseColor("#212121"),
        toolbar = Color.parseColor("#1F1F1F"),
        pill = Color.parseColor("#2C2C2C"),
        accent = Color.parseColor("#D99B4E"),
        text = Color.parseColor("#EDEDED"),
        muted = Color.parseColor("#8E8E93"),
        stroke = Color.parseColor("#323232"),
        isLight = false
    ),
    AMOLED(
        key = "amoled",
        bg = Color.parseColor("#000000"),
        surface = Color.parseColor("#0B0B0B"),
        card = Color.parseColor("#141414"),
        toolbar = Color.parseColor("#000000"),
        pill = Color.parseColor("#1F1F1F"),
        accent = Color.parseColor("#5CB88A"),
        text = Color.parseColor("#F2F2F2"),
        muted = Color.parseColor("#8A8A8A"),
        stroke = Color.parseColor("#262626"),
        isLight = false
    ),
    MIDNIGHT(
        key = "midnight",
        bg = Color.parseColor("#151518"),
        surface = Color.parseColor("#1C1C20"),
        card = Color.parseColor("#242429"),
        toolbar = Color.parseColor("#1C1C20"),
        pill = Color.parseColor("#2E2E35"),
        accent = Color.parseColor("#D97757"),
        text = Color.parseColor("#ECECEF"),
        muted = Color.parseColor("#92929A"),
        stroke = Color.parseColor("#33333A"),
        isLight = false
    ),
    EMERALD(
        key = "emerald",
        bg = Color.parseColor("#0E1311"),
        surface = Color.parseColor("#141B18"),
        card = Color.parseColor("#1A231F"),
        toolbar = Color.parseColor("#141B18"),
        pill = Color.parseColor("#22302A"),
        accent = Color.parseColor("#52B788"),
        text = Color.parseColor("#E6EFEA"),
        muted = Color.parseColor("#8A9E94"),
        stroke = Color.parseColor("#27362F"),
        isLight = false
    ),
    SLATE_LIGHT(
        key = "slate_light",
        bg = Color.parseColor("#F4F4F5"),
        surface = Color.parseColor("#FFFFFF"),
        card = Color.parseColor("#FFFFFF"),
        toolbar = Color.parseColor("#FFFFFF"),
        pill = Color.parseColor("#E4E4E7"),
        accent = Color.parseColor("#B4691E"),
        text = Color.parseColor("#18181B"),
        muted = Color.parseColor("#71717A"),
        stroke = Color.parseColor("#D4D4D8"),
        isLight = true
    );

    companion object {
        fun fromKey(key: String?): ThemePalette =
            entries.firstOrNull { it.key == key } ?: OBSIDIAN
    }
}

object SnakeTheme {

    data class BrowserColors(
        val bg: Int,
        val surface: Int,
        val card: Int,
        val toolbar: Int,
        val pill: Int,
        val accent: Int,
        val text: Int,
        val muted: Int,
        val stroke: Int,
        val isLight: Boolean
    )

    data class MaterialYouTokens(
        val screenBg: Int,
        val cardSurface: Int,
        val elevatedSurface: Int,
        val pillContainer: Int,
        val primaryAccent: Int,
        val onPrimaryAccent: Int,
        val activeContainer: Int,
        val onActiveContainer: Int,
        val categoryHeader: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val outlineStroke: Int,
        val dividerColor: Int,
        val switchTrackOff: Int,
        val switchThumbOff: Int,
        val isLight: Boolean
    )

    fun isDynamicColorAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !Build.FINGERPRINT.equals("robolectric", ignoreCase = true)

    fun isDynamicThemeActive(context: Context): Boolean =
        isDynamicColorAvailable() && SnakePreferences(context.applicationContext).useDynamicTheme

    fun resolveThemeSignature(context: Context): String {
        val prefs = SnakePreferences(context.applicationContext)
        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return "${isDynamicThemeActive(context)}:${prefs.themePalette.key}:$nightMode"
    }

    fun resolveTokens(palette: ThemePalette, context: Context? = null): MaterialYouTokens {
        if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isDynamicThemeActive(context)) {
            readSystemDynamicTokens(context)?.let { return it }
        }
        return staticTokensFor(palette)
    }

    fun resolveBrowserColors(context: Context, palette: ThemePalette = SnakePreferences(context).themePalette): BrowserColors {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isDynamicThemeActive(context)) {
            val tokens = readSystemDynamicTokens(context)
            if (tokens != null) {
                return if (tokens.isLight) {
                    BrowserColors(
                        bg = tokens.screenBg,
                        surface = tokens.cardSurface,
                        card = tokens.cardSurface,
                        toolbar = tokens.cardSurface,
                        pill = tokens.pillContainer,
                        accent = tokens.primaryAccent,
                        text = tokens.textPrimary,
                        muted = tokens.textSecondary,
                        stroke = tokens.dividerColor,
                        isLight = true
                    )
                } else {
                    BrowserColors(
                        bg = tokens.screenBg,
                        surface = tokens.cardSurface,
                        card = tokens.elevatedSurface,
                        toolbar = ColorUtils.blendARGB(tokens.screenBg, tokens.cardSurface, 0.55f),
                        pill = tokens.pillContainer,
                        accent = tokens.primaryAccent,
                        text = tokens.textPrimary,
                        muted = tokens.textSecondary,
                        stroke = tokens.dividerColor,
                        isLight = false
                    )
                }
            }
        }
        return BrowserColors(
            bg = palette.bg,
            surface = palette.surface,
            card = palette.card,
            toolbar = palette.toolbar,
            pill = palette.pill,
            accent = palette.accent,
            text = palette.text,
            muted = palette.muted,
            stroke = palette.stroke,
            isLight = palette.isLight
        )
    }

    private fun staticTokensFor(palette: ThemePalette): MaterialYouTokens = when (palette) {
        ThemePalette.OBSIDIAN -> MaterialYouTokens(
            // Warm Obsidian charcoal + Golden Orange/Amber Material Design 3 tonal palette
            screenBg = Color.parseColor("#161310"),
            cardSurface = Color.parseColor("#231E19"),
            elevatedSurface = Color.parseColor("#2B241E"),
            pillContainer = Color.parseColor("#352D26"),
            primaryAccent = Color.parseColor("#FFB86B"),
            onPrimaryAccent = Color.parseColor("#482900"),
            activeContainer = Color.parseColor("#5E3C16"),
            onActiveContainer = Color.parseColor("#FFDCBA"),
            categoryHeader = Color.parseColor("#F3C38E"),
            textPrimary = Color.parseColor("#EFE0D4"),
            textSecondary = Color.parseColor("#C7B9AD"),
            outlineStroke = Color.parseColor("#968A80"),
            dividerColor = Color.parseColor("#453A31"),
            switchTrackOff = Color.parseColor("#28221D"),
            switchThumbOff = Color.parseColor("#968A80"),
            isLight = false
        )
        ThemePalette.AMOLED -> MaterialYouTokens(
            // Pitch OLED Black + Luminous Jade-Emerald Material Design 3 tonal palette
            screenBg = Color.parseColor("#000000"),
            cardSurface = Color.parseColor("#131815"),
            elevatedSurface = Color.parseColor("#19201C"),
            pillContainer = Color.parseColor("#212A25"),
            primaryAccent = Color.parseColor("#7EDBAE"),
            onPrimaryAccent = Color.parseColor("#003824"),
            activeContainer = Color.parseColor("#144B34"),
            onActiveContainer = Color.parseColor("#9BF7C9"),
            categoryHeader = Color.parseColor("#96E5BE"),
            textPrimary = Color.parseColor("#E2E8E4"),
            textSecondary = Color.parseColor("#B5C2BA"),
            outlineStroke = Color.parseColor("#7E8B83"),
            dividerColor = Color.parseColor("#2C3831"),
            switchTrackOff = Color.parseColor("#161C19"),
            switchThumbOff = Color.parseColor("#7E8B83"),
            isLight = false
        )
        ThemePalette.MIDNIGHT -> MaterialYouTokens(
            // Deep Twilit Indigo-Slate + Warm Coral/Terracotta Material Design 3 tonal palette
            screenBg = Color.parseColor("#13131A"),
            cardSurface = Color.parseColor("#201F2B"),
            elevatedSurface = Color.parseColor("#272634"),
            pillContainer = Color.parseColor("#323041"),
            primaryAccent = Color.parseColor("#FFB49B"),
            onPrimaryAccent = Color.parseColor("#571E0B"),
            activeContainer = Color.parseColor("#5E2F21"),
            onActiveContainer = Color.parseColor("#FFDBCF"),
            categoryHeader = Color.parseColor("#FFC2AD"),
            textPrimary = Color.parseColor("#E6E2EE"),
            textSecondary = Color.parseColor("#C2BDCE"),
            outlineStroke = Color.parseColor("#8E899B"),
            dividerColor = Color.parseColor("#3B384A"),
            switchTrackOff = Color.parseColor("#262433"),
            switchThumbOff = Color.parseColor("#8E899B"),
            isLight = false
        )
        ThemePalette.EMERALD -> MaterialYouTokens(
            // Deep Pine Forest + Botanical Mint-Emerald Material Design 3 tonal palette
            screenBg = Color.parseColor("#0F1512"),
            cardSurface = Color.parseColor("#1A2420"),
            elevatedSurface = Color.parseColor("#212D28"),
            pillContainer = Color.parseColor("#2A3832"),
            primaryAccent = Color.parseColor("#7DD8AC"),
            onPrimaryAccent = Color.parseColor("#003823"),
            activeContainer = Color.parseColor("#174D36"),
            onActiveContainer = Color.parseColor("#9AF5C7"),
            categoryHeader = Color.parseColor("#96E4BE"),
            textPrimary = Color.parseColor("#DFECE5"),
            textSecondary = Color.parseColor("#B3C5BB"),
            outlineStroke = Color.parseColor("#7F9188"),
            dividerColor = Color.parseColor("#2F3F38"),
            switchTrackOff = Color.parseColor("#1E2924"),
            switchThumbOff = Color.parseColor("#7F9188"),
            isLight = false
        )
        ThemePalette.SLATE_LIGHT -> MaterialYouTokens(
            // Clean Alabaster + Amber-Bronze Material Design 3 Light tonal palette
            screenBg = ThemePalette.SLATE_LIGHT.bg,
            cardSurface = Color.parseColor("#FFFFFF"),
            elevatedSurface = Color.parseColor("#EFECE8"),
            pillContainer = Color.parseColor("#E5E1DC"),
            primaryAccent = ThemePalette.SLATE_LIGHT.accent,
            onPrimaryAccent = Color.parseColor("#FFFFFF"),
            activeContainer = Color.parseColor("#FFDEC2"),
            onActiveContainer = Color.parseColor("#341B00"),
            categoryHeader = ThemePalette.SLATE_LIGHT.accent,
            textPrimary = Color.parseColor("#1C1B1A"),
            textSecondary = Color.parseColor("#57524E"),
            outlineStroke = Color.parseColor("#7D7670"),
            dividerColor = Color.parseColor("#DCD7D1"),
            switchTrackOff = Color.parseColor("#E6E3DF"),
            switchThumbOff = Color.parseColor("#78726C"),
            isLight = true
        )
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun readSystemDynamicTokens(context: Context): MaterialYouTokens? = runCatching {
        fun sys(@ColorRes id: Int): Int = ContextCompat.getColor(context, id)

        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isLight = nightMode == Configuration.UI_MODE_NIGHT_NO

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return@runCatching readApi34SystemDynamicTokens(context, isLight)
        }

        val n1_0 = sys(android.R.color.system_neutral1_0)
        val n1_10 = sys(android.R.color.system_neutral1_10)
        val n1_50 = sys(android.R.color.system_neutral1_50)
        val n1_100 = sys(android.R.color.system_neutral1_100)
        val n1_800 = sys(android.R.color.system_neutral1_800)
        val n1_900 = sys(android.R.color.system_neutral1_900)
        val n1_1000 = sys(android.R.color.system_neutral1_1000)

        val n2_50 = sys(android.R.color.system_neutral2_50)
        val n2_100 = sys(android.R.color.system_neutral2_100)
        val n2_200 = sys(android.R.color.system_neutral2_200)
        val n2_400 = sys(android.R.color.system_neutral2_400)
        val n2_500 = sys(android.R.color.system_neutral2_500)
        val n2_700 = sys(android.R.color.system_neutral2_700)
        val n2_800 = sys(android.R.color.system_neutral2_800)
        val n2_900 = sys(android.R.color.system_neutral2_900)

        val a1_0 = sys(android.R.color.system_accent1_0)
        val a1_100 = sys(android.R.color.system_accent1_100)
        val a1_200 = sys(android.R.color.system_accent1_200)
        val a1_600 = sys(android.R.color.system_accent1_600)
        val a1_800 = sys(android.R.color.system_accent1_800)

        val a2_100 = sys(android.R.color.system_accent2_100)
        val a2_700 = sys(android.R.color.system_accent2_700)
        val a2_900 = sys(android.R.color.system_accent2_900)

        if (isLight) {
            MaterialYouTokens(
                screenBg = ColorUtils.blendARGB(n2_50, n2_100, 0.35f),
                cardSurface = n1_0,
                elevatedSurface = ColorUtils.blendARGB(n2_50, n2_100, 0.55f),
                pillContainer = n2_100,
                primaryAccent = a1_600,
                onPrimaryAccent = a1_0,
                activeContainer = a2_100,
                onActiveContainer = a2_900,
                categoryHeader = a1_600,
                textPrimary = n1_900,
                textSecondary = n2_700,
                outlineStroke = n2_500,
                dividerColor = n2_200,
                switchTrackOff = n2_100,
                switchThumbOff = n2_500,
                isLight = true
            )
        } else {
            MaterialYouTokens(
                screenBg = n2_900,
                cardSurface = ColorUtils.blendARGB(n2_900, n2_800, 0.76f),
                elevatedSurface = n2_800,
                pillContainer = ColorUtils.blendARGB(n2_800, n2_700, 0.30f),
                primaryAccent = a1_200,
                onPrimaryAccent = a1_800,
                activeContainer = a2_700,
                onActiveContainer = a2_100,
                categoryHeader = ColorUtils.blendARGB(a1_200, a1_100, 0.28f),
                textPrimary = n1_100,
                textSecondary = n2_200,
                outlineStroke = n2_400,
                dividerColor = n2_700,
                switchTrackOff = ColorUtils.blendARGB(n2_900, n2_800, 0.45f),
                switchThumbOff = n2_400,
                isLight = false
            )
        }
    }.getOrNull()

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun readApi34SystemDynamicTokens(context: Context, isLight: Boolean): MaterialYouTokens {
        fun sys(@ColorRes id: Int): Int = ContextCompat.getColor(context, id)
        return if (isLight) {
            MaterialYouTokens(
                screenBg = sys(android.R.color.system_surface_container_light),
                cardSurface = sys(android.R.color.system_surface_container_lowest_light),
                elevatedSurface = sys(android.R.color.system_surface_container_high_light),
                pillContainer = sys(android.R.color.system_surface_container_highest_light),
                primaryAccent = sys(android.R.color.system_primary_light),
                onPrimaryAccent = sys(android.R.color.system_on_primary_light),
                activeContainer = sys(android.R.color.system_secondary_container_light),
                onActiveContainer = sys(android.R.color.system_on_secondary_container_light),
                categoryHeader = sys(android.R.color.system_primary_light),
                textPrimary = sys(android.R.color.system_on_surface_light),
                textSecondary = sys(android.R.color.system_on_surface_variant_light),
                outlineStroke = sys(android.R.color.system_outline_light),
                dividerColor = sys(android.R.color.system_outline_variant_light),
                switchTrackOff = sys(android.R.color.system_surface_container_highest_light),
                switchThumbOff = sys(android.R.color.system_outline_light),
                isLight = true
            )
        } else {
            MaterialYouTokens(
                screenBg = ColorUtils.blendARGB(sys(android.R.color.system_surface_dark), sys(android.R.color.system_surface_container_high_dark), 0.62f),
                cardSurface = ColorUtils.blendARGB(sys(android.R.color.system_surface_container_highest_dark), sys(android.R.color.system_primary_dark), 0.038f),
                elevatedSurface = ColorUtils.blendARGB(sys(android.R.color.system_surface_container_highest_dark), sys(android.R.color.system_on_surface_dark), 0.06f),
                pillContainer = ColorUtils.blendARGB(sys(android.R.color.system_surface_container_highest_dark), sys(android.R.color.system_on_surface_dark), 0.08f),
                primaryAccent = sys(android.R.color.system_primary_dark),
                onPrimaryAccent = sys(android.R.color.system_on_primary_dark),
                activeContainer = sys(android.R.color.system_secondary_container_dark),
                onActiveContainer = sys(android.R.color.system_on_secondary_container_dark),
                categoryHeader = sys(android.R.color.system_primary_dark),
                textPrimary = sys(android.R.color.system_on_surface_dark),
                textSecondary = sys(android.R.color.system_on_surface_variant_dark),
                outlineStroke = sys(android.R.color.system_outline_dark),
                dividerColor = sys(android.R.color.system_outline_variant_dark),
                switchTrackOff = sys(android.R.color.system_surface_container_dark),
                switchThumbOff = sys(android.R.color.system_outline_dark),
                isLight = false
            )
        }
    }

    fun applySystemBars(
        activity: Activity,
        palette: ThemePalette,
        statusBarColor: Int = resolveBrowserColors(activity, palette).toolbar,
        blockScreenshots: Boolean = SnakePreferences(activity).let { it.blockScreenshots || it.pinLockEnabled }
    ) {
        val resolved = resolveBrowserColors(activity, palette)
        applySystemBars(activity, resolved, statusBarColor, blockScreenshots)
    }

    fun applySystemBars(
        activity: Activity,
        colors: BrowserColors,
        statusBarColor: Int = colors.toolbar,
        blockScreenshots: Boolean = SnakePreferences(activity).let { it.blockScreenshots || it.pinLockEnabled }
    ) {
        if (blockScreenshots) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        activity.window.statusBarColor = statusBarColor
        activity.window.navigationBarColor = colors.bg
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = colors.isLight
        controller.isAppearanceLightNavigationBars = colors.isLight
    }

    data class ExpressiveDialogColors(
        val dialogBg: Int,
        val surfaceContainer: Int,
        val activePillContainer: Int,
        val activePillContent: Int,
        val activePillSubtext: Int,
        val stroke: Int,
        val divider: Int,
        val title: Int,
        val body: Int,
        val positiveBg: Int,
        val positiveText: Int,
        val secondaryBg: Int,
        val secondaryText: Int
    )

    fun resolveDialogColors(palette: ThemePalette, context: Context? = null): ExpressiveDialogColors {
        val tokens = resolveTokens(palette, context)
        return ExpressiveDialogColors(
            dialogBg = tokens.elevatedSurface,
            surfaceContainer = tokens.cardSurface,
            activePillContainer = tokens.activeContainer,
            activePillContent = tokens.onActiveContainer,
            activePillSubtext = ColorUtils.blendARGB(tokens.onActiveContainer, tokens.textSecondary, 0.25f),
            stroke = tokens.outlineStroke,
            divider = tokens.dividerColor,
            title = tokens.textPrimary,
            body = tokens.textSecondary,
            positiveBg = tokens.primaryAccent,
            positiveText = tokens.onPrimaryAccent,
            secondaryBg = Color.TRANSPARENT,
            secondaryText = tokens.primaryAccent
        )
    }

    /**
     * Attaches Material 3 Expressive tactile scale bounce on touch press/release
     * without consuming any touch event so clicks and ripples continue uninterrupted.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attachExpressivePressAnimation(
        view: View,
        scaleDown: Float = 0.97f,
        onPressChange: ((Boolean) -> Unit)? = null
    ) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(scaleDown)
                        .scaleY(scaleDown)
                        .setDuration(110L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                    onPressChange?.invoke(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(190L)
                        .setInterpolator(OvershootInterpolator(1.4f))
                        .start()
                    onPressChange?.invoke(false)
                }
            }
            false
        }
    }

    /**
     * Applies Material You rounded surface (28dp, no stroke), filled/outlined pill action buttons,
     * typography, and fade/scale entrance animation to [dialog].
     */
    fun styleExpressiveDialog(
        dialog: AlertDialog,
        palette: ThemePalette,
        @Suppress("UNUSED_PARAMETER") destructivePositive: Boolean = false
    ) {
        val applyNow = {
            val density = dialog.context.resources.displayMetrics.density
            val colors = resolveDialogColors(palette, dialog.context)
            val resolvedLight = resolveTokens(palette, dialog.context).isLight
            val rippleAlphaColor = ColorUtils.setAlphaComponent(
                if (resolvedLight) Color.BLACK else Color.WHITE,
                32
            )

            dialog.window?.let { win ->
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(colors.dialogBg)
                    cornerRadius = 28f * density
                }
                val insetH = (16 * density).toInt()
                win.setBackgroundDrawable(InsetDrawable(shape, insetH, 0, insetH, 0))
                win.decorView.apply {
                    scaleX = 0.96f
                    scaleY = 0.96f
                    alpha = 0f
                    animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(160L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            }

            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.apply {
                textSize = 22f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setTextColor(colors.title)
            }
            dialog.findViewById<TextView>(android.R.id.message)?.apply {
                textSize = 14f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setTextColor(colors.body)
                setLineSpacing(3f * density, 1f)
            }

            styleDialogPillButton(
                dialog.getButton(AlertDialog.BUTTON_POSITIVE),
                bgDrawable = pillRippleSurface(colors.positiveBg, rippleAlphaColor, 20f, density),
                fgColor = colors.positiveText,
                density = density
            )
            styleDialogPillButton(
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE),
                bgDrawable = outlinedPillRippleSurface(colors.stroke, rippleAlphaColor, 20f, density, colors.secondaryBg),
                fgColor = colors.secondaryText,
                density = density
            )
            val neutralBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            if (neutralBtn != null) {
                val isIconOnlyNeutral = neutralBtn.text.isNullOrBlank() &&
                    neutralBtn.compoundDrawablesRelative.any { it != null }
                if (!isIconOnlyNeutral) {
                    styleDialogPillButton(
                        neutralBtn,
                        bgDrawable = outlinedPillRippleSurface(colors.stroke, rippleAlphaColor, 20f, density, colors.secondaryBg),
                        fgColor = colors.secondaryText,
                        density = density
                    )
                }
            }
        }

        if (dialog.isShowing) {
            applyNow()
        } else {
            dialog.setOnShowListener { applyNow() }
        }
    }

    private fun styleDialogPillButton(
        btn: Button?,
        bgDrawable: Drawable,
        fgColor: Int,
        density: Float
    ) {
        if (btn == null || btn.visibility != View.VISIBLE) return
        btn.apply {
            background = bgDrawable
            backgroundTintList = null
            stateListAnimator = null
            elevation = 0f
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(fgColor)
            val hPad = (20 * density).toInt()
            val vPad = (8 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            minHeight = (40 * density).toInt()
            minimumHeight = (40 * density).toInt()
            (layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = (8 * density).toInt()
        }
    }

    fun pillRippleSurface(
        fillColor: Int,
        rippleColor: Int,
        cornerRadiusDp: Float,
        density: Float
    ): RippleDrawable {
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = cornerRadiusDp * density
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.WHITE)
            cornerRadius = cornerRadiusDp * density
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    fun outlinedPillRippleSurface(
        strokeColor: Int,
        rippleColor: Int,
        cornerRadiusDp: Float,
        density: Float,
        fillColor: Int = Color.TRANSPARENT
    ): RippleDrawable {
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            setStroke((1f * density).toInt().coerceAtLeast(1), strokeColor)
            cornerRadius = cornerRadiusDp * density
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.WHITE)
            cornerRadius = cornerRadiusDp * density
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    fun asymmetricCardSurface(
        fillColor: Int,
        rippleColor: Int,
        index: Int,
        count: Int,
        density: Float,
        outerDp: Float = 20f,
        innerDp: Float = 4f,
        strokeColor: Int = Color.TRANSPARENT
    ): Drawable {
        val outer = outerDp * density
        val inner = innerDp * density
        val radii = when {
            count <= 1 -> floatArrayOf(outer, outer, outer, outer, outer, outer, outer, outer)
            index == 0 -> floatArrayOf(outer, outer, outer, outer, inner, inner, inner, inner)
            index == count - 1 -> floatArrayOf(inner, inner, inner, inner, outer, outer, outer, outer)
            else -> floatArrayOf(inner, inner, inner, inner, inner, inner, inner, inner)
        }
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadii = radii
            if (strokeColor != Color.TRANSPARENT) {
                setStroke(maxOf(1, density.toInt()), strokeColor)
            }
        }
        if (rippleColor == Color.TRANSPARENT) return content
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.WHITE)
            cornerRadii = radii
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    fun roundedSurface(fillColor: Int, strokeColor: Int, radiusDp: Float, density: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = radiusDp * density
            if (strokeColor != Color.TRANSPARENT) {
                setStroke((1f * density).toInt().coerceAtLeast(1), strokeColor)
            }
        }
}
