package com.nromanovich.stealthsnake

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat

/**
 * Dialogs for setting, changing, and verifying the 4-8 digit browser PIN,
 * styled to match Material You alert dialogs.
 */
object PinDialogHelper {

    fun showSetPinDialog(
        activity: Activity,
        prefs: SnakePreferences,
        includeBody: Boolean = false,
        onPinSaved: () -> Unit
    ): AlertDialog {
        val palette = prefs.themePalette
        val dialogColors = SnakeTheme.resolveDialogColors(palette, activity)
        val density = activity.resources.displayMetrics.density
        val container = createDialogRoot(activity, density)

        container.addView(createTitle(activity, activity.getString(R.string.pin_setup_title), dialogColors))
        if (includeBody) {
            container.addView(createSubtitle(activity, activity.getString(R.string.pin_setup_body), dialogColors, density))
        }

        val input = createPinInput(activity, R.string.pin_hint, dialogColors, density).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = (14 * density).toInt()
        }
        val confirm = createPinInput(activity, R.string.pin_confirm_hint, dialogColors, density).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = (10 * density).toInt()
        }
        val errorTv = createInlineError(activity, density)

        val clearErrorWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                errorTv.visibility = View.GONE
            }
        }
        input.addTextChangedListener(clearErrorWatcher)
        confirm.addTextChangedListener(clearErrorWatcher)

        container.addView(input)
        container.addView(confirm)
        container.addView(errorTv)

        val dialog = AlertDialog.Builder(activity)
            .setView(container)
            .setPositiveButton(R.string.action_ok, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        PinLockCoordinator.registerActiveDialog(activity, dialog)

        var cancelled = false
        dialog.setOnCancelListener { cancelled = true }
        dialog.setOnDismissListener { cancelled = true }

        dialog.setOnShowListener {
            SnakeTheme.styleExpressiveDialog(dialog, palette)
            val okBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val cancelBtn = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            okBtn?.setOnClickListener {
                val pin = input.text?.toString().orEmpty().trim()
                val confirmPin = confirm.text?.toString().orEmpty().trim()
                when {
                    !PinManager.isValidPinFormat(pin) -> {
                        errorTv.text = activity.getString(R.string.pin_setup_invalid)
                        errorTv.visibility = View.VISIBLE
                        shakeView(input, density)
                    }
                    pin != confirmPin -> {
                        errorTv.text = activity.getString(R.string.pin_setup_mismatch)
                        errorTv.visibility = View.VISIBLE
                        shakeView(confirm, density)
                        confirm.setText("")
                    }
                    else -> {
                        okBtn.isEnabled = false
                        cancelBtn?.isEnabled = false
                        input.isEnabled = false
                        confirm.isEnabled = false
                        dialog.setCancelable(false)
                        dialog.setCanceledOnTouchOutside(false)
                        PinLockCoordinator.ensureRegistered(activity)
                        PinManager.derivePinAsync(pin) { result ->
                            if (cancelled || !dialog.isShowing || activity.isFinishing || activity.isDestroyed) {
                                result.getOrNull()?.let { PinManager.discardDerivedPin(prefs, it) }
                                return@derivePinAsync
                            }
                            if (!PinLockCoordinator.isActivityStarted(activity) || PinLockCoordinator.isLocked(prefs)) {
                                // Activity moved to background or locked mid-KDF: clean up uncommitted TEE key and abort.
                                result.getOrNull()?.let { PinManager.discardDerivedPin(prefs, it) }
                                cancelled = true
                                runCatching { dialog.dismiss() }
                                return@derivePinAsync
                            }
                            result.onSuccess { derived ->
                                PinManager.commitDerivedPin(prefs, derived)
                                prefs.pinPolicySelectionPending = true
                                // Atomically mark live foreground session unlocked immediately after commit.
                                PinLockCoordinator.markUnlocked()
                                cancelled = true
                                dialog.dismiss()
                                onPinSaved()
                                if (!activity.isFinishing && !activity.isDestroyed) {
                                    showPinFailureActionDialog(
                                        activity = activity,
                                        prefs = prefs,
                                        isInitialSetup = true,
                                        onDone = {
                                            Toast.makeText(activity, R.string.pin_saved, Toast.LENGTH_SHORT).show()
                                            onPinSaved()
                                        }
                                    )
                                }
                            }.onFailure {
                                okBtn.isEnabled = true
                                cancelBtn?.isEnabled = true
                                input.isEnabled = true
                                confirm.isEnabled = true
                                dialog.setCancelable(true)
                                dialog.setCanceledOnTouchOutside(true)
                                errorTv.text = activity.getString(R.string.pin_setup_error)
                                errorTv.visibility = View.VISIBLE
                            }
                        }
                    }
                }
            }
        }
        dialog.show()
        return dialog
    }

    fun showVerifyCurrentPinDialog(
        activity: Activity,
        prefs: SnakePreferences,
        titleRes: Int,
        onVerified: () -> Unit
    ): AlertDialog {
        val palette = prefs.themePalette
        val dialogColors = SnakeTheme.resolveDialogColors(palette, activity)
        val density = activity.resources.displayMetrics.density
        val container = createDialogRoot(activity, density)

        container.addView(createTitle(activity, activity.getString(titleRes), dialogColors))

        val input = createPinInput(activity, R.string.pin_hint, dialogColors, density).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = (14 * density).toInt()
        }
        val errorTv = createInlineError(activity, density)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                errorTv.visibility = View.GONE
            }
        })

        container.addView(input)
        container.addView(errorTv)

        val dialog = AlertDialog.Builder(activity)
            .setView(container)
            .setPositiveButton(R.string.action_ok, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        PinLockCoordinator.registerActiveDialog(activity, dialog)

        var cancelled = false
        dialog.setOnCancelListener { cancelled = true }
        dialog.setOnDismissListener { cancelled = true }

        dialog.setOnShowListener {
            SnakeTheme.styleExpressiveDialog(dialog, palette)
            val okBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val cancelBtn = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            okBtn?.setOnClickListener {
                okBtn.isEnabled = false
                cancelBtn?.isEnabled = false
                input.isEnabled = false
                dialog.setCancelable(false)
                dialog.setCanceledOnTouchOutside(false)
                PinLockCoordinator.ensureRegistered(activity)
                val candidate = input.text?.toString().orEmpty().trim()
                PinManager.verifyPinAsync(activity, prefs, candidate) { res ->
                    if (res is PinManager.VerifyResult.TriggerHardLock) {
                        cancelled = true
                        runCatching { dialog.dismiss() }
                        if (!activity.isFinishing && !activity.isDestroyed && PinLockCoordinator.isActivityStarted(activity)) {
                            PinLockCoordinator.enforceLockIfNeeded(activity)
                        }
                        return@verifyPinAsync
                    }
                    if (res is PinManager.VerifyResult.TriggerAutoWipe) {
                        cancelled = true
                        runCatching { dialog.dismiss() }
                        if (activity.isFinishing || activity.isDestroyed || !PinLockCoordinator.isActivityStarted(activity)) {
                            PinLockCoordinator.executeDetachedAutoWipe(activity.applicationContext)
                            return@verifyPinAsync
                        }
                        PinLockCoordinator.wipeAllSensitiveDataFromSecondary(activity)
                        Toast.makeText(activity, R.string.pin_wiped, Toast.LENGTH_LONG).show()
                        // Restart the task so live MainActivity tabs/WebViews are destroyed too
                        // (mirrors the overlay auto-wipe path) instead of returning to them unlocked.
                        activity.startActivity(
                            Intent(activity, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            }
                        )
                        activity.finish()
                        return@verifyPinAsync
                    }
                    if (activity.isFinishing || activity.isDestroyed) return@verifyPinAsync
                    when (res) {
                        is PinManager.VerifyResult.Success -> {
                            if (cancelled || !dialog.isShowing || !PinLockCoordinator.isActivityStarted(activity) || PinLockCoordinator.isLocked(prefs)) {
                                cancelled = true
                                runCatching { dialog.dismiss() }
                                return@verifyPinAsync
                            }
                            PinLockCoordinator.markUnlocked()
                            cancelled = true
                            dialog.dismiss()
                            onVerified()
                        }
                        is PinManager.VerifyResult.Failed -> {
                            if (cancelled || !dialog.isShowing) return@verifyPinAsync
                            okBtn.isEnabled = true
                            cancelBtn?.isEnabled = true
                            input.isEnabled = true
                            dialog.setCancelable(true)
                            dialog.setCanceledOnTouchOutside(true)
                            input.setText("")
                            errorTv.text = when {
                                res.keyPermanentlyLost -> activity.getString(R.string.pin_keystore_lost)
                                res.keystoreError -> activity.getString(R.string.pin_keystore_unavailable)
                                res.lockoutRemainingMs > 0L -> activity.getString(R.string.pin_locked_out, ((res.lockoutRemainingMs + 999L) / 1000L).toInt())
                                prefs.pinAutoWipeOnFail && prefs.pinFailureAction == PinFailureAction.LOCK_FOREVER ->
                                    activity.getString(R.string.pin_wrong_before_lock, res.remaining)
                                prefs.pinAutoWipeOnFail -> activity.getString(R.string.pin_wrong, res.remaining)
                                else -> activity.getString(R.string.pin_wrong_simple)
                            }
                            errorTv.visibility = View.VISIBLE
                            shakeView(input, density)
                        }
                        is PinManager.VerifyResult.TriggerAutoWipe -> Unit
                        is PinManager.VerifyResult.TriggerHardLock -> Unit
                    }
                }
            }
        }
        dialog.show()
        return dialog
    }

    /**
     * Material 3 Expressive single-choice dialog letting the user pick what happens
     * after 5 consecutive incorrect PIN attempts: wiping the session or locking permanently.
     */
    fun showPinFailureActionDialog(
        activity: Activity,
        prefs: SnakePreferences,
        isInitialSetup: Boolean = false,
        onDone: () -> Unit
    ): AlertDialog {
        val palette = prefs.themePalette
        val tokens = SnakeTheme.resolveTokens(palette, activity)
        val dialogColors = SnakeTheme.resolveDialogColors(palette, activity)
        val density = activity.resources.displayMetrics.density
        val rippleColor = ColorUtils.setAlphaComponent(if (tokens.isLight) Color.BLACK else Color.WHITE, 28)

        val container = createDialogRoot(activity, density)
        container.addView(createTitle(activity, activity.getString(R.string.pin_failure_policy_title), dialogColors))

        var selectedAction = prefs.pinFailureAction

        val optionData = listOf(
            Triple(PinFailureAction.WIPE_SESSION, R.string.pin_failure_wipe_title, R.string.pin_failure_wipe_desc),
            Triple(PinFailureAction.LOCK_FOREVER, R.string.pin_failure_lock_title, R.string.pin_failure_lock_desc)
        )

        val cardViews = mutableMapOf<PinFailureAction, Triple<LinearLayout, View, View>>()

        fun renderSelection() {
            for ((action, views) in cardViews) {
                val (row, radioOuter, radioInner) = views
                val active = action == selectedAction
                row.isSelected = active
                ViewCompat.setStateDescription(row, if (active) "Selected" else "Not selected")
                radioOuter.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke((2f * density).toInt().coerceAtLeast(1), if (active) tokens.primaryAccent else dialogColors.body)
                }
                radioInner.visibility = if (active) View.VISIBLE else View.INVISIBLE
                radioInner.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(tokens.primaryAccent)
                }
            }
        }

        optionData.forEachIndexed { idx, (action, titleRes, descRes) ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                val pH = (4 * density).toInt()
                val pV = (10 * density).toInt()
                setPadding(pH, pV, pH, pV)
                val mask = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.WHITE)
                    cornerRadius = 14f * density
                }
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(rippleColor),
                    null,
                    mask
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = if (idx == 0) (14 * density).toInt() else (6 * density).toInt()
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedAction = action
                    renderSelection()
                }
            }

            val radioFrame = android.widget.FrameLayout(activity).apply {
                val sz = (20 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    topMargin = (2 * density).toInt()
                    marginEnd = (16 * density).toInt()
                }
            }
            val radioOuter = View(activity).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            val radioInner = View(activity).apply {
                val innerSz = (10 * density).toInt()
                layoutParams = android.widget.FrameLayout.LayoutParams(innerSz, innerSz, Gravity.CENTER)
            }
            radioFrame.addView(radioOuter)
            radioFrame.addView(radioInner)

            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(activity).apply {
                text = activity.getString(titleRes)
                textSize = 16f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setTextColor(dialogColors.title)
            })
            textCol.addView(TextView(activity).apply {
                text = activity.getString(descRes)
                textSize = 13.5f
                setTextColor(dialogColors.body)
                setLineSpacing(2f * density, 1f)
                setPadding(0, (2 * density).toInt(), 0, 0)
            })

            row.addView(radioFrame)
            row.addView(textCol)
            container.addView(row)
            cardViews[action] = Triple(row, radioOuter, radioInner)
        }
        renderSelection()

        val scrollWrapper = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(container)
        }
        val builder = AlertDialog.Builder(activity)
            .setView(scrollWrapper)
            .setCancelable(!isInitialSetup)
            .setPositiveButton(R.string.pin_failure_confirm) { _, _ ->
                prefs.commitPinFailurePolicy(selectedAction, autoWipeOnFail = true)
                onDone()
            }
        if (!isInitialSetup) {
            builder.setNegativeButton(R.string.action_cancel, null)
        }
        val policyDialog = builder.create().apply {
            setCanceledOnTouchOutside(!isInitialSetup)
        }
        PinLockCoordinator.registerActiveDialog(activity, policyDialog)
        policyDialog.setOnShowListener {
            SnakeTheme.styleExpressiveDialog(policyDialog, palette)
        }
        policyDialog.show()
        return policyDialog
    }

    private fun createDialogRoot(activity: Activity, density: Float): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * density).toInt()
            setPadding(pad, pad, pad, (8 * density).toInt())
        }

    private fun shakeView(view: View, density: Float) {
        val d = 8f * density
        ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, -d, d, -d * 0.6f, d * 0.6f, 0f).apply {
            duration = 260L
            start()
        }
    }

    private fun createTitle(
        activity: Activity,
        title: String,
        colors: SnakeTheme.ExpressiveDialogColors
    ): TextView = TextView(activity).apply {
        text = title
        textSize = 22f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setTextColor(colors.title)
    }

    private fun createSubtitle(
        activity: Activity,
        subtitle: String,
        colors: SnakeTheme.ExpressiveDialogColors,
        density: Float
    ): TextView = TextView(activity).apply {
        text = subtitle
        textSize = 14f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setTextColor(colors.body)
        setLineSpacing(3f * density, 1f)
        setPadding(0, (10 * density).toInt(), 0, (2 * density).toInt())
    }

    private fun createInlineError(activity: Activity, density: Float): TextView =
        TextView(activity).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#EF4444"))
            visibility = View.GONE
            setPadding((4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt(), 0)
        }

    private fun createPinInput(
        activity: Activity,
        hintRes: Int,
        colors: SnakeTheme.ExpressiveDialogColors,
        density: Float
    ): EditText {
        return EditText(activity).apply {
            hint = activity.getString(hintRes)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            filters = arrayOf(InputFilter.LengthFilter(PinManager.MAX_PIN_LENGTH))
            setSingleLine(true)
            textSize = 15.5f
            setTextColor(colors.title)
            setHintTextColor(colors.body)
            background = SnakeTheme.roundedSurface(colors.surfaceContainer, colors.stroke, 12f, density)
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            SnakeTheme.styleEditTextAccent(this, SnakePreferences(activity).themePalette, activity)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * density).toInt()
            }
        }
    }
}
