package com.nromanovich.stealthsnake

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

enum class CaptureMode(val key: String) {
    ASK("ask"),
    PRIVATE("private"),
    GALLERY("gallery");

    companion object {
        fun fromKey(key: String?): CaptureMode =
            entries.firstOrNull { it.key == key } ?: ASK
    }
}

enum class SearchEngine(val key: String, val queryTemplate: String?) {
    DUCKDUCKGO("ddg", "https://duckduckgo.com/?q=%s"),
    STARTPAGE("startpage", "https://www.startpage.com/sp/search?query=%s"),
    BRAVE("brave", "https://search.brave.com/search?q=%s"),
    CUSTOM("custom", null),
    OFF("off", null);

    companion object {
        fun fromKey(key: String?): SearchEngine =
            entries.firstOrNull { it.key == key } ?: DUCKDUCKGO
    }
}

enum class SupportedLocale(val tag: String) {
    SYSTEM(""),
    EN("en"),
    RU("ru");

    companion object {
        fun fromTag(tag: String?): SupportedLocale =
            entries.firstOrNull { it.tag == tag } ?: EN
    }
}

enum class ProtectionPreset(val key: String) {
    MAX_STEALTH("max_stealth"),
    COMPATIBILITY("compatibility"),
    CUSTOM("custom");

    companion object {
        fun fromKey(key: String?): ProtectionPreset =
            entries.firstOrNull { it.key == key } ?: MAX_STEALTH
    }
}

data class SnakeProfile(
    val id: String,
    val name: String,
    val builtinBlocklistEnabled: Boolean,
    val customBlocklistRaw: String,
    val blockWebRtcIce: Boolean,
    val blockBeacons: Boolean,
    val noiseCanvasWebgl: Boolean,
    val noiseAudio: Boolean,
    val hideVisibilityFocus: Boolean,
    val lockViewport: Boolean,
    val sensorFallback: Boolean,
    val fuzzPerformanceNow: Boolean,
    val microJitterEnabled: Boolean,
    val blockExternalSchemes: Boolean,
    val suppressJsDialogs: Boolean,
    val forceDarkPages: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("builtinBlocklistEnabled", builtinBlocklistEnabled)
        put("customBlocklistRaw", customBlocklistRaw)
        put("blockWebRtcIce", blockWebRtcIce)
        put("blockBeacons", blockBeacons)
        put("noiseCanvasWebgl", noiseCanvasWebgl)
        put("noiseAudio", noiseAudio)
        put("hideVisibilityFocus", hideVisibilityFocus)
        put("lockViewport", lockViewport)
        put("sensorFallback", sensorFallback)
        put("fuzzPerformanceNow", fuzzPerformanceNow)
        put("microJitterEnabled", microJitterEnabled)
        put("blockExternalSchemes", blockExternalSchemes)
        put("suppressJsDialogs", suppressJsDialogs)
        put("forceDarkPages", forceDarkPages)
    }

    companion object {
        fun fromJson(obj: JSONObject): SnakeProfile? {
            val id = obj.optString("id", "").trim()
            val name = obj.optString("name", "").trim()
            if (id.isEmpty() || name.isEmpty()) return null
            return SnakeProfile(
                id = id,
                name = name,
                builtinBlocklistEnabled = obj.optBoolean("builtinBlocklistEnabled", true),
                customBlocklistRaw = obj.optString("customBlocklistRaw", ""),
                blockWebRtcIce = obj.optBoolean("blockWebRtcIce", true),
                blockBeacons = obj.optBoolean("blockBeacons", true),
                noiseCanvasWebgl = obj.optBoolean("noiseCanvasWebgl", true),
                noiseAudio = obj.optBoolean("noiseAudio", true),
                hideVisibilityFocus = obj.optBoolean("hideVisibilityFocus", true),
                lockViewport = obj.optBoolean("lockViewport", true),
                sensorFallback = obj.optBoolean("sensorFallback", true),
                fuzzPerformanceNow = obj.optBoolean("fuzzPerformanceNow", true),
                microJitterEnabled = obj.optBoolean("microJitterEnabled", true),
                blockExternalSchemes = obj.optBoolean("blockExternalSchemes", true),
                suppressJsDialogs = obj.optBoolean("suppressJsDialogs", true),
                forceDarkPages = obj.optBoolean("forceDarkPages", false)
            )
        }
    }
}

/**
 * Private SharedPreferences-backed configuration store for Stealth Snake.
 * All defaults match the maximum-stealth baseline.
 */
class SnakePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var preset: ProtectionPreset
        get() = ProtectionPreset.fromKey(prefs.getString(KEY_PRESET, ProtectionPreset.MAX_STEALTH.key))
        set(value) = prefs.edit().putString(KEY_PRESET, value.key).apply()

    // Capture & Storage
    var captureMode: CaptureMode
        get() = CaptureMode.fromKey(prefs.getString(KEY_CAPTURE_MODE, CaptureMode.ASK.key))
        set(value) = prefs.edit().putString(KEY_CAPTURE_MODE, value.key).apply()

    var autoClearCapturesOnWipe: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CLEAR_CAPTURES, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CLEAR_CAPTURES, value).apply()

    var autoClearOfflineOnWipe: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CLEAR_OFFLINE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CLEAR_OFFLINE, value).apply()

    // Navigation & QoL
    var homeUrl: String
        get() = prefs.getString(KEY_HOME_URL, MainActivity.DEFAULT_START_URL)
            ?.takeIf { it.isNotBlank() } ?: MainActivity.DEFAULT_START_URL
        set(value) {
            val normalized = MainActivity.normalizeUrl(value) ?: MainActivity.DEFAULT_START_URL
            prefs.edit().putString(KEY_HOME_URL, normalized).apply()
        }

    var searchEngine: SearchEngine
        get() = SearchEngine.fromKey(prefs.getString(KEY_SEARCH_ENGINE, SearchEngine.DUCKDUCKGO.key))
        set(value) = prefs.edit().putString(KEY_SEARCH_ENGINE, value.key).apply()

    var customSearchTemplate: String
        get() = prefs.getString(KEY_CUSTOM_SEARCH_TEMPLATE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CUSTOM_SEARCH_TEMPLATE, normalizeSearchTemplate(value)).apply()

    var vpnKillSwitchEnabled: Boolean
        get() = prefs.getBoolean(KEY_VPN_KILL_SWITCH, false)
        set(value) = prefs.edit().putBoolean(KEY_VPN_KILL_SWITCH, value).apply()

    var vpnBypassStartCheck: Boolean
        get() = prefs.getBoolean(KEY_VPN_BYPASS_START_CHECK, false)
        set(value) = prefs.edit().putBoolean(KEY_VPN_BYPASS_START_CHECK, value).apply()

    var forceDarkPages: Boolean
        get() = prefs.getBoolean(KEY_FORCE_DARK, false)
        set(value) {
            prefs.edit().putBoolean(KEY_FORCE_DARK, value).apply()
            syncActiveProfileIfAny()
        }

    var textZoomPercent: Int
        get() = prefs.getInt(KEY_TEXT_ZOOM, 100).coerceIn(85, 130)
        set(value) = prefs.edit().putInt(KEY_TEXT_ZOOM, value.coerceIn(85, 130)).apply()

    // Network & Blocking
    var builtinBlocklistEnabled: Boolean
        get() = prefs.getBoolean(KEY_BUILTIN_BLOCKLIST, true)
        set(value) = putBooleanAndMarkCustom(KEY_BUILTIN_BLOCKLIST, value)

    var customBlocklistRaw: String
        get() = prefs.getString(KEY_CUSTOM_BLOCKLIST, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_BLOCKLIST, value).apply()
            syncActiveProfileIfAny()
        }

    var blockWebRtcIce: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_WEBRTC, true)
        set(value) = putBooleanAndMarkCustom(KEY_BLOCK_WEBRTC, value)

    var blockBeacons: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_BEACONS, true)
        set(value) = putBooleanAndMarkCustom(KEY_BLOCK_BEACONS, value)

    // Anti-Fingerprinting (JS DocumentStart)
    var noiseCanvasWebgl: Boolean
        get() = prefs.getBoolean(KEY_NOISE_CANVAS, true)
        set(value) = putBooleanAndMarkCustom(KEY_NOISE_CANVAS, value)

    var noiseAudio: Boolean
        get() = prefs.getBoolean(KEY_NOISE_AUDIO, true)
        set(value) = putBooleanAndMarkCustom(KEY_NOISE_AUDIO, value)

    var hideVisibilityFocus: Boolean
        get() = prefs.getBoolean(KEY_HIDE_VISIBILITY, true)
        set(value) = putBooleanAndMarkCustom(KEY_HIDE_VISIBILITY, value)

    var lockViewport: Boolean
        get() = prefs.getBoolean(KEY_LOCK_VIEWPORT, true)
        set(value) = putBooleanAndMarkCustom(KEY_LOCK_VIEWPORT, value)

    var sensorFallback: Boolean
        get() = prefs.getBoolean(KEY_SENSOR_FALLBACK, true)
        set(value) = putBooleanAndMarkCustom(KEY_SENSOR_FALLBACK, value)

    var fuzzPerformanceNow: Boolean
        get() = prefs.getBoolean(KEY_FUZZ_PERF, true)
        set(value) = putBooleanAndMarkCustom(KEY_FUZZ_PERF, value)

    // Behavioral Protection
    var microJitterEnabled: Boolean
        get() = prefs.getBoolean(KEY_JITTER_ENABLED, true)
        set(value) = putBooleanAndMarkCustom(KEY_JITTER_ENABLED, value)

    var microJitterMinSec: Int
        get() = prefs.getInt(KEY_JITTER_MIN_SEC, 12).coerceIn(5, 60)
        set(value) {
            val clampedMin = value.coerceIn(5, 60)
            val editor = prefs.edit().putInt(KEY_JITTER_MIN_SEC, clampedMin)
            if (prefs.getInt(KEY_JITTER_MAX_SEC, 20) < clampedMin) {
                editor.putInt(KEY_JITTER_MAX_SEC, clampedMin)
            }
            editor.apply()
        }

    var microJitterMaxSec: Int
        get() = prefs.getInt(KEY_JITTER_MAX_SEC, 20).coerceIn(microJitterMinSec, 90)
        set(value) {
            val clampedMax = value.coerceIn(microJitterMinSec, 90)
            prefs.edit().putInt(KEY_JITTER_MAX_SEC, clampedMax).apply()
        }

    var blockExternalSchemes: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_SCHEMES, true)
        set(value) = putBooleanAndMarkCustom(KEY_BLOCK_SCHEMES, value)

    var suppressJsDialogs: Boolean
        get() = prefs.getBoolean(KEY_SUPPRESS_DIALOGS, true)
        set(value) = putBooleanAndMarkCustom(KEY_SUPPRESS_DIALOGS, value)

    // Session & UI
    var locale: SupportedLocale
        get() = SupportedLocale.fromTag(prefs.getString(KEY_LOCALE, SupportedLocale.SYSTEM.tag))
        set(value) {
            prefs.edit().putString(KEY_LOCALE, value.tag).apply()
            applyLocale(value)
        }

    var confirmBeforeWipe: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM_WIPE, false)
        set(value) = prefs.edit().putBoolean(KEY_CONFIRM_WIPE, value).apply()

    var autoWipeOnExit: Boolean
        get() = prefs.getBoolean(KEY_AUTO_WIPE_EXIT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_WIPE_EXIT, value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    var hapticFeedbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC, value).apply()

    var showStatusBar: Boolean
        get() = prefs.getBoolean(KEY_SHOW_STATUS_BAR, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_STATUS_BAR, value).apply()

    var blockScreenshots: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_SCREENSHOTS, false)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_SCREENSHOTS, value).apply()

    var pinLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_PIN_LOCK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_PIN_LOCK_ENABLED, value).apply()

    var pinSalt: String
        get() = prefs.getString(KEY_PIN_SALT, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PIN_SALT, value).apply()

    var pinHash: String
        get() = prefs.getString(KEY_PIN_HASH, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PIN_HASH, value).apply()

    var pinAutoWipeOnFail: Boolean
        get() = prefs.getBoolean(KEY_PIN_AUTO_WIPE_ON_FAIL, true)
        set(value) = prefs.edit().putBoolean(KEY_PIN_AUTO_WIPE_ON_FAIL, value).apply()

    // Security-critical counters use commit(): an attacker must not be able to reset the
    // brute-force counter by killing the process before an async apply() reaches disk.
    // They are only written from PinManager (background KDF executor / tiny settings writes).
    var pinFailedAttempts: Int
        get() = prefs.getInt(KEY_PIN_FAILED_ATTEMPTS, 0).coerceAtLeast(0)
        set(value) { prefs.edit().putInt(KEY_PIN_FAILED_ATTEMPTS, value.coerceAtLeast(0)).commit() }

    /** Wall-clock start of the current brute-force lockout (0 = none). */
    var pinLockoutStartedAtMs: Long
        get() = prefs.getLong(KEY_PIN_LOCKOUT_STARTED_AT, 0L).coerceAtLeast(0L)
        set(value) { prefs.edit().putLong(KEY_PIN_LOCKOUT_STARTED_AT, value.coerceAtLeast(0L)).commit() }

    /** Wall-clock end of the current brute-force lockout (0 = none). */
    var pinLockoutUntilMs: Long
        get() = prefs.getLong(KEY_PIN_LOCKOUT_UNTIL, 0L).coerceAtLeast(0L)
        set(value) { prefs.edit().putLong(KEY_PIN_LOCKOUT_UNTIL, value.coerceAtLeast(0L)).commit() }

    var useDynamicTheme: Boolean
        get() = prefs.getBoolean(KEY_USE_DYNAMIC_THEME, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_DYNAMIC_THEME, value).apply()

    var themePalette: ThemePalette
        get() = ThemePalette.fromKey(prefs.getString(KEY_THEME_PALETTE, ThemePalette.OBSIDIAN.key))
        set(value) = prefs.edit()
            .putString(KEY_THEME_PALETTE, value.key)
            .putBoolean(KEY_USE_DYNAMIC_THEME, false)
            .apply()

    fun enableDynamicTheme() {
        prefs.edit().putBoolean(KEY_USE_DYNAMIC_THEME, true).apply()
    }

    var downloadsEnabled: Boolean
        get() = prefs.getBoolean(KEY_DOWNLOADS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_DOWNLOADS_ENABLED, value).apply()

    var sessionDirty: Boolean
        get() = prefs.getBoolean(KEY_SESSION_DIRTY, false)
        set(value) = prefs.edit().putBoolean(KEY_SESSION_DIRTY, value).apply()

    var startHintDismissed: Boolean
        get() = prefs.getBoolean(KEY_START_HINT_DISMISSED, false)
        set(value) = prefs.edit().putBoolean(KEY_START_HINT_DISMISSED, value).apply()

    var onboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).apply()

    var activeProfileId: String?
        get() = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)?.takeIf { it.isNotBlank() }
        private set(value) = prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, value).apply()

    private fun putBooleanAndMarkCustom(key: String, value: Boolean) {
        prefs.edit()
            .putBoolean(key, value)
            .putString(KEY_PRESET, ProtectionPreset.CUSTOM.key)
            .apply()
        syncActiveProfileIfAny()
    }

    fun applyPreset(target: ProtectionPreset) {
        if (target == ProtectionPreset.CUSTOM) {
            preset = ProtectionPreset.CUSTOM
            return
        }
        val max = (target == ProtectionPreset.MAX_STEALTH)
        prefs.edit()
            .remove(KEY_ACTIVE_PROFILE_ID)
            .putString(KEY_PRESET, target.key)
            .putBoolean(KEY_BUILTIN_BLOCKLIST, true)
            .putBoolean(KEY_BLOCK_WEBRTC, true)
            .putBoolean(KEY_BLOCK_BEACONS, true)
            .putBoolean(KEY_HIDE_VISIBILITY, true)
            .putBoolean(KEY_LOCK_VIEWPORT, true)
            .putBoolean(KEY_BLOCK_SCHEMES, true)
            .putBoolean(KEY_SUPPRESS_DIALOGS, true)
            // Compatibility mode relaxes aggressive canvas/audio/sensor/jitter noise for strict anti-bots
            .putBoolean(KEY_NOISE_CANVAS, max)
            .putBoolean(KEY_NOISE_AUDIO, max)
            .putBoolean(KEY_SENSOR_FALLBACK, max)
            .putBoolean(KEY_FUZZ_PERF, max)
            .putBoolean(KEY_JITTER_ENABLED, max)
            .apply()
    }

    fun listProfiles(): List<SnakeProfile> {
        val raw = prefs.getString(KEY_PROFILES_JSON, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    SnakeProfile.fromJson(obj)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun createProfileFromCurrent(rawName: String): SnakeProfile {
        val baseName = rawName.replace(Regex("\\s+"), " ").trim().take(32)
        require(baseName.isNotEmpty()) { "Profile name cannot be blank" }
        val existingNames = listProfiles().map { it.name.lowercase(Locale.ROOT) }.toSet()
        var uniqueName = baseName
        var suffix = 2
        while (uniqueName.lowercase(Locale.ROOT) in existingNames) {
            val tag = " ($suffix)"
            uniqueName = baseName.take(32 - tag.length).trimEnd() + tag
            suffix++
        }
        val profile = captureSnapshot(id = UUID.randomUUID().toString(), name = uniqueName)
        val updated = listProfiles() + profile
        saveProfiles(updated)
        prefs.edit()
            .putString(KEY_ACTIVE_PROFILE_ID, profile.id)
            .putString(KEY_PRESET, ProtectionPreset.CUSTOM.key)
            .apply()
        return profile
    }

    fun applyProfile(profileId: String): Boolean {
        val profile = listProfiles().firstOrNull { it.id == profileId } ?: return false
        prefs.edit()
            .putString(KEY_ACTIVE_PROFILE_ID, profile.id)
            .putString(KEY_PRESET, ProtectionPreset.CUSTOM.key)
            .putBoolean(KEY_BUILTIN_BLOCKLIST, profile.builtinBlocklistEnabled)
            .putString(KEY_CUSTOM_BLOCKLIST, profile.customBlocklistRaw)
            .putBoolean(KEY_BLOCK_WEBRTC, profile.blockWebRtcIce)
            .putBoolean(KEY_BLOCK_BEACONS, profile.blockBeacons)
            .putBoolean(KEY_NOISE_CANVAS, profile.noiseCanvasWebgl)
            .putBoolean(KEY_NOISE_AUDIO, profile.noiseAudio)
            .putBoolean(KEY_HIDE_VISIBILITY, profile.hideVisibilityFocus)
            .putBoolean(KEY_LOCK_VIEWPORT, profile.lockViewport)
            .putBoolean(KEY_SENSOR_FALLBACK, profile.sensorFallback)
            .putBoolean(KEY_FUZZ_PERF, profile.fuzzPerformanceNow)
            .putBoolean(KEY_JITTER_ENABLED, profile.microJitterEnabled)
            .putBoolean(KEY_BLOCK_SCHEMES, profile.blockExternalSchemes)
            .putBoolean(KEY_SUPPRESS_DIALOGS, profile.suppressJsDialogs)
            .putBoolean(KEY_FORCE_DARK, profile.forceDarkPages)
            .apply()
        return true
    }

    fun deleteProfile(profileId: String): Boolean {
        val current = listProfiles()
        val remaining = current.filterNot { it.id == profileId }
        if (remaining.size == current.size) return false
        saveProfiles(remaining)
        if (activeProfileId == profileId) {
            prefs.edit().remove(KEY_ACTIVE_PROFILE_ID).apply()
        }
        return true
    }

    private fun captureSnapshot(id: String, name: String): SnakeProfile = SnakeProfile(
        id = id,
        name = name,
        builtinBlocklistEnabled = builtinBlocklistEnabled,
        customBlocklistRaw = customBlocklistRaw,
        blockWebRtcIce = blockWebRtcIce,
        blockBeacons = blockBeacons,
        noiseCanvasWebgl = noiseCanvasWebgl,
        noiseAudio = noiseAudio,
        hideVisibilityFocus = hideVisibilityFocus,
        lockViewport = lockViewport,
        sensorFallback = sensorFallback,
        fuzzPerformanceNow = fuzzPerformanceNow,
        microJitterEnabled = microJitterEnabled,
        blockExternalSchemes = blockExternalSchemes,
        suppressJsDialogs = suppressJsDialogs,
        forceDarkPages = forceDarkPages
    )

    private fun syncActiveProfileIfAny() {
        val currentId = activeProfileId ?: return
        val profiles = listProfiles()
        val idx = profiles.indexOfFirst { it.id == currentId }
        if (idx < 0) {
            prefs.edit().remove(KEY_ACTIVE_PROFILE_ID).apply()
            return
        }
        val updated = profiles.toMutableList()
        updated[idx] = captureSnapshot(id = currentId, name = profiles[idx].name)
        saveProfiles(updated)
    }

    private fun saveProfiles(profiles: List<SnakeProfile>) {
        val arr = JSONArray()
        for (p in profiles) {
            arr.put(p.toJson())
        }
        prefs.edit().putString(KEY_PROFILES_JSON, arr.toString()).apply()
    }

    fun resetToDefaults(preservePin: Boolean = false) {
        val keepPin = preservePin && pinLockEnabled
        val savedSalt = if (keepPin) pinSalt else ""
        val savedHash = if (keepPin) pinHash else ""
        val savedAutoWipe = if (keepPin) pinAutoWipeOnFail else true
        val savedAttempts = if (keepPin) pinFailedAttempts else 0
        val savedLockoutStart = if (keepPin) pinLockoutStartedAtMs else 0L
        val savedLockoutUntil = if (keepPin) pinLockoutUntilMs else 0L
        prefs.edit().clear().putBoolean(KEY_USE_DYNAMIC_THEME, true).apply()
        if (keepPin) {
            // Reset must not double as a brute-force counter / lockout reset.
            prefs.edit()
                .putBoolean(KEY_PIN_LOCK_ENABLED, true)
                .putString(KEY_PIN_SALT, savedSalt)
                .putString(KEY_PIN_HASH, savedHash)
                .putBoolean(KEY_PIN_AUTO_WIPE_ON_FAIL, savedAutoWipe)
                .putInt(KEY_PIN_FAILED_ATTEMPTS, savedAttempts)
                .putLong(KEY_PIN_LOCKOUT_STARTED_AT, savedLockoutStart)
                .putLong(KEY_PIN_LOCKOUT_UNTIL, savedLockoutUntil)
                .apply()
        } else {
            PinLockCoordinator.onPinCleared()
        }
        applyLocale(SupportedLocale.SYSTEM)
    }

    /**
     * Removes user-identifying preferences (home URL, custom search template,
     * custom blocklist, saved profiles, dirty marker) on failed-PIN emergency wipe
     * while keeping PIN protection itself intact.
     */
    fun scrubSensitiveDataForPanicWipe() {
        prefs.edit()
            .remove(KEY_HOME_URL)
            .remove(KEY_CUSTOM_SEARCH_TEMPLATE)
            .remove(KEY_CUSTOM_BLOCKLIST)
            .remove(KEY_PROFILES_JSON)
            .remove(KEY_ACTIVE_PROFILE_ID)
            .putBoolean(KEY_SESSION_DIRTY, false)
            .commit() // panic wipe must hit disk even if the process is killed right after
    }

    fun parsedCustomRules(): List<String> =
        BlocklistEngine.fromRules(customBlocklistRaw.lines()).rules

    fun toSnakeFeatureFlags(): SnakeScript.FeatureFlags =
        SnakeScript.FeatureFlags(
            noiseCanvasWebgl = noiseCanvasWebgl,
            noiseAudio = noiseAudio,
            hideVisibilityFocus = hideVisibilityFocus,
            lockViewport = lockViewport,
            sensorFallback = sensorFallback,
            fuzzPerformanceNow = fuzzPerformanceNow,
            blockWebRtcIce = blockWebRtcIce,
            blockBeacons = blockBeacons
        )

    companion object {
        private const val PREFS_NAME = "stealth_snake_prefs"
        private val SCHEME_PREFIX_REGEX = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):(.*)$")
        private val HOST_WITH_PORT_REGEX = Regex(
            "^(localhost|[^/:?#\\s]+\\.[^/:?#\\s]+):(\\d+)([/?#].*)?$",
            RegexOption.IGNORE_CASE
        )

        private const val KEY_PRESET = "preset"
        private const val KEY_CAPTURE_MODE = "capture_mode"
        private const val KEY_AUTO_CLEAR_CAPTURES = "auto_clear_captures"
        private const val KEY_AUTO_CLEAR_OFFLINE = "auto_clear_offline"
        private const val KEY_HOME_URL = "home_url"
        private const val KEY_SEARCH_ENGINE = "search_engine"
        private const val KEY_FORCE_DARK = "force_dark"
        private const val KEY_TEXT_ZOOM = "text_zoom"
        private const val KEY_BUILTIN_BLOCKLIST = "builtin_blocklist"
        private const val KEY_CUSTOM_BLOCKLIST = "custom_blocklist"
        private const val KEY_BLOCK_WEBRTC = "block_webrtc"
        private const val KEY_BLOCK_BEACONS = "block_beacons"
        private const val KEY_NOISE_CANVAS = "noise_canvas"
        private const val KEY_NOISE_AUDIO = "noise_audio"
        private const val KEY_HIDE_VISIBILITY = "hide_visibility"
        private const val KEY_LOCK_VIEWPORT = "lock_viewport"
        private const val KEY_SENSOR_FALLBACK = "sensor_fallback"
        private const val KEY_FUZZ_PERF = "fuzz_perf"
        private const val KEY_JITTER_ENABLED = "jitter_enabled"
        private const val KEY_JITTER_MIN_SEC = "jitter_min_sec"
        private const val KEY_JITTER_MAX_SEC = "jitter_max_sec"
        private const val KEY_BLOCK_SCHEMES = "block_schemes"
        private const val KEY_SUPPRESS_DIALOGS = "suppress_dialogs"
        private const val KEY_LOCALE = "app_locale"
        private const val KEY_CONFIRM_WIPE = "confirm_wipe"
        private const val KEY_AUTO_WIPE_EXIT = "auto_wipe_exit"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_HAPTIC = "haptic"
        private const val KEY_SHOW_STATUS_BAR = "show_status_bar"
        private const val KEY_THEME_PALETTE = "theme_palette"
        private const val KEY_USE_DYNAMIC_THEME = "use_dynamic_theme"
        private const val KEY_DOWNLOADS_ENABLED = "downloads_enabled"
        private const val KEY_START_HINT_DISMISSED = "start_hint_dismissed"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_PROFILES_JSON = "custom_profiles_json"
        private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
        private const val KEY_CUSTOM_SEARCH_TEMPLATE = "custom_search_template"
        private const val KEY_VPN_KILL_SWITCH = "vpn_kill_switch"
        private const val KEY_VPN_BYPASS_START_CHECK = "vpn_bypass_start_check"
        private const val KEY_SESSION_DIRTY = "session_dirty"
        private const val KEY_BLOCK_SCREENSHOTS = "block_screenshots"
        private const val KEY_PIN_LOCK_ENABLED = "pin_lock_enabled"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_AUTO_WIPE_ON_FAIL = "pin_auto_wipe_on_fail"
        private const val KEY_PIN_FAILED_ATTEMPTS = "pin_failed_attempts"
    private const val KEY_PIN_LOCKOUT_STARTED_AT = "pin_lockout_started_at"
    private const val KEY_PIN_LOCKOUT_UNTIL = "pin_lockout_until"

        fun applyLocale(locale: SupportedLocale) {
            val locales = if (locale == SupportedLocale.SYSTEM) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(locale.tag)
            }
            AppCompatDelegate.setApplicationLocales(locales)
        }

        private fun hasValidHttpAuthority(url: String): Boolean {
            val host = runCatching { android.net.Uri.parse(url).host }.getOrNull()
            return !host.isNullOrBlank()
        }

        private fun resolveHttpSchemeOrHostPort(trimmed: String): String? {
            val schemeMatch = SCHEME_PREFIX_REGEX.matchEntire(trimmed) ?: return null
            val scheme = schemeMatch.groupValues[1].lowercase(Locale.ROOT)
            val rest = schemeMatch.groupValues[2]
            if ((scheme == "http" || scheme == "https") && rest.startsWith("//")) {
                return if (hasValidHttpAuthority(trimmed)) trimmed else null
            }
            if (HOST_WITH_PORT_REGEX.matches(trimmed) && !trimmed.any { it.isWhitespace() }) {
                return "https://$trimmed"
            }
            return null
        }

        fun normalizeUrl(rawInput: String): String? {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.equals("about:blank", ignoreCase = true)) return "about:blank"
            if (SCHEME_PREFIX_REGEX.matchEntire(trimmed) != null) {
                return resolveHttpSchemeOrHostPort(trimmed)
            }
            if (trimmed.any { it.isWhitespace() }) return null
            return "https://$trimmed"
        }

        fun normalizeSearchTemplate(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            if (SCHEME_PREFIX_REGEX.matchEntire(trimmed) != null) {
                return resolveHttpSchemeOrHostPort(trimmed).orEmpty()
            }
            if (trimmed.any { it.isWhitespace() }) return ""
            return "https://$trimmed"
        }

        fun buildSearchOrUrl(
            rawInput: String,
            engine: SearchEngine,
            customTemplate: String = ""
        ): String? {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.equals("about:blank", ignoreCase = true)) return "about:blank"

            if (SCHEME_PREFIX_REGEX.matchEntire(trimmed) != null) {
                // Non-http(s) explicit schemes (javascript:, data:, file:, intent:, etc.) are rejected.
                return resolveHttpSchemeOrHostPort(trimmed)
            }

            val lower = trimmed.lowercase(Locale.ROOT)
            val looksLikeHost = !trimmed.any { it.isWhitespace() } &&
                (trimmed.contains('.') || lower == "localhost" || lower.startsWith("localhost/"))
            if (looksLikeHost) {
                return "https://$trimmed"
            }
            if (engine == SearchEngine.CUSTOM) {
                val safeTemplate = normalizeSearchTemplate(customTemplate)
                if (safeTemplate.isEmpty()) return null
                val encoded = android.net.Uri.encode(trimmed)
                return if (safeTemplate.contains("%s")) {
                    safeTemplate.replace("%s", encoded)
                } else {
                    safeTemplate + encoded
                }
            }
            val template = engine.queryTemplate ?: return null
            return template.replace("%s", android.net.Uri.encode(trimmed))
        }
    }
}
