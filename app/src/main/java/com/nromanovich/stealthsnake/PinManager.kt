package com.nromanovich.stealthsnake

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Handles PBKDF2-HMAC-SHA256 PIN hashing, constant-time verification,
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
    private const val PBKDF2_ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16

    private val backgroundExecutor: Executor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "snake-pin-kdf").apply { isDaemon = true }
        }
    }

    private fun defaultWorkExecutor(): Executor =
        if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
            Executor { it.run() }
        } else {
            backgroundExecutor
        }

    fun isValidPinFormat(pin: String): Boolean =
        pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it.isDigit() }

    fun generateSalt(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(SALT_BYTES)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun hashPin(pin: String, saltBase64: String): String {
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            val hash = factory.generateSecret(spec).encoded
            Base64.encodeToString(hash, Base64.NO_WRAP)
        } finally {
            spec.clearPassword()
        }
    }

    fun setPin(prefs: SnakePreferences, pin: String) {
        require(isValidPinFormat(pin)) { "PIN must be $MIN_PIN_LENGTH-$MAX_PIN_LENGTH digits" }
        val salt = generateSalt()
        val hash = hashPin(pin, salt)
        prefs.pinSalt = salt
        prefs.pinHash = hash
        prefs.pinFailedAttempts = 0
        clearLockout(prefs)
        prefs.pinLockEnabled = true
        PinLockCoordinator.onPinConfigured()
    }

    fun setPinAsync(
        context: Context,
        prefs: SnakePreferences,
        pin: String,
        executor: Executor = defaultWorkExecutor(),
        onComplete: (Result<Unit>) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        val isSync = Build.FINGERPRINT.equals("robolectric", ignoreCase = true) && executor === defaultWorkExecutor()
        val deliver: (Result<Unit>) -> Unit = { result ->
            if (isSync && Looper.myLooper() == Looper.getMainLooper()) {
                onComplete(result)
            } else {
                mainHandler.post { onComplete(result) }
            }
        }
        executor.execute {
            val res = runCatching {
                setPin(prefs, pin)
                PinLockCoordinator.ensureRegistered(context)
            }
            deliver(res)
        }
    }

    fun clearPin(prefs: SnakePreferences) {
        prefs.pinLockEnabled = false
        prefs.pinSalt = ""
        prefs.pinHash = ""
        prefs.pinFailedAttempts = 0
        clearLockout(prefs)
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
         */
        data class Failed(
            val attemptsUsed: Int,
            val remaining: Int,
            val lockoutRemainingMs: Long = 0L
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

    @Synchronized
    fun verifyPin(prefs: SnakePreferences, candidate: String): VerifyResult {
        if (!prefs.pinLockEnabled) {
            return VerifyResult.Success
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
        if (prefs.pinSalt.isEmpty() || prefs.pinHash.isEmpty()) {
            val attempts = prefs.pinFailedAttempts
            return VerifyResult.Failed(
                attemptsUsed = attempts,
                remaining = (MAX_FAILED_ATTEMPTS - attempts).coerceAtLeast(0)
            )
        }
        val expectedBytes = runCatching { Base64.decode(prefs.pinHash, Base64.NO_WRAP) }.getOrNull()
        val actualBytes = runCatching {
            Base64.decode(hashPin(candidate, prefs.pinSalt), Base64.NO_WRAP)
        }.getOrNull()

        val matches = expectedBytes != null && actualBytes != null &&
            MessageDigest.isEqual(expectedBytes, actualBytes)
        if (matches) {
            prefs.pinFailedAttempts = 0
            clearLockout(prefs)
            return VerifyResult.Success
        }

        val nextAttempts = prefs.pinFailedAttempts + 1
        prefs.pinFailedAttempts = nextAttempts
        if (prefs.pinAutoWipeOnFail && nextAttempts >= MAX_FAILED_ATTEMPTS) {
            prefs.pinFailedAttempts = 0
            return VerifyResult.TriggerAutoWipe
        }
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
        val isSync = Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
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
