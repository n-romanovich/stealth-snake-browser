package com.nromanovich.stealthsnake

import android.content.Context
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.security.SecureRandom

/**
 * Builds and registers `assets/snake_core.js` via `WebViewCompat.addDocumentStartJavaScript`
 * so the hardening runtime executes in C++ before any page script across all frames.
 */
object SnakeScript {
    data class FeatureFlags(
        val noiseCanvasWebgl: Boolean = true,
        val noiseAudio: Boolean = true,
        val noiseDomRect: Boolean = true,
        val hideVisibilityFocus: Boolean = true,
        val lockViewport: Boolean = true,
        val sensorFallback: Boolean = true,
        val fuzzPerformanceNow: Boolean = true,
        val spoofTimezone: Boolean = true,
        val blockWebRtcIce: Boolean = true,
        val blockBeacons: Boolean = true,
        val blockWebSockets: Boolean = false
    )

    private const val ASSET_SNAKE_CORE = "snake_core.js"
    private const val CONFIG_TOKEN = "__SS_CONFIG_JSON__"
    const val DEFAULT_CHROME_MAJOR = 124
    private val secureRandom = SecureRandom()

    fun generateSessionSeed(random: SecureRandom = secureRandom): String =
        "%08x".format(random.nextInt())

    fun loadTemplate(context: Context, assetName: String = ASSET_SNAKE_CORE): String =
        context.assets.open(assetName).use { loadTemplate(it) }

    fun loadTemplate(input: InputStream): String =
        input.bufferedReader(Charsets.UTF_8).readText()

    /**
     * Injects the serialized `{"seed": ..., "blocklist": [...]}` config into the JS template.
     * Validates that the placeholder token appears exactly once.
     */
    fun build(
        template: String,
        seed: String,
        blocklistRules: List<String>,
        flags: FeatureFlags = FeatureFlags(),
        chromeMajor: Int = DEFAULT_CHROME_MAJOR,
        chromeFullVersion: String? = null
    ): String {
        require(seed.matches(Regex("^[0-9a-f]{8}$"))) { "Session seed must be 8 lowercase hex chars" }
        val first = template.indexOf(CONFIG_TOKEN)
        check(first >= 0 && template.indexOf(CONFIG_TOKEN, first + CONFIG_TOKEN.length) < 0) {
            "Config token $CONFIG_TOKEN must appear exactly once in snake_core.js"
        }

        val flagsJson = JSONObject().apply {
            put("noiseCanvasWebgl", flags.noiseCanvasWebgl)
            put("noiseAudio", flags.noiseAudio)
            put("noiseDomRect", flags.noiseDomRect)
            put("hideVisibilityFocus", flags.hideVisibilityFocus)
            put("lockViewport", flags.lockViewport)
            put("sensorFallback", flags.sensorFallback)
            put("fuzzPerformanceNow", flags.fuzzPerformanceNow)
            put("spoofTimezone", flags.spoofTimezone)
            put("blockWebRtcIce", flags.blockWebRtcIce)
            put("blockBeacons", flags.blockBeacons)
            put("blockWebSockets", flags.blockWebSockets)
        }
        val config = JSONObject().apply {
            put("seed", seed)
            put("blocklist", JSONArray(blocklistRules))
            put("flags", flagsJson)
            // Keeps navigator.userAgent / userAgentData in lock-step with the HTTP User-Agent header.
            put("chromeMajor", chromeMajor)
            if (!chromeFullVersion.isNullOrBlank()) put("chromeFullVersion", chromeFullVersion)
        }
        return template.replace(CONFIG_TOKEN, config.toString(), ignoreCase = false)
    }

    /**
     * Registers the built script on [webView] for all origins (`*`).
     * Returns the [ScriptHandler] when `DOCUMENT_START_SCRIPT` is supported, or `null`.
     */
    fun install(webView: WebView, script: String): ScriptHandler? {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            return null
        }
        return WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
    }
}
