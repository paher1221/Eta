package io.github.mangi.eta.agent.tool

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.mangi.eta.agent.device.AgentNotificationHistoryService
import java.util.Locale
import org.json.JSONArray

/** 每轮冻结的运行条件；不包含用户开关，也不触发授权请求。 */
internal data class AgentToolCapabilities(
    // Root 相关能力已整体移除，恒为 false；保留字段以兼容提示词与工具目录的既有签名。
    val rootAvailable: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val usageAllowed: Boolean = true,
    val locationAllowed: Boolean = true,
    val colorOs: Boolean = true,
    val calendarReadable: Boolean = true,
    val calendarWritable: Boolean = true,
    val flashlightAvailable: Boolean = true,
    val hotspotAvailable: Boolean = true,
    val nfcAvailable: Boolean = true,
    val nightLightAvailable: Boolean = true,
) {
    fun unavailableCode(name: String): String? {
        if (name in setOf("get_flashlight", "set_flashlight") && !flashlightAvailable) return "DEVICE_UNSUPPORTED"
        if (name in setOf("get_hotspot", "set_hotspot") && !hotspotAvailable) return "DEVICE_UNSUPPORTED"
        val requirement = AgentToolRequirements.find(name) ?: return "UNKNOWN_TOOL"
        return when (requirement.systemAccess) {
            ToolSystemAccess.CALENDAR_READ -> if (calendarReadable) null else "CALENDAR_PERMISSION_REQUIRED"
            ToolSystemAccess.CALENDAR_WRITE -> if (calendarWritable) null else "CALENDAR_PERMISSION_REQUIRED"
            ToolSystemAccess.NONE -> null
            ToolSystemAccess.NOTIFICATIONS -> if (notificationsAllowed) null else "NOTIFICATION_ACCESS_REQUIRED"
            ToolSystemAccess.USAGE -> if (usageAllowed) null else "APP_USAGE_ACCESS_REQUIRED"
            ToolSystemAccess.LOCATION -> if (locationAllowed) null else "LOCATION_PERMISSION_REQUIRED"
        }
    }

    fun project(tools: JSONArray): JSONArray {
        val projected = AgentToolRequirements.project(tools)
        return JSONArray().also { visible ->
            for (index in 0 until projected.length()) {
                val tool = projected.getJSONObject(index)
                val name = tool.getJSONObject("function").getString("name")
                if (unavailableCode(name) == null) {
                    visible.put(tool)
                }
            }
        }
    }

    companion object {
        fun isColorOsDevice(): Boolean = Build.MANUFACTURER.lowercase(Locale.ROOT) in
            setOf("oppo", "oneplus", "realme")

        fun capture(context: Context): AgentToolCapabilities {
            return AgentToolCapabilities(
                notificationsAllowed = AgentNotificationHistoryService.isEnabled(context),
                usageAllowed = AgentPersonalContextTools.hasUsageAccess(context),
                locationAllowed = context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED &&
                    (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED),
                colorOs = isColorOsDevice(),
                calendarReadable = io.github.mangi.eta.agent.device.CalendarPermissions.granted(context, false),
                calendarWritable = io.github.mangi.eta.agent.device.CalendarPermissions.granted(context, true),
                flashlightAvailable = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH),
                hotspotAvailable = Build.VERSION.SDK_INT >= 36,
                nfcAvailable = context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC),
                nightLightAvailable = context.resources.getIdentifier("config_nightDisplayAvailable", "bool", "android").let { it != 0 && context.resources.getBoolean(it) },
            )
        }
    }
}
