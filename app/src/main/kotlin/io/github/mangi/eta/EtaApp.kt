package io.github.mangi.eta

import io.github.mangi.eta.agent.voice.SpeechOssUpload
import android.app.Application
import android.content.ComponentCallbacks2
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.ScheduledTaskRepository
import io.github.mangi.eta.ui.app.PredictiveBackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** 模块 UI 进程的 Application。 */
class EtaApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AppStrings.init(this)
        Prefs.initLocal(this)
        if (!AppProcessPolicy.shouldInitializeFullRuntime(Application.getProcessName(), packageName)) {
            return
        }
        TerminalRuntime.initialize(this)
        applicationScope.launch { TerminalRuntime.backgroundJobs(this@EtaApp).resume() }
        SettingsDataStore.init(this)
        val predictiveBackEnabled = runBlocking(Dispatchers.IO) {
            AppearanceSettingsRepository.settings().predictiveBackEnabled
        }
        PredictiveBackController.apply(applicationInfo, predictiveBackEnabled)
        AgentMemoryRepository.init(this)
        ProviderRepository.init(this)
        McpServerRepository.init(this)
        ScheduledTaskRepository.init(this)
        applicationScope.launch {
            runCatching {
                ScheduledTaskRepository.reconcileAfterRestartOrClockChange(
                    recomputeAllSchedules = false,
                    rescheduleAlarm = true,
                    context = this@EtaApp,
                )
            }.onFailure { throwable ->
                AndroidAgentLogger.warn(
                    "Scheduled task reconcile failed: type=${throwable.safeLogType()}"
                )
            }
        }
        applicationScope.launch {
            try {
                SpeechOssUpload(this@EtaApp).retryPending(this@EtaApp)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                AndroidAgentLogger.warn("Eta speech cleanup unavailable: type=${error.javaClass.simpleName}")
            }
        }
        applicationScope.launch {
            LinuxEnvironmentSettingsRepository.initialize(this@EtaApp)
            runCatching {
                SkillRuntime.createIndexService(this@EtaApp).listInstalledSkills()
            }.onFailure { throwable ->
                AndroidAgentLogger.warn(
                    "Agent skill index prewarm failed: type=${throwable.safeLogType()}"
                )
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            BrowserSessionRegistry.releaseIdlePages()
        }
    }
}
