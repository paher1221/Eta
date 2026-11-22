package io.github.mangi.eta.agent.tool

import android.content.Context
import android.os.Build
import java.util.Locale
import org.json.JSONArray

/** 每轮冻结的运行条件；不包含用户开关，也不触发授权请求。 */
internal data class AgentToolCapabilities(
    // Root 相关能力已整体移除，恒为 false；保留字段以兼容提示词与工具目录的既有签名。
    val rootAvailable: Boolean = false,
    val hotspotAvailable: Boolean = true,
    val nfcAvailable: Boolean = true,
    val nightLightAvailable: Boolean = true,
) {
    fun unavailableCode(name: String): String? {
        if (name in setOf("get_hotspot", "set_hotspot") && !hotspotAvailable) return "DEVICE_UNSUPPORTED"
        val requirement = AgentToolRequirements.find(name) ?: return "UNKNOWN_TOOL"
        return when (requirement.systemAccess) {
            ToolSystemAccess.NONE -> null
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
                hotspotAvailable = Build.VERSION.SDK_INT >= 36,
                nfcAvailable = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC),
                nightLightAvailable = context.resources.getIdentifier("config_nightDisplayAvailable", "bool", "android").let { it != 0 && context.resources.getBoolean(it) },
            )
        }
    }
}
