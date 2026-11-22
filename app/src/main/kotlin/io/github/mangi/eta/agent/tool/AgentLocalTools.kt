package io.github.mangi.eta.agent.tool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.device.LocalNetworkPermission
import io.github.mangi.eta.agent.web.AgentWebTools
import io.github.mangi.eta.agent.web.WebHttpTransport
import io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy
import io.github.mangi.eta.agent.model.AgentTerminalToolCatalog
import io.github.mangi.eta.agent.model.AgentFileToolCatalog
import io.github.mangi.eta.agent.runtime.AgentAppContext
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.GuiControlCoordinator
import io.github.mangi.eta.agent.skill.SkillCompatibilityChecker
import io.github.mangi.eta.agent.skill.SkillIndexService
import io.github.mangi.eta.agent.skill.SkillInstallErrorCode
import io.github.mangi.eta.agent.skill.SkillInstallResult
import io.github.mangi.eta.agent.skill.SkillLoader
import io.github.mangi.eta.agent.skill.SkillPackageInstaller
import io.github.mangi.eta.agent.skill.SkillParser
import io.github.mangi.eta.agent.skill.SkillResourceReader
import io.github.mangi.eta.agent.skill.SkillResourceReadResult
import io.github.mangi.eta.agent.skill.GitHubSkillRepositoryParser
import io.github.mangi.eta.agent.skill.GitHubSkillInspection
import io.github.mangi.eta.agent.skill.GitHubSkillRepository
import io.github.mangi.eta.agent.skill.GitHubSkillSourceException
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.terminal.TerminalExecutionContext
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.agent.terminal.RootShellTerminalController
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.data.repository.AgentMemoryException
import io.github.mangi.eta.data.repository.AgentMemoryMutation
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentMemoryWriteResult
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.data.repository.WebToolsSettingsRepository
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.runBlocking

internal class AgentLocalTools(
    private val context: Context,
    private val logger: AgentLogger,
    private val browserRunId: String = "",
    private val browserToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS)
    },
    private val terminalToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS)
    },
    private val memoryToolsEnabled: () -> Boolean = {
        runBlocking { AgentMemoryRepository.isEnabled() }
    },
    private val memoryWritable: Boolean = true,
    private val beforeToolExecution: (String) -> ToolExecutionDecision = {
        ToolExecutionDecision.Allow
    },
    private val skillIndexService: SkillIndexService? = null,
    private val skillLoader: SkillLoader? = null,
    private val skillResourceReader: SkillResourceReader? = null,
    private val githubSkillSource: PublicGitHubSkillSource? = null,
    private val skillPackageInstaller: SkillPackageInstaller? = null,
    runAvailableSkillIds: Set<String> = emptySet(),
    pendingSkillConflict: PendingSkillConflictCapability? = null,
    private val isToolEnabled: (String) -> Boolean = {
        Prefs.isToolEnabled(it)
    },
    private val guiControl: GuiControlCoordinator.RunControl? = null,
    private val imageBudgetProvider: () -> io.github.mangi.eta.agent.media.ModelImageBudget = {
        io.github.mangi.eta.agent.media.ModelImageBudget.DEFAULT
    },
    browserSession: AgentBrowserSession? = null,
) : AgentModelClient.ToolExecutor, AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val browserSessionRef = lazy { browserSession ?: AgentBrowserSession() }
    private val imageTools = AgentImageTools(context, imageBudgetProvider)
    private val webTools = AgentWebTools(
        transport = WebHttpTransport(AgentHttpClient.client),
        localNetworkAccess = { LocalNetworkPermission.accessState(context).name.lowercase(Locale.ROOT) },
        runtimeConfig = { runBlocking { WebToolsSettingsRepository.resolveRuntimeConfig(context) } },
    )
    val terminalExecutionContext = LinuxEnvironmentSettingsRepository.current(context).terminalEnvironment.let { environment ->
        TerminalExecutionContext(
            linuxEnvironment = environment,
            linuxRootfsPath = LinuxEnvironmentPaths.rootfsDir(context, checkNotNull(environment.linuxDistribution)).absolutePath,
            sharedMounts = SharedFolderMounts.current(),
        )
    }
    private val terminalController = RootShellTerminalController(
        logger = logger,
        executionContext = terminalExecutionContext,
        backgroundJobs = io.github.mangi.eta.agent.terminal.TerminalRuntime.backgroundJobs(context),
        detachedSupervisor = DetachedTaskSupervisor(
            logger = logger,
            recordsFile = DetachedTaskSupervisor.defaultRecordsFile(context),
            linuxRootfsPath = terminalExecutionContext.linuxRootfsPath,
            linuxSharedMountsProvider = { SharedFolderMounts.current() },
        ),
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
    )

    private val runAvailableSkillIds = runAvailableSkillIds
        .mapTo(mutableSetOf(), SkillParser::normalizeSkillLookup)
    private val mutatedSkillIds = ConcurrentHashMap.newKeySet<String>()
    private val skillTreeMutationUncertain = AtomicBoolean(false)
    private val pendingSkillConflict = AtomicReference(pendingSkillConflict)
    private val inspectedGitHubSnapshots =
        ConcurrentHashMap<String, GitHubInspectionSnapshot>()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (browserSessionRef.isInitialized()) browserSessionRef.value.interruptAgentAction(browserRunId)
        webTools.close()
        terminalController.interruptAll()
        githubSkillSource?.close()
        inspectedGitHubSnapshots.clear()
        guiControl?.close()
    }

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        if (closed.get()) throw AgentRunCancelledException()
        val control = guiControl
        return if (control != null && AgentToolRequirements.requiresGuiControl(toolCall.name)) {
            control.withAction { executeLocal(toolCall) }
        } else {
            executeLocal(toolCall)
        }
    }

    private fun executeLocal(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult =
        runCatching {
            if (closed.get()) throw AgentRunCancelledException()
            val args = JSONObject(toolCall.argumentsJson.ifBlank { "{}" })
            if (!isToolEnabled(toolCall.name)) {
                return@runCatching AgentModelClient.ToolResult(
                    content = errorResult("TOOL_DISABLED", "工具 ${toolCall.name} 已在工具列表中关闭，本次未执行"),
                    sensitive = AgentSensitiveToolPolicy.isSensitive(toolCall.name),
                )
            }
            memoryToolPermissionError(toolCall.name)?.let { return@runCatching it }
            when (val decision = beforeToolExecution(toolCall.name)) {
                ToolExecutionDecision.Allow -> Unit
                is ToolExecutionDecision.Reject -> {
                    return@runCatching textResult(
                        errorResult(
                            code = decision.code,
                            message = decision.message,
                        ),
                    )
                }
            }
            when (toolCall.name) {
                "browser_use" -> browserUse(args, toolCall.id)
                "web_search", "fetch_url" -> {
                    if (!browserToolsEnabled()) textResult(errorResult("BROWSER_TOOLS_DISABLED", "请先启用网页搜索、读取与浏览器工具"))
                    else textResult(webTools.execute(toolCall.name, args))
                }
                "set_clipboard" -> textResult(setClipboard(args))
                "get_clipboard" -> textResult(getClipboard())
                "read_image" -> fileVisionTool { imageTools.readImage(args) }
                in AgentTerminalToolCatalog.names -> textResult(terminalTool {
                    terminalController.executeTool(toolCall.name, args)
                })
                in AgentFileToolCatalog.names -> textResult(terminalTool { terminalController.fileTool(toolCall.name, args) })
                "memory_get" -> textResult(memoryGet(args))
                "memory_write" -> textResult(memoryWrite(args))
                "skills_list" -> textResult(skillsList(args))
                "skills_read" -> textResult(skillsRead(args))
                "skills_read_resource" -> textResult(skillsReadResource(args))
                "skills_list_curated" -> textResult(skillsListCurated())
                "skills_inspect_github" -> textResult(skillsInspectGitHub(args))
                "skills_install_from_github" -> textResult(skillsInstallFromGitHub(args))
                else -> textResult(
                    errorResult(
                        code = "UNKNOWN_TOOL",
                        message = "未知工具：${toolCall.name}"
                    )
                )
            }
        }.getOrElse { throwable ->
            if (throwable is AgentRunCancelledException) throw throwable
            textResult(
                errorResult(
                    code = when (throwable) {
                        is InvalidToolArgumentException, is org.json.JSONException -> "INVALID_ARGUMENT"
                        else -> "TOOL_ERROR"
                    },
                    message = throwable.message ?: throwable.javaClass.simpleName
                )
            )
        }.let { result ->
            if (result.sensitive || !AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
                result
            } else {
                result.copy(sensitive = true)
            }
        }

    private fun terminalTool(block: () -> String): String {
        if (!terminalToolsEnabled()) {
            return errorResult("TERMINAL_TOOLS_DISABLED", "请先启用终端/文件工具")
        }
        return block()
    }

    private fun fileVisionTool(block: () -> AgentModelClient.ToolResult): AgentModelClient.ToolResult {
        if (!terminalToolsEnabled()) {
            return textResult(errorResult("TERMINAL_TOOLS_DISABLED", "请先启用终端/文件工具"))
        }
        return block()
    }

    private fun memoryToolPermissionError(toolName: String): AgentModelClient.ToolResult? {
        if (toolName == "memory_write" && !memoryWritable) {
            return AgentModelClient.ToolResult(
                content = errorResult("REAL_MEMORY_READ_ONLY", "角色会话的现实记忆只读；剧情请使用角色记忆工具"),
                sensitive = true,
            )
        }
        if (toolName !in MEMORY_TOOL_NAMES || memoryToolsEnabled()) return null
        return AgentModelClient.ToolResult(
            content = errorResult("MEMORY_DISABLED", "记忆已在设置中关闭"),
            sensitive = true,
        )
    }

    private fun memoryGet(args: JSONObject): String = try {
        val result = AgentMemoryRepository.read(
            query = args.optString("query").takeIf(String::isNotBlank),
            startLine = args.optInt("start_line", 1),
            maxChars = args.optInt("max_chars", 12_000),
        )
        JSONObject()
            .put("ok", true)
            .put("revision", result.snapshot.revision)
            .put("bytes", result.snapshot.byteSize)
            .put("line_count", result.snapshot.lineCount)
            .put("start_line", result.startLine ?: JSONObject.NULL)
            .put("end_line", result.endLine ?: JSONObject.NULL)
            .put("matched_lines", result.matchedLines)
            .put("has_more", result.hasMore)
            .put("content", result.content)
            .toString()
    } catch (failure: AgentMemoryException) {
        errorResult(failure.code, failure.message ?: "记忆读取失败")
    }

    private fun memoryWrite(args: JSONObject): String = try {
        val revision = args.getString("revision")
        val mutation = when (args.getString("mode")) {
            "replace_range" -> AgentMemoryMutation.ReplaceRange(
                revision = revision,
                startLine = args.getInt("start_line"),
                endLine = args.getInt("end_line"),
                content = args.requiredText("content"),
            )
            "append" -> AgentMemoryMutation.Append(
                revision = revision,
                content = args.requiredText("content"),
            )
            "clear" -> AgentMemoryMutation.Clear(revision)
            else -> error("不支持的记忆写入模式")
        }
        when (val result = AgentMemoryRepository.mutate(mutation)) {
            is AgentMemoryWriteResult.Success -> JSONObject()
                .put("ok", true)
                .put("revision", result.snapshot.revision)
                .put("bytes", result.snapshot.byteSize)
                .put("line_count", result.snapshot.lineCount)
                .toString()
            is AgentMemoryWriteResult.Conflict -> JSONObject()
                .put("ok", false)
                .put("code", "MEMORY_CONFLICT")
                .put("message", "记忆已发生变化，请先调用 memory_get 获取最新内容")
                .put("revision", result.snapshot.revision)
                .put("bytes", result.snapshot.byteSize)
                .put("line_count", result.snapshot.lineCount)
                .toString()
        }
    } catch (failure: AgentMemoryException) {
        errorResult(failure.code, failure.message ?: "记忆写入失败")
    }

    private fun browserUse(args: JSONObject, toolCallId: String): AgentModelClient.ToolResult {
        if (!browserToolsEnabled()) {
            return textResult(errorResult("BROWSER_TOOLS_DISABLED", "请先启用网页浏览工具"))
        }
        val result = browserSessionRef.value.execute(
            context = context,
            args = args,
            runId = browserRunId,
            toolCallId = toolCallId,
            checkCancellation = { if (closed.get()) throw AgentRunCancelledException() },
        )
        return AgentModelClient.ToolResult(
            content = result.content,
            images = result.images.map { image ->
                AgentModelClient.ModelImage(
                    reference = image.dataUrl,
                    mimeType = image.mimeType,
                    bytes = image.bytes,
                    width = image.width,
                    height = image.height,
                    source = "agent_browser",
                    preserveOriginal = true,
                )
            },
        )
    }

    private fun checkCancellation() {
        if (closed.get() || Thread.currentThread().isInterrupted) throw AgentRunCancelledException()
    }

    private fun setClipboard(args: JSONObject): String {
        val text = args.requiredText("text")
        if (text.length > MAX_CLIPBOARD_TEXT_CHARS) {
            return errorResult("TEXT_TOO_LONG", "剪贴板文本最多支持 $MAX_CLIPBOARD_TEXT_CHARS 个字符")
        }
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val ok = runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText("eta_agent", text))
            true
        }.getOrDefault(false)
        val json = JSONObject()
            .put("ok", ok)
            .put("tool", "set_clipboard")
            .put("chars", text.length)
        if (!ok) {
            json.put("code", "CLIPBOARD_WRITE_FAILED").put("message", "写入系统剪贴板失败")
        }
        return json.toString()
    }

    private fun getClipboard(): String {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = runCatching { clipboard.primaryClip }.getOrNull()
        val text = clip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(requireContext())?.toString().orEmpty()
        val json = JSONObject()
            .put("ok", true)
            .put("tool", "get_clipboard")
            .put("text", text.take(8_000))
            .put("truncated", text.length > 8_000)
        if (text.isEmpty()) {
            json.put("code", "CLIPBOARD_EMPTY").put("message", "剪贴板为空，或当前应用无权读取剪贴板")
        }
        return json.toString()
    }

    private fun requireContext(): Context =
        AgentAppContext.resolve()
            ?: error("无法获取 Android 进程 Context")

    private fun String.isOkJson(): Boolean =
        runCatching { JSONObject(this).optBoolean("ok", false) }.getOrDefault(false)

    private fun JSONObject.optNullableInt(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null

    // ==================== Skills tools ====================

    private fun skillsList(args: JSONObject): String {
        if (skillTreeMutationUncertain.get()) return nextTurnRequired("Skill 树")
        val indexService = skillIndexService
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能服务未初始化")
        val query = args.optString("query").trim().lowercase()
        val limit = args.optInt("limit", 50).coerceIn(1, 200)
        val entries = indexService.listInstalledSkills()
            .filter { entry -> SkillCompatibilityChecker.evaluate(entry).available }
            .filter { entry -> isVisibleInCurrentRun(entry.id) }
            .filter { entry ->
                if (query.isBlank()) true
                else listOf(entry.id, entry.name, entry.description, entry.skillFilePath, entry.rootPath)
                    .any { it.lowercase().contains(query) }
            }
            .take(limit)
        val items = JSONArray()
        entries.forEach { entry ->
            val capabilities = JSONArray()
            if (entry.hasScripts) capabilities.put("scripts")
            if (entry.hasReferences) capabilities.put("references")
            if (entry.hasAssets) capabilities.put("assets")
            if (entry.hasEvals) capabilities.put("evals")
            items.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("name", entry.name)
                    .put("description", entry.description)
                    .put("enabled", entry.enabled)
                    .put("source", entry.source)
                    .put("rootPath", entry.rootPath)
                    .put("skillFilePath", entry.skillFilePath)
                    .put("capabilities", capabilities)
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("count", entries.size)
            .put("items", items)
            .toString()
    }

    private fun skillsRead(args: JSONObject): String {
        if (skillTreeMutationUncertain.get()) return nextTurnRequired("Skill 树")
        val indexService = skillIndexService
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能服务未初始化")
        val loader = skillLoader
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能加载器未初始化")
        val skillId = args.optString("skillId").trim()
        if (skillId.isBlank()) return errorResult("MISSING_PARAM", "缺少 skillId")
        val maxChars = args.optInt("maxChars", 16_000).coerceIn(512, 64_000)
        val entry = indexService.findInstalledSkill(skillId)
            ?: return errorResult("NOT_FOUND", "未找到 skill：$skillId")
        if (!isVisibleInCurrentRun(entry.id)) return nextTurnRequired(entry.id)
        val compat = SkillCompatibilityChecker.evaluate(entry)
        if (!compat.available) return errorResult("INCOMPATIBLE", compat.reason ?: "当前环境不可用")
        val resolved = loader.load(entry, "agent 主动读取 skill")
            ?: return errorResult("READ_FAILED", "读取 SKILL.md 失败：${entry.skillFilePath}")
        val body = if (resolved.bodyMarkdown.length <= maxChars) {
            resolved.bodyMarkdown
        } else {
            resolved.bodyMarkdown.take(maxChars) + "\n..."
        }
        val references = JSONArray()
        resolved.loadedReferences.forEach { references.put(it) }
        val frontmatter = JSONObject()
        resolved.frontmatter.forEach { (k, v) -> frontmatter.put(k, v) }
        return JSONObject()
            .put("ok", true)
            .put("id", entry.id)
            .put("name", entry.name)
            .put("description", entry.description)
            .put("rootPath", entry.rootPath)
            .put("skillFilePath", entry.skillFilePath)
            .put("scriptsDir", resolved.scriptsDir ?: JSONObject.NULL)
            .put("assetsDir", resolved.assetsDir ?: JSONObject.NULL)
            .put("references", references)
            .put("frontmatter", frontmatter)
            .put("bodyMarkdown", body)
            .toString()
    }

    private fun skillsReadResource(args: JSONObject): String {
        if (skillTreeMutationUncertain.get()) return nextTurnRequired("Skill 树")
        val indexService = skillIndexService
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能服务未初始化")
        val reader = skillResourceReader
            ?: return errorResult("SKILLS_UNAVAILABLE", "Skill 资源读取器未初始化")
        val skillId = args.getString("skillId").trim()
        val relativePath = args.getString("relativePath").trim()
        val maxChars = args.optInt("maxChars", 16_000).coerceIn(512, 64_000)
        val entry = indexService.findInstalledSkill(skillId)
            ?: return errorResult("NOT_FOUND", "未找到已启用 Skill：$skillId")
        if (!isVisibleInCurrentRun(entry.id)) return nextTurnRequired(entry.id)
        val compatibility = SkillCompatibilityChecker.evaluate(entry)
        if (!compatibility.available) {
            return errorResult(
                "INCOMPATIBLE",
                compatibility.reason ?: "当前环境不可用",
            )
        }
        return when (val result = reader.readText(entry, relativePath)) {
            is SkillResourceReadResult.Success -> {
                val truncated = result.text.length > maxChars
                val visibleText = if (truncated) {
                    result.text.take(maxChars).let { prefix ->
                        if (prefix.lastOrNull()?.isHighSurrogate() == true) {
                            prefix.dropLast(1)
                        } else {
                            prefix
                        }
                    }
                } else {
                    result.text
                }
                JSONObject()
                    .put("ok", true)
                    .put("skillId", entry.id)
                    .put("relativePath", result.relativePath)
                    .put("text", visibleText)
                    .put("truncated", truncated)
                    .put("totalChars", result.text.length)
                    .toString()
            }
            is SkillResourceReadResult.Failure -> errorResult(
                code = result.error.code.name,
                message = result.error.message,
            )
        }
    }

    private fun skillsListCurated(): String {
        val source = githubSkillSource
            ?: return errorResult("SKILL_INSTALLER_UNAVAILABLE", "GitHub Skill 服务未初始化")
        return skillSourceResult {
            val inspection = source.listCurated()
            rememberInspection(
                repository = GitHubSkillRepositoryParser.parse(inspection.repository),
                inspection = inspection,
                rememberDefault = true,
            )
            inspectionResult(inspection)
        }
    }

    private fun skillsInspectGitHub(args: JSONObject): String {
        val source = githubSkillSource
            ?: return errorResult("SKILL_INSTALLER_UNAVAILABLE", "GitHub Skill 服务未初始化")
        return skillSourceResult {
            val repository = GitHubSkillRepositoryParser.resolve(
                repository = args.getString("repository"),
                explicitRef = args.optString("ref").takeIf { args.has("ref") },
                explicitPath = args.optString("path").takeIf { args.has("path") },
            )
            val inspection = source.inspect(repository)
            rememberInspection(
                repository = repository,
                inspection = inspection,
                rememberDefault = repository.ref == null,
            )
            inspectionResult(inspection)
        }
    }

    private fun skillsInstallFromGitHub(args: JSONObject): String {
        val replaceExisting = args.optBoolean("replaceExisting", false)
        return skillSourceResult {
            val requestedRepository = GitHubSkillRepositoryParser.resolve(
                repository = args.getString("repository"),
                explicitRef = args.optString("ref").takeIf { args.has("ref") },
                explicitPath = null,
            )
            val pathsJson = args.getJSONArray("paths")
            val selectedPaths = (0 until pathsJson.length()).map { index ->
                GitHubSkillRepositoryParser.normalizeRelativePath(pathsJson.getString(index))
            }
            if (replaceExisting && selectedPaths.size != 1) {
                return@skillSourceResult errorResult(
                    "SKILL_REPLACE_SCOPE_TOO_BROAD",
                    "一次只能替换一个 Skill 路径；请逐个重试",
                )
            }
            val expectedReplacementId = args.optString("expectedReplacementId").trim()
            val repository = if (replaceExisting) {
                validateReplacementReplay(
                    requestedRepository = requestedRepository,
                    selectedPaths = selectedPaths,
                    expectedReplacementId = expectedReplacementId,
                )?.let { return@skillSourceResult it }
                requestedRepository.copy(ref = pendingSkillConflict.get()!!.commitSha)
            } else {
                val snapshot = inspectedGitHubSnapshots[
                    inspectionKey(requestedRepository.slug, requestedRepository.ref)
                ] ?: return@skillSourceResult errorResult(
                    "SKILL_INSPECTION_REQUIRED",
                    "安装前必须在本轮先检查同一仓库与 ref 的 Skill 候选",
                )
                val invalidSelection = selectedPaths.firstOrNull {
                    it !in snapshot.candidatesByPath
                }
                if (invalidSelection != null) {
                    return@skillSourceResult errorResult(
                        "INVALID_SKILL_SELECTION",
                        "所选路径不在本轮检查返回的候选中：$invalidSelection",
                    )
                }
                val snapshotPrefix = snapshot.prefix
                if (
                    snapshotPrefix != null &&
                    selectedPaths.any {
                        it != snapshotPrefix && !it.startsWith("$snapshotPrefix/")
                    }
                ) {
                    return@skillSourceResult errorResult(
                        "INVALID_SKILL_SELECTION",
                        "所选路径不在本轮检查的目录范围内",
                    )
                }
                requestedRepository.copy(ref = snapshot.commitSha)
            }
            val prefix = requestedRepository.path?.takeUnless { it == "." }
            if (
                prefix != null &&
                selectedPaths.any { it != prefix && !it.startsWith("$prefix/") }
            ) {
                return@skillSourceResult errorResult(
                    "INVALID_SKILL_SELECTION",
                    "所选路径不在 GitHub URL 指定目录内",
                )
            }
            val source = githubSkillSource
                ?: return@skillSourceResult errorResult(
                    "SKILL_INSTALLER_UNAVAILABLE",
                    "GitHub Skill 服务未初始化",
                )
            val installer = skillPackageInstaller
                ?: return@skillSourceResult errorResult(
                    "SKILL_INSTALLER_UNAVAILABLE",
                    "Skill 安装器未初始化",
                )
            source.downloadArchive(repository).use { archive ->
                if (closed.get()) {
                    return@skillSourceResult errorResult(
                        "SKILL_INSTALL_CANCELLED",
                        "Skill 安装已取消，未提交文件",
                    )
                }
                val result = installer.installRepositoryZip(
                    openStream = { archive.file.inputStream() },
                    selectedPaths = selectedPaths,
                    replaceUserSkills = replaceExisting,
                    expectedReplacementIds = if (replaceExisting) {
                        setOf(expectedReplacementId)
                    } else {
                        emptySet()
                    },
                    isCancelled = closed::get,
                )
                installResult(
                    result = result,
                    repository = archive.repository,
                    ref = archive.ref,
                    commitSha = archive.commitSha,
                    selectedPaths = selectedPaths,
                )
            }
        }
    }

    private fun inspectionResult(
        inspection: io.github.mangi.eta.agent.skill.GitHubSkillInspection,
    ): String {
        val installedIds = skillIndexService
            ?.listSkillsForManagement()
            .orEmpty()
            .filter { it.installed }
            .mapTo(mutableSetOf()) { SkillParser.normalizeSkillLookup(it.id) }
        val items = JSONArray()
        inspection.candidates.forEach { candidate ->
            items.put(
                JSONObject()
                    .put("name", candidate.name)
                    .put("path", candidate.path)
                    .put(
                        "installed",
                        SkillParser.normalizeSkillLookup(candidate.name) in installedIds,
                    ),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("repository", inspection.repository)
            .put("ref", inspection.ref)
            .put("commitSha", inspection.commitSha)
            .put("prefix", inspection.prefix ?: JSONObject.NULL)
            .put("count", inspection.candidates.size)
            .put("items", items)
            .toString()
    }

    private fun rememberInspection(
        repository: GitHubSkillRepository,
        inspection: GitHubSkillInspection,
        rememberDefault: Boolean,
    ) {
        val snapshot = GitHubInspectionSnapshot(
            commitSha = inspection.commitSha,
            prefix = inspection.prefix,
            candidatesByPath = inspection.candidates.associate { it.path to it.name },
        )
        inspectedGitHubSnapshots[inspectionKey(repository.slug, repository.ref)] = snapshot
        inspectedGitHubSnapshots[inspectionKey(repository.slug, inspection.ref)] = snapshot
        inspectedGitHubSnapshots[inspectionKey(repository.slug, inspection.commitSha)] = snapshot
        if (rememberDefault) {
            inspectedGitHubSnapshots[inspectionKey(repository.slug, null)] = snapshot
        }
    }

    private fun inspectionKey(repository: String, ref: String?): String =
        "${repository.lowercase(Locale.ROOT)}@${ref.orEmpty()}"

    private fun validateReplacementReplay(
        requestedRepository: GitHubSkillRepository,
        selectedPaths: List<String>,
        expectedReplacementId: String,
    ): String? {
        val pending = pendingSkillConflict.get() ?: return errorResult(
            "SKILL_REPLACE_CAPABILITY_REQUIRED",
            "没有可供精确重放的 Skill 冲突",
        )
        if (
            !requestedRepository.slug.equals(pending.repository, ignoreCase = true) ||
            requestedRepository.ref != pending.commitSha ||
            selectedPaths.singleOrNull() != pending.selectedPath ||
            expectedReplacementId != pending.expectedReplacementId
        ) {
            return errorResult(
                "SKILL_REPLACE_CAPABILITY_MISMATCH",
                "覆盖参数必须精确重放冲突结果中的仓库、commitSha、路径与 Skill ID",
            )
        }
        return null
    }

    private fun isVisibleInCurrentRun(skillId: String): Boolean {
        val normalized = SkillParser.normalizeSkillLookup(skillId)
        return normalized in runAvailableSkillIds && normalized !in mutatedSkillIds
    }

    private fun nextTurnRequired(skillId: String): String = errorResult(
        "NEXT_TURN_REQUIRED",
        "Skill $skillId 在本轮已安装或变更，将从下一轮对话开始可用",
    )

    private fun installResult(
        result: SkillInstallResult,
        repository: String,
        ref: String,
        commitSha: String,
        selectedPaths: List<String>,
    ): String = when (result) {
        is SkillInstallResult.Success -> {
            pendingSkillConflict.set(null)
            val installed = JSONArray()
            result.installed.forEach { skill ->
                mutatedSkillIds += SkillParser.normalizeSkillLookup(skill.id)
                installed.put(
                    JSONObject()
                        .put("id", skill.id)
                        .put("name", skill.name),
                )
            }
            JSONObject()
                .put("ok", true)
                .put("repository", repository)
                .put("ref", ref)
                .put("commitSha", commitSha)
                .put("selectedPaths", JSONArray(selectedPaths))
                .put("installed", installed)
                .put("available", "next_turn")
                .put("scriptsExecuted", false)
                .put("message", "Skill 已安装并启用，将从下一轮对话开始可用；安装过程未执行脚本")
                .toString()
        }
        is SkillInstallResult.Conflict -> {
            val conflicts = JSONArray()
            result.conflicts.forEach { conflict ->
                conflicts.put(
                    JSONObject()
                        .put("id", conflict.id)
                        .put("name", conflict.name)
                        .put("replaceAllowed", conflict.replaceAllowed),
                )
            }
            pendingSkillConflict.set(
                result.conflicts.singleOrNull()
                    ?.takeIf { it.replaceAllowed && selectedPaths.size == 1 }
                    ?.let { conflict ->
                        PendingSkillConflictCapability(
                            repository = repository,
                            commitSha = commitSha,
                            selectedPath = selectedPaths.single(),
                            expectedReplacementId = conflict.id,
                            expectedReplacementName = conflict.name,
                        )
                    },
            )
            JSONObject()
                .put("ok", false)
                .put("code", "SKILL_CONFLICT")
                .put("message", "Skill 已存在；可替换的单个用户 Skill 可按返回参数直接重试，内置 Skill 不可覆盖")
                .put("repository", repository)
                .put("ref", ref)
                .put("commitSha", commitSha)
                .put("selectedPaths", JSONArray(selectedPaths))
                .put("conflicts", conflicts)
                .toString()
        }
        is SkillInstallResult.Failure -> {
            if (result.error.code == SkillInstallErrorCode.COMMIT_FAILED) {
                skillTreeMutationUncertain.set(true)
            }
            errorResult(
                code = result.error.code.name,
                message = result.error.message,
            )
        }
    }

    private inline fun skillSourceResult(block: () -> String): String = try {
        block()
    } catch (failure: GitHubSkillSourceException) {
        errorResult(failure.code, failure.message ?: "GitHub Skill 请求失败")
    }

    private fun errorResult(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()

    private fun textResult(content: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(content)

    private class InvalidToolArgumentException(message: String) : IllegalArgumentException(message)

    private data class GitHubInspectionSnapshot(
        val commitSha: String,
        val prefix: String?,
        val candidatesByPath: Map<String, String>,
    )

    private companion object {
        val MEMORY_TOOL_NAMES = setOf("memory_get", "memory_write")
        const val MAX_CLIPBOARD_TEXT_CHARS = 1_000
    }
}
