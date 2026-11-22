package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolCatalogTest {
    @Test
    fun featureFlagsProduceExactUniqueToolUnions() {
        val base = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
        ).toolNames()
        val baseTools = base.toSet()
        val variants = listOf(
            ToolVariant(terminalTools = false, browserTools = false, addedTools = emptySet()),
            ToolVariant(terminalTools = false, browserTools = true, addedTools = BROWSER_TOOLS),
            ToolVariant(terminalTools = true, browserTools = false, addedTools = TERMINAL_TOOLS),
            ToolVariant(
                terminalTools = true,
                browserTools = true,
                addedTools = BROWSER_TOOLS + TERMINAL_TOOLS,
            ),
        )

        assertEquals(base.size, baseTools.size)
        assertTrue(
            base.containsAll(
                setOf("set_clipboard", "skills_list", "skills_read", "skills_read_resource"),
            ),
        )
        assertFalse("browser_use" in base)
        assertFalse("web_search" in base)
        assertFalse("fetch_url" in base)
        assertFalse("terminal" in base)

        variants.forEach { variant ->
            val names = AgentToolCatalog.build(
                terminalTools = variant.terminalTools,
                browserTools = variant.browserTools,
            ).toolNames()
            val label = "terminal=${variant.terminalTools}, browser=${variant.browserTools}"

            assertEquals("$label must not contain duplicate tools", names.size, names.toSet().size)
            assertEquals("$label must be an exact union", baseTools + variant.addedTools, names.toSet())
        }
    }

    @Test
    fun browserToolAllowsArbitraryUrlsAndFormSubmission() {
        val function = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = true,
        ).function("browser_use")
        val properties = function
            .getJSONObject("parameters")
            .getJSONObject("properties")

        assertEquals("string", properties.getJSONObject("url").getString("type"))
        assertEquals("boolean", properties.getJSONObject("submit").getString("type"))
        assertFalse(properties.getJSONObject("url").getString("description").contains("HTTPS"))
        assertFalse(function.getString("description").contains("拦截"))
    }

    @Test
    fun textToolsDeclareTheSameLimitsAsRuntime() {
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false)

        assertEquals(20_000, tools.maxTextLength("set_clipboard"))
    }

    @Test
    fun terminalSeparatesAndroidAndLinuxEnvironments() {
        val terminal = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = false,
        ).function("terminal")
        val environment = terminal
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("environment")

        assertEquals(listOf("android", "linux"), environment.getJSONArray("enum").stringValues())
        assertTrue(environment.getString("description").contains("android"))
        assertTrue(environment.getString("description").contains("linux"))
    }

    @Test
    fun memoryToolsAreExposedOnlyWhenEnabledAndDeclareBoundedOperations() {
        val disabled = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            memoryTools = false,
        ).toolNames()
        assertFalse("memory_get" in disabled)
        assertFalse("memory_write" in disabled)

        val enabled = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            memoryTools = true,
        )
        assertTrue("memory_get" in enabled.toolNames())
        val write = enabled.function("memory_write")
        val properties = write.getJSONObject("parameters").getJSONObject("properties")
        assertEquals(3_500, properties.getJSONObject("content").getInt("maxLength"))
        assertEquals(
            listOf("replace_range", "append", "clear"),
            properties.getJSONObject("mode").getJSONArray("enum").stringValues(),
        )
    }

    private fun JSONArray.toolNames(): List<String> =
        (0 until length()).map { index ->
            getJSONObject(index).getJSONObject("function").getString("name")
        }

    private fun JSONArray.function(name: String): JSONObject =
        (0 until length())
            .asSequence()
            .map { index -> getJSONObject(index).getJSONObject("function") }
            .first { function -> function.getString("name") == name }

    private fun JSONObject.requiredNames(): Set<String> =
        optJSONArray("required")?.stringValues()?.toSet().orEmpty()

    private fun JSONArray.stringValues(): List<String> =
        (0 until length()).map(::getString)

    private fun JSONArray.maxTextLength(name: String): Int =
        function(name)
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("text")
            .getInt("maxLength")

    private data class ToolVariant(
        val terminalTools: Boolean,
        val browserTools: Boolean,
        val addedTools: Set<String>,
    )

    private companion object {
        val BROWSER_TOOLS = setOf("browser_use", "web_search", "fetch_url")
        val TERMINAL_TOOLS = setOf(
            "read_image",
            "terminal",
            "terminal_job",
            "terminal_daemon",
            "read_file",
            "edit_file",
            "stat_file",
            "glob_files",
            "grep_files",
            "write_file",
            "list_directory",
        )
    }
}
