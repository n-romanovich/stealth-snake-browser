package com.nromanovich.stealthsnake

import android.os.Build

/**
 * JNI bridge to native C11 / ARMv8-Crypto PBKDF2-HMAC-SHA256 (`libsnake_pin_kdf.so`).
 * Kept isolated under `-keep` R8 rules so JNI symbol resolution stays intact in release builds.
 */
internal object NativePinKdf {

    @Volatile
    private var nativeLoaded: Boolean = false

    @Volatile
    private var nativeLoadFailure: Throwable? = null

    init {
        if (!Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
            try {
                System.loadLibrary("snake_pin_kdf")
                nativeLoaded = true
            } catch (t: Throwable) {
                nativeLoadFailure = t
            }
        }
    }

    fun isAvailable(): Boolean = nativeLoaded

    fun derivePbkdf2HmacSha256(
        passwordUtf8: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBytes: Int
    ): ByteArray {
        check(nativeLoaded) {
            "Native PIN KDF library unavailable" +
                (nativeLoadFailure?.message?.let { ": $it" } ?: "")
        }
        return nativeDerivePbkdf2HmacSha256(passwordUtf8, salt, iterations, keyLengthBytes)
            ?: error("Native PIN KDF returned null")
    }

    @JvmStatic
    private external fun nativeDerivePbkdf2HmacSha256(
        passwordUtf8: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBytes: Int
    ): ByteArray?
}
