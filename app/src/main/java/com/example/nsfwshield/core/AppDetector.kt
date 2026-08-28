package com.example.nsfwshield.core

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.provider.Settings

/**
 * Detects the package name of the app currently in the foreground.
 *
 * On Android 10 (API 29) the standard, non-invasive approach is UsageStatsManager,
 * which requires the user to grant PACKAGE_USAGE_STATS (a protected permission the
 * user enables manually in Settings). No AccessibilityService is used.
 */
object AppDetector {

    fun hasUsageAccess(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            ctx.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Returns the foreground package name, or null if it cannot be determined
     * (no usage access, or no recent usage stats).
     */
    fun foregroundPackage(ctx: Context): String? {
        if (!hasUsageAccess(ctx)) return null
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        // Look back 10 seconds — enough to see the current foreground app.
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 10_000L, now)
            ?: return null
        return stats.maxByOrNull { it.lastTimeUsed }?.packageName
    }

    /**
     * Opens the system Usage Access settings page so the user can grant access.
     */
    fun openUsageAccessSettings(ctx: Context) {
        val intent = android.content.Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }
}
