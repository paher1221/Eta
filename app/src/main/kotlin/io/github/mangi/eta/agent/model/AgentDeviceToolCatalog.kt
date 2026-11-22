package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 常用设备能力的结构化 schema；按风险组决定是否向模型公开。 */
internal object AgentDeviceToolCatalog {
    fun appendTo(
        tools: JSONArray,
        directTools: Boolean,
        sensitiveReadTools: Boolean,
        sensitiveActionTools: Boolean,
    ) {
        if (directTools) appendDirectTools(tools)
        if (sensitiveReadTools) appendSensitiveReadTools(tools)
        if (sensitiveActionTools) appendSensitiveActionTools(tools)
    }

    private fun appendDirectTools(tools: JSONArray) {
        tools
            .put(
                function(
                    "get_flashlight",
                    "查询手电筒实际状态及是否被相机占用。",
                    properties(),
                ),
            )
            .put(
                function(
                    "set_flashlight",
                    "开启或关闭手电筒并通过系统回调验证；已处于目标状态时不重复切换。",
                    properties("enabled" to boolean("true 开启，false 关闭")),
                    "enabled",
                ),
            )
            .put(emptyFunction("get_display_state", "查询亮度、自动亮度、旋转和息屏时间。"))
            .put(emptyFunction("get_sound_state", "读取各类音量、响铃及勿扰状态。"))
            .put(
                function(
                    "set_do_not_disturb",
                    "直接设置勿扰策略并读回验证。",
                    properties("mode" to enumString("勿扰模式", "off", "priority", "alarms", "silent")),
                    "mode",
                ),
            )
            .put(
                function(
                    "set_volume",
                    "直接设置系统音量，不要操作音量 GUI。",
                    properties(
                        "stream" to enumString("音量通道", "media", "alarm", "ring", "notification", "voice_call", "system"),
                        "percent" to integer("0 到 100 的音量百分比", 0, 100),
                        "user_id" to userId(),
                    ),
                    "stream", "percent",
                ),
            )
            .put(
                function(
                    "inspect_app",
                    "读取当前 Android 用户中指定应用的版本、UID、安装来源与路径、启用状态和请求权限，不要求应用具有桌面入口。只支持 Eta 所属用户，不跨工作资料查询。权限列表按 permission_offset/permission_limit 分页。",
                    properties(
                        "package_name" to string("精确 Android 包名", 255),
                        "user_id" to userId(),
                        "permission_offset" to integer("请求权限列表的起始索引，默认 0", 0, Int.MAX_VALUE),
                        "permission_limit" to integer("最多返回权限数量，默认 100", 1, 200),
                    ),
                    "package_name",
                ),
            )
            .put(
                function(
                    "set_alarm",
                    "直接创建系统闹钟，不要用 GUI。涉及相对日期时先用 get_current_context 换算；hour/minute 使用设备本地时间。系统不接受直达操作时可能只打开时钟页面。",
                    properties(
                        "hour" to integer("0 到 23", 0, 23),
                        "minute" to integer("0 到 59", 0, 59),
                        "label" to string("闹钟标签，最多 100 字", 100),
                        "repeat_days" to stringArray(
                            "重复星期；不提供表示仅下一次",
                            "mon", "tue", "wed", "thu", "fri", "sat", "sun",
                        ),
                        "vibrate" to boolean("是否振动，默认 true"),
                    ),
                    "hour", "minute",
                ),
            )
            .put(
                function(
                    "set_timer",
                    "直接创建系统计时器，不要用 GUI。duration_seconds 必须是 1 到 86400 秒。",
                    properties(
                        "duration_seconds" to integer("计时秒数", 1, 86_400),
                        "label" to string("计时器标签，最多 100 字", 100),
                    ),
                    "duration_seconds",
                ),
            )
            .put(emptyFunction("device_status", "读取电池、内存、存储、系统版本与开机时长。"))
            .put(emptyFunction("network_info", "读取当前联网方式、联网验证状态和当前 Wi‑Fi 基本信息，不返回保存的密码。"))
            .put(
                function(
                    "media_control",
                    "直接控制当前媒体会话，不要操作播放器 GUI。",
                    properties(
                        "action" to enumString(
                            "媒体动作",
                            "play", "pause", "play_pause", "next", "previous", "stop",
                        ),
                    ),
                    "action",
                ),
            )
    }

    private fun appendSensitiveReadTools(tools: JSONArray) {
        tools
            .put(
                function(
                    "recent_notifications",
                    "读取 Eta 在用户授予通知使用权后记录的最近通知标题与正文。结果不写入持久会话。",
                    properties(
                        "package_name" to string("可选的精确应用包名过滤", 255),
                        "limit" to integer("最多返回数量，默认 10", 1, 20),
                    ),
                ),
            )
            .put(
                function(
                    "search_notification_history",
                    "检索 Eta 在用户授予通知使用权后记录的最近 7 天通知。原始结果不写入持久会话。",
                    properties(
                        "query" to string("可选标题或正文关键词", 200),
                        "package_name" to string("可选精确包名", 255),
                        "max_age_hours" to integer("回溯小时数，默认 24", 1, 168),
                        "limit" to integer("最多返回数量，默认 20", 1, 50),
                    ),
                ),
            )
            .put(
                function(
                    "recent_app_activity",
                    "读取最近打开应用的时间顺序，需要系统使用情况访问权。",
                    properties(
                        "package_name" to string("可选精确包名", 255),
                        "max_age_hours" to integer("回溯小时数，默认 24", 1, 168),
                        "limit" to integer("最多返回数量，默认 20", 1, 50),
                    ),
                ),
            )
            .put(
                function(
                    "app_usage_summary",
                    "按前台时长汇总最近应用使用情况，需要系统使用情况访问权。",
                    properties(
                        "max_age_hours" to integer("统计小时数，默认 24", 1, 168),
                        "limit" to integer("最多返回数量，默认 20", 1, 50),
                    ),
                ),
            )
            .put(emptyFunction("get_current_location", "读取系统已有的最近位置，不持续监听或主动唤醒 GPS。"))
            .put(emptyFunction("get_device_environment", "读取锁屏、勿扰、铃声、音频输出和外接显示器状态。"))
    }

    private fun appendSensitiveActionTools(tools: JSONArray) = Unit

    private fun emptyFunction(name: String, description: String): JSONObject =
        function(name, description, properties())

    private fun function(
        name: String,
        description: String,
        properties: JSONObject,
        vararg required: String,
    ): JSONObject =
        AgentToolSchema.function(
            name = name,
            description = description,
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .also { schema ->
                    if (required.isNotEmpty()) schema.put("required", JSONArray(required.toList()))
                },
        )

    private fun properties(vararg entries: Pair<String, JSONObject>): JSONObject =
        JSONObject().also { target -> entries.forEach { (name, schema) -> target.put(name, schema) } }

    private fun string(description: String, maxLength: Int? = null): JSONObject =
        JSONObject()
            .put("type", "string")
            .put("description", description)
            .also { schema -> maxLength?.let { schema.put("maxLength", it) } }

    private fun boolean(description: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", description)

    private fun userId(): JSONObject =
        integer("Android 用户编号，默认 Eta 所属用户；其他用户或工作资料返回 USER_SCOPE_UNSUPPORTED", 0, Int.MAX_VALUE)

    private fun integer(description: String, minimum: Int, maximum: Int): JSONObject =
        JSONObject()
            .put("type", "integer")
            .put("minimum", minimum)
            .put("maximum", maximum)
            .put("description", description)

    private fun enumString(description: String, vararg values: String): JSONObject =
        string(description).put("enum", JSONArray(values.toList()))

    private fun stringArray(description: String, vararg values: String): JSONObject =
        JSONObject()
            .put("type", "array")
            .put("items", enumString(description, *values))
            .put("uniqueItems", true)
            .put("maxItems", 7)
            .put("description", description)
}
