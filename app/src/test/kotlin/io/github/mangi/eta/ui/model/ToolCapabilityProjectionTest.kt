package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCapabilityProjectionTest {
    private fun card(id: String) = ToolItemUi(id, id, id)

    @Test fun projectToolGroupsKeepsAllToolsAndDropsEmptyGroups() {
        val groups = listOf(ToolGroupUi("device", "设备", listOf(card("terminal"), card("observe_screen"))))
        assertEquals(groups, projectToolGroups(groups, false))
        assertEquals(groups, projectToolGroups(groups, true))
        val withEmpty = listOf(ToolGroupUi("root", "增强", emptyList())) + groups
        assertEquals(groups, projectToolGroups(withEmpty, false))
    }

    @Test fun browserCardsReferToTheRealBrowserTool() {
        listOf("browser_use", "browser_read", "browser_interact", "browser_screenshot").forEach { id ->
            assertEquals("browser_use", actualToolName(id))
            assertEquals(AgentToolRequirements.find("browser_use"), toolCardRequirement(id))
        }
    }

    @Test fun missingOrdinaryPermissionOpensPermissions() {
        val capabilities = AgentToolCapabilities(notificationsAllowed = false)
        assertEquals(AgentToolsAction.OpenPermissions, toolCardAction("recent_notifications", capabilities))
        assertEquals(AgentToolsAction.OpenBrowser, toolCardAction("browser_read", capabilities))
        assertEquals(AgentToolsAction.OpenWebToolsSettings, toolCardAction("web_search", capabilities))
        assertEquals(AgentToolsAction.OpenWebToolsSettings, toolCardAction("fetch_url", capabilities))
        assertEquals(null, toolCardAction("terminal", capabilities))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownCardCannotSilentlyAcquireCapabilityDefaults() {
        toolCardRequirement("unknown")
    }
}
