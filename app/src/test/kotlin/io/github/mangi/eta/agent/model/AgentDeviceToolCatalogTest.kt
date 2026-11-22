package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDeviceToolCatalogTest {
    @Test
    fun riskGroupsExposeOnlyTheirOwnTools() {
        val none = names(false, false, false)
        val direct = names(true, false, false)
        val reads = names(false, true, false)
        val actions = names(false, false, true)

        assertFalse("set_alarm" in none)
        assertTrue("set_alarm" in direct)
        assertTrue("inspect_app" in direct)
        assertFalse("inspect_app" in reads)
        assertTrue("recent_notifications" in reads)
        assertTrue("search_notification_history" in reads)
        assertTrue("recent_app_activity" in reads)
        assertTrue("app_usage_summary" in reads)
        assertTrue("get_current_location" in reads)
        assertTrue("get_device_environment" in reads)
        assertFalse("read_image" in reads)
        assertFalse("send_message" in reads)
        assertFalse("send_message" in actions)
        assertFalse("personal_context" in reads)
        assertTrue(actions.isEmpty())
    }

    private fun names(
        direct: Boolean,
        reads: Boolean,
        actions: Boolean,
    ): Set<String> {
        val tools = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            deviceDirectTools = direct,
            deviceSensitiveReadTools = reads,
            deviceSensitiveActionTools = actions,
        )
        return tools.toolNames()
    }

    private fun JSONArray.toolNames(): Set<String> =
        (0 until length()).mapTo(mutableSetOf()) { index ->
            getJSONObject(index).getJSONObject("function").getString("name")
        }
}
