package io.github.mangi.eta.ui.app

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.PermissionHealthItemUi
import io.github.mangi.eta.ui.model.PermissionHealthUiState
import io.github.mangi.eta.ui.model.PermissionStatusUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 权限健康检查。 */
internal class PermissionHealthStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    var permissionHealthState by mutableStateOf(PermissionHealthUiState(emptyList()))
        private set


    private var permissionRefreshJob: Job? = null

    fun refreshPermissionHealth() {
        permissionRefreshJob?.cancel()
        permissionRefreshJob = scope.launch(Dispatchers.IO) {
            val refreshed = buildPermissionHealthState(appContext)
            withContext(Dispatchers.Main) { permissionHealthState = refreshed }
        }
    }
}

private fun buildPermissionHealthState(context: Context): PermissionHealthUiState {
    val backgroundRunningEnabled = isIgnoringBatteryOptimizations(context)
    val appListEnabled = hasAppListAccess(context)
    val notificationsEnabled = context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()

    return PermissionHealthUiState(
        items = listOfNotNull(
            PermissionHealthItemUi(
                id = "background",
                title = context.getString(R.string.state_background_running_permission_dde21b),
                summary = "",
                status = if (backgroundRunningEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (backgroundRunningEnabled) null else context.getString(R.string.state_ui_to_open_13ec17),
            ),
            PermissionHealthItemUi(
                id = "app_list",
                title = context.getString(R.string.state_application_list_reading_135f16),
                summary = "",
                status = if (appListEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (appListEnabled) null else context.getString(R.string.state_ui_to_open_13ec17),
            ),
            localNetworkPermissionHealthItem(context),
            PermissionHealthItemUi(
                id = "notifications",
                title = context.getString(R.string.capability_notifications_title),
                summary = context.getString(R.string.capability_notifications_summary),
                status = if (notificationsEnabled) PermissionStatusUi.Available else PermissionStatusUi.Disabled,
                primaryActionLabel = context.getString(R.string.state_ui_go_to_settings_1f2998),
            ),
        )
    )
}


private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
}

private fun hasAppListAccess(context: Context): Boolean {
    return try {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0)
        packages.size > 10
    } catch (e: Exception) {
        false
    }
}
