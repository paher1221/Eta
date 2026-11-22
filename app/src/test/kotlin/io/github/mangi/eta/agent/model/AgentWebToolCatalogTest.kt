package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolRequirements
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentWebToolCatalogTest {
    @Test
    fun publicWebToolsNeedNeitherRootNorAccessibility() {
        listOf("web_search", "fetch_url").forEach { name ->
            val requirement = requireNotNull(AgentToolRequirements.find(name))
            assertEquals(false, requirement.accessibility)
        }
    }

}
