package io.github.mangi.eta.agent.runtime

import android.os.SystemClock
import io.github.mangi.eta.core.AgentLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把外部入口从前台工具的真实操作对象中隔离开。
 *
 * 关闭动作、窗口稳定确认和首张截图排除共享同一份入口描述，避免各层分别猜测入口状态。
 */
internal class EntrySurfaceGuard private constructor(
    internal val targetPackageName: String?,
    private val logger: AgentLogger,
    private val ownedSurfaceDismissal: (() -> Boolean)?,
) {
    private val triggered = AtomicBoolean(false)
    private val dismissalCompleted = AtomicBoolean(false)
    // 无障碍窗口可能早于退场 Surface 消失；必须由关闭后的首张截图消费，不能在关闭确认时清除。
    private val screenshotExclusionPending = AtomicBoolean(targetPackageName != null)

    val wasTriggered: Boolean
        get() = triggered.get()

    fun dismissOnce(): Boolean {
        if (dismissalCompleted.get()) return true
        if (!triggered.compareAndSet(false, true)) return dismissalCompleted.get()
        ownedSurfaceDismissal?.let { dismiss ->
            val startedAt = System.nanoTime()
            val completed = runCatching(dismiss).getOrDefault(false)
            val waitedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
            if (completed) {
                dismissalCompleted.set(true)
                logger.debug {
                    "Agent runtime owned entry surface dismissed before foreground operation " +
                        "waitedMs=$waitedMillis"
                }
            } else {
                // 自有入口关闭是幂等定向操作，失败后允许再次确认，不会误退底层 App。
                triggered.set(false)
                logger.warn(
                    "Agent runtime owned entry surface dismiss incomplete before foreground " +
                        "operation: waitedMs=$waitedMillis"
                )
            }
            return completed
        }

        // 无障碍回退退场已移除；没有自有入口关闭能力时无法完成退场。
        triggered.set(false)
        logger.warn("Agent runtime entry surface dismiss unavailable: no owned surface dismissal")
        return false
    }

    fun consumeScreenshotExcludedPackages(): Set<String> {
        val packageName = targetPackageName ?: return emptySet()
        return if (screenshotExclusionPending.compareAndSet(true, false)) {
            setOf(packageName)
        } else {
            emptySet()
        }
    }

    companion object {
        fun from(
            handoff: AgentRuntimeWire.EntryHandoff?,
            logger: AgentLogger,
            etaVoiceSurfaceDismissal: (() -> Boolean)? = null,
        ): EntrySurfaceGuard? {
            if (handoff?.dismissEntrySurfaceOnForegroundOperation != true) return null
            val packageName = when (handoff.source) {
                AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE -> ETA_PACKAGE_NAME
                else -> null
            }
            val ownedSurfaceDismissal = etaVoiceSurfaceDismissal.takeIf {
                handoff.source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE
            }
            return EntrySurfaceGuard(packageName, logger, ownedSurfaceDismissal)
        }

        private const val ETA_PACKAGE_NAME = "io.github.mangi.eta"
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
