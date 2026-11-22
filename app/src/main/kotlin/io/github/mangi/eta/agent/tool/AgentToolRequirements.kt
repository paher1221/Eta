package io.github.mangi.eta.agent.tool

import org.json.JSONArray
import org.json.JSONObject
import io.github.mangi.eta.agent.model.AgentTerminalToolCatalog
import io.github.mangi.eta.agent.model.AgentFileToolCatalog

internal enum class ToolSystemAccess { NONE }

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
            "browser_use", "web_search", "fetch_url",
            "set_clipboard", "get_clipboard",
            "memory_get", "memory_write",
            "character_memory_get", "character_memory_write",
            "skills_list", "skills_read", "skills_read_resource", "skills_list_curated",
            "skills_inspect_github", "skills_install_from_github",
            "generate_image",
        )
        register(
            "terminal", "terminal_job", "terminal_daemon", "read_file",
            "write_file", "list_directory", "read_image", "edit_file", "stat_file", "glob_files", "grep_files",
        )
        listOf(
            "set_clipboard", "get_clipboard",
        ).forEach { name -> put(name, getValue(name).copy(gui = true)) }
    }

    val toolNames: Set<String> get() = definitions.keys

    fun find(name: String): LocalToolRequirement? = definitions[name]

    fun requiresGuiControl(name: String?): Boolean = name?.let(::find)?.gui == true

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
