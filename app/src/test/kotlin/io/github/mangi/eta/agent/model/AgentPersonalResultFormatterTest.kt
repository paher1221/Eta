package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AgentPersonalResultFormatterTest {
    @Test
    fun memoryGetPresentsNaturalQueryOrReadStatusWithoutRawLineOrByteNumbers() {
        val querySuccess = summarize("memory_get", """{"matched_lines":3,"line_count":500,"bytes":15000}""")
        assertEquals("找到 3 条相关记忆", querySuccess)
        assertFalse(querySuccess!!.contains("行"))
        assertFalse(querySuccess.contains("字节"))

        val queryEmpty = summarize("memory_get", """{"matched_lines":0,"line_count":500,"bytes":15000}""")
        assertEquals("未找到相关记忆", queryEmpty)

        val fullRead = summarize("memory_get", """{"line_count":200,"bytes":8000}""")
        assertEquals("已读取记忆", fullRead)
        assertFalse(fullRead!!.contains("行"))
        assertFalse(fullRead.contains("字节"))

        val partialRead = summarize("memory_get", """{"has_more":true,"line_count":200,"bytes":8000}""")
        assertEquals("已读取前段记忆", partialRead)

        // 剧情记忆
        val charQuery = summarize("character_memory_get", """{"matched_lines":2}""")
        assertEquals("找到 2 条相关剧情", charQuery)
        val charEmpty = summarize("character_memory_get", """{"matched_lines":0}""")
        assertEquals("未找到相关剧情", charEmpty)
        val charRead = summarize("character_memory_get", """{}""")
        assertEquals("已读取剧情记忆", charRead)
    }

    @Test
    fun memoryWritePresentsActionConclusionWithoutByteOrLineCounts() {
        assertEquals("已保存记忆", summarize("memory_write", """{"mode":"append","bytes":5000,"line_count":120}"""))
        assertEquals("已更新记忆", summarize("memory_write", """{"mode":"replace_range","bytes":5000,"line_count":120}"""))
        assertEquals("已清空记忆", summarize("memory_write", """{"mode":"clear"}"""))

        assertEquals("已保存剧情记忆", summarize("character_memory_write", """{"mode":"append"}"""))
        assertEquals("已更新剧情记忆", summarize("character_memory_write", """{"mode":"replace_range"}"""))
        assertEquals("已清空剧情记忆", summarize("character_memory_write", """{"mode":"clear"}"""))
    }

    @Test
    fun memoryArgumentsAreCleanAndConcise() {
        assertEquals("追加记忆", AgentPersonalResultFormatter.summarizeArguments("memory_write", """{"mode":"append","content":"abc"}"""))
        assertEquals("更新记忆", AgentPersonalResultFormatter.summarizeArguments("memory_write", """{"mode":"replace_range","content":"abc"}"""))
        assertEquals("清空记忆", AgentPersonalResultFormatter.summarizeArguments("memory_write", """{"mode":"clear"}"""))
        assertEquals("检索记忆", AgentPersonalResultFormatter.summarizeArguments("memory_get", """{"query":"咖啡偏好"}"""))
        assertEquals("读取记忆", AgentPersonalResultFormatter.summarizeArguments("memory_get", """{}"""))

        assertEquals("追加剧情记忆", AgentPersonalResultFormatter.summarizeArguments("character_memory_write", """{"mode":"append"}"""))
        assertEquals("检索剧情记忆", AgentPersonalResultFormatter.summarizeArguments("character_memory_get", """{"query":"身世"}"""))
    }

    @Test
    fun nonMemoryToolsReturnNull() {
        assertEquals(null, AgentPersonalResultFormatter.summarize("browser_use", JSONObject("""{"ok":true}""")))
        assertEquals(null, AgentPersonalResultFormatter.summarizeArguments("browser_use", """{"action":"navigate"}"""))
    }

    private fun summarize(toolName: String, json: String): String? =
        AgentPersonalResultFormatter.summarize(toolName, JSONObject(json).also {
            if (!it.has("ok")) it.put("ok", true)
        })
}
