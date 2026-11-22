# 全局系统提示词（角色模块）

把系统提示词从「模型服务商详情页」移到「角色」模块下，作为全局设置：换任何模型/服务商都生效。

- 补丁文件：`docs/patches/global-system-prompt.patch`（基于 `3a24d81d` 之后的代码）
- 落地提交：`081e9699`（功能）+ `c87b2164`（修复英文文案撇号转义）

## 上游更新后如何重打

1. 合并上游后，先确认下列文件是否被上游改动；有冲突时以补丁意图为准手工合并。
2. 或直接尝试：`git apply docs/patches/global-system-prompt.patch`（冲突时改用 `--3way`）。

## 改动要点（手工重打时的核对清单）

1. `config/Prefs.kt`：新增键 `AGENT_GLOBAL_SYSTEM_PROMPT = "agent_global_system_prompt"`。
2. `data/repository/RuntimeConfigRepository.kt` 的 `buildRuntimeConfig`：
   ```kotlin
   val globalPrompt = Prefs.getString(Prefs.Keys.AGENT_GLOBAL_SYSTEM_PROMPT).trim()
   val systemPrompt = globalPrompt.ifBlank { provider.systemPrompt.orEmpty().trim() }
   ```
   全局提示词优先；留空回退服务商级配置（`ProviderSetting.systemPrompt` 数据字段保留，兼容旧数据）。
3. `ui/navigation/AppRoute.kt`：新增 `data object CharacterSystemPrompt : AppRoute`。
4. `ui/screens/characters/CharacterContextScreens.kt`：新增 `CharacterSystemPromptScreen`（直接读写 Prefs，COUI / MIUIX 双风格）。
5. `ui/screens/characters/CharacterLibraryScreen.kt`：在「我的」分组（人设入口旁）加入口，两种 UI 风格都要加。
6. `ui/app/AgentAppRoot.kt`：注册 `entry<AppRoute.CharacterSystemPrompt>` 并导入新页面。
7. `ui/app/AgentAppShell.kt`：路由标题映射到 `R.string.character_system_prompt_title`。
8. `ui/pages/providers/ModelProviderDetailScreen.kt`：删除「偏好与策略」分组下的系统提示词编辑块（COUI 与 MIUIX 两个分支），并清理未用导入（`CouiTextField`、`rememberDraftTextFieldState`、`TextFieldLineLimits`、`SmallTitle`、`CouiPreferenceGroup`、`TextField`）。
9. 字符串资源（values / values-b+zh+Hans / values-b+zh+Hant 的 `strings_characters.xml`）：
   - `character_system_prompt_title` / `_entry_summary` / `_hint` / `_content_label` / `_save`

## 踩坑记录

- 英文 `character_system_prompt_hint` 含撇号必须转义（`provider\'s`），否则资源合并失败。
- 角色卡提示词优先级高于全局提示词：`AgentPromptBuilder.buildInitialMessages` 中 `roleplayContext != null` 时不使用 `config.systemPrompt`，此逻辑保持不动。
- `android-release.yml` 只在 push 到 `main` 或 `v*` 标签时触发；`my-version` 分支需手动 workflow_dispatch。
