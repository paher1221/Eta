package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 上下文与应用入口工具 schema。 */
internal object AgentContextAppToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "get_current_context",
                    description = "获取手机当前时间、时区和最近系统位置；涉及现在、今天、明天或所在位置时调用。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "search_apps",
                    description = "搜索手机上已安装的 Android 应用，返回应用名和包名。打开应用前如果不确定包名，先调用这个工具。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "应用名或包名片段，例如 QQ、微信、com.tencent")
                                )
                                .put(
                                    "include_system",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "是否包含系统应用，默认 false")
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "最多返回 1 到 20 个结果，默认 10")
                                )
                        )
                        .put("required", JSONArray().put("query"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "launch_app",
                    description = "启动一个已安装 Android 应用。优先提供 package_name；只有应用名时允许模糊匹配，匹配多个会返回候选而不会启动。ok=true 表示启动请求已提交。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "package_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "精确 Android 包名，例如 com.tencent.mobileqq")
                                )
                                .put(
                                    "app_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "应用显示名，例如 QQ")
                                )
                                .put(
                                    "display",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("auto").put("virtual").put("main"))
                                        .put("default", "auto")
                                        .put(
                                            "description",
                                            "启动目标画布：auto（默认，跨应用任务优先在后台虚拟屏执行，不打断用户当前主屏）、virtual（显式在后台虚拟屏启动）、main（显式在物理主屏前台打开）。"
                                        )
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "present_to_user",
                    description = "将后台虚拟屏中已经准备就绪的应用画面无缝放大并移交到物理主屏前台，供用户直接查看、完成验证或确认付款。当任务在虚拟屏内已完成前置操作、到达收银台/二维码/结果页，或遇到安全窗口需要用户本人操作时调用。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "reason",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "可选，向用户呈现画面的简短原因，例如“已到达支付确认页”")
                                )
                        )
                        .put("additionalProperties", false),
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "open_uri",
                    description = "把一个确定有效的 URI 显式交给 Android 外部应用处理，例如 https、tel、geo 或应用 deep link。它不用于读取网页或网页交互。不要编造 URI。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "uri",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "确定有效、可由系统处理的 URI")
                                )
                        )
                        .put("required", JSONArray().put("uri"))
                )
            )
    }
}
