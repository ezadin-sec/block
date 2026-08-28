package com.example.nsfwshield

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralised preferences access. Single responsibility: read/write the small set
 * of persistent flags the app needs. Values are primitives only.
 */
object Prefs {
    private const val NAME = "shield_prefs"
    private const val KEY_PROTECTION_ON = "protection_on"
    private const val KEY_SENSITIVITY = "sensitivity"
    private const val KEY_STRICT = "strict_mode"
    private const val KEY_TARGET_PACKAGES = "target_packages"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_PIN_ITERATIONS = "pin_iterations"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isProtectionOn(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_PROTECTION_ON, false)

    fun setProtectionOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_PROTECTION_ON, on).apply()
    }

    fun sensitivity(ctx: Context): Int =
        prefs(ctx).getInt(KEY_SENSITIVITY, SENSITIVITY_CONSERVATIVE)

    fun setSensitivity(ctx: Context, value: Int) {
        prefs(ctx).edit().putInt(KEY_SENSITIVITY, value).apply()
    }

    fun isStrict(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_STRICT, false)

    fun setStrict(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_STRICT, on).apply()
    }

    fun targetPackages(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_TARGET_PACKAGES, DEFAULT_TARGETS) ?: DEFAULT_TARGETS

    fun setTargetPackages(ctx: Context, pkgs: Set<String>) {
        prefs(ctx).edit().putStringSet(KEY_TARGET_PACKAGES, pkgs).apply()
    }

    internal fun pinHash(ctx: Context): String? =
        prefs(ctx).getString(KEY_PIN_HASH, null)

    internal fun pinSalt(ctx: Context): ByteArray? =
        prefs(ctx).getString(KEY_PIN_SALT, null)?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }

    internal fun pinIterations(ctx: Context): Int =
        prefs(ctx).getInt(KEY_PIN_ITERATIONS, 0)

    internal fun setPinData(ctx: Context, hashB64: String, saltB64: String, iterations: Int) {
        prefs(ctx).edit()
            .putString(KEY_PIN_HASH, hashB64)
            .putString(KEY_PIN_SALT, saltB64)
            .putInt(KEY_PIN_ITERATIONS, iterations)
            .apply()
    }

    internal fun clearPin(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_PIN_HASH)
            .remove(KEY_PIN_SALT)
            .remove(KEY_PIN_ITERATIONS)
            .apply()
    }

    const val SENSITIVITY_CONSERVATIVE = 0
    const val SENSITIVITY_BALANCED = 1
    const val SENSITIVITY_LESS = 2

    val DEFAULT_TARGETS: Set<String> = setOf(
        "com.twitter.android",      // X (formerly Twitter)
        "com.zhiliaoapp.musically", // TikTok (often present alongside)
        "org.telegram.messenger",   // Telegram official
        "org.telegram.plus",        // Telegram Plus
        "com.android.chrome",       // Chrome
    )
}
