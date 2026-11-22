package io.github.mangi.eta.agent.tool

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.GuiControlCoordinator
import io.github.mangi.eta.core.AgentLogger
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentLocalToolsPermissionTest {
    @Test
    fun backgroundToolsDoNotAcquireAnotherRunsGuiControl() {
        val coordinator = GuiControlCoordinator()
        val owner = coordinator.openRun("owner", AgentRunController())
        owner.withAction { }
        val background = coordinator.openRun("background", AgentRunController())
        try {
            tools(guiControl = background).use { tools ->
                val result = tools.execute(AgentModelClient.ToolCall("terminal", "terminal", "{}"))
                assertEquals("TERMINAL_TOOLS_DISABLED", JSONObject(result.content).getString("code"))
                assertEquals("owner", coordinator.state.value.ownerRunId)
            }
        } finally {
            owner.close()
        }
    }

    @Test
    fun rootIdentityCallsAreRejectedBeforeAnyOperation() {
        val tools = tools(
            terminalEnabled = { true },
        )
        listOf(
            "terminal" to "{\"action\":\"open\",\"identity\":\"root\"}",
            "edit_file" to "{\"path\":\"x\",\"identity\":\"root\",\"old_text\":\"a\",\"new_text\":\"b\"}",
        ).forEach { (name, args) ->
            val result = tools.execute(AgentModelClient.ToolCall("old-$name", name, args))
            assertEquals("ROOT_REQUIRED", JSONObject(result.content).getString("code"))
        }
        tools.close()
    }

    @Test
    fun terminalPermissionIsRecheckedImmediatelyBeforeExecution() {
        val enabled = AtomicBoolean(true)
        val tools = tools(terminalEnabled = enabled::get)
        enabled.set(false)

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "call-1",
                name = "terminal",
                argumentsJson = "{\"command\":\"id\"}",
            )
        )

        assertEquals("TERMINAL_TOOLS_DISABLED", JSONObject(result.content).getString("code"))
        listOf("terminal_job", "terminal_daemon", "read_file", "edit_file", "glob_files", "grep_files", "stat_file").forEach { name ->
            val fileResult = tools.execute(AgentModelClient.ToolCall("disabled-$name", name, "{}"))
            assertEquals(name, "TERMINAL_TOOLS_DISABLED", JSONObject(fileResult.content).getString("code"))
        }
        tools.close()
    }

    @Test
    fun browserPermissionIsRecheckedImmediatelyBeforeExecution() {
        val enabled = AtomicBoolean(true)
        val tools = tools(browserEnabled = enabled::get)
        enabled.set(false)

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "call-1",
                name = "browser_use",
                argumentsJson = "{\"action\":\"get_page_info\"}",
            )
        )

        assertEquals("BROWSER_TOOLS_DISABLED", JSONObject(result.content).getString("code"))
        listOf("web_search", "fetch_url").forEach { name ->
            val webResult = tools.execute(AgentModelClient.ToolCall("disabled-$name", name, "{}"))
            assertEquals(name, "BROWSER_TOOLS_DISABLED", JSONObject(webResult.content).getString("code"))
        }
        tools.close()
    }

    @Test
    fun memoryPermissionIsRecheckedImmediatelyBeforeExecution() {
        val enabled = AtomicBoolean(true)
        val tools = tools(memoryEnabled = enabled::get)
        enabled.set(false)

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "call-memory",
                name = "memory_get",
                argumentsJson = "{}",
            ),
        )

        assertEquals("MEMORY_DISABLED", JSONObject(result.content).getString("code"))
        assertEquals(true, result.sensitive)
        tools.close()
    }

    @Test
    fun roleplayCannotWriteRealMemoryEvenWithAStaleToolDeclaration() {
        val tools = tools(memoryEnabled = { true }, memoryWritable = false)
        try {
            val result = tools.execute(AgentModelClient.ToolCall("write", "memory_write", "{}"))
            assertEquals("REAL_MEMORY_READ_ONLY", JSONObject(result.content).getString("code"))
            assertTrue(result.sensitive)
        } finally {
            tools.close()
        }
    }

    @Test
    fun foregroundToolIsRejectedWhenEntrySurfaceIsNotReady() {
        val tools = tools(
            beforeToolExecution = {
                ToolExecutionDecision.Reject(
                    code = "ENTRY_SURFACE_NOT_READY",
                    message = "入口窗口尚未确认关闭",
                )
            },
        )

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "call-1",
                name = "memory_get",
                argumentsJson = "{}",
            ),
        )

        assertEquals("ENTRY_SURFACE_NOT_READY", JSONObject(result.content).getString("code"))
        tools.close()
    }

    @Test
    fun foregroundToolPropagatesGateFailure() {
        val tools = tools(
            beforeToolExecution = {
                ToolExecutionDecision.Reject(
                    code = "GATE_UNAVAILABLE",
                    message = "执行闸门不可用",
                )
            },
        )

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "call-1",
                name = "memory_get",
                argumentsJson = "{}",
            ),
        )
        val json = JSONObject(result.content)

        assertEquals("GATE_UNAVAILABLE", json.getString("code"))
        assertEquals("执行闸门不可用", json.getString("message"))
        tools.close()
    }

    @Test
    fun missingActionArgumentsDoNotBecomeZeroCoordinatesOrEmptyText() {
        val tools = tools()
        try {
            listOf(
                "set_clipboard" to "{}",
            ).forEach { (name, arguments) ->
                val result = tools.execute(AgentModelClient.ToolCall("missing", name, arguments))
                assertEquals(name, "INVALID_ARGUMENT", JSONObject(result.content).getString("code"))
            }
        } finally { tools.close() }
    }

    @Test
    fun missingAndNonTextInputDoNotChangeClipboard() {
        val context = RuntimeEnvironment.getApplication() as Context
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "existing text"))
        tools().use { tools ->
            listOf("set_clipboard").forEach { name ->
                listOf("{}", """{"text":null}""", """{"text":{"value":"replacement"}}""").forEach { args ->
                    val result = JSONObject(tools.execute(AgentModelClient.ToolCall("invalid-$name", name, args)).content)
                    assertEquals(result.toString(), "INVALID_ARGUMENT", result.getString("code"))
                    assertEquals("existing text", clipboard.primaryClip!!.getItemAt(0).text.toString())
                }
            }
            val cleared = JSONObject(tools.execute(AgentModelClient.ToolCall("clear", "set_clipboard", """{"text":""}""")).content)
            assertTrue(cleared.toString(), cleared.getBoolean("ok"))
            assertEquals("", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test
    fun disabledIndividualToolReturnsToolDisabledErrorCode() {
        val tools = AgentLocalTools(
            context = RuntimeEnvironment.getApplication() as Context,
            logger = NoOpLogger,
            browserRunId = "test-run",
            isToolEnabled = { it != "memory_get" },
        )
        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "call-disabled",
                name = "memory_get",
                argumentsJson = "{}",
            ),
        )
        val json = JSONObject(result.content)
        assertEquals("TOOL_DISABLED", json.getString("code"))
        assertTrue(result.sensitive)
        tools.close()
    }

    private fun tools(
        terminalEnabled: () -> Boolean = { false },
        browserEnabled: () -> Boolean = { false },
        memoryEnabled: () -> Boolean = { false },
        memoryWritable: Boolean = true,
        beforeToolExecution: (String) -> ToolExecutionDecision = {
            ToolExecutionDecision.Allow
        },
        guiControl: GuiControlCoordinator.RunControl? = null,
    ): AgentLocalTools =
        AgentLocalTools(
            context = RuntimeEnvironment.getApplication() as Context,
            logger = NoOpLogger,
            browserRunId = "test-run",
            terminalToolsEnabled = terminalEnabled,
            browserToolsEnabled = browserEnabled,
            memoryToolsEnabled = memoryEnabled,
            memoryWritable = memoryWritable,
            beforeToolExecution = beforeToolExecution,
            guiControl = guiControl,
        )

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
