package io.github.mangi.eta.config

import android.content.Context
import android.content.SharedPreferences
import io.github.mangi.eta.agent.model.AgentFileToolCatalog
import io.github.mangi.eta.agent.model.AgentTerminalToolCatalog
import io.github.mangi.eta.data.model.ReasoningEffort
import org.json.JSONArray

/**
 * 模块配置中枢。
 *
 * - Eta Runtime 自己消费的开关保存在 App 私有配置中。
 * - Hook 进程相关配置已随 Xposed 模块能力移除，仅保留本地配置。
 */
internal object Prefs {

    /** 远程配置组名，保留常量以兼容历史数据。 */
    const val GROUP = "eta_prefs"

    private const val LOCAL_AGENT_GROUP = "eta_agent_preferences"

    /** 所有功能开关 key。默认值按功能风险独立定义。 */
    object Keys {
        const val AGENT_TERMINAL_TOOLS = "agent_terminal_tools"
        const val AGENT_BROWSER_TOOLS = "agent_browser_tools"
        const val AGENT_DEVICE_DIRECT_TOOLS = "agent_device_direct_tools"
        const val AGENT_DEVICE_SENSITIVE_READ_TOOLS = "agent_device_sensitive_read_tools"
        const val AGENT_DEVICE_SENSITIVE_ACTION_TOOLS = "agent_device_sensitive_action_tools"
        const val AGENT_THINKING_ENABLED = "agent_thinking_enabled"
        const val AGENT_DEFAULT_REASONING_EFFORT = "agent_default_reasoning_effort"
        const val AGENT_AUTO_COMPACTION_ENABLED = "agent_auto_compaction_enabled"
        const val AGENT_SUBAGENT_ENABLED = "agent_subagent_enabled"
        const val AGENT_KEEP_SCREEN_ON_ENABLED = "agent_keep_screen_on_enabled"
        const val AGENT_RUNTIME_CONFIG_JSON = "agent_runtime_config_json"
        const val AGENT_GLOBAL_SYSTEM_PROMPT = "agent_global_system_prompt"
        const val AGENT_DISABLED_TOOLS_JSON = "agent_disabled_tools_json"

        /** 全部布尔开关及其默认值。 */
        val BOOLEAN_DEFAULTS: Map<String, Boolean> = mapOf(
            AGENT_TERMINAL_TOOLS to true,
            AGENT_BROWSER_TOOLS to true,
            AGENT_DEVICE_DIRECT_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to true,
            AGENT_THINKING_ENABLED to true,
            AGENT_AUTO_COMPACTION_ENABLED to true,
            AGENT_SUBAGENT_ENABLED to true,
            AGENT_KEEP_SCREEN_ON_ENABLED to true,
        )

        /** 由 Eta Runtime 最终裁决的开关。 */
        val LOCAL_AGENT_KEYS: Set<String> = setOf(
            AGENT_TERMINAL_TOOLS,
            AGENT_BROWSER_TOOLS,
            AGENT_DEVICE_DIRECT_TOOLS,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
            AGENT_THINKING_ENABLED,
            AGENT_AUTO_COMPACTION_ENABLED,
            AGENT_SUBAGENT_ENABLED,
            AGENT_KEEP_SCREEN_ON_ENABLED,
        )
    }

    @Volatile
    private var localAgent: SharedPreferences? = null

    /** App 进程调用：初始化 Agent 配置。 */
    fun initLocal(context: Context) {
        if (localAgent == null) {
            synchronized(this) {
                if (localAgent == null) {
                    localAgent = context.applicationContext.getSharedPreferences(
                        LOCAL_AGENT_GROUP,
                        Context.MODE_PRIVATE,
                    ).also(::migrateLegacyGroupSwitchesIfNeeded)
                }
            }
        }
    }

    /**
     * 读取布尔开关。配置不可用时回退各功能自己的默认值；
     * 默认值与设置页展示保持一致。
     */
    fun isEnabled(key: String): Boolean {
        val default = Keys.BOOLEAN_DEFAULTS[key] ?: true
        return localAgent?.getBoolean(key, default) ?: default
    }

    fun getString(key: String): String {
        return localAgent?.getString(key, "") ?: ""
    }

    fun defaultReasoningEffort(
        preferences: SharedPreferences? = localAgent,
    ): ReasoningEffort {
        val raw = runCatching {
            preferences?.getString(Keys.AGENT_DEFAULT_REASONING_EFFORT, null)
        }.getOrNull()
        val parsed = ReasoningEffort.fromWireValue(raw)
        return if (parsed != null && parsed != ReasoningEffort.OFF) parsed else ReasoningEffort.DEFAULT
    }

    /** Eta 设置页与 Runtime 使用的本地 Agent 配置。 */
    fun localAgentPreferences(): SharedPreferences? = localAgent

    /** 将运行时工具名映射为工具列表中的主开关标识，确保同一能力的不同动作变体受同一开关约束。 */
    fun canonicalToolSwitchId(toolName: String): String = when (toolName) {
        "tap" -> "tap_area"
        "long_press_element" -> "long_press"
        "scroll_element" -> "scroll"
        "input_text", "replace_text", "clear_text", "paste_text" -> "type_text"
        "wait", "wait_for_package" -> "wait_for_text"
        "browser_read", "browser_interact", "browser_screenshot" -> "browser_use"
        else -> toolName
    }

    fun disabledTools(preferences: SharedPreferences? = localAgent): Set<String> {
        val raw = runCatching {
            preferences?.getString(Keys.AGENT_DISABLED_TOOLS_JSON, null)
        }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            buildSet(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.optString(index).trim()
                    if (item.isNotEmpty()) add(item)
                }
            }
        }.getOrDefault(emptySet())
    }

    fun isToolEnabledInSet(toolName: String, disabled: Set<String>): Boolean {
        if (disabled.isEmpty()) return true
        val canonical = canonicalToolSwitchId(toolName)
        return toolName !in disabled && canonical !in disabled
    }

    fun isToolEnabled(
        toolName: String,
        preferences: SharedPreferences? = localAgent,
    ): Boolean = isToolEnabledInSet(toolName, disabledTools(preferences))

    fun setToolEnabled(
        toolName: String,
        enabled: Boolean,
    ): Boolean = setToolsEnabled(listOf(toolName), enabled)

    fun setToolsEnabled(
        toolNames: Collection<String>,
        enabled: Boolean,
    ): Boolean {
        val local = localAgent ?: return false
        val updated = disabledTools(local).toMutableSet()
        toolNames.forEach { rawName ->
            val canonical = canonicalToolSwitchId(rawName)
            if (enabled) {
                updated -= rawName
                updated -= canonical
            } else {
                updated += canonical
            }
        }
        val encoded = JSONArray(updated.sorted()).toString()
        val editor = local.edit().putString(Keys.AGENT_DISABLED_TOOLS_JSON, encoded)
        LEGACY_TOOL_GROUP_KEYS.forEach { key ->
            if (local.contains(key) && !local.getBoolean(key, true)) {
                editor.putBoolean(key, true)
            }
        }
        return runCatching { editor.commit() }.getOrDefault(false)
    }

    private fun migrateLegacyGroupSwitchesIfNeeded(local: SharedPreferences) {
        if (local.contains(Keys.AGENT_DISABLED_TOOLS_JSON)) return
        val disabledLegacyGroups = LEGACY_TOOL_GROUP_KEYS.filter { key ->
            local.contains(key) && !local.getBoolean(key, true)
        }
        if (disabledLegacyGroups.isEmpty()) return
        val migratedDisabled = linkedSetOf<String>()
        if (Keys.AGENT_BROWSER_TOOLS in disabledLegacyGroups) {
            migratedDisabled += listOf("web_search", "fetch_url", "browser_use")
        }
        if (Keys.AGENT_TERMINAL_TOOLS in disabledLegacyGroups) {
            migratedDisabled += AgentTerminalToolCatalog.names
            migratedDisabled += AgentFileToolCatalog.names
            migratedDisabled += "read_image"
        }
        if (Keys.AGENT_DEVICE_DIRECT_TOOLS in disabledLegacyGroups) {
            migratedDisabled += listOf(
                "set_alarm", "set_timer", "device_status", "inspect_app",
                "network_info", "top_memory_apps", "top_storage_apps",
                "media_control", "set_volume",
            )
        }
        if (Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS in disabledLegacyGroups) {
            migratedDisabled += listOf(
                "read_personal_item", "summarize_bills", "get_setting", "wifi_credentials",
                "recent_notifications", "search_notification_history", "recent_app_activity",
                "app_usage_summary", "get_current_location", "get_device_environment",
                "list_alarms", "list_active_timers", "search_clipboard_history",
                "get_health_summary", "read_sms_code", "get_logcat", "search_media",
                "search_audio", "search_recordings", "search_files", "search_calendar_events",
                "search_contacts", "search_call_history", "search_messages", "search_downloads",
                "search_notes", "search_coloros_recordings", "search_recording_summaries",
                "search_system_memories", "search_saved_places", "search_personal_orders",
                "search_qq_chat_images", "search_wechat_chat_images",
            )
        }
        if (Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS in disabledLegacyGroups) {
            migratedDisabled += listOf("set_setting", "set_device_state", "app_state_control")
        }
        val editor = local.edit()
            .putString(Keys.AGENT_DISABLED_TOOLS_JSON, JSONArray(migratedDisabled.sorted()).toString())
        disabledLegacyGroups.forEach { key -> editor.putBoolean(key, true) }
        runCatching { editor.commit() }
    }

    private val LEGACY_TOOL_GROUP_KEYS = listOf(
        Keys.AGENT_TERMINAL_TOOLS,
        Keys.AGENT_BROWSER_TOOLS,
        Keys.AGENT_DEVICE_DIRECT_TOOLS,
        Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
        Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
    )
}
