package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolRequirements
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWebToolCatalogTest {
    @Test
    fun publicWebToolsAreRegisteredWithoutExtraAuthorization() {
        listOf("web_search", "fetch_url").forEach { name ->
            val requirement = requireNotNull(AgentToolRequirements.find(name))
            assertTrue(requirement.systemAccess == AgentToolRequirements.ToolSystemAccess.NONE)
            assertTrue(!requirement.gui)
        }
    }

}
