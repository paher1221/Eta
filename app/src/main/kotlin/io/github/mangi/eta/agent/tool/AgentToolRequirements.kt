package io.github.mangi.eta.agent.tool

import org.json.JSONArray
import org.json.JSONObject
import io.github.mangi.eta.agent.model.AgentTerminalToolCatalog
import io.github.mangi.eta.agent.model.AgentFileToolCatalog

internal enum class ToolSystemAccess { NONE, NOTIFICATIONS, USAGE, LOCATION, CALENDAR_READ, CALENDAR_WRITE }

internal data class LocalToolRequirement(
    val accessibility: Boolean = false,
    val systemAccess: ToolSystemAccess = ToolSystemAccess.NONE,
    val gui: Boolean = false,
    val freshObservation: Boolean = false,
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
            "observe_screen", "release_gui", "tap", "tap_area", "tap_element", "long_press",
            "long_press_element", "swipe", "scroll", "scroll_element", "type_text", "input_text",
            "replace_text", "clear_text", "set_clipboard", "get_clipboard", "paste_text",
            "wait", "wait_for_text", "wait_for_package", "open_system_panel",
            "set_alarm", "set_timer", "device_status", "inspect_app", "media_control", "set_volume",
            "search_notification_history", "recent_app_activity", "app_usage_summary",
            "get_current_location", "get_device_environment", "memory_get", "memory_write",
            "character_memory_get", "character_memory_write",
            "skills_list", "skills_read", "skills_read_resource", "skills_list_curated",
            "skills_inspect_github", "skills_install_from_github",
            "generate_image",
        )
        register(
            "press_key", "network_info", "recent_notifications",
            "terminal", "terminal_job", "terminal_daemon", "read_file",
            "write_file", "list_directory", "read_image", "edit_file", "stat_file", "glob_files", "grep_files",
        )
        listOf(
            "observe_screen", "tap", "tap_area", "tap_element", "long_press",
            "long_press_element", "swipe", "scroll", "scroll_element", "type_text", "input_text",
            "replace_text", "clear_text", "paste_text", "press_key", "open_system_panel",
            "wait_for_text", "wait_for_package",
        ).forEach { name -> put(name, getValue(name).copy(accessibility = true)) }
        listOf(
            "observe_screen", "launch_app", "present_to_user", "open_uri", "open_system_panel",
            "wait", "wait_for_text", "wait_for_package", "set_clipboard", "get_clipboard",
        ).forEach { name -> put(name, getValue(name).copy(gui = true)) }
        listOf(
            "tap", "tap_area", "tap_element", "long_press", "long_press_element", "swipe",
            "scroll", "scroll_element", "type_text", "input_text", "replace_text", "clear_text",
            "paste_text", "press_key",
        ).forEach { name -> put(name, getValue(name).copy(gui = true, freshObservation = true)) }
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

    fun requiresAccessibility(name: String): Boolean = find(name)?.accessibility == true

    fun requiresGuiControl(name: String?): Boolean = name?.let(::find)?.gui == true

    fun requiresFreshObservation(name: String): Boolean = find(name)?.freshObservation == true

    fun hasForegroundIntentFallback(name: String?): Boolean = name == "set_alarm" || name == "set_timer"

    /** 复制后收窄，不能修改下一轮或另一个 run 共用的原始 Schema。 */
    fun project(tools: JSONArray): JSONArray = JSONArray().also { result ->
        for (index in 0 until tools.length()) {
            val original = tools.getJSONObject(index)
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
            "press_key" -> properties?.getJSONObject("button")?.let { button ->
                val values = button.getJSONArray("enum")
                button.put("enum", JSONArray().also { allowed ->
                    for (i in 0 until values.length()) {
                        val value = values.getString(i)
                        if (!value.equals("PASTE", ignoreCase = true)) allowed.put(value)
                    }
                })
                button.put("description", "无障碍支持的系统按键；输入文本请使用 type_text。")
            }
        }
    }
}
