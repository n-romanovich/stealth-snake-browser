package com.nromanovich.stealthsnake

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.net.http.SslCertificate
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Material You TLS certificate and page-security inspector
 * anchored to the toolbar's left security icon (`siteSecurityIcon`).
 */
object SiteInfoSheet {

    data class PageSecurityState(
        val url: String,
        val vpnKillSwitchEnabled: Boolean,
        val vpnActive: Boolean,
        val httpBypassed: Boolean,
        val sslBypassed: Boolean,
        val blockedHosts: Map<String, Int>,
        val certificate: SslCertificate? = null,
        val scriptProtectionActive: Boolean = true
    )

    data class CertificateDetails(
        val subjectCn: String,
        val subjectOrg: String,
        val issuerCn: String,
        val issuerOrg: String,
        val validFrom: Date?,
        val validTo: Date?,
        val daysRemaining: Long?,
        val signatureAlgorithm: String,
        val publicKeyInfo: String,
        val serialHex: String,
        val sans: List<String>,
        val sha256Fingerprint: String
    )

    fun show(
        activity: Activity,
        prefs: SnakePreferences,
        state: PageSecurityState,
        onProfileOrBlocklistChanged: (reloadPage: Boolean) -> Unit
    ): AlertDialog {
        val palette = prefs.themePalette
        val dc = SnakeTheme.resolveDialogColors(palette, activity)
        val isLight = SnakeTheme.resolveTokens(palette, activity).isLight
        val rippleColor = ColorUtils.setAlphaComponent(if (isLight) Color.BLACK else Color.WHITE, 30)
        val density = activity.resources.displayMetrics.density
        val pad = (22 * density).toInt()

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (22 * density).toInt(), pad, (18 * density).toInt())
        }

        val host = runCatching { Uri.parse(state.url).host }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: state.url.ifBlank { MainActivity.DEFAULT_START_URL }

        // Centered Material You header matching the system panel aesthetic
        content.addView(TextView(activity).apply {
            text = host
            textSize = 22f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(dc.title)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })

        val (statusText, statusColor) = resolveConnectionBadge(activity, state, dc)
        content.addView(TextView(activity).apply {
            text = statusText
            textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(statusColor)
            gravity = Gravity.CENTER
            setPadding(0, (4 * density).toInt(), 0, 0)
        })

        if (!state.scriptProtectionActive) {
            content.addView(TextView(activity).apply {
                text = activity.getString(R.string.site_info_snake_script_unsupported)
                textSize = 12.5f
                setTextColor(Color.parseColor("#F59E0B"))
                gravity = Gravity.CENTER
                setPadding(0, (4 * density).toInt(), 0, 0)
            })
        }

        content.addView(View(activity).apply {
            setBackgroundColor(dc.divider)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (2 * density).toInt().coerceAtLeast(1)
            ).apply {
                topMargin = (14 * density).toInt()
                bottomMargin = (14 * density).toInt()
            }
        })

        // Primary Certificate Summary inside a single flat tonal pill container (no border)
        val summaryPill = createCertificateSummaryPill(activity, state, dc, density)
        content.addView(summaryPill)

        // Flat cryptography rows directly on the sheet surface
        val details = extractCertificateDetails(state.certificate)
        if (details != null) {
            buildCryptographySection(activity, details, dc, rippleColor, density)?.let { cryptoSec ->
                content.addView(cryptoSec)
            }
        }

        lateinit var dialog: AlertDialog
        val blockedSec = buildBlockedSection(
            activity = activity,
            prefs = prefs,
            state = state,
            colors = dc,
            rippleColor = rippleColor,
            density = density,
            onChanged = {
                dialog.dismiss()
                onProfileOrBlocklistChanged(false)
            }
        )
        content.addView(blockedSec)

        // Bottom right-aligned filled primary pill Close button
        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, (18 * density).toInt(), 0, 0)
        }
        footer.addView(TextView(activity).apply {
            text = activity.getString(android.R.string.ok)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(dc.positiveText)
            gravity = Gravity.CENTER
            minimumHeight = (40 * density).toInt()
            minWidth = (76 * density).toInt()
            background = SnakeTheme.pillRippleSurface(dc.positiveBg, rippleColor, 20f, density)
            setPadding((22 * density).toInt(), 0, (22 * density).toInt(), 0)
            setOnClickListener { dialog.dismiss() }
        })
        content.addView(footer)

        val scroll = ScrollView(activity).apply { addView(content) }
        dialog = AlertDialog.Builder(activity)
            .setView(scroll)
            .create()
        dialog.setOnShowListener {
            dialog.window?.let { win ->
                val outerShape = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(dc.dialogBg)
                    cornerRadius = 28f * density
                }
                val insetH = (16 * density).toInt()
                win.setBackgroundDrawable(InsetDrawable(outerShape, insetH, 0, insetH, 0))
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
        }
        dialog.show()
        return dialog
    }

    private fun createCertificateSummaryPill(
        activity: Activity,
        state: PageSecurityState,
        colors: SnakeTheme.ExpressiveDialogColors,
        density: Float
    ): LinearLayout {
        val pill = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = SnakeTheme.roundedSurface(colors.activePillContainer, Color.TRANSPARENT, 24f, density)
            val pH = (18 * density).toInt()
            val pV = (16 * density).toInt()
            setPadding(pH, pV, pH, pV)
        }
        pill.addView(TextView(activity).apply {
            text = activity.getString(R.string.site_info_cert_section)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(colors.activePillContent)
            setPadding(0, 0, 0, (8 * density).toInt())
        })

        val details = extractCertificateDetails(state.certificate)
        if (details == null) {
            val isHttps = state.url.startsWith("https://", ignoreCase = true)
            pill.addView(TextView(activity).apply {
                text = activity.getString(
                    if (isHttps) R.string.site_info_cert_unavailable else R.string.site_info_cert_none
                )
                textSize = 13.5f
                setTextColor(colors.activePillSubtext)
            })
            return pill
        }

        val subjectDisplay = listOfNotNull(
            details.subjectCn.takeIf { it.isNotBlank() },
            details.subjectOrg.takeIf { it.isNotBlank() }
        ).joinToString(" · ").ifBlank { "—" }

        val issuerDisplay = listOfNotNull(
            details.issuerCn.takeIf { it.isNotBlank() },
            details.issuerOrg.takeIf { it.isNotBlank() }
        ).joinToString(" · ").ifBlank { "—" }

        addKeyValueRow(
            activity = activity,
            parent = pill,
            label = activity.getString(R.string.site_info_cert_issued_to),
            value = subjectDisplay,
            labelColor = colors.activePillSubtext,
            valueColor = colors.activePillContent,
            density = density
        )
        addKeyValueRow(
            activity = activity,
            parent = pill,
            label = activity.getString(R.string.site_info_cert_issued_by),
            value = issuerDisplay,
            labelColor = colors.activePillSubtext,
            valueColor = colors.activePillContent,
            density = density
        )

        val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val fromStr = details.validFrom?.let { df.format(it) } ?: "?"
        val toStr = details.validTo?.let { df.format(it) } ?: "?"
        val days = details.daysRemaining
        val validitySuffix = when {
            days == null -> ""
            days < 0 -> " · ${activity.getString(R.string.site_info_cert_expired)}"
            else -> " · ${activity.getString(R.string.site_info_cert_days_left, days.toInt())}"
        }
        val validityColor = when {
            days == null -> colors.activePillContent
            days < 0 -> Color.parseColor("#FFB4AB")
            days < 14 -> Color.parseColor("#F59E0B")
            else -> colors.activePillContent
        }
        addKeyValueRow(
            activity = activity,
            parent = pill,
            label = activity.getString(R.string.site_info_cert_validity),
            value = "$fromStr — $toStr$validitySuffix",
            labelColor = colors.activePillSubtext,
            valueColor = validityColor,
            density = density
        )
        return pill
    }

    private fun buildCryptographySection(
        activity: Activity,
        details: CertificateDetails,
        colors: SnakeTheme.ExpressiveDialogColors,
        rippleColor: Int,
        density: Float
    ): LinearLayout? {
        val algoText = listOfNotNull(
            details.publicKeyInfo.takeIf { it.isNotBlank() },
            details.signatureAlgorithm.takeIf { it.isNotBlank() }
        ).joinToString(" · ")

        val hasAnyCrypto = algoText.isNotBlank() ||
            details.serialHex.isNotBlank() ||
            details.sans.isNotEmpty() ||
            details.sha256Fingerprint.isNotBlank()

        if (!hasAnyCrypto) return null

        val section = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((4 * density).toInt(), (16 * density).toInt(), (4 * density).toInt(), 0)
        }
        section.addView(sectionLabel(activity, activity.getString(R.string.site_info_cert_details_toggle), colors, density))

        if (algoText.isNotBlank()) {
            addMonoBlock(activity, section, activity.getString(R.string.site_info_cert_algorithm), algoText, colors, density)
        }
        if (details.serialHex.isNotBlank()) {
            addMonoBlock(activity, section, activity.getString(R.string.site_info_cert_serial), details.serialHex, colors, density)
        }
        if (details.sans.isNotEmpty()) {
            val preview = details.sans.take(6).joinToString(", ") +
                if (details.sans.size > 6) " (+${details.sans.size - 6})" else ""
            addMonoBlock(activity, section, activity.getString(R.string.site_info_cert_sans), preview, colors, density)
        }
        if (details.sha256Fingerprint.isNotBlank()) {
            val fpRow = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = SnakeTheme.pillRippleSurface(Color.TRANSPARENT, rippleColor, 14f, density)
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
                setOnClickListener {
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("SHA-256", details.sha256Fingerprint))
                    Toast.makeText(activity, R.string.site_info_cert_copied, Toast.LENGTH_SHORT).show()
                }
            }
            fpRow.addView(TextView(activity).apply {
                text = activity.getString(R.string.site_info_cert_sha256)
                textSize = 11.5f
                setTextColor(colors.body)
            })
            fpRow.addView(TextView(activity).apply {
                text = details.sha256Fingerprint
                textSize = 11.5f
                typeface = Typeface.MONOSPACE
                setTextColor(colors.positiveBg)
                setPadding(0, (2 * density).toInt(), 0, 0)
            })
            section.addView(fpRow)
        }
        return section
    }

    private fun buildBlockedSection(
        activity: Activity,
        prefs: SnakePreferences,
        state: PageSecurityState,
        colors: SnakeTheme.ExpressiveDialogColors,
        rippleColor: Int,
        density: Float,
        onChanged: () -> Unit
    ): LinearLayout {
        val section = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((4 * density).toInt(), (16 * density).toInt(), (4 * density).toInt(), 0)
        }
        val totalBlocked = state.blockedHosts.values.sum()
        section.addView(sectionLabel(activity, activity.getString(R.string.site_info_blocked_header, totalBlocked), colors, density))

        if (state.blockedHosts.isEmpty()) {
            section.addView(TextView(activity).apply {
                text = activity.getString(R.string.site_info_blocked_none)
                textSize = 13.5f
                setTextColor(colors.body)
            })
            return section
        }

        val customRulesSet = prefs.parsedCustomRules().toSet()
        val sortedHosts = state.blockedHosts.entries.sortedByDescending { it.value }.take(15)
        for ((blockedHost, count) in sortedHosts) {
            val rule = "||${blockedHost.lowercase(Locale.ROOT)}"
            val inCustom = rule in customRulesSet
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (44 * density).toInt()
                setPadding(0, (3 * density).toInt(), 0, (3 * density).toInt())
            }
            val hostTv = TextView(activity).apply {
                text = activity.getString(R.string.site_info_blocked_item, blockedHost, count)
                textSize = 13.5f
                setTextColor(colors.title)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            val toggleBtn = TextView(activity).apply {
                text = activity.getString(if (inCustom) R.string.site_info_rule_remove else R.string.site_info_rule_keep)
                textSize = 12.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(colors.secondaryText)
                gravity = Gravity.CENTER
                minimumHeight = (34 * density).toInt()
                background = SnakeTheme.outlinedPillRippleSurface(colors.stroke, rippleColor, 17f, density, colors.secondaryBg)
                setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
                setOnClickListener {
                    toggleCustomRule(prefs, rule)
                    Toast.makeText(
                        activity,
                        activity.getString(if (inCustom) R.string.site_info_rule_removed else R.string.site_info_rule_added, blockedHost),
                        Toast.LENGTH_SHORT
                    ).show()
                    onChanged()
                }
            }
            row.addView(hostTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (10 * density).toInt()
            })
            row.addView(toggleBtn)
            section.addView(row)
        }
        return section
    }

    internal fun extractCertificateDetails(
        cert: SslCertificate?,
        nowMs: Long = System.currentTimeMillis()
    ): CertificateDetails? {
        if (cert == null) return null
        val x509 = resolveX509Certificate(cert)
        val validFrom = cert.validNotBeforeDate ?: x509?.notBefore
        val validTo = cert.validNotAfterDate ?: x509?.notAfter
        // floorDiv, not toDays(): toDays() truncates toward zero, so a cert that expired 12h ago would
        // read as "0 days left" instead of expired.
        val daysRemaining = validTo?.let {
            Math.floorDiv(it.time - nowMs, TimeUnit.DAYS.toMillis(1))
        }

        val publicKeyInfo = x509?.publicKey?.let { key ->
            when (key) {
                is RSAPublicKey -> "RSA ${key.modulus.bitLength()}-bit"
                is ECPublicKey -> "EC ${key.params.order.bitLength()}-bit"
                else -> key.algorithm
            }
        }.orEmpty()

        val serialHex = x509?.serialNumber?.toByteArray()?.let { formatHexColon(it) }.orEmpty()
        val sha256 = x509?.encoded?.let { encoded ->
            val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
            formatHexColon(digest)
        }.orEmpty()

        val sans = runCatching {
            x509?.subjectAlternativeNames
                ?.filter { it.size >= 2 && it[0] == 2 }
                ?.mapNotNull { it[1]?.toString() }
                .orEmpty()
        }.getOrDefault(emptyList())

        return CertificateDetails(
            subjectCn = cert.issuedTo?.cName.orEmpty(),
            subjectOrg = cert.issuedTo?.oName.orEmpty(),
            issuerCn = cert.issuedBy?.cName.orEmpty(),
            issuerOrg = cert.issuedBy?.oName.orEmpty(),
            validFrom = validFrom,
            validTo = validTo,
            daysRemaining = daysRemaining,
            signatureAlgorithm = x509?.sigAlgName.orEmpty(),
            publicKeyInfo = publicKeyInfo,
            serialHex = serialHex,
            sans = sans,
            sha256Fingerprint = sha256
        )
    }

    private fun resolveX509Certificate(cert: SslCertificate): X509Certificate? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cert.x509Certificate?.let { return it }
        }
        return runCatching {
            val bundle = SslCertificate.saveState(cert)
            val bytes = bundle.getByteArray("x509-certificate") ?: return null
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(bytes)) as? X509Certificate
        }.getOrNull()
    }

    private fun formatHexColon(bytes: ByteArray): String =
        bytes.joinToString(":") { b -> "%02X".format(Locale.US, b) }

    internal fun toggleCustomRule(prefs: SnakePreferences, rule: String) {
        val normalized = rule.trim().lowercase(Locale.ROOT)
        val lines = prefs.customBlocklistRaw
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        val removed = lines.removeAll { it.equals(normalized, ignoreCase = true) }
        if (!removed) {
            lines.add(normalized)
        }
        prefs.customBlocklistRaw = lines.joinToString("\n")
    }

    private fun resolveConnectionBadge(
        activity: Activity,
        state: PageSecurityState,
        colors: SnakeTheme.ExpressiveDialogColors
    ): Pair<String, Int> {
        val url = state.url
        return when {
            url.isBlank() || url == MainActivity.DEFAULT_START_URL ->
                activity.getString(R.string.site_info_local_page) to colors.body
            state.sslBypassed ->
                activity.getString(R.string.site_info_ssl_bypassed) to Color.parseColor("#F59E0B")
            url.startsWith("http://", ignoreCase = true) || state.httpBypassed ->
                activity.getString(R.string.site_info_http_unencrypted) to Color.parseColor("#F59E0B")
            url.startsWith("https://", ignoreCase = true) ->
                activity.getString(R.string.site_info_https_secure) to colors.positiveBg
            else ->
                activity.getString(R.string.site_info_local_page) to colors.body
        }
    }

    private fun sectionLabel(
        activity: Activity,
        title: String,
        colors: SnakeTheme.ExpressiveDialogColors,
        density: Float
    ): TextView = TextView(activity).apply {
        text = title
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(colors.positiveBg)
        setPadding(0, 0, 0, (8 * density).toInt())
    }

    private fun addKeyValueRow(
        activity: Activity,
        parent: LinearLayout,
        label: String,
        value: String,
        labelColor: Int,
        valueColor: Int,
        density: Float
    ) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (3 * density).toInt(), 0, (3 * density).toInt())
        }
        row.addView(TextView(activity).apply {
            text = label
            textSize = 12.5f
            setTextColor(labelColor)
            layoutParams = LinearLayout.LayoutParams((86 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (8 * density).toInt()
            }
        })
        row.addView(TextView(activity).apply {
            text = value
            textSize = 13f
            setTextColor(valueColor)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        parent.addView(row)
    }

    private fun addMonoBlock(
        activity: Activity,
        parent: LinearLayout,
        label: String,
        value: String,
        colors: SnakeTheme.ExpressiveDialogColors,
        density: Float
    ) {
        parent.addView(TextView(activity).apply {
            text = label
            textSize = 11.5f
            setTextColor(colors.body)
            setPadding(0, (2 * density).toInt(), 0, 0)
        })
        parent.addView(TextView(activity).apply {
            text = value
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(colors.title)
            setPadding(0, (1 * density).toInt(), 0, (6 * density).toInt())
        })
    }
}
