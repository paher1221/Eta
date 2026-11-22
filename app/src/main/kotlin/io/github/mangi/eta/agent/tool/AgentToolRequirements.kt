package io.github.mangi.eta.agent.tool

import org.json.JSONArray
import org.json.JSONObject
import io.github.mangi.eta.agent.model.AgentTerminalToolCatalog
import io.github.mangi.eta.agent.model.AgentFileToolCatalog

internal enum class ToolSystemAccess { NONE, NOTIFICATIONS, USAGE, LOCATION, CALENDAR_READ, CALENDAR_WRITE }

internal data class LocalToolRequirement(
    val systemAccess: ToolSystemAccess = ToolSystemAccess.NONE,
    val gui: Boolean = false,
)

/** 展示、模型目录与执行边界共同使用的本地工具能力合同。未登记的工具不能发布。 */
internal object AgentToolRequirements {
    private val definitions = buildMap {
        fun register(vararg names: String) {
            names.forEach { name ->
                check(put(name, LocalToolRequirement()) == null) { "Duplicate tool: $name" }
            }
        }
        register(
            "get_current_context", "search_apps", "launch_app", "present_to_user", "open_uri", "browser_use", "web_search", "fetch_url",
            "set_clipboard", "get_clipboard",
            "set_alarm", "set_timer", "device_status", "inspect_app", "media_control", "set_volume",
            "get_flashlight", "set_flashlight", "get_display_state", "get_sound_state", "set_do_not_disturb",
            "search_notification_history", "recent_app_activity", "app_usage_summary",
            "get_current_location", "get_device_environment", "memory_get", "memory_write",
            "character_memory_get", "character_memory_write",
            "skills_list", "skills_read", "skills_read_resource", "skills_list_curated",
            "skills_inspect_github", "skills_install_from_github",
            "generate_image",
        )
        register(
            "network_info", "recent_notifications",
            "terminal", "terminal_job", "terminal_daemon", "read_file",
            "write_file", "list_directory", "read_image", "edit_file", "stat_file", "glob_files", "grep_files",
        )
        listOf(
            "launch_app", "present_to_user", "open_uri", "set_clipboard", "get_clipboard",
        ).forEach { name -> put(name, getValue(name).copy(gui = true)) }
        mapOf(
            "recent_notifications" to ToolSystemAccess.NOTIFICATIONS,
            "search_notification_history" to ToolSystemAccess.NOTIFICATIONS,
            "recent_app_activity" to ToolSystemAccess.USAGE,
            "app_usage_summary" to ToolSystemAccess.USAGE,
            "get_current_location" to ToolSystemAccess.LOCATION,
        ).forEach { (name, access) -> put(name, getValue(name).copy(systemAccess = access)) }
    }

    val toolNames: Set<String> get() = definitions.keys

    fun find(name: String): LocalToolRequirement? = definitions[name]

    fun requiresGuiControl(name: String?): Boolean = name?.let(::find)?.gui == true

    fun hasForegroundIntentFallback(name: String?): Boolean = name == "set_alarm" || name == "set_timer"

    /** 复制后收窄，不能修改下一轮或另一个 run 共用的原始 Schema。 */
    fun project(tools: JSONArray): JSONArray = JSONArray().also { result ->
        for (index in 0 until tools.length()) {
            val original = tools.getJSONObject(index)
            val function = original.getJSONObject("function")
            if (find(function.getString("name")) == null) {
                throw IllegalArgumentException("未登记的工具不能进入模型目录：${function.getString("name")}")
            }
            val tool = JSONObject(original.toString())
            projectUnprivileged(tool.getJSONObject("function"))
            result.put(tool)
        }
    }

    private fun projectUnprivileged(function: JSONObject) {
        val properties = function.getJSONObject("parameters").optJSONObject("properties")
        if (function.getString("name") in AgentFileToolCatalog.names + AgentTerminalToolCatalog.names) {
            properties?.optJSONObject("identity")?.put("enum", JSONArray().put("user"))
                ?.put("description", "显式选择宿主身份；当前可用 user（Eta App UID）。省略时沿用本次运行的默认身份。")
        }
        when (function.getString("name")) {
            "read_image" -> properties?.getJSONObject("path")?.put("description",
                "当前应用有权读取的绝对图片路径、file URI 或已授权的 content URI。")
        }
    }
}
