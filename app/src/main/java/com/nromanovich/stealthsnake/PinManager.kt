package com.nromanovich.stealthsnake

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.VisibleForTesting
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Handles native PBKDF2-HMAC-SHA256 + optional Hardware TEE Pepper-After-KDF (`v2`)
 * PIN derivation, constant-time verification, silent `v1 -> v2` migration,
 * failed-attempt tracking, background lock timeout logic, and async execution.
 */
object PinManager {

    const val MIN_PIN_LENGTH = 4
    const val MAX_PIN_LENGTH = 8
    const val MAX_FAILED_ATTEMPTS = 5
    const val BACKGROUND_LOCK_TIMEOUT_MS = 5_000L
    private const val LOCKOUT_BASE_MS = 30_000L
    private const val LOCKOUT_MAX_MS = 15 * 60_000L

    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val HMAC_SHA256_ALGORITHM = "HmacSHA256"
    private const val KEY_ALIAS_PREFIX = "stealth_snake_pin_tee_v2_"
    private const val PBKDF2_ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16
    private val V2_DOMAIN_PREFIX = "v2".toByteArray(Charsets.US_ASCII)

    internal sealed interface HmacTagResult {
        class Success(val tag: ByteArray) : HmacTagResult
        data object PermanentKeyInvalidOrMissing : HmacTagResult
        data object TransientError : HmacTagResult
    }

    internal interface HardwareKeystoreProvider {
        fun createHardwareHmacKey(): String?
        fun computeHmacTag(alias: String, salt: ByteArray, kdfOut: ByteArray): HmacTagResult
        fun deleteKey(alias: String)
    }

    private object AndroidHardwareKeystoreProvider : HardwareKeystoreProvider {
        override fun createHardwareHmacKey(): String? {
            val alias = "$KEY_ALIAS_PREFIX${UUID.randomUUID()}"
            return try {
                val kg = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
                    ANDROID_KEYSTORE
                )
                kg.init(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                        .setKeySize(KEY_LENGTH_BITS)
                        .build()
                )
                val key = kg.generateKey()
                val keyInfo = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
                    .getKeySpec(key, KeyInfo::class.java) as KeyInfo
                val isHardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                        keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
                } else {
                    @Suppress("DEPRECATION")
                    keyInfo.isInsideSecureHardware
                }
                if (!isHardware) {
                    deleteKey(alias)
                    null
                } else {
                    alias
                }
            } catch (_: Throwable) {
                deleteKey(alias)
                null
            }
        }

        override fun computeHmacTag(alias: String, salt: ByteArray, kdfOut: ByteArray): HmacTagResult {
            return try {
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (!ks.containsAlias(alias)) {
                    return HmacTagResult.PermanentKeyInvalidOrMissing
                }
                val key = ks.getKey(alias, null) as? SecretKey
                    ?: return HmacTagResult.PermanentKeyInvalidOrMissing
                // Provider intentionally omitted so JCA delayed provider selection binds to the Keystore key.
                val mac = Mac.getInstance(HMAC_SHA256_ALGORITHM)
                mac.init(key)
                mac.update(V2_DOMAIN_PREFIX)
                mac.update(0x00.toByte())
                updateIntBe(mac, salt.size)
                mac.update(salt)
                mac.update(0x00.toByte())
                updateIntBe(mac, kdfOut.size)
                mac.update(kdfOut)
                HmacTagResult.Success(mac.doFinal())
            } catch (_: UnrecoverableKeyException) {
                HmacTagResult.PermanentKeyInvalidOrMissing
            } catch (_: KeyPermanentlyInvalidatedException) {
                HmacTagResult.PermanentKeyInvalidOrMissing
            } catch (_: InvalidKeyException) {
                HmacTagResult.PermanentKeyInvalidOrMissing
            } catch (_: Throwable) {
                HmacTagResult.TransientError
            }
        }

        override fun deleteKey(alias: String) {
            if (alias.isEmpty()) return
            runCatching {
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (ks.containsAlias(alias)) {
                    ks.deleteEntry(alias)
                }
            }
        }

        private fun updateIntBe(mac: Mac, value: Int) {
            mac.update((value ushr 24).toByte())
            mac.update((value ushr 16).toByte())
            mac.update((value ushr 8).toByte())
            mac.update(value.toByte())
        }
    }

    @VisibleForTesting
    @Volatile
    internal var hardwareKeystoreProvider: HardwareKeystoreProvider = AndroidHardwareKeystoreProvider

    @VisibleForTesting
    internal fun resetHardwareKeystoreProviderForTests() {
        hardwareKeystoreProvider = AndroidHardwareKeystoreProvider
    }

    private val backgroundExecutor: Executor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "snake-pin-kdf").apply { isDaemon = true }
        }
    }

    private fun isRobolectric(): Boolean =
        Build.FINGERPRINT.equals("robolectric", ignoreCase = true)

    private fun defaultWorkExecutor(): Executor =
        if (isRobolectric()) {
            Executor { it.run() }
        } else {
            backgroundExecutor
        }

    fun isValidPinFormat(pin: String): Boolean =
        pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it in '0'..'9' }

    fun generateSalt(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(SALT_BYTES)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    internal fun deleteKeystoreAlias(alias: String) {
        if (alias.isNotEmpty()) {
            hardwareKeystoreProvider.deleteKey(alias)
        }
    }

    /**
     * Derives the 256-bit `v1` PBKDF2-HMAC-SHA256 (100,000 iterations) digest directly
     * via the native C11/ARMv8-Crypto implementation (or JVM JCA under Robolectric).
     */
    fun hashPin(pin: String, saltBase64: String): String {
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val chars = pin.toCharArray()
        return try {
            val hash = pbkdf2HmacSha256(chars, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
            try {
                Base64.encodeToString(hash, Base64.NO_WRAP)
            } finally {
                hash.fill(0)
            }
        } finally {
            chars.fill('\u0000')
        }
    }

    private fun encodeCharArrayUtf8(chars: CharArray): ByteArray {
        val bb = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .encode(CharBuffer.wrap(chars))
        return try {
            val out = ByteArray(bb.remaining())
            bb.get(out)
            out
        } finally {
            if (bb.hasArray()) {
                bb.array().fill(0)
            }
        }
    }

    @VisibleForTesting
    internal fun pbkdf2HmacSha256(
        passwordChars: CharArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBits: Int
    ): ByteArray {
        require(iterations > 0) { "iterations must be positive" }
        require(keyLengthBits > 0 && keyLengthBits % 8 == 0) {
            "keyLengthBits must be a positive multiple of 8"
        }
        if (isRobolectric()) {
            val spec = PBEKeySpec(passwordChars, salt, iterations, keyLengthBits)
            return try {
                val secret = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec)
                try {
                    secret.encoded
                } finally {
                    runCatching { secret.destroy() }
                }
            } finally {
                spec.clearPassword()
            }
        }
        val pwBytes = encodeCharArrayUtf8(passwordChars)
        return try {
            NativePinKdf.derivePbkdf2HmacSha256(
                passwordUtf8 = pwBytes,
                salt = salt,
                iterations = iterations,
                keyLengthBytes = keyLengthBits ushr 3
            )
        } finally {
            pwBytes.fill(0)
        }
    }

    internal data class DerivedPin(
        val salt: String,
        val hash: String,
        val version: Int = SnakePreferences.PIN_VERSION_V1_PBKDF2,
        val keyAlias: String = ""
    )

    private fun deriveCredential(pin: String): DerivedPin {
        require(isValidPinFormat(pin)) { "PIN must be $MIN_PIN_LENGTH-$MAX_PIN_LENGTH digits" }
        val saltBase64 = generateSalt()
        val saltBytes = Base64.decode(saltBase64, Base64.NO_WRAP)
        val chars = pin.toCharArray()
        val kdfOut = try {
            pbkdf2HmacSha256(chars, saltBytes, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        } finally {
            chars.fill('\u0000')
        }
        var newAlias: String? = null
        return try {
            newAlias = hardwareKeystoreProvider.createHardwareHmacKey()
            if (newAlias != null) {
                when (val hmac = hardwareKeystoreProvider.computeHmacTag(newAlias, saltBytes, kdfOut)) {
                    is HmacTagResult.Success -> {
                        val tagBase64 = try {
                            Base64.encodeToString(hmac.tag, Base64.NO_WRAP)
                        } finally {
                            hmac.tag.fill(0)
                        }
                        return DerivedPin(
                            salt = saltBase64,
                            hash = tagBase64,
                            version = SnakePreferences.PIN_VERSION_V2_TEE_PEPPER,
                            keyAlias = newAlias
                        )
                    }
                    else -> {
                        hardwareKeystoreProvider.deleteKey(newAlias)
                        newAlias = null
                    }
                }
            }
            DerivedPin(
                salt = saltBase64,
                hash = Base64.encodeToString(kdfOut, Base64.NO_WRAP),
                version = SnakePreferences.PIN_VERSION_V1_PBKDF2,
                keyAlias = ""
            )
        } catch (t: Throwable) {
            if (newAlias != null) {
                hardwareKeystoreProvider.deleteKey(newAlias)
            }
            throw t
        } finally {
            kdfOut.fill(0)
        }
    }

    @Synchronized
    fun setPin(prefs: SnakePreferences, pin: String) {
        val derived = deriveCredential(pin)
        commitDerivedPin(prefs, derived)
    }

    @Synchronized
    internal fun commitDerivedPin(prefs: SnakePreferences, derived: DerivedPin) {
        val previousAlias = prefs.pinKeyAlias
        prefs.commitPinCredentials(
            salt = derived.salt,
            hash = derived.hash,
            enabled = true,
            version = derived.version,
            keyAlias = derived.keyAlias
        )
        if (previousAlias.isNotEmpty() && previousAlias != derived.keyAlias) {
            deleteKeystoreAlias(previousAlias)
        }
        PinLockCoordinator.onPinConfigured()
    }

    /** Cleans up a newly generated Keystore key when async PIN setup is cancelled or backgrounded before commit. */
    @Synchronized
    internal fun discardDerivedPin(prefs: SnakePreferences, derived: DerivedPin) {
        if (derived.keyAlias.isNotEmpty() && derived.keyAlias != prefs.pinKeyAlias) {
            deleteKeystoreAlias(derived.keyAlias)
        }
    }

    internal fun derivePinAsync(
        pin: String,
        executor: Executor = defaultWorkExecutor(),
        onComplete: (Result<DerivedPin>) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        val isSync = isRobolectric() && executor === defaultWorkExecutor()
        val deliver: (Result<DerivedPin>) -> Unit = { result ->
            if (isSync && Looper.myLooper() == Looper.getMainLooper()) {
                onComplete(result)
            } else {
                mainHandler.post { onComplete(result) }
            }
        }
        executor.execute {
            val res = runCatching { deriveCredential(pin) }
            deliver(res)
        }
    }

    fun setPinAsync(
        context: Context,
        prefs: SnakePreferences,
        pin: String,
        executor: Executor = defaultWorkExecutor(),
        onComplete: (Result<Unit>) -> Unit
    ) {
        PinLockCoordinator.ensureRegistered(context)
        derivePinAsync(pin, executor) { res ->
            onComplete(res.map { derived -> commitDerivedPin(prefs, derived) })
        }
    }

    @Synchronized
    fun clearPin(prefs: SnakePreferences) {
        val oldAlias = prefs.pinKeyAlias
        prefs.commitPinCredentials(
            salt = "",
            hash = "",
            enabled = false,
            version = SnakePreferences.PIN_VERSION_NONE,
            keyAlias = ""
        )
        if (oldAlias.isNotEmpty()) {
            deleteKeystoreAlias(oldAlias)
        }
        PinLockCoordinator.onPinCleared()
    }

    private fun clearLockout(prefs: SnakePreferences) {
        if (prefs.pinLockoutUntilMs != 0L || prefs.pinLockoutStartedAtMs != 0L) {
            prefs.pinLockoutStartedAtMs = 0L
            prefs.pinLockoutUntilMs = 0L
        }
    }

    sealed class VerifyResult {
        data object Success : VerifyResult()

        /**
         * @param lockoutRemainingMs > 0 when brute-force throttling is active (only used when
         * auto-wipe is disabled); the candidate was not evaluated.
         * @param keystoreError true when `v2` TEE HMAC failed due to Keystore unavailability or
         * permanent key loss (fails closed without incrementing brute-force counters).
         */
        data class Failed(
            val attemptsUsed: Int,
            val remaining: Int,
            val lockoutRemainingMs: Long = 0L,
            val keystoreError: Boolean = false,
            val keyPermanentlyLost: Boolean = false
        ) : VerifyResult()

        data object TriggerAutoWipe : VerifyResult()
    }

    /** Exponential back-off: 30s after the 5th failure, doubling per further failure, capped at 15 min. */
    internal fun lockoutDurationMs(failedAttempts: Int): Long {
        if (failedAttempts < MAX_FAILED_ATTEMPTS) return 0L
        val shift = (failedAttempts - MAX_FAILED_ATTEMPTS).coerceAtMost(5)
        return (LOCKOUT_BASE_MS shl shift).coerceAtMost(LOCKOUT_MAX_MS)
    }

    /**
     * Remaining lockout in ms. Robust against the wall clock being rolled back: if "now" is earlier
     * than the recorded start, the full original duration is still owed.
     */
    internal fun lockoutRemainingMs(prefs: SnakePreferences, nowMs: Long): Long {
        val until = prefs.pinLockoutUntilMs
        if (until <= 0L) return 0L
        val started = prefs.pinLockoutStartedAtMs
        if (started > 0L && nowMs < started) return (until - started).coerceAtLeast(0L)
        return (until - nowMs).coerceAtLeast(0L)
    }

    private fun failClosedWithoutAttempt(
        prefs: SnakePreferences,
        keystoreError: Boolean,
        keyPermanentlyLost: Boolean = false
    ): VerifyResult.Failed {
        val attempts = prefs.pinFailedAttempts
        return VerifyResult.Failed(
            attemptsUsed = attempts,
            remaining = (MAX_FAILED_ATTEMPTS - attempts).coerceAtLeast(0),
            lockoutRemainingMs = 0L,
            keystoreError = keystoreError,
            keyPermanentlyLost = keyPermanentlyLost
        )
    }

    private fun upgradeV1ToV2IfHardwareAvailable(
        prefs: SnakePreferences,
        saltBase64: String,
        saltBytes: ByteArray,
        kdfOut: ByteArray
    ) {
        val oldAlias = prefs.pinKeyAlias
        var newAlias: String? = null
        try {
            newAlias = hardwareKeystoreProvider.createHardwareHmacKey() ?: return
            when (val hmac = hardwareKeystoreProvider.computeHmacTag(newAlias, saltBytes, kdfOut)) {
                is HmacTagResult.Success -> {
                    val tagBase64 = try {
                        Base64.encodeToString(hmac.tag, Base64.NO_WRAP)
                    } finally {
                        hmac.tag.fill(0)
                    }
                    prefs.commitMigratedPinCredentials(
                        salt = saltBase64,
                        hash = tagBase64,
                        version = SnakePreferences.PIN_VERSION_V2_TEE_PEPPER,
                        keyAlias = newAlias
                    )
                    if (oldAlias.isNotEmpty() && oldAlias != newAlias) {
                        deleteKeystoreAlias(oldAlias)
                    }
                }
                else -> {
                    hardwareKeystoreProvider.deleteKey(newAlias)
                }
            }
        } catch (_: Throwable) {
            if (newAlias != null && newAlias != prefs.pinKeyAlias) {
                hardwareKeystoreProvider.deleteKey(newAlias)
            }
        }
    }

    @Synchronized
    fun verifyPin(prefs: SnakePreferences, candidate: String): VerifyResult {
        if (!prefs.pinLockEnabled) {
            return VerifyResult.Success
        }
        if (prefs.pinAutoWipeOnFail && prefs.pinAutoWipePending) {
            return VerifyResult.TriggerAutoWipe
        }
        if (!prefs.pinAutoWipeOnFail) {
            val locked = lockoutRemainingMs(prefs, System.currentTimeMillis())
            if (locked > 0L) {
                return VerifyResult.Failed(
                    attemptsUsed = prefs.pinFailedAttempts,
                    remaining = 0,
                    lockoutRemainingMs = locked
                )
            }
        }
        val version = prefs.pinVersion
        val saltBase64 = prefs.pinSalt
        val expectedBase64 = prefs.pinHash
        val keyAlias = prefs.pinKeyAlias
        if (saltBase64.isEmpty() || expectedBase64.isEmpty() ||
            (version != SnakePreferences.PIN_VERSION_V1_PBKDF2 &&
                version != SnakePreferences.PIN_VERSION_V2_TEE_PEPPER) ||
            (version == SnakePreferences.PIN_VERSION_V2_TEE_PEPPER && keyAlias.isEmpty())
        ) {
            return failClosedWithoutAttempt(prefs, keystoreError = false)
        }

        val saltBytes = runCatching { Base64.decode(saltBase64, Base64.NO_WRAP) }.getOrNull()
            ?: return failClosedWithoutAttempt(prefs, keystoreError = false)
        val expectedBytes = runCatching { Base64.decode(expectedBase64, Base64.NO_WRAP) }.getOrNull()
            ?: return failClosedWithoutAttempt(prefs, keystoreError = false)

        val chars = candidate.toCharArray()
        val kdfOut = try {
            pbkdf2HmacSha256(chars, saltBytes, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        } catch (_: Throwable) {
            expectedBytes.fill(0)
            return failClosedWithoutAttempt(prefs, keystoreError = false)
        } finally {
            chars.fill('\u0000')
        }

        val matches = try {
            when (version) {
                SnakePreferences.PIN_VERSION_V1_PBKDF2 -> {
                    val ok = MessageDigest.isEqual(expectedBytes, kdfOut)
                    if (ok) {
                        upgradeV1ToV2IfHardwareAvailable(prefs, saltBase64, saltBytes, kdfOut)
                    }
                    ok
                }
                SnakePreferences.PIN_VERSION_V2_TEE_PEPPER -> {
                    when (val hmac = hardwareKeystoreProvider.computeHmacTag(keyAlias, saltBytes, kdfOut)) {
                        is HmacTagResult.Success -> try {
                            MessageDigest.isEqual(expectedBytes, hmac.tag)
                        } finally {
                            hmac.tag.fill(0)
                        }
                        HmacTagResult.PermanentKeyInvalidOrMissing -> {
                            return failClosedWithoutAttempt(prefs, keystoreError = true, keyPermanentlyLost = true)
                        }
                        HmacTagResult.TransientError -> {
                            return failClosedWithoutAttempt(prefs, keystoreError = true, keyPermanentlyLost = false)
                        }
                    }
                }
                else -> return failClosedWithoutAttempt(prefs, keystoreError = false)
            }
        } finally {
            expectedBytes.fill(0)
            kdfOut.fill(0)
        }

        if (matches) {
            if (prefs.pinFailedAttempts != 0) {
                prefs.pinFailedAttempts = 0
            }
            clearLockout(prefs)
            return VerifyResult.Success
        }

        val nextAttempts = prefs.pinFailedAttempts + 1
        if (prefs.pinAutoWipeOnFail && nextAttempts >= MAX_FAILED_ATTEMPTS) {
            prefs.commitAutoWipePending()
            return VerifyResult.TriggerAutoWipe
        }
        prefs.pinFailedAttempts = nextAttempts
        val remaining = (MAX_FAILED_ATTEMPTS - nextAttempts).coerceAtLeast(0)
        var lockoutMs = 0L
        if (!prefs.pinAutoWipeOnFail) {
            lockoutMs = lockoutDurationMs(nextAttempts)
            if (lockoutMs > 0L) {
                val now = System.currentTimeMillis()
                prefs.pinLockoutStartedAtMs = now
                prefs.pinLockoutUntilMs = now + lockoutMs
            }
        }
        return VerifyResult.Failed(
            attemptsUsed = nextAttempts,
            remaining = remaining,
            lockoutRemainingMs = lockoutMs
        )
    }

    fun verifyPinAsync(
        context: Context,
        prefs: SnakePreferences,
        candidate: String,
        executor: Executor = defaultWorkExecutor(),
        onResult: (VerifyResult) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        val isSync = isRobolectric() && executor === defaultWorkExecutor()
        executor.execute {
            val result = verifyPin(prefs, candidate)
            if (isSync && Looper.myLooper() == Looper.getMainLooper()) {
                onResult(result)
            } else {
                mainHandler.post { onResult(result) }
            }
        }
    }

    fun shouldLockAfterBackground(
        pinEnabled: Boolean,
        lastBackgroundAtMs: Long,
        nowMs: Long
    ): Boolean {
        if (!pinEnabled) return false
        if (lastBackgroundAtMs <= 0L) return true
        val elapsed = nowMs - lastBackgroundAtMs
        if (elapsed < 0L) return true
        return elapsed >= BACKGROUND_LOCK_TIMEOUT_MS
    }
}
