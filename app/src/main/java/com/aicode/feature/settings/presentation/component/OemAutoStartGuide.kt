package com.aicode.feature.settings.presentation.component

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.aicode.core.util.FileLogger

/**
 * Vendor auto-start management navigation: Chinese ROMs (MIUI/EMUI/ColorOS/OriginOS/Flyme) block apps from auto-starting by default,
 * and background processes and foreground services are reclaimed by the system. Tapping "Auto-start management" on the settings page navigates to the corresponding vendor auto-start settings page,
 * and if the intent is unavailable on that ROM, it falls back to this app's details page (where the user can manually enable background running/auto-start).
 */
object OemAutoStartGuide {
    private const val TAG = "OemAutoStartGuide"

    private data class VendorEntry(val component: String, val keywords: List<String>)

    // Each vendor's auto-start management page (package/Activity). Existence is verified with resolveActivity before navigating.
    private val vendorEntries = listOf(
        VendorEntry(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            listOf("xiaomi", "redmi")
        ),
        VendorEntry(
            "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            listOf("huawei", "honor")
        ),
        VendorEntry(
            "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
            listOf("oppo", "realme", "oneplus")
        ),
        VendorEntry(
            "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            listOf("vivo", "iqoo")
        ),
        VendorEntry(
            "com.meizu.safe/com.meizu.safe.permission.SmartBGActivity",
            listOf("meizu")
        )
    )

    /** Tries to navigate to the vendor auto-start management page; falls back to the app details page when there is no match or navigation fails. */
    fun openAutoStartSettings(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val entry = vendorEntries.firstOrNull { vendor ->
            vendor.keywords.any { manufacturer.contains(it) }
        }
        if (entry != null && startResolved(context, entry.component)) return
        openAppDetails(context)
    }

    private fun startResolved(context: Context, component: String): Boolean {
        val (pkg, cls) = component.split("/", limit = 2)
        val intent = Intent().setComponent(ComponentName(pkg, cls))
        if (intent.resolveActivity(context.packageManager) == null) {
            FileLogger.w(TAG, "Auto-start settings page unavailable: $component")
            return false
        }
        return runCatching { context.startActivity(intent) }
            .onFailure { FileLogger.e(TAG, "Failed to navigate to auto-start settings: $component", it) }
            .isSuccess
    }

    /** Navigates to this app's system settings details page (permissions page). Reused by the permission reminder dialog. */
    internal fun openAppDetails(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
        runCatching { context.startActivity(intent) }
            .onFailure { FileLogger.e(TAG, "Failed to navigate to app details page", it) }
    }
}