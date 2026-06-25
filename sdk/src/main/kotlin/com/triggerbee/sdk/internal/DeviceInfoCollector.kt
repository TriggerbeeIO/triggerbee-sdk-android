package com.triggerbee.sdk.internal

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.triggerbee.sdk.BuildConfig
import com.triggerbee.sdk.models.DeviceInfo
import java.util.Locale
import java.util.TimeZone

/**
 * One-shot snapshot of the device, taken at [com.triggerbee.sdk.Triggerbee.init].
 *
 * Fields are static for the process lifetime (OS version, model, screen size don't change
 * mid-session), so we capture once and reuse on every outgoing request. Locale and time zone
 * *can* change at runtime but it's not worth the per-request cost — consumers who care can
 * re-init the SDK after a config-change.
 */
internal object DeviceInfoCollector {
    fun collect(context: Context): DeviceInfo {
        val metrics = context.resources.displayMetrics
        return DeviceInfo(
            platform = "android",
            type = detectType(context),
            osVersion = Build.VERSION.RELEASE.orEmpty(),
            osApiLevel = Build.VERSION.SDK_INT,
            sdkVersion = BuildConfig.SDK_VERSION,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            appVersion = readAppVersion(context),
            locale = Locale.getDefault().toLanguageTag(),
            timeZone = TimeZone.getDefault().id,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
        )
    }

    // Standard Android sw600dp threshold: phones < 600dp on the shortest side, tablets >= 600dp.
    // Matches the resource-qualifier convention used by Material Design and the system itself.
    private fun detectType(context: Context): String =
        if (context.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "mobile"

    private fun readAppVersion(context: Context): String? = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (_: PackageManager.NameNotFoundException) {
        // The app's own package always exists; this catch covers ROM-level oddities and
        // keeps DeviceInfo collection from failing init when versionName isn't readable.
        null
    }
}
