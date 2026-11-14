package com.nromanovich.stealthsnake

import android.animation.LayoutTransition
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * UI builders for [MainActivity]'s Android 16 Expressive overflow menu popup,
 * multi-step onboarding dialog, and grouped tabs sheet.
 */
object BrowserUiHelper {

    data class MenuEntry(
        val iconRes: Int,
        val title: String,
        val enabled: Boolean,
        val action: () -> Unit
    )

    fun showOverflowMenu(
        activity: Activity,
        anchor: View,
        palette: ThemePalette,
        entries: List<MenuEntry>
    ): PopupWindow {
        val density = activity.resources.displayMetrics.density
        val colors = SnakeTheme.resolveDialogColors(palette, activity)
        val isLight = SnakeTheme.resolveTokens(palette, activity).isLight
        val rippleColor = ColorUtils.setAlphaComponent(if (isLight) Color.BLACK else Color.WHITE, 30)
        val menuWidth = (244 * density).toInt()

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = SnakeTheme.roundedSurface(colors.dialogBg, Color.TRANSPARENT, 16f, density)
            val padV = (8 * density).toInt()
            setPadding((6 * density).toInt(), padV, (6 * density).toInt(), padV)
            elevation = 8f * density
        }

        val popup = PopupWindow(
            card,
            menuWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 8f * density
        }

        for (entry in entries) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (46 * density).toInt()
                val hPad = (14 * density).toInt()
                val vPad = (11 * density).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                background = SnakeTheme.pillRippleSurface(Color.TRANSPARENT, rippleColor, 12f, density)
                isEnabled = entry.enabled
                alpha = if (entry.enabled) 1.0f else 0.4f
                setOnClickListener {
                    popup.dismiss()
                    entry.action()
                }
            }
            val icon = ImageView(activity).apply {
                setImageResource(entry.iconRes)
                setColorFilter(colors.title)
                layoutParams = LinearLayout.LayoutParams((20 * density).toInt(), (20 * density).toInt()).apply {
                    marginEnd = (14 * density).toInt()
                }
            }
            val label = TextView(activity).apply {
                text = entry.title
                textSize = 14.5f
                setTextColor(colors.title)
            }
            row.addView(icon)
            row.addView(label)
            card.addView(row)
        }

        popup.showAsDropDown(anchor, (-188 * density).toInt(), (6 * density).toInt())
        card.pivotX = menuWidth.toFloat()
        card.pivotY = 0f
        card.scaleX = 0.94f
        card.scaleY = 0.94f
        card.alpha = 0f
        card.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(150L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        return popup
    }

    fun showOnboardingDialog(
        activity: Activity,
        prefs: SnakePreferences,
        onDismissed: (AlertDialog) -> Unit
    ): AlertDialog {
        val palette = prefs.themePalette
        val colors = SnakeTheme.resolveDialogColors(palette, activity)
        val density = activity.resources.displayMetrics.density
        val isLight = SnakeTheme.resolveTokens(palette, activity).isLight
        val rippleColor = ColorUtils.setAlphaComponent(if (isLight) Color.BLACK else Color.WHITE, 30)

        val presetLabel = when (prefs.preset) {
            ProtectionPreset.MAX_STEALTH -> activity.getString(R.string.preset_max_stealth)
            ProtectionPreset.COMPATIBILITY -> activity.getString(R.string.preset_compatibility)
            ProtectionPreset.CUSTOM -> activity.getString(R.string.preset_custom)
        }
        val accountsLabel = if (prefs.autoWipeOnExit) {
            activity.getString(R.string.onboarding_accounts_ephemeral)
        } else {
            activity.getString(R.string.onboarding_accounts_saved)
        }
        val captureLabel = when (prefs.captureMode) {
            CaptureMode.ASK -> activity.getString(R.string.capture_mode_ask)
            CaptureMode.PRIVATE -> activity.getString(R.string.capture_mode_private)
            CaptureMode.GALLERY -> activity.getString(R.string.capture_mode_gallery)
        }

        val steps = listOf(
            activity.getString(R.string.onboarding_title_1) to activity.getString(R.string.onboarding_body_1),
            activity.getString(R.string.onboarding_title_2) to activity.getString(
                R.string.onboarding_body_2,
                presetLabel,
                accountsLabel,
                captureLabel,
                prefs.homeUrl
            ),
            activity.getString(R.string.onboarding_title_3) to activity.getString(R.string.onboarding_body_3),
            activity.getString(R.string.onboarding_title_pin) to activity.getString(R.string.onboarding_body_pin)
        )

        var stepIdx = 0
        val pad = (24 * density).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, (20 * density).toInt())
        }
        val titleTv = TextView(activity).apply {
            textSize = 22f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(colors.title)
        }
        val bodyTv = TextView(activity).apply {
            textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setLineSpacing(3f * density, 1f)
            setTextColor(colors.body)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (16 * density).toInt()
                bottomMargin = (22 * density).toInt()
            }
        }

        val makePillBtn: (Boolean, Int) -> Button = { filled, fg ->
            Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
                background = if (filled) {
                    SnakeTheme.pillRippleSurface(colors.positiveBg, rippleColor, 20f, density)
                } else {
                    SnakeTheme.outlinedPillRippleSurface(colors.stroke, rippleColor, 20f, density, colors.secondaryBg)
                }
                isAllCaps = false
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(fg)
                val hPad = (20 * density).toInt()
                setPadding(hPad, 0, hPad, 0)
                minHeight = (40 * density).toInt()
                minimumHeight = (40 * density).toInt()
            }
        }

        val actionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val skipBtn = makePillBtn(false, colors.secondaryText).apply {
            text = activity.getString(R.string.onboarding_skip)
        }
        val pinCtaBtn = makePillBtn(false, colors.secondaryText).apply {
            text = activity.getString(R.string.onboarding_pin_cta)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (40 * density).toInt()
            ).apply {
                marginStart = (8 * density).toInt()
            }
        }
        val nextBtn = makePillBtn(true, colors.positiveText).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (40 * density).toInt()
            ).apply {
                marginStart = (8 * density).toInt()
            }
        }
        actionsRow.addView(skipBtn)
        actionsRow.addView(pinCtaBtn)
        actionsRow.addView(nextBtn)
        box.addView(titleTv)
        box.addView(bodyTv)
        box.addView(actionsRow)

        val dialog = AlertDialog.Builder(activity)
            .setView(box)
            .setCancelable(false)
            .create()
        dialog.setOnDismissListener { onDismissed(dialog) }

        fun renderStep(animateStep: Boolean) {
            titleTv.text = steps[stepIdx].first
            bodyTv.text = steps[stepIdx].second
            skipBtn.visibility = if (stepIdx == steps.lastIndex) View.GONE else View.VISIBLE
            pinCtaBtn.visibility = if (stepIdx == steps.lastIndex && !prefs.pinLockEnabled) View.VISIBLE else View.GONE
            nextBtn.text = if (stepIdx == steps.lastIndex) {
                activity.getString(R.string.onboarding_done)
            } else {
                activity.getString(R.string.onboarding_next)
            }
            if (animateStep) {
                bodyTv.alpha = 0f
                bodyTv.animate()
                    .alpha(1f)
                    .setDuration(150L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }

        pinCtaBtn.setOnClickListener {
            PinDialogHelper.showSetPinDialog(activity, prefs, includeBody = false) {
                pinCtaBtn.visibility = View.GONE
            }
        }
        skipBtn.setOnClickListener {
            prefs.onboardingCompleted = true
            dialog.dismiss()
        }
        nextBtn.setOnClickListener {
            if (stepIdx < steps.lastIndex) {
                stepIdx++
                renderStep(animateStep = true)
            } else {
                prefs.onboardingCompleted = true
                dialog.dismiss()
            }
        }

        renderStep(animateStep = false)
        dialog.show()
        SnakeTheme.styleExpressiveDialog(dialog, palette)
        return dialog
    }

    fun showSessionsSheet(
        activity: Activity,
        palette: ThemePalette,
        sessionsProvider: () -> List<BrowserSession>,
        activeSessionIdProvider: () -> Long,
        onSelectSession: (BrowserSession) -> Unit,
        onCloseSession: (BrowserSession) -> Unit,
        onNewSession: () -> Unit,
        onWipeAll: () -> Unit
    ): BottomSheetDialog {
        val density = activity.resources.displayMetrics.density
        val colors = SnakeTheme.resolveDialogColors(palette, activity)
        val isLight = SnakeTheme.resolveTokens(palette, activity).isLight
        val rippleColor = ColorUtils.setAlphaComponent(if (isLight) Color.BLACK else Color.WHITE, 30)
        val topCorner = 28f * density

        val sheet = BottomSheetDialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(colors.dialogBg)
                cornerRadii = floatArrayOf(topCorner, topCorner, topCorner, topCorner, 0f, 0f, 0f, 0f)
            }
            setPadding((16 * density).toInt(), (22 * density).toInt(), (16 * density).toInt(), (20 * density).toInt())
        }

        val titleTv = TextView(activity).apply {
            textSize = 22f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(colors.title)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(titleTv)

        val dividerBar = View(activity).apply {
            setBackgroundColor(colors.divider)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (2 * density).toInt().coerceAtLeast(1)
            ).apply {
                setMargins((24 * density).toInt(), (14 * density).toInt(), (24 * density).toInt(), (10 * density).toInt())
            }
        }
        root.addView(dividerBar)

        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(listContainer)
        }
        root.addView(scroll)

        fun renderList() {
            val sessions = sessionsProvider()
            val activeId = activeSessionIdProvider()
            titleTv.text = activity.getString(R.string.sessions_sheet_title, sessions.size)
            listContainer.removeAllViews()
            val rowGap = (4 * density).toInt()

            sessions.forEachIndexed { index, session ->
                val isActive = session.id == activeId
                val rowBg = if (isActive) {
                    SnakeTheme.pillRippleSurface(colors.activePillContainer, rippleColor, 28f, density)
                } else {
                    SnakeTheme.pillRippleSurface(Color.TRANSPARENT, rippleColor, 28f, density)
                }
                val contentTint = if (isActive) colors.activePillContent else colors.title
                val subTint = if (isActive) colors.activePillSubtext else colors.body
                val leadingTint = if (isActive) colors.activePillContent else colors.body

                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = (64 * density).toInt()
                    background = rowBg
                    setPadding((16 * density).toInt(), (12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        if (index > 0) topMargin = rowGap
                    }
                    SnakeTheme.attachExpressivePressAnimation(this, scaleDown = 0.96f)
                    setOnClickListener {
                        postDelayed({
                            // A double-tap queues two runnables; the second must not re-select, and the
                            // Activity may have gone away during the 90ms animation delay.
                            if (activity.isFinishing || activity.isDestroyed || !sheet.isShowing) return@postDelayed
                            sheet.dismiss()
                            onSelectSession(session)
                        }, 90L)
                    }
                }

                val leadingIcon = ImageView(activity).apply {
                    setImageResource(R.drawable.ic_chrome_home)
                    setColorFilter(leadingTint)
                    layoutParams = LinearLayout.LayoutParams((22 * density).toInt(), (22 * density).toInt()).apply {
                        marginEnd = (16 * density).toInt()
                    }
                }

                val textCol = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = (6 * density).toInt()
                    }
                }

                val rawUrl = session.webView.url?.takeIf { it.isNotBlank() } ?: session.currentUrl
                val host = runCatching { Uri.parse(rawUrl).host }.getOrNull().orEmpty()
                val displayTitle = when {
                    session.currentTitle.isNotBlank() -> session.currentTitle
                    host.isNotBlank() -> host
                    rawUrl == MainActivity.DEFAULT_START_URL -> activity.getString(R.string.sessions_blank_title)
                    else -> rawUrl
                }
                val sessionTitleTv = TextView(activity).apply {
                    text = displayTitle
                    textSize = 16f
                    typeface = Typeface.create(
                        if (isActive) "sans-serif-medium" else "sans-serif",
                        Typeface.NORMAL
                    )
                    setTextColor(contentTint)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }

                val urlSubTv = TextView(activity).apply {
                    val urlDisplay = if (rawUrl == MainActivity.DEFAULT_START_URL) {
                        activity.getString(R.string.url_hint)
                    } else {
                        rawUrl
                    }
                    text = if (session.blockedRequestCount > 0) {
                        "$urlDisplay · ${activity.getString(R.string.sessions_blocked_count, session.blockedRequestCount)}"
                    } else {
                        urlDisplay
                    }
                    textSize = 13.5f
                    setTextColor(subTint)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, (2 * density).toInt(), 0, 0)
                }

                textCol.addView(sessionTitleTv)
                textCol.addView(urlSubTv)

                val closeMask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
                val closeBtn = TextView(activity).apply {
                    text = "✕"
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTextColor(leadingTint)
                    contentDescription = activity.getString(R.string.sessions_close_desc)
                    background = RippleDrawable(ColorStateList.valueOf(rippleColor), null, closeMask)
                    layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt())
                    setOnClickListener {
                        onCloseSession(session)
                        renderList()
                    }
                }

                row.addView(leadingIcon)
                row.addView(textCol)
                row.addView(closeBtn)
                listContainer.addView(row)
            }
        }

        val bottomBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt(), 0)
        }
        val wipeAllBtn = TextView(activity).apply {
            text = activity.getString(R.string.sessions_wipe_all)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(colors.title)
            gravity = Gravity.CENTER
            minHeight = (40 * density).toInt()
            background = SnakeTheme.outlinedPillRippleSurface(colors.stroke, rippleColor, 20f, density, colors.secondaryBg)
            val hPad = (20 * density).toInt()
            setPadding(hPad, 0, hPad, 0)
            setOnClickListener {
                sheet.dismiss()
                onWipeAll()
            }
        }
        val newBtn = TextView(activity).apply {
            text = "+ " + activity.getString(R.string.sessions_new)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(colors.positiveText)
            gravity = Gravity.CENTER
            minHeight = (40 * density).toInt()
            background = SnakeTheme.pillRippleSurface(colors.positiveBg, rippleColor, 20f, density)
            val hPad = (20 * density).toInt()
            setPadding(hPad, 0, hPad, 0)
            setOnClickListener {
                sheet.dismiss()
                onNewSession()
            }
        }
        bottomBar.addView(wipeAllBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (40 * density).toInt()))
        bottomBar.addView(View(activity), LinearLayout.LayoutParams(0, 0, 1f))
        bottomBar.addView(newBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (40 * density).toInt()))
        root.addView(bottomBar)

        renderList()
        sheet.setContentView(root)
        sheet.setOnShowListener {
            sheet.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }
        sheet.show()
        return sheet
    }
}
