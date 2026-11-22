package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.device.AccessibilityDeviceController.ElementObservation
import io.github.mangi.eta.agent.device.AccessibilityDeviceController.Observation
import io.github.mangi.eta.agent.device.AccessibilityDeviceController.UiNode
import org.json.JSONArray
import org.json.JSONObject

/**
 * 动作后观察的模型投影：先给结论（界面是否变化、是否切换应用或窗口），再给新的节点列表。
 * 变化判定只比较节点的可见语义（文本、描述、类名、view_id、位置），不依赖时间戳或动画帧。
 */
internal object AgentAfterActionSummary {
    fun build(before: ElementObservation?, observation: Observation): JSONObject {
        val metadata = JSONObject(observation.content)
        val after = observation.elementObservation
        val nodes = after?.nodes.orEmpty()
        val status = metadata.optString("ui_tree_status").ifBlank {
            when {
                after == null -> "query_failed"
                nodes.isEmpty() -> "empty"
                else -> "available"
            }
        }
        val changed = if (status == "available" && nodes.isNotEmpty()) {
            before == null || signature(before.nodes) != signature(nodes)
        } else {
            null
        }
        val note = if (changed == null) {
            metadata.optString("ui_tree_note").ifBlank { uiTreeNote(status, observation.image != null) }
        } else if (changed) {
            "以上为动作后的新界面，可直接用该 observation_id 继续操作；需要更多节点或截图时再调用 observe_screen"
        } else {
            "界面没有可见变化，动作可能未生效；请换目标或方式，不要原样重复"
        }
        return JSONObject()
            .put("observation_id", after?.id ?: JSONObject.NULL)
            .put("observation_source", after?.source?.wireName ?: metadata.opt("observation_source") ?: JSONObject.NULL)
            .put("package", after?.packageName ?: metadata.optJSONObject("focus")?.optString("package").orEmpty())
            .put("screen_changed", changed ?: JSONObject.NULL)
            .put(
                "package_changed",
                if (after != null) before != null && before.packageName != after.packageName else JSONObject.NULL,
            )
            .put(
                "window_changed",
                if (after != null) before != null && before.windowId != after.windowId else JSONObject.NULL,
            )
            .put("ui_tree_status", status)
            .put("ui_tree_truncated", after?.truncated ?: false)
            .put("ui_nodes", JSONArray().also { array -> nodes.forEach { array.put(it.compactJson()) } })
            .put("note", note)
            .apply {
                metadata.optJSONObject("focus")?.let { put("focus", it) }
                metadata.optString("code").takeIf(String::isNotBlank)?.let { put("code", it) }
            }
    }

    private fun uiTreeNote(status: String, screenshotAttached: Boolean): String {
        val description = when (status) {
            "not_requested" -> "本次未请求节点树"
            "available" -> "节点树可用"
            "empty" -> "已读取窗口，但没有可用节点；不能据此判断应用不支持当前显示屏"
            "no_window" -> "目标显示屏未返回可观察窗口"
            "root_unavailable" -> "已找到目标窗口，但暂时无法读取根节点"
            "timeout" -> "节点查询超时"
            "busy" -> "上一次节点查询尚未结束"
            "query_failed" -> "节点查询失败"
            else -> "无障碍服务不可用，当前没有节点读取通道"
        }
        if (status == "not_requested" || status == "available") return description
        return description + if (screenshotAttached) {
            "；可结合本次截图观察界面"
        } else {
            "；可请求截图确认当前界面"
        }
    }

    private fun signature(nodes: List<UiNode>): List<String> =
        nodes.map { "${it.className}|${it.viewId}|${it.text}|${it.desc}|${it.bounds.toShortString()}" }

    /** 精简字段：省略默认值为 false 的布尔与包名，降低每步附带观察的 token 开销。 */
    private fun UiNode.compactJson(): JSONObject = JSONObject()
        .put("index", index)
        .apply {
            if (text.isNotBlank()) put("text", text)
            if (desc.isNotBlank()) put("desc", desc)
            put("class", className.substringAfterLast('.'))
            if (viewId.isNotBlank()) put("view_id", viewId.substringAfterLast('/'))
            put("center", JSONObject().put("x", centerX).put("y", centerY))
            if (clickable) put("clickable", true)
            if (longClickable) put("long_clickable", true)
            if (scrollable) put("scrollable", true)
            if (editable) put("editable", true)
            if (focused) put("focused", true)
            if (password) put("password", true)
            if (!enabled) put("enabled", false)
            checked?.let { put("checked", it) }
            if (selected) put("selected", true)
            if (hint.isNotBlank()) put("hint", hint)
        }
}
