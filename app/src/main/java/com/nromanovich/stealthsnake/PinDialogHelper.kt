package com.nromanovich.stealthsnake

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

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

        dialog.setOnShowListener {
            SnakeTheme.styleExpressiveDialog(dialog, palette)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
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
                        PinManager.setPin(prefs, pin)
                        PinLockCoordinator.onPinConfigured()
                        // The user just authenticated by choosing the PIN; don't lock them out of
                        // the live session with the PIN they just created.
                        PinLockCoordinator.markUnlocked()
                        Toast.makeText(activity, R.string.pin_saved, Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        onPinSaved()
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

        dialog.setOnShowListener {
            SnakeTheme.styleExpressiveDialog(dialog, palette)
            val okBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            okBtn?.setOnClickListener {
                okBtn.isEnabled = false
                input.isEnabled = false
                val candidate = input.text?.toString().orEmpty().trim()
                PinManager.verifyPinAsync(activity, prefs, candidate) { res ->
                    if (activity.isFinishing || activity.isDestroyed) return@verifyPinAsync
                    when (res) {
                        is PinManager.VerifyResult.Success -> {
                            PinLockCoordinator.markUnlocked()
                            dialog.dismiss()
                            onVerified()
                        }
                        is PinManager.VerifyResult.Failed -> {
                            okBtn.isEnabled = true
                            input.isEnabled = true
                            input.setText("")
                            errorTv.text = if (res.lockoutRemainingMs > 0L) {
                                activity.getString(R.string.pin_locked_out, ((res.lockoutRemainingMs + 999L) / 1000L).toInt())
                            } else if (prefs.pinAutoWipeOnFail) {
                                activity.getString(R.string.pin_wrong, res.remaining)
                            } else {
                                activity.getString(R.string.pin_wrong_simple)
                            }
                            errorTv.visibility = View.VISIBLE
                            shakeView(input, density)
                        }
                        is PinManager.VerifyResult.TriggerAutoWipe -> {
                            dialog.dismiss()
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
                        }
                    }
                }
            }
        }
        dialog.show()
        return dialog
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
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * density).toInt()
            }
        }
    }
}
