package com.hank.flow.open.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat

enum class PermissionItem { Microphone, Notifications, Overlay, Accessibility }

data class PermissionStatus(val item: PermissionItem, val granted: Boolean)

object PermissionChecks {

    fun all(context: Context): List<PermissionStatus> = listOf(
        check(context, PermissionItem.Microphone),
        check(context, PermissionItem.Notifications),
        check(context, PermissionItem.Overlay),
        check(context, PermissionItem.Accessibility),
    )

    fun check(context: Context, item: PermissionItem): PermissionStatus = when (item) {
        PermissionItem.Microphone -> PermissionStatus(item, hasRuntimePermission(context, Manifest.permission.RECORD_AUDIO))
        PermissionItem.Notifications -> PermissionStatus(item, hasNotificationsPermission(context))
        PermissionItem.Overlay -> PermissionStatus(item, Settings.canDrawOverlays(context))
        PermissionItem.Accessibility -> PermissionStatus(item, isAccessibilityServiceEnabled(context, accessibilityServiceFqn(context)))
    }

    private fun hasRuntimePermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationsPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasRuntimePermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } else true

    private fun isAccessibilityServiceEnabled(context: Context, fqn: String): Boolean {
        val enabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1
        if (!enabled) return false
        val services = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(services) }
        return splitter.any { it.equals(fqn, ignoreCase = true) }
    }

    // `<applicationId>/<服务类 FQN>` 格式，前半段必须等于实际 applicationId；
    // debug 构建带 .debug 后缀，因此用 packageName 而不是写死。
    private fun accessibilityServiceFqn(context: Context): String =
        "${context.packageName}/com.hank.flow.open.service.FlowAccessibilityService"
}
