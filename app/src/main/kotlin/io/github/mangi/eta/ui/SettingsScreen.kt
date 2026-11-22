package io.github.mangi.eta.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.ImportContacts
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.JoinLeft
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SportsBar
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.BuildConfig
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.ImageGenSettings
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.repository.ImageGenSettingsRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.data.update.AppLatestRelease
import io.github.mangi.eta.data.update.AppUpdateChecker
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.LanguagePreference
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.StatusWarning
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiDropdownPreference
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiPreferenceGroup
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSettingsIcon
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.screens.imagegen.shortName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private object SettingsIconColors {
    val Blue = Color(0xFF4285F4)
    val Green = StatusSuccess
    val Orange = Color(0xFFFA7B17)
    val Yellow = StatusWarning
}

@Composable
private fun SettingsGroupCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        content()
    }
}

@Composable
private fun SettingsIcon(
    icon: ImageVector,
    enabled: Boolean = true,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier
            .padding(end = 8.dp)
            .size(24.dp),
        tint = if (enabled) {
            MiuixTheme.colorScheme.onBackground
        } else {
            MiuixTheme.colorScheme.disabledOnSecondaryVariant
        },
    )
}

/**
 * 模块配置界面。
 *
 * 开关默认值由 [Prefs.Keys.BOOLEAN_DEFAULTS] 统一定义，写入 App 本地配置。
 */
@Composable
internal fun SettingsScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPageContent(context = context, onNavigate = onNavigate, onBack = onBack)
}

@Composable
private fun SettingsPageContent(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()

    // 关于组：版本信息与更新检查。结果对话框在列表外渲染，状态需要页面级 owner。
    val appVersionName = BuildConfig.VERSION_NAME
    val appVersionSummary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    val openUrl: (String) -> Unit = { url ->
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
    var checkingUpdate by remember { mutableStateOf(false) }
    var availableUpdate by remember { mutableStateOf<AppLatestRelease?>(null) }
    var retainedUpdate by remember { mutableStateOf<AppLatestRelease?>(null) }
    if (availableUpdate != null) retainedUpdate = availableUpdate
    val checkForUpdate: () -> Unit = {
        if (!checkingUpdate) {
            checkingUpdate = true
            coroutineScope.launch {
                val release = runCatching {
                    withContext(Dispatchers.IO) { AppUpdateChecker.fetchLatest() }
                }.getOrNull()
                checkingUpdate = false
                when {
                    release == null -> Toast.makeText(
                        context.applicationContext,
                        context.getString(R.string.ui_update_check_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                    AppUpdateChecker.isNewer(release.version, appVersionName) -> availableUpdate = release
                    else -> Toast.makeText(
                        context.applicationContext,
                        context.getString(R.string.ui_update_already_latest),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    // Provider / Model 选中状态展示
    val initialProviders = remember { ProviderRepository.cachedProviders() }
    val initialProviderId = remember { RuntimeConfigRepository.cachedSelectedProviderId() }
    val initialModelId = remember { RuntimeConfigRepository.cachedSelectedModelId() }
    val providers by ProviderRepository.providersFlow().collectAsState(initial = initialProviders)
    val selectedProviderId by RuntimeConfigRepository.selectedProviderIdFlow()
        .collectAsState(initial = initialProviderId)
    val selectedModelId by RuntimeConfigRepository.selectedModelIdFlow()
        .collectAsState(initial = initialModelId)
    val selectedProvider = remember(providers, selectedProviderId) {
        providers.find { it.id == selectedProviderId }
    }
    val selectedModel = remember(selectedProvider, selectedModelId) {
        selectedProvider?.models?.find { it.id == selectedModelId }
    }
    val providerSummary = selectedProvider?.let { provider ->
        val providerDisplayName = BuiltinProviders.defaultNameRes(provider.id, provider.name)?.let { stringResource(it) } ?: provider.name
        "$providerDisplayName / ${selectedModel?.displayName ?: stringResource(R.string.settings_model_not_selected)}"
    } ?: stringResource(R.string.settings_not_configured)

    val agentPrefs = remember { Prefs.localAgentPreferences() }
    val pickerState = remember(providers, selectedProviderId, selectedModelId) {
        AgentModelPickerProjector.project(providers, selectedProviderId, selectedModelId)
    }
    val effectivePrimary = remember(providers, pickerState.selectedModel?.id) {
        providers.enabledModel(pickerState.selectedModel?.id)
    }
    val effectiveCapabilities = remember(effectivePrimary) {
        effectivePrimary?.let { (provider, model) ->
            RuntimeConfigRepository.reasoningCapabilities(provider, model)
        }
    }
    val effortOptions = remember(effectiveCapabilities) {
        effectiveCapabilities?.selectableEfforts
            ?.filter { it != ReasoningEffort.OFF }
            .orEmpty()
    }
    val providerDefaultLabel = stringResource(R.string.conversation_title_settings_reasoning_provider_default)
    val effortLabels = remember(effortOptions, providerDefaultLabel) {
        effortOptions.map { effort ->
            when (effort) {
                ReasoningEffort.DEFAULT -> providerDefaultLabel
                else -> effort.displayName
            }
        }
    }
    val defaultThinkingKey = Prefs.Keys.AGENT_THINKING_ENABLED
    val defaultThinkingFallback = Prefs.Keys.BOOLEAN_DEFAULTS.getValue(defaultThinkingKey)
    var defaultThinkingEnabled by remember(agentPrefs) {
        mutableStateOf(
            agentPrefs?.getBoolean(defaultThinkingKey, defaultThinkingFallback)
                ?: defaultThinkingFallback,
        )
    }
    var savedDefaultEffort by remember(agentPrefs) {
        mutableStateOf(Prefs.defaultReasoningEffort(agentPrefs))
    }
    DisposableEffect(agentPrefs) {
        val targetPrefs = agentPrefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, key ->
            when (key) {
                defaultThinkingKey -> {
                    defaultThinkingEnabled = changedPrefs.getBoolean(defaultThinkingKey, defaultThinkingFallback)
                }
                Prefs.Keys.AGENT_DEFAULT_REASONING_EFFORT -> {
                    savedDefaultEffort = Prefs.defaultReasoningEffort(changedPrefs)
                }
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val effectiveSelectedEffort = remember(effectiveCapabilities, savedDefaultEffort) {
        effectiveCapabilities?.normalize(savedDefaultEffort) ?: ReasoningEffort.DEFAULT
    }
    val selectedEffortIndex = remember(effortOptions, effectiveSelectedEffort) {
        effortOptions.indexOf(effectiveSelectedEffort).takeIf { it >= 0 } ?: 0
    }
    val onDefaultEffortSelect: (Int) -> Unit = { index ->
        val chosen = effortOptions.getOrNull(index)
        val targetPrefs = agentPrefs
        if (chosen != null && targetPrefs != null) {
            if (putStringSync(targetPrefs, Prefs.Keys.AGENT_DEFAULT_REASONING_EFFORT, chosen.wireValue)) {
                savedDefaultEffort = chosen
            } else {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    val defaultEffortTitle = stringResource(R.string.settings_default_reasoning_effort)
    val defaultEffortUnsupported = stringResource(R.string.conversation_title_settings_reasoning_unsupported)
    val defaultEffortEnabled = agentPrefs != null && defaultThinkingEnabled && effortOptions.size > 1
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(
                title = stringResource(R.string.ui_set_up_7debf9),
                onBack = onBack,
                overlay = {
                    val activeUpdate = availableUpdate ?: retainedUpdate
                    CouiConfirmDialog(
                        visible = availableUpdate != null,
                        title = stringResource(R.string.ui_update_available_title),
                        message = activeUpdate?.let { update ->
                            stringResource(R.string.ui_update_available_message, update.version, appVersionName)
                        },
                        confirmText = stringResource(R.string.ui_update_go_download),
                        confirmEnabled = activeUpdate != null,
                        onConfirm = {
                            val target = activeUpdate
                            availableUpdate = null
                            if (target != null) openUrl(target.url)
                        },
                        onDismissRequest = { availableUpdate = null },
                        onDismissFinished = { if (availableUpdate == null) retainedUpdate = null },
                    )
                },
            ) {
                CouiPreferenceGroup(title = stringResource(R.string.settings_llm_providers)) {
                    item {
                        CouiPreference(
                            title = stringResource(R.string.ui_model_provider_e8c7f5),
                            summary = providerSummary,
                            icon = { CouiSettingsIcon(Icons.Rounded.Cloud, SettingsIconColors.Blue) },
                            onClick = { onNavigate(AppRoute.ModelProviders) },
                        )
                    }
                    item {
                        SwitchPref(
                            context = context,
                            prefs = agentPrefs,
                            title = stringResource(R.string.ui_deep_thinking_enabled_by_default_c032d6),
                            key = Prefs.Keys.AGENT_THINKING_ENABLED,
                            icon = Icons.Rounded.Psychology,
                            iconTint = SettingsIconColors.Blue,
                        )
                    }
                    if (effortOptions.size > 1) {
                        item {
                            CouiDropdownPreference(
                                title = defaultEffortTitle,
                                items = effortLabels,
                                selectedIndex = selectedEffortIndex,
                                onSelectedIndexChange = onDefaultEffortSelect,
                                icon = {
                                    CouiSettingsIcon(
                                        icon = Icons.Rounded.Tune,
                                        tint = SettingsIconColors.Blue,
                                        enabled = defaultEffortEnabled,
                                    )
                                },
                                enabled = defaultEffortEnabled,
                            )
                        }
                    } else {
                        item {
                            CouiPreference(
                                title = defaultEffortTitle,
                                summary = defaultEffortUnsupported,
                                icon = {
                                    CouiSettingsIcon(
                                        icon = Icons.Rounded.Tune,
                                        tint = SettingsIconColors.Blue,
                                        enabled = false,
                                    )
                                },
                                enabled = false,
                                showChevron = false,
                            )
                        }
                    }
                }

                CouiPreferenceGroup(title = stringResource(R.string.settings_context_extensions)) {
                    item {
                        SwitchPref(
                            context = context,
                            prefs = agentPrefs,
                            title = stringResource(R.string.settings_auto_compaction),
                            key = Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED,
                            icon = Icons.Rounded.Layers,
                            iconTint = SettingsIconColors.Blue,
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.conversation_title_settings_title),
                            icon = { CouiSettingsIcon(Icons.Rounded.AutoAwesome, SettingsIconColors.Blue) },
                            onClick = { onNavigate(AppRoute.ConversationTitleSettings) },
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.ui_memory_b55ff5),
                            icon = {
                                CouiSettingsIcon(
                                    Icons.AutoMirrored.Rounded.MenuBook,
                                    SettingsIconColors.Orange,
                                )
                            },
                            onClick = { onNavigate(AppRoute.Memory) },
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.ui_misc_route_subagents),
                            icon = { CouiSettingsIcon(Icons.Rounded.JoinLeft, SettingsIconColors.Blue) },
                            onClick = { onNavigate(AppRoute.Subagents) },
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.route_skills),
                            icon = {
                                CouiSettingsIcon(
                                    Icons.Rounded.ImportContacts,
                                    SettingsIconColors.Green,
                                )
                            },
                            onClick = { onNavigate(AppRoute.Skills) },
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.route_mcp_servers),
                            icon = {
                                CouiSettingsIcon(
                                    Icons.Rounded.AccountTree,
                                    SettingsIconColors.Blue,
                                )
                            },
                            onClick = { onNavigate(AppRoute.McpServers) },
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.route_scheduled_tasks),
                            icon = {
                                CouiSettingsIcon(
                                    Icons.Rounded.Schedule,
                                    SettingsIconColors.Blue,
                                )
                            },
                            onClick = { onNavigate(AppRoute.ScheduledTasks) },
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.conversation_dock_characters),
                            icon = {
                                CouiSettingsIcon(
                                    Icons.Rounded.SportsBar,
                                    SettingsIconColors.Orange,
                                )
                            },
                            onClick = { onNavigate(AppRoute.Characters) },
                        )
                    }
                }

                CouiPreferenceGroup(title = stringResource(R.string.ui_tool_a72ef1)) {
                    item {
                        CouiPreference(
                            title = stringResource(R.string.settings_tools_list),
                            icon = { CouiSettingsIcon(Icons.Rounded.Dashboard, SettingsIconColors.Green) },
                            onClick = { onNavigate(AppRoute.Tools) },
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.web_tools_settings_title),
                            icon = {
                                CouiSettingsIcon(
                                    Icons.Rounded.TravelExplore,
                                    SettingsIconColors.Blue,
                                )
                            },
                            onClick = { onNavigate(AppRoute.WebToolsSettings) },
                        )
                    }
                    item {
                        val imageGenSettings by remember { ImageGenSettingsRepository.settingsFlow() }
                            .collectAsState(initial = ImageGenSettings())
                        CouiPreference(
                            title = stringResource(R.string.image_gen_settings_title),
                            value = if (imageGenSettings.enabled) imageGenSettings.backend.shortName() else stringResource(R.string.ui_misc_not_enabled),
                            icon = {
                                CouiSettingsIcon(
                                    Icons.Rounded.Image,
                                    SettingsIconColors.Orange,
                                )
                            },
                            onClick = { onNavigate(AppRoute.ImageGenSettings) },
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.ui_linux_tool_environment_314d22),
                            icon = {
                                CouiSettingsIcon(
                                    Icons.Rounded.Inventory2,
                                    SettingsIconColors.Orange,
                                )
                            },
                            onClick = { onNavigate(AppRoute.LinuxEnvironment) },
                        )
                    }
                }

                CouiPreferenceGroup(title = stringResource(R.string.settings_general)) {
                    item {
                        SwitchPref(
                            context = context,
                            prefs = agentPrefs,
                            title = stringResource(R.string.settings_keep_screen_on),
                            key = Prefs.Keys.AGENT_KEEP_SCREEN_ON_ENABLED,
                            icon = Icons.Rounded.BrightnessHigh,
                            iconTint = SettingsIconColors.Orange,
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.appearance_title),
                            icon = { CouiSettingsIcon(Icons.Rounded.Palette, SettingsIconColors.Orange) },
                            onClick = { onNavigate(AppRoute.AppearanceSettings) },
                        )
                    }
                    item {
                        LanguagePreference(
                            iconTint = SettingsIconColors.Blue,
                            startAction = {
                                CouiSettingsIcon(Icons.Rounded.Language, SettingsIconColors.Blue)
                            },
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.data_backup_title),
                            icon = {
                                CouiSettingsIcon(
                                    Icons.Rounded.Description,
                                    SettingsIconColors.Blue,
                                )
                            },
                            onClick = { onNavigate(AppRoute.DataBackup) },
                        )
                    }
                }

                CouiPreferenceGroup(title = stringResource(R.string.ui_about_bed172)) {
                    item {
                        CouiPreference(
                            title = stringResource(R.string.ui_about_version_title),
                            summary = appVersionSummary,
                            icon = { CouiSettingsIcon(Icons.Rounded.Info, SettingsIconColors.Blue) },
                            showChevron = false,
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.ui_about_update_title),
                            summary = if (checkingUpdate) {
                                stringResource(R.string.ui_about_update_checking)
                            } else {
                                null
                            },
                            icon = {
                                CouiSettingsIcon(
                                    icon = Icons.Rounded.SystemUpdate,
                                    tint = SettingsIconColors.Green,
                                    enabled = !checkingUpdate,
                                )
                            },
                            enabled = !checkingUpdate,
                            onClick = checkForUpdate,
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.ui_about_feedback_title),
                            icon = {
                                CouiSettingsIcon(
                                    Icons.Rounded.BugReport,
                                    SettingsIconColors.Orange,
                                )
                            },
                            onClick = { openUrl("https://github.com/Mangi-11/Eta/issues") },
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.ui_about_github_star_title),
                            summary = stringResource(R.string.ui_about_github_star_hint),
                            icon = { CouiSettingsIcon(Icons.Rounded.Code, SettingsIconColors.Blue) },
                            onClick = { openUrl("https://github.com/Mangi-11/Eta") },
                        )
                    }
                }

                CouiPageBottomSpacer()
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.ui_set_up_7debf9),
                onBack = onBack,
                overlay = {
                    val activeUpdate = availableUpdate ?: retainedUpdate
                    GlassDialog(
                        show = availableUpdate != null,
                        backdrop = currentGlassOverlayBackdrop(),
                        title = stringResource(R.string.ui_update_available_title),
                        summary = activeUpdate?.let { update ->
                            stringResource(R.string.ui_update_available_message, update.version, appVersionName)
                        },
                        onDismissRequest = { availableUpdate = null },
                        onDismissFinished = { if (availableUpdate == null) retainedUpdate = null },
                    ) {
                        MiuixDialogActions(
                            confirmText = stringResource(R.string.ui_update_go_download),
                            confirmEnabled = activeUpdate != null,
                            onCancel = { availableUpdate = null },
                            onConfirm = {
                                val target = activeUpdate
                                availableUpdate = null
                                if (target != null) openUrl(target.url)
                            },
                        )
                    }
                },
            ) {
                item(key = "section_agent_title") {
                    SmallTitle(stringResource(R.string.settings_llm_providers))
                }
                item(key = "section_agent") {
                    SettingsGroupCard {
                        ArrowPreference(
                            title = stringResource(R.string.ui_model_provider_e8c7f5),
                            summary = providerSummary,
                            startAction = { SettingsIcon(Icons.Rounded.Cloud) },
                            onClick = { onNavigate(AppRoute.ModelProviders) },
                        )
                        SwitchPref(
                            context = context,
                            prefs = agentPrefs,
                            title = stringResource(R.string.ui_deep_thinking_enabled_by_default_c032d6),
                            key = Prefs.Keys.AGENT_THINKING_ENABLED,
                            icon = Icons.Rounded.Psychology,
                        )
                        if (effortOptions.size > 1) {
                            GlassDropdownPreference(
                                title = defaultEffortTitle,
                                items = effortLabels,
                                selectedIndex = selectedEffortIndex,
                                backdrop = currentGlassOverlayBackdrop(),
                                enabled = defaultEffortEnabled,
                                onSelectedIndexChange = onDefaultEffortSelect,
                                startAction = {
                                    SettingsIcon(
                                        icon = Icons.Rounded.Tune,
                                        enabled = defaultEffortEnabled,
                                    )
                                },
                            )
                        } else {
                            BasicComponent(
                                title = defaultEffortTitle,
                                summary = defaultEffortUnsupported,
                                startAction = {
                                    SettingsIcon(
                                        icon = Icons.Rounded.Tune,
                                        enabled = false,
                                    )
                                },
                                enabled = false,
                            )
                        }
                    }
                }

                item(key = "section_context_extensions_title") {
                    SmallTitle(stringResource(R.string.settings_context_extensions))
                }
                item(key = "section_context_extensions") {
                    SettingsGroupCard {
                        SwitchPref(
                            context = context,
                            prefs = agentPrefs,
                            title = stringResource(R.string.settings_auto_compaction),
                            key = Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED,
                            icon = Icons.Rounded.Layers,
                        )
                        ArrowPreference(
                            title = stringResource(R.string.conversation_title_settings_title),
                            startAction = { SettingsIcon(Icons.Rounded.AutoAwesome) },
                            onClick = { onNavigate(AppRoute.ConversationTitleSettings) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.ui_memory_b55ff5),
                            startAction = { SettingsIcon(Icons.AutoMirrored.Rounded.MenuBook) },
                            onClick = { onNavigate(AppRoute.Memory) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.ui_misc_route_subagents),
                            startAction = { SettingsIcon(Icons.Rounded.JoinLeft) },
                            onClick = { onNavigate(AppRoute.Subagents) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.route_skills),
                            startAction = { SettingsIcon(Icons.Rounded.ImportContacts) },
                            onClick = { onNavigate(AppRoute.Skills) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.route_mcp_servers),
                            startAction = { SettingsIcon(Icons.Rounded.AccountTree) },
                            onClick = { onNavigate(AppRoute.McpServers) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.route_scheduled_tasks),
                            startAction = { SettingsIcon(Icons.Rounded.Schedule) },
                            onClick = { onNavigate(AppRoute.ScheduledTasks) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.conversation_dock_characters),
                            startAction = { SettingsIcon(Icons.Rounded.SportsBar) },
                            onClick = { onNavigate(AppRoute.Characters) },
                        )
                    }
                }

                item(key = "section_tools_title") {
                    SmallTitle(stringResource(R.string.ui_tool_a72ef1))
                }
                item(key = "section_tools") {
                    SettingsGroupCard {
                        ArrowPreference(
                            title = stringResource(R.string.settings_tools_list),
                            startAction = { SettingsIcon(Icons.Rounded.Dashboard) },
                            onClick = { onNavigate(AppRoute.Tools) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.web_tools_settings_title),
                            startAction = { SettingsIcon(Icons.Rounded.TravelExplore) },
                            onClick = { onNavigate(AppRoute.WebToolsSettings) },
                        )
                        val imageGenSettings by remember { ImageGenSettingsRepository.settingsFlow() }
                            .collectAsState(initial = ImageGenSettings())
                        ArrowPreference(
                            title = stringResource(R.string.image_gen_settings_title),
                            summary = if (imageGenSettings.enabled) {
                                stringResource(R.string.ui_misc_enabled_with_value, imageGenSettings.backend.shortName())
                            } else {
                                stringResource(R.string.ui_misc_not_enabled)
                            },
                            startAction = { SettingsIcon(Icons.Rounded.Image) },
                            onClick = { onNavigate(AppRoute.ImageGenSettings) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.ui_linux_tool_environment_314d22),
                            startAction = { SettingsIcon(Icons.Rounded.Inventory2) },
                            onClick = { onNavigate(AppRoute.LinuxEnvironment) },
                        )
                    }
                }

                item(key = "section_general_title") {
                    SmallTitle(stringResource(R.string.settings_general))
                }
                item(key = "section_general") {
                    SettingsGroupCard {
                        SwitchPref(
                            context = context,
                            prefs = agentPrefs,
                            title = stringResource(R.string.settings_keep_screen_on),
                            key = Prefs.Keys.AGENT_KEEP_SCREEN_ON_ENABLED,
                            icon = Icons.Rounded.BrightnessHigh,
                        )
                        ArrowPreference(
                            title = stringResource(R.string.appearance_title),
                            startAction = { SettingsIcon(Icons.Rounded.Palette) },
                            onClick = { onNavigate(AppRoute.AppearanceSettings) },
                        )
                        LanguagePreference(
                            startAction = { SettingsIcon(Icons.Rounded.Language) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.data_backup_title),
                            startAction = { SettingsIcon(Icons.Rounded.Description) },
                            onClick = { onNavigate(AppRoute.DataBackup) },
                        )
                    }
                }

                item(key = "section_about_title") {
                    SmallTitle(stringResource(R.string.ui_about_bed172))
                }
                item(key = "section_about") {
                    SettingsGroupCard {
                        BasicComponent(
                            title = stringResource(R.string.ui_about_version_title),
                            summary = appVersionSummary,
                            startAction = { SettingsIcon(Icons.Rounded.Info) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.ui_about_update_title),
                            summary = if (checkingUpdate) {
                                stringResource(R.string.ui_about_update_checking)
                            } else {
                                null
                            },
                            startAction = {
                                SettingsIcon(
                                    icon = Icons.Rounded.SystemUpdate,
                                    enabled = !checkingUpdate,
                                )
                            },
                            enabled = !checkingUpdate,
                            onClick = checkForUpdate,
                        )
                        ArrowPreference(
                            title = stringResource(R.string.ui_about_feedback_title),
                            startAction = { SettingsIcon(Icons.Rounded.BugReport) },
                            onClick = { openUrl("https://github.com/Mangi-11/Eta/issues") },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.ui_about_github_star_title),
                            summary = stringResource(R.string.ui_about_github_star_hint),
                            startAction = { SettingsIcon(Icons.Rounded.Code) },
                            onClick = { openUrl("https://github.com/Mangi-11/Eta") },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchPref(
    context: Context,
    prefs: SharedPreferences?,
    title: String,
    summary: String? = null,
    key: String,
    icon: ImageVector,
    iconTint: Color = Color.Unspecified,
) {
    val enabled = prefs != null
    val default = Prefs.Keys.BOOLEAN_DEFAULTS[key] ?: true
    var checked by remember(prefs, key) {
        mutableStateOf(prefs?.getBoolean(key, default) ?: default)
    }
    DisposableEffect(prefs, key) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, changedKey ->
            if (changedKey == key) {
                checked = changedPrefs.getBoolean(key, default)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val onToggle: (Boolean) -> Unit = { value ->
        val targetPrefs = prefs
        if (targetPrefs != null) {
            if (putBooleanSync(targetPrefs, key, value)) {
                checked = value
            } else {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiSwitchPreference(
                title = title,
                summary = summary,
                checked = checked,
                onCheckedChange = onToggle,
                icon = { CouiSettingsIcon(icon = icon, tint = iconTint, enabled = enabled) },
                enabled = enabled,
            )
        }
        AppearanceUiStyle.MIUIX -> {
            SwitchPreference(
                title = title,
                summary = summary,
                checked = checked,
                onCheckedChange = onToggle,
                startAction = {
                    SettingsIcon(icon = icon, enabled = enabled)
                },
                enabled = enabled,
            )
        }
    }
}

private fun putBooleanSync(
    prefs: SharedPreferences,
    key: String,
    value: Boolean,
): Boolean =
    runCatching { prefs.edit().putBoolean(key, value).commit() }.getOrDefault(false)

private fun putStringSync(
    prefs: SharedPreferences,
    key: String,
    value: String,
): Boolean =
    runCatching { prefs.edit().putString(key, value).commit() }.getOrDefault(false)


