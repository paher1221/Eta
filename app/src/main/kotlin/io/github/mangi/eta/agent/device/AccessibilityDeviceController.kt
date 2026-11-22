package io.github.mangi.eta.agent.device

import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AgentLogger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.Display
import org.json.JSONArray
import org.json.JSONObject

/** 基于无障碍服务的设备控制执行器；不依赖 Root 或外部 Shell。 */
internal class AccessibilityDeviceController(
    private val logger: AgentLogger,
    private val screenshotExcludedPackages: () -> Set<String> = { emptySet() },
) : AutoCloseable {
    override fun close() = Unit

    data class Observation(
        val content: String,
        val image: AgentModelClient.ModelImage?,
        val elementObservation: ElementObservation?,
        val coordinateSpace: CoordinateSpace?,
    )

    data class ElementObservation(
        val id: String,
        val source: ElementSource,
        val packageName: String,
        val windowId: Int?,
        val nodes: List<UiNode>,
        val maxNodes: Int,
        val truncated: Boolean,
        internal val accessibilitySnapshot: AgentAccessibilityService.NodeSnapshot? = null,
    )

    enum class ElementSource(val wireName: String) {
        ACCESSIBILITY("accessibility"),
    }

    data class CoordinateSpace(
        val screenWidth: Int,
        val screenHeight: Int,
        val screenshotWidth: Int,
        val screenshotHeight: Int
    ) {
        fun fromScreenshot(x: Int, y: Int): ScreenPoint {
            require(x in 0 until screenshotWidth && y in 0 until screenshotHeight) {
                "截图坐标超出范围：($x,$y) not in ${screenshotWidth}x$screenshotHeight"
            }
            return ScreenPoint(
                x = (x.toFloat() * screenWidth / screenshotWidth).toInt(),
                y = (y.toFloat() * screenHeight / screenshotHeight).toInt()
            )
        }

        fun summary(): String =
            "screen=${screenWidth}x$screenHeight,screenshot=${screenshotWidth}x$screenshotHeight"
    }

    data class ScreenPoint(val x: Int, val y: Int)

    fun screenDimensions(displayId: Int = Display.DEFAULT_DISPLAY): Pair<Int, Int> = screenSize(displayId)

    data class UiNode(
        val index: Int,
        val text: String,
        val desc: String,
        val className: String,
        val packageName: String,
        val viewId: String,
        val bounds: Rect,
        val clickable: Boolean,
        val longClickable: Boolean,
        val scrollable: Boolean,
        val focused: Boolean,
        val editable: Boolean,
        val password: Boolean,
        val enabled: Boolean,
        val checked: Boolean? = null,
        val selected: Boolean = false,
        val hint: String = "",
    ) {
        val centerX: Int get() = bounds.centerX()
        val centerY: Int get() = bounds.centerY()
    }

    fun observe(
        includeScreenshot: Boolean,
        includeUiTree: Boolean,
        maxNodes: Int,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): Observation {
        val accessibility = AgentAccessibilityService.current()
            ?: return Observation(accessibilityUnavailable(), null, null, null)
        val nodeLimit = maxNodes.coerceIn(1, 120)
        val display = screenSize(displayId)
        val accessibilityObservation = accessibility.observeNodeSnapshot(
            maxNodes = nodeLimit,
            displayId = displayId,
            includeNodes = includeUiTree,
        )
        val elementObservation = if (includeUiTree) {
            val accessibilitySnapshot = accessibilityObservation?.snapshot
            if (accessibilitySnapshot != null) {
                ElementObservation(
                    id = accessibilitySnapshot.id,
                    source = ElementSource.ACCESSIBILITY,
                    packageName = accessibilitySnapshot.packageName,
                    windowId = accessibilitySnapshot.windowId,
                    nodes = accessibilitySnapshot.nodes.map { it.toUiNode() },
                    maxNodes = nodeLimit,
                    truncated = accessibilitySnapshot.truncated,
                    accessibilitySnapshot = accessibilitySnapshot,
                )
            } else {
                null
            }
        } else {
            null
        }
        val nodes = elementObservation?.nodes.orEmpty()
        val capture = if (includeScreenshot) captureScreenshot(displayId) else ScreenCapture.notRequested()
        val image = capture.image
        val coordinateSpace = if (image?.width != null && image.height != null) {
            CoordinateSpace(
                screenWidth = display.first,
                screenHeight = display.second,
                screenshotWidth = image.width,
                screenshotHeight = image.height
            )
        } else {
            null
        }
        val json = JSONObject()
            .put("ok", true)
            .put("tool", "observe_screen")
            .put("canvas", CanvasTarget.MAIN_DISPLAY.wireName)
            .put("display_id", displayId)
            .put("screen", JSONObject().put("width", display.first).put("height", display.second))
            .put(
                "accessibility",
                JSONObject()
                    .put(
                        "available",
                        accessibilityObservation?.status != AgentAccessibilityService.NodeSnapshotStatus.SERVICE_UNAVAILABLE,
                    )
                    .put("package", accessibilityObservation?.window?.packageName.orEmpty())
                    .put(
                        "note",
                        if (elementObservation != null && nodes.isNotEmpty()) {
                            "节点来自无障碍服务，支持 tap_element、type_text、scroll_element 等稳定节点动作"
                        } else {
                            "本次未取得可用节点树；无障碍服务未连接或当前页面不可读"
                        }
                    )
            )
            .put(
                "coordinate_contract",
                if (coordinateSpace == null) {
                    JSONObject()
                        .put("note", "坐标工具必须显式填写 coordinate_space：ui_nodes 的 center/bounds 用 screen；本次未附图")
                } else {
                    JSONObject()
                        .put("recommended_coordinate_space", "normalized")
                        .put(
                            "screenshot",
                            JSONObject()
                                .put("width", coordinateSpace.screenshotWidth)
                                .put("height", coordinateSpace.screenshotHeight)
                        )
                        .put(
                            "screen",
                            JSONObject()
                                .put("width", coordinateSpace.screenWidth)
                                .put("height", coordinateSpace.screenHeight)
                        )
                        .put(
                            "scale_to_screen",
                            JSONObject()
                                .put("x", coordinateSpace.screenWidth.toDouble() / coordinateSpace.screenshotWidth)
                                .put("y", coordinateSpace.screenHeight.toDouble() / coordinateSpace.screenshotHeight)
                        )
                        .put(
                            "note",
                            "看截图定位用 normalized（0–999，相对整屏），不受模型侧图片缩放影响；ui_nodes 的 center/bounds 用 screen",
                        )
                }
            )
            .put("observation_id", elementObservation?.id ?: JSONObject.NULL)
            .put("observation_source", elementObservation?.source?.wireName ?: JSONObject.NULL)
            .put("window_id", elementObservation?.windowId ?: JSONObject.NULL)
            .put("node_limit", elementObservation?.maxNodes ?: 0)
            .put("ui_tree_truncated", elementObservation?.truncated ?: false)
            .put("ui_nodes", nodes.toJsonArray())
            .put(
                "screenshot",
                if (image == null) {
                    capture.toJson().put("attached", false)
                } else {
                    capture.toJson()
                        .put("attached", true)
                        .put("mime_type", image.mimeType)
                        .put("bytes", image.bytes)
                        .put("width", image.width)
                        .put("height", image.height)
                }
            )
        return Observation(json.toString(), image, elementObservation, coordinateSpace)
    }

    fun tap(x: Int, y: Int, displayId: Int = Display.DEFAULT_DISPLAY): String {
        val service = AgentAccessibilityService.current() ?: return accessibilityUnavailable()
        validatePoint(x, y, displayId)
        val result = service.gestureTap(x.toFloat(), y.toFloat(), displayId = displayId)
        if (result.ok) waitForUiSettle("tap")
        return nodeActionJson("tap", result)
    }

    fun longPress(x: Int, y: Int, durationMs: Int, displayId: Int = Display.DEFAULT_DISPLAY): String {
        val service = AgentAccessibilityService.current() ?: return accessibilityUnavailable()
        validatePoint(x, y, displayId)
        val duration = durationMs.coerceIn(300, 3_000)
        val result = service.gestureTap(x.toFloat(), y.toFloat(), duration.toLong(), displayId = displayId)
        if (result.ok) waitForUiSettle("long_press")
        return nodeActionJson("long_press", result.copy(method = "GESTURE_LONG_PRESS"))
    }

    fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): String {
        val service = AgentAccessibilityService.current() ?: return accessibilityUnavailable()
        validatePoint(x1, y1, displayId)
        validatePoint(x2, y2, displayId)
        val duration = durationMs.coerceIn(100, 2_000)
        val result = service.gestureSwipe(
            x1.toFloat(),
            y1.toFloat(),
            x2.toFloat(),
            y2.toFloat(),
            duration.toLong(),
            displayId = displayId,
        )
        if (result.ok) waitForUiSettle("swipe")
        return nodeActionJson("swipe", result)
    }

    fun scroll(
        direction: String,
        amount: String = "",
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): String {
        val parsed = ScrollDirection.parse(direction)
            ?: return scrollErrorJson(
                "scroll",
                null,
                "INVALID_ARGUMENT",
                "direction 仅支持 up/down/left/right",
            )
        val parsedAmount = ScrollAmount.parse(amount)
            ?: return scrollErrorJson("scroll", parsed, "INVALID_ARGUMENT", "amount 仅支持 small/page")
        val service = AgentAccessibilityService.current() ?: return accessibilityUnavailable()
        return scrollActionJson("scroll", service.scrollCurrent(parsed, parsedAmount, displayId = displayId))
    }

    fun inputText(text: String, displayId: Int = Display.DEFAULT_DISPLAY): String {
        if (text.isEmpty()) return errorJson("INVALID_ARGUMENT", "text 不能为空")
        if (text.length > MAX_INPUT_TEXT_CHARS) {
            return errorJson("TEXT_TOO_LONG", "input_text 最多支持 $MAX_INPUT_TEXT_CHARS 个字符")
        }
        val service = AgentAccessibilityService.current() ?: return errorJson(
            "ACCESSIBILITY_UNAVAILABLE",
            "input_text 需要无障碍服务确认真实输入焦点；本次未发送任何按键",
        )
        val result = service.inputTextFocused(text, displayId = displayId)
        if (result.ok) waitForUiSettle("input_text")
        return nodeActionJson("input_text", result)
    }

    fun replaceText(
        text: String,
        index: Int?,
        observation: ElementObservation?,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): String {
        if (text.length > MAX_REPLACE_TEXT_CHARS) {
            return errorJson("TEXT_TOO_LONG", "replace_text 最多支持 $MAX_REPLACE_TEXT_CHARS 个字符")
        }
        val service = AgentAccessibilityService.current()
            ?: return errorJson("ACCESSIBILITY_UNAVAILABLE", "replace_text 需要先启用 Eta Agent 无障碍服务")
        val snapshot = observation?.accessibilitySnapshot
        val result = service.setTextNode(snapshot, index, text, displayId = displayId)
        if (result.ok) waitForUiSettle("replace_text")
        return nodeActionJson("replace_text", result)
    }

    fun clearText(
        index: Int?,
        observation: ElementObservation?,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): String =
        replaceText("", index, observation, displayId = displayId).let { result ->
            val json = JSONObject(result)
            json.put("tool", "clear_text").toString()
        }

    fun tapElement(observation: ElementObservation, index: Int): String {
        val snapshot = observation.accessibilitySnapshot
            ?: return errorJson("STALE_NODE", "无法在当前界面唯一确认目标节点，请重新观察屏幕")
        val service = AgentAccessibilityService.current()
            ?: return errorJson("ACCESSIBILITY_UNAVAILABLE", "无障碍服务已断开，请重新观察屏幕")
        val result = service.clickNode(snapshot, index)
        if (result.ok) waitForUiSettle("tap")
        return nodeActionJson("tap_element", result)
    }

    fun longPressElement(
        observation: ElementObservation,
        index: Int,
        durationMs: Int,
    ): String {
        val snapshot = observation.accessibilitySnapshot
            ?: return errorJson("STALE_NODE", "无法在当前界面唯一确认目标节点，请重新观察屏幕")
        val service = AgentAccessibilityService.current()
            ?: return errorJson("ACCESSIBILITY_UNAVAILABLE", "无障碍服务已断开，请重新观察屏幕")
        val result = service.longClickNode(snapshot, index, durationMs.toLong())
        if (result.ok) waitForUiSettle("long_press")
        return nodeActionJson("long_press_element", result)
    }

    fun scrollElement(
        observation: ElementObservation,
        index: Int,
        direction: String,
        amount: String = "",
    ): String {
        val parsed = ScrollDirection.parse(direction)
            ?: return scrollErrorJson(
                "scroll_element",
                null,
                "INVALID_ARGUMENT",
                "direction 仅支持 up/down/left/right",
            )
        val parsedAmount = ScrollAmount.parse(amount)
            ?: return scrollErrorJson("scroll_element", parsed, "INVALID_ARGUMENT", "amount 仅支持 small/page")
        val snapshot = observation.accessibilitySnapshot
            ?: return scrollErrorJson(
                "scroll_element",
                parsed,
                "STALE_NODE",
                "无法在当前界面唯一确认滚动目标，请重新观察屏幕",
            )
        val service = AgentAccessibilityService.current()
            ?: return scrollErrorJson(
                "scroll_element",
                parsed,
                "ACCESSIBILITY_UNAVAILABLE",
                "无障碍服务已断开，请重新观察屏幕",
            )
        return scrollActionJson(
            tool = "scroll_element",
            result = service.scrollNode(snapshot, index, parsed, parsedAmount),
        )
    }

    fun pressKey(
        button: String,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): String {
        val normalized = button.uppercase()
        val service = AgentAccessibilityService.current() ?: return accessibilityUnavailable()
        return when (normalized) {
            "BACK", "HOME", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS" -> {
                val actionResult = service.globalActionResult(normalized)
                if (actionResult.ok) {
                    waitForUiSettle("press_key")
                    okJson("press_key", "accessibility").let {
                        JSONObject(it).put("button", normalized).toString()
                    }
                } else {
                    nodeActionJson("press_key", actionResult).let {
                        JSONObject(it).put("button", normalized).toString()
                    }
                }
            }
            "ENTER" -> {
                val result = service.imeEnter(displayId = displayId)
                if (result.ok) waitForUiSettle("press_key")
                nodeActionJson("press_key", result).let {
                    JSONObject(it).put("button", normalized).toString()
                }
            }
            else -> errorJson(
                "INVALID_ARGUMENT",
                "button 仅支持 BACK/HOME/ENTER/RECENTS/NOTIFICATIONS/QUICK_SETTINGS",
            )
        }
    }

    fun waitMs(durationMs: Int): String {
        val duration = durationMs.coerceIn(100, 30_000)
        Thread.sleep(duration.toLong())
        return JSONObject()
            .put("ok", true)
            .put("tool", "wait")
            .put("duration_ms", duration)
            .toString()
    }

    fun waitForText(
        text: String,
        timeoutMs: Int,
        includeDesc: Boolean,
        matchMode: String,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): String {
        val needle = text.trim()
        if (needle.isBlank()) return errorJson("INVALID_ARGUMENT", "text 不能为空")
        val timeout = timeoutMs.coerceIn(500, 60_000)
        val deadline = System.currentTimeMillis() + timeout
        var attempts = 0
        while (System.currentTimeMillis() <= deadline) {
            attempts++
            val service = AgentAccessibilityService.current() ?: return accessibilityUnavailable()
            val nodes = service.queryNodes(120, displayId).map { it.toUiNode() }
            val match = nodes.firstOrNull { node ->
                val haystacks = if (includeDesc) listOf(node.text, node.desc) else listOf(node.text)
                haystacks.any { value -> matches(value, needle, matchMode) }
            }
            if (match != null) {
                val matchedNode = match.toJson()
                matchedNode.remove("index")
                matchedNode.put("actionable", false)
                return JSONObject()
                    .put("ok", true)
                    .put("tool", "wait_for_text")
                    .put("attempts", attempts)
                    .put("matched_node", matchedNode)
                    .put("note", "等待查询不会发布元素快照；如需节点动作，请重新调用 observe_screen")
                    .toString()
            }
            Thread.sleep(350)
        }
        return JSONObject()
            .put("ok", false)
            .put("tool", "wait_for_text")
            .put("code", "TIMEOUT")
            .put("message", "等待文本超时：$needle")
            .put("attempts", attempts)
            .toString()
    }

    fun waitForPackage(
        packageName: String,
        timeoutMs: Int,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): String {
        val target = packageName.trim()
        if (target.isBlank()) return errorJson("INVALID_ARGUMENT", "package_name 不能为空")
        val timeout = timeoutMs.coerceIn(500, 60_000)
        val deadline = System.currentTimeMillis() + timeout
        var attempts = 0
        var lastPackage = ""
        while (System.currentTimeMillis() <= deadline) {
            attempts++
            val service = AgentAccessibilityService.current() ?: return accessibilityUnavailable()
            lastPackage = service.currentPackageName(displayId).orEmpty()
            if (lastPackage == target) {
                return JSONObject()
                    .put("ok", true)
                    .put("tool", "wait_for_package")
                    .put("package_name", target)
                    .put("attempts", attempts)
                    .toString()
            }
            Thread.sleep(350)
        }
        return JSONObject()
            .put("ok", false)
            .put("tool", "wait_for_package")
            .put("code", "TIMEOUT")
            .put("message", "等待应用前台超时：$target")
            .put("last_package", lastPackage)
            .put("attempts", attempts)
            .toString()
    }

    fun clipboardSet(context: Context, text: String): String {
        if (text.length > MAX_CLIPBOARD_TEXT_CHARS) {
            return errorJson("TEXT_TOO_LONG", "剪贴板文本最多支持 $MAX_CLIPBOARD_TEXT_CHARS 个字符")
        }
        val serviceResult = AgentAccessibilityService.current()?.copyToClipboard(text)
        val ok = serviceResult?.ok ?: runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("eta_agent", text))
            true
        }.getOrDefault(false)
        val json = JSONObject()
            .put("ok", ok)
            .put("tool", "set_clipboard")
            .put("chars", text.length)
        if (!ok) {
            json
                .put("code", serviceResult?.code?.ifBlank { null } ?: "CLIPBOARD_WRITE_FAILED")
                .put(
                    "message",
                    serviceResult?.message?.ifBlank { null } ?: "写入系统剪贴板失败",
                )
        }
        return json.toString()
    }

    fun clipboardGet(context: Context): String {
        val serviceResult = AgentAccessibilityService.current()?.readClipboard()
        val result = serviceResult ?: run {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = runCatching { clipboard.primaryClip }.getOrNull()
            if (clip == null || clip.itemCount <= 0) {
                AgentAccessibilityService.ClipboardReadResult.failure()
            } else {
                AgentAccessibilityService.ClipboardReadResult(
                    ok = true,
                    text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty(),
                )
            }
        }
        val json = JSONObject()
            .put("ok", result.ok)
            .put("tool", "get_clipboard")
            .put("text", result.text.take(8_000))
            .put("truncated", result.text.length > 8_000)
        if (!result.ok) {
            json
                .put("code", result.code)
                .put("message", "剪贴板为空，或当前应用无权读取剪贴板")
        }
        return json.toString()
    }

    fun pasteText(
        text: String,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): String {
        if (text.length > MAX_CLIPBOARD_TEXT_CHARS) {
            return errorJson("TEXT_TOO_LONG", "paste_text 最多支持 $MAX_CLIPBOARD_TEXT_CHARS 个字符")
        }
        val service = AgentAccessibilityService.current() ?: return errorJson(
            "ACCESSIBILITY_UNAVAILABLE",
            "paste_text 需要无障碍服务确认真实输入焦点；本次未修改剪贴板",
        )
        val result = service.pasteText(text, displayId = displayId)
        if (result.ok) waitForUiSettle("input_text")
        return nodeActionJson("paste_text", result)
    }

    fun openSystemPanel(panel: String): String {
        val normalized = panel.lowercase()
        val accessibilityAction = when (normalized) {
            "notifications", "notification" -> "NOTIFICATIONS"
            "quick_settings", "quicksettings", "settings" -> "QUICK_SETTINGS"
            else -> return errorJson("INVALID_ARGUMENT", "panel 仅支持 notifications/quick_settings")
        }
        val service = AgentAccessibilityService.current() ?: return accessibilityUnavailable()
        val actionResult = service.globalActionResult(accessibilityAction)
        if (actionResult.ok) {
            waitForUiSettle("press_key")
            return okJson("open_system_panel", "accessibility").let {
                JSONObject(it).put("panel", normalized).toString()
            }
        }
        return nodeActionJson("open_system_panel", actionResult).let {
            JSONObject(it).put("panel", normalized).toString()
        }
    }

    private fun captureScreenshot(displayId: Int = Display.DEFAULT_DISPLAY): ScreenCapture {
        val excludedPackages = screenshotExcludedPackages()
        // 优先逐窗口截图以排除 Eta 浮层。
        val service = AgentAccessibilityService.current()
        if (service != null) {
            val captureStartedAt = SystemClock.elapsedRealtime()
            val result = runCatching {
                service.captureScreenshotExcludingOverlays(excludedPackages)
            }.getOrNull()
            val bitmap = result?.bitmap
            if (bitmap != null) {
                val capturedAt = SystemClock.elapsedRealtime()
                val image = try {
                    runCatching {
                        AgentImageCodec.fromScreenBitmap(bitmap, source = "screen")
                    }.onFailure { throwable ->
                        logger.warn(
                            "Agent device action=encode_screenshot outcome=failed " +
                                "source=accessibility type=${throwable.javaClass.simpleName}"
                        )
                    }.getOrNull()
                } finally {
                    bitmap.recycle()
                }
                if (image != null) {
                    logger.debug {
                        "Agent device action=capture_screenshot outcome=completed source=accessibility " +
                            "capture_ms=${capturedAt - captureStartedAt} " +
                            "encode_ms=${SystemClock.elapsedRealtime() - capturedAt} " +
                            "image=${image.width}x${image.height} bytes=${image.bytes}"
                    }
                }
                if (image != null && image.bytes > 0) {
                    return ScreenCapture(
                        image = image,
                        source = "accessibility",
                        complete = result.complete,
                        partial = result.partial,
                        expectedWindows = result.expectedWindows,
                        capturedWindows = result.capturedWindows,
                        missingWindowIds = result.missingWindowIds,
                        failureCodes = result.failureCodes.map { "${it.key}:${it.value}" },
                        timedOut = result.timedOut,
                        criticalWindowMissing = result.criticalWindowMissing,
                    )
                }
            }
            if (result?.criticalWindowMissing == true) {
                logger.warn(
                    "Agent device action=capture_screenshot outcome=failed " +
                        "reason=critical_window_missing failures=${result.failureCodes.size}"
                )
                return ScreenCapture(
                    image = null,
                    source = "accessibility",
                    complete = false,
                    partial = false,
                    expectedWindows = result.expectedWindows,
                    capturedWindows = result.capturedWindows,
                    missingWindowIds = result.missingWindowIds,
                    failureCodes = result.failureCodes.map { "${it.key}:${it.value}" },
                    timedOut = result.timedOut,
                    criticalWindowMissing = true,
                )
            }
        }
        val code = "SCREENSHOT_UNAVAILABLE"
        logger.debug {
            "Agent device action=capture_screenshot outcome=unavailable " +
                "code=$code excludedPackages=${excludedPackages.size}"
        }
        return ScreenCapture.failed(source = "accessibility", code = code)
    }

    private fun screenSize(displayId: Int = Display.DEFAULT_DISPLAY): Pair<Int, Int> {
        AgentAccessibilityService.current()?.displaySize(displayId)?.let { return it }
        throw DeviceControlUnavailableException()
    }

    private fun validatePoint(x: Int, y: Int, displayId: Int = Display.DEFAULT_DISPLAY) {
        val (width, height) = screenSize(displayId)
        require(x in 0 until width && y in 0 until height) {
            "坐标超出屏幕范围：($x,$y) not in ${width}x$height"
        }
    }

    private fun List<UiNode>.toJsonArray(): JSONArray =
        JSONArray().also { array ->
            forEach { node -> array.put(node.toJson()) }
        }

    private fun UiNode.toJson(): JSONObject =
        JSONObject()
            .put("index", index)
            .put("text", text)
            .put("desc", desc)
            .put("class", className)
            .put("package", packageName)
            .put("view_id", viewId)
            .put("bounds", bounds.toShortString())
            .put("center", JSONObject().put("x", centerX).put("y", centerY))
            .put("clickable", clickable)
            .put("long_clickable", longClickable)
            .put("scrollable", scrollable)
            .put("focused", focused)
            .put("editable", editable)
            .put("password", password)
            .put("enabled", enabled)
            .apply {
                checked?.let { put("checked", it) }
                if (selected) put("selected", true)
                if (hint.isNotBlank()) put("hint", hint)
            }

    private fun errorJson(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message.take(240))
            .toString()

    private fun accessibilityUnavailable(): String = errorJson(
        "ACCESSIBILITY_UNAVAILABLE",
        "无障碍服务未连接；请在系统设置中启用 Eta 的无障碍服务后重试",
    )

    private fun nodeActionJson(
        tool: String,
        result: AgentAccessibilityService.NodeActionResult,
    ): String = JSONObject()
        .put("ok", result.ok)
        .put("tool", tool)
        .put("executor", "accessibility")
        .put("method", result.method)
        .apply {
            if (!result.ok) {
                put("code", result.code)
                result.message.takeIf(String::isNotBlank)?.let { put("message", it.take(240)) }
            }
        }
        .toString()

    private fun scrollActionJson(
        tool: String,
        result: AgentAccessibilityService.ScrollActionResult,
    ): String = JSONObject()
        .put("ok", result.ok)
        .put("tool", tool)
        .put("direction", result.direction.name.lowercase())
        .put("moved", result.moved)
        .put("executor", "accessibility")
        .put("method", result.method)
        .apply {
            result.deltaY?.let { put("delta_y", it) }
            result.deltaX?.let { put("delta_x", it) }
            if (!result.ok) {
                put("code", result.code)
                result.message.takeIf(String::isNotBlank)?.let { put("message", it.take(240)) }
            }
        }
        .toString()

    private fun okJson(tool: String, executor: String): String =
        JSONObject()
            .put("ok", true)
            .put("tool", tool)
            .put("executor", executor)
            .toString()

    private fun matches(value: String, needle: String, matchMode: String): Boolean =
        when (matchMode) {
            "exact" -> value == needle
            "regex" -> runCatching { Regex(needle).containsMatchIn(value) }.getOrDefault(false)
            else -> value.contains(needle, ignoreCase = true)
        }

    private fun AgentAccessibilityService.UiNode.toUiNode(): UiNode =
        UiNode(
            index = index,
            text = text,
            desc = contentDescription,
            className = className,
            packageName = packageName,
            viewId = viewId,
            bounds = bounds,
            clickable = clickable,
            longClickable = longClickable,
            scrollable = scrollable,
            focused = focused,
            editable = editable,
            password = password,
            enabled = enabled,
            checked = checked,
            selected = selected,
            hint = hint,
        )

    private fun waitForUiSettle(tool: String) {
        val delayMs = when (tool) {
            "tap", "long_press", "press_key" -> 350L
            "swipe" -> 650L
            "input_text" -> 500L
            else -> 250L
        }
        Thread.sleep(delayMs)
    }

    private enum class CanvasTarget(val wireName: String) {
        MAIN_DISPLAY("main_display"),
    }

    private data class ScreenCapture(
        val image: AgentModelClient.ModelImage?,
        val source: String,
        val complete: Boolean,
        val partial: Boolean,
        val expectedWindows: Int,
        val capturedWindows: Int,
        val missingWindowIds: List<Int> = emptyList(),
        val failureCodes: List<String> = emptyList(),
        val timedOut: Boolean = false,
        val criticalWindowMissing: Boolean = false,
        val failureCode: String? = null,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("source", source)
            .put("complete", complete)
            .put("partial", partial)
            .put("expected_windows", expectedWindows)
            .put("captured_windows", capturedWindows)
            .apply {
                if (missingWindowIds.isNotEmpty()) put("missing_window_ids", JSONArray(missingWindowIds))
                if (failureCodes.isNotEmpty()) put("failure_codes", JSONArray(failureCodes))
                if (timedOut) put("timed_out", true)
                if (criticalWindowMissing) put("critical_window_missing", true)
                failureCode?.let { put("code", it) }
            }

        companion object {
            fun notRequested(): ScreenCapture = ScreenCapture(
                image = null,
                source = "not_requested",
                complete = false,
                partial = false,
                expectedWindows = 0,
                capturedWindows = 0,
            )

            fun failed(source: String, code: String? = null): ScreenCapture = ScreenCapture(
                image = null,
                source = source,
                complete = false,
                partial = false,
                expectedWindows = 1,
                capturedWindows = 0,
                failureCode = code,
            )
        }
    }

    private companion object {
        const val MAX_INPUT_TEXT_CHARS = 2_000
        const val MAX_REPLACE_TEXT_CHARS = 8_000
        const val MAX_CLIPBOARD_TEXT_CHARS = 8_000
    }
}
