package io.github.mangi.eta.agent.model

/** 标记原始参数或结果不得进入持久会话的工具。 */
internal object AgentSensitiveToolPolicy {
    fun isSensitive(toolName: String): Boolean =
        toolName.startsWith("mcp_") || toolName in sensitiveTools

    private val sensitiveTools = setOf(
        "read_image",
        "memory_get",
        "memory_write",
        "character_memory_get",
        "character_memory_write",
    )
}
