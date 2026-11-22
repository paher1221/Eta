package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.imagegen.ImageGenToolCatalog
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.roleplay.CharacterMemoryTools
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolRequirementsTest {
    @Test
    fun everyRegisteredToolHasExactlyOneRequirement() {
        val tools = catalog()
            .also { CharacterMemoryTools.appendSchemas(it) }
            .also { ImageGenToolCatalog.appendTo(it) }
        val executionOnly = setOf("input_text", "replace_text", "clear_text", "paste_text")
        assertEquals(AgentToolRequirements.toolNames - executionOnly, tools.names())
        executionOnly.forEach { name -> assertFalse("$name 仅保留执行兼容，不再向模型暴露", name in tools.names()) }
        assertEquals(tools.length(), tools.names().size)
        assertFalse(tools.toString().contains("rootRequirement"))
    }

    @Test
    fun ordinaryCatalogDoesNotExposeCharacterMemoryTools() {
        assertTrue(catalog().names().none { it in CharacterMemoryTools.NAMES })
        assertTrue(catalog().names().none { it in ImageGenToolCatalog.names })
    }

    @Test
    fun unknownToolCannotEnterTheModelCatalog() {
        val unknown = JSONArray().put(JSONObject().put("function", JSONObject().put("name", "new_tool")))
        assertThrows(IllegalArgumentException::class.java) {
            AgentToolRequirements.project(unknown)
        }
    }

    @Test
    fun projectionNarrowsPrivilegedKeysWithoutMutatingSource() {
        val original = catalog()
        val projected = AgentToolRequirements.project(original)
        assertTrue(setOf("terminal", "terminal_job", "terminal_daemon", "read_file", "read_image", "browser_use").all {
            it in projected.names()
        })
        assertEquals("[\"user\"]", projected.properties("terminal").getJSONObject("identity").getJSONArray("enum").toString())
        assertEquals(1, original.properties("terminal").getJSONObject("identity").getJSONArray("enum").length())
        assertFalse(projected.toString().contains("/data/local/tmp/eta"))
        assertFalse(projected.properties("read_image").getJSONObject("path").toString().contains("Root"))
    }

    @Test
    fun ordinaryAuthorizationGatesTheCorrespondingTools() {
        val restricted = AgentToolCapabilities(
            notificationsAllowed = false, usageAllowed = false, locationAllowed = false,
        )
        val names = restricted.project(catalog()).names()
        assertTrue(setOf("launch_app", "open_uri", "terminal", "browser_use").all { it in names })
        assertTrue(setOf("recent_notifications", "app_usage_summary", "get_current_location").none { it in names })
        assertEquals("NOTIFICATION_ACCESS_REQUIRED", restricted.unavailableCode("recent_notifications"))
        assertEquals("APP_USAGE_ACCESS_REQUIRED", restricted.unavailableCode("app_usage_summary"))
        assertEquals("LOCATION_PERMISSION_REQUIRED", restricted.unavailableCode("get_current_location"))
        assertEquals(null, restricted.copy(notificationsAllowed = true).unavailableCode("recent_notifications"))
    }

    private fun catalog() = AgentToolCatalog.build(
        terminalTools = true, browserTools = true, deviceDirectTools = true,
        deviceSensitiveReadTools = true, deviceSensitiveActionTools = true,
        skillGitHubDiscovery = true, skillGitHubInstall = true, memoryTools = true,
        capabilities = AgentToolCapabilities(),
    )

    private fun JSONArray.names(): Set<String> = (0 until length()).mapTo(linkedSetOf()) {
        getJSONObject(it).getJSONObject("function").getString("name")
    }

    private fun JSONArray.properties(name: String): JSONObject = (0 until length())
        .map { getJSONObject(it).getJSONObject("function") }
        .single { it.getString("name") == name }.getJSONObject("parameters").getJSONObject("properties")
}
