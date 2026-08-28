package com.example.nsfwshield.security

import android.content.Context
import com.example.nsfwshield.Prefs
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Stores the user's PIN using PBKDF2WithHmacSHA256 with a random salt and a
 * deliberately high iteration count. The PIN is never stored in plaintext and
 * never held in memory longer than needed.
 *
 * Verification uses a constant-time comparison to reduce timing side-channels.
 */
object PinManager {

    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16

    fun isPinSet(ctx: Context): Boolean =
        Prefs.pinHash(ctx) != null && Prefs.pinSalt(ctx) != null && Prefs.pinIterations(ctx) > 0

    /**
     * Sets a new PIN. Call only after the caller has confirmed the PIN twice.
     * Returns true on success, false if a PIN already exists and [overwrite] is false.
     */
    fun setPin(ctx: Context, pin: CharArray, overwrite: Boolean = false): Boolean {
        if (!overwrite && isPinSet(ctx)) return false
        require(pin.isNotEmpty()) { "PIN must not be empty" }

        val salt = ByteArray(SALT_LENGTH_BYTES)
        SecureRandom().nextBytes(salt)

        val hash = derive(pin, salt, ITERATIONS)

        val hashB64 = android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP)
        val saltB64 = android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP)
        Prefs.setPinData(ctx, hashB64, saltB64, ITERATIONS)

        pin.fill(0.toChar())
        return true
    }

    /**
     * Verifies a PIN against the stored hash. Returns true on match.
     * If no PIN is set, returns false.
     */
    fun verify(ctx: Context, pin: CharArray): Boolean {
        val salt = Prefs.pinSalt(ctx) ?: return false
        val storedHash = Prefs.pinHash(ctx) ?: return false
        val iterations = Prefs.pinIterations(ctx).takeIf { it > 0 } ?: return false

        val storedBytes = android.util.Base64.decode(storedHash, android.util.Base64.NO_WRAP)
        val candidate = derive(pin, salt, iterations)
        pin.fill(0.toChar())

        return constantTimeEquals(storedBytes, candidate)
    }

    /**
     * Changes the PIN. Requires the current PIN to be verified first by the caller.
     */
    fun changePin(ctx: Context, currentPin: CharArray, newPin: CharArray): Boolean {
        if (!verify(ctx, currentPin)) return false
        return setPin(ctx, newPin, overwrite = true)
    }

    private fun derive(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin, salt, iterations, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }
}
