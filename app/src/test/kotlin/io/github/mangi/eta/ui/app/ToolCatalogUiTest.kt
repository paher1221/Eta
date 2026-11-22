package io.github.mangi.eta.ui.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.TravelExplore
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.toolDisplayNameResource
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.components.iconForTool
import io.github.mangi.eta.ui.model.projectToolGroups
import io.github.mangi.eta.ui.model.toolCardRequirement
import io.github.mangi.eta.ui.screens.mcp.collapseDescriptionPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ToolCatalogUiTest {
    @Test
    fun everyRuntimeToolAndDisplayedCardHasASpecificIcon() {
        val tools = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            skillGitHubDiscovery = true,
            skillGitHubInstall = true,
            memoryTools = true,
            capabilities = AgentToolCapabilities(rootAvailable = true),
        )
        val runtimeNames = (0 until tools.length()).map {
            tools.getJSONObject(it).getJSONObject("function").getString("name")
        }
        val cardIds = buildToolsState(RuntimeEnvironment.getApplication()).groups
            .flatMap { it.tools }.map { it.id }
        (runtimeNames + cardIds).distinct().forEach { name ->
            assertNotEquals(name, Icons.Rounded.Build, iconForTool(name))
        }
    }

    @Test
    fun hostedSearchNamesAndDynamicMcpNamesUseTheirToolIcons() {
        assertEquals(Icons.Rounded.TravelExplore, iconForTool("网页搜索"))
        assertEquals(iconForTool("网页搜索"), iconForTool("web_search"))
        assertEquals(Icons.Rounded.Language, iconForTool("browser_use"))
        assertEquals(Icons.AutoMirrored.Rounded.Article, iconForTool("fetch_url"))
        assertEquals(Icons.Rounded.Extension, iconForTool("mcp_server_search_012345"))
        assertEquals(Icons.Rounded.Build, iconForTool("unknown_tool"))
    }

    @Test
    fun publicWebCardsHaveNamesAndStayInTheWebGroup() {
        val web = buildToolsState(RuntimeEnvironment.getApplication()).groups.first { it.id == "web" }
        assertTrue(web.tools.map { it.id }.containsAll(listOf("web_search", "fetch_url", "browser_use")))
        assertEquals(R.string.tool_web_search, toolDisplayNameResource("web_search"))
        assertEquals(R.string.tool_fetch_url, toolDisplayNameResource("fetch_url"))
    }

    @Test
    fun terminalToolsExposeSeparateLifecyclesInTheSameGroup() {
        val terminal = buildToolsState(RuntimeEnvironment.getApplication()).groups.first { it.id == "terminal" }
        assertTrue(terminal.tools.map { it.id }.containsAll(listOf("terminal", "terminal_job", "terminal_daemon")))
        assertEquals(R.string.tool_terminal_job, toolDisplayNameResource("terminal_job"))
        assertEquals(R.string.tool_terminal_daemon, toolDisplayNameResource("terminal_daemon"))
        assertEquals(R.string.tool_subagent, toolDisplayNameResource("subagent"))
        assertEquals(R.string.tool_subagent_profile, toolDisplayNameResource("subagent_profile"))
    }

    @Test
    fun everyDisplayedCardHasExplicitMetadataAndKeepsItsOriginalOrder() {
        val groups = buildToolsState(RuntimeEnvironment.getApplication()).groups
        val allCards = groups.flatMap { it.tools }
        allCards.forEach { toolCardRequirement(it.id) }
        assertEquals(allCards.size, allCards.map { it.id }.distinct().size)
        assertEquals(groups, projectToolGroups(groups, true))
        assertEquals(groups, projectToolGroups(groups, false))
    }

    @Test
    fun disabledToolsAreOmittedFromModelCatalogAndPersistInPrefs() {
        val context = RuntimeEnvironment.getApplication()
        Prefs.initLocal(context)
        Prefs.setToolsEnabled(listOf("web_search", "fetch_url"), false)
        assertFalse(Prefs.isToolEnabled("web_search"))
        assertFalse(Prefs.isToolEnabled("fetch_url"))
        assertTrue(Prefs.isToolEnabled("browser_use"))

        val tools = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            capabilities = AgentToolCapabilities(rootAvailable = true),
            disabledTools = Prefs.disabledTools(),
        )
        val names = (0 until tools.length()).map {
            tools.getJSONObject(it).getJSONObject("function").getString("name")
        }.toSet()
        assertFalse("web_search" in names)
        assertFalse("fetch_url" in names)
        assertTrue("browser_use" in names)

        Prefs.setToolsEnabled(listOf("web_search", "fetch_url"), true)
        assertTrue(Prefs.isToolEnabled("web_search"))
        assertTrue(Prefs.isToolEnabled("fetch_url"))
    }

    @Test
    fun mcpDescriptionPreviewCollapsesHeadingsAndBulletLines() {
        val raw = """
            # Search Tool
            - First capability
            * Second capability
        """.trimIndent()
        assertEquals(
            "Search Tool · First capability · Second capability",
            collapseDescriptionPreview(raw),
        )
    }
}
