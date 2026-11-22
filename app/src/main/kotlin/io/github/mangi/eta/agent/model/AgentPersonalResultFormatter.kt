package io.github.mangi.eta.agent.model

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import org.json.JSONObject

/**
 * 记忆工具结果摘要。
 * 面向用户展示自然流畅的结论，不暴露私密正文，不堆砌底层字节数或行数等机器指标。
 */
internal object AgentPersonalResultFormatter {
    fun summarizeArguments(toolName: String, argumentsJson: String): String? = runCatching {
        val args = JSONObject(argumentsJson)
        val isCharacter = toolName.startsWith("character_")
        when (toolName) {
            "memory_get", "character_memory_get" -> {
                val hasQuery = args.optString("query").trim().isNotEmpty()
                when {
                    isCharacter && hasQuery ->
                        AppStrings.get(R.string.trace_personal_arg_search_story_memory, "检索剧情记忆")
                    isCharacter ->
                        AppStrings.get(R.string.trace_personal_arg_read_story_memory, "读取剧情记忆")
                    hasQuery ->
                        AppStrings.get(R.string.trace_personal_arg_search_memory, "检索记忆")
                    else ->
                        AppStrings.get(R.string.trace_personal_arg_read_memory, "读取记忆")
                }
            }
            "memory_write", "character_memory_write" -> {
                when (args.optString("mode")) {
                    "append" -> if (isCharacter) {
                        AppStrings.get(R.string.trace_personal_arg_append_story_memory, "追加剧情记忆")
                    } else {
                        AppStrings.get(R.string.trace_personal_arg_append_memory, "追加记忆")
                    }
                    "clear" -> if (isCharacter) {
                        AppStrings.get(R.string.trace_personal_arg_clear_story_memory, "清空剧情记忆")
                    } else {
                        AppStrings.get(R.string.trace_personal_arg_clear_memory, "清空记忆")
                    }
                    else -> if (isCharacter) {
                        AppStrings.get(R.string.trace_personal_arg_update_story_memory, "更新剧情记忆")
                    } else {
                        AppStrings.get(R.string.trace_personal_arg_update_memory, "更新记忆")
                    }
                }
            }
            else -> null
        }
    }.getOrNull()

    fun summarize(toolName: String, json: JSONObject): String? {
        if (json.opt("ok") == false || json.optString("status") in setOf("unconfirmed", "failed")) return null

        return when (toolName) {
            "memory_get", "character_memory_get" -> summarizeMemoryGet(toolName, json)
            "memory_write", "character_memory_write" -> summarizeMemoryWrite(toolName, json)
            else -> null
        }
    }

    private fun summarizeMemoryGet(toolName: String, json: JSONObject): String {
        val isCharacter = toolName.startsWith("character_")
        val matchedLines = json.optInt("matched_lines", -1)
        if (matchedLines >= 0) {
            return if (matchedLines > 0) {
                if (isCharacter) {
                    AppStrings.quantity(
                        R.plurals.trace_personal_story_found,
                        matchedLines,
                        "找到 %1\$d 条相关剧情",
                        "找到 %1\$d 条相关剧情",
                        matchedLines,
                    )
                } else {
                    AppStrings.quantity(
                        R.plurals.trace_personal_memory_found,
                        matchedLines,
                        "找到 %1\$d 条相关记忆",
                        "找到 %1\$d 条相关记忆",
                        matchedLines,
                    )
                }
            } else {
                if (isCharacter) {
                    AppStrings.get(R.string.trace_personal_story_empty, "未找到相关剧情")
                } else {
                    AppStrings.get(R.string.trace_personal_memory_empty, "未找到相关记忆")
                }
            }
        }
        val hasMore = json.optBoolean("has_more")
        return when {
            isCharacter && hasMore ->
                AppStrings.get(R.string.trace_personal_story_read_prefix, "已读取前段剧情记忆")
            isCharacter ->
                AppStrings.get(R.string.trace_personal_story_read, "已读取剧情记忆")
            hasMore ->
                AppStrings.get(R.string.trace_personal_memory_read_prefix, "已读取前段记忆")
            else ->
                AppStrings.get(R.string.trace_personal_memory_read, "已读取记忆")
        }
    }

    private fun summarizeMemoryWrite(toolName: String, json: JSONObject): String {
        val isCharacter = toolName.startsWith("character_")
        return when (json.optString("mode")) {
            "clear" -> if (isCharacter) {
                AppStrings.get(R.string.trace_personal_story_cleared, "已清空剧情记忆")
            } else {
                AppStrings.get(R.string.trace_personal_memory_cleared, "已清空记忆")
            }
            "append" -> if (isCharacter) {
                AppStrings.get(R.string.trace_personal_story_saved, "已保存剧情记忆")
            } else {
                AppStrings.get(R.string.trace_personal_memory_saved, "已保存记忆")
            }
            else -> if (isCharacter) {
                AppStrings.get(R.string.trace_personal_story_updated, "已更新剧情记忆")
            } else {
                AppStrings.get(R.string.trace_personal_memory_updated, "已更新记忆")
            }
        }
    }
}
