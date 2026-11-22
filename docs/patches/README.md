# 个人定制补丁集（my-version）

本目录收录 `my-version` 分支相对上游 `Mangi-11/Eta` 的全部个人改动。
分叉基线：`cf98d1e0`（上游 Merge pull request #313）。

## 补丁清单（按应用顺序）

| 序号 | 文件 | 内容 | 对应提交 |
|---|---|---|---|
| 01 | `01-strip-xposed-root-device-tools.patch` | 剔除 Xposed 模块声明、Root 设备控制、手机控制功能、无障碍与 GUI 设备控制工具族 | `7992d7f9`…`082abe8e` |
| 02 | `02-ci-manual-build.patch` | 新增手动触发的构建流程；缺签名 Secrets 时降级为仅出 Debug 包 | `05e6e519`、`b9b26fd2` |
| 03 | `03-remove-system-assistant-overlay.patch` | 删除系统助手浮窗链路（入口、服务与设置页）及关联修复 | `0e37fd10`…`c5ad4d6c` |
| 04 | `04-remove-home-suggestion-cards.patch` | 移除主页空会话建议卡片 | `6c0ce42b` |
| 05 | `05-remove-voice-module.patch` | 移除应用内语音模块（保留 handoff 机制常量） | `3a24d81d` |
| 06 | `06-global-system-prompt-and-docs.patch` | 系统提示词移至角色模块全局生效 + 本补丁目录 | `081e9699`…`88edc3e3` |
| 07 | `07-sidebar-mcp-entry.patch` | 侧边栏工作台 Skills 下方新增 MCP 服务器入口（设置页原入口保留） | `068bee4c` |
| 全量 | `full-personal-modifications.patch` | 以上全部改动的合并差异（含本目录） | `cf98d1e0`…`88edc3e3` |

## 上游更新后的重打方法

```bash
# 合并上游后，按序尝试应用（冲突时加 --3way）：
git apply --3way docs/patches/01-strip-xposed-root-device-tools.patch
git apply --3way docs/patches/02-ci-manual-build.patch
# ……依序到 06
# 或一步到位：
git apply --3way docs/patches/full-personal-modifications.patch
```

- 冲突高发区：上游若重构了工具注册表、设置页结构或主页布局，对应补丁需按意图手工合并，不要盲目保一边。
- 应用后必跑：`lint` 与单元测试（CI 的 verify job 会自动执行）。

## 各补丁要点与踩坑

1. **01 剔除工具族**：删除量大（约 3.5 万行）。注意 `gradle/libs.versions.toml` 同步删依赖；测试断言需排除被剔除集合；音量图标用镜像命名空间（`c4f7aabc`）。
2. **02 CI**：`android-release.yml` 只在 push `main` 或 `v*` 标签时自动触发，`my-version` 需手动 workflow_dispatch；缺签名 Secrets 时只出 Debug 包。
3. **03 系统助手浮窗**：`SYSTEM_ALERT_WINDOW` 权限随此删除；现存 overlay 字符串/组件是玻璃 UI 命名，与该权限无关，不要误恢复。
4. **04 建议卡片**：只删主页四个建议卡片，注意键盘控制器引用（`c5ad4d6c` 的修复属于 03 区间，勿漏）。
5. **05 语音模块**：保留 `ETA_VOICE_HANDOFF_SOURCE` 常量与 handoff 机制；语音阶段枚举需补回（`30d4fe3c`）。
6. **06 全局系统提示词**：优先级为 全局提示词 > 服务商级配置 > 内置默认；`ProviderSetting.systemPrompt` 字段保留以兼容旧数据。英文文案撇号必须转义（`provider\'s`）。角色卡提示词优先于全局提示词（`AgentPromptBuilder` 的 roleplay 分支不动）。
7. **07 侧边栏 MCP 入口**：回调链为 `AgentAppRoot → AgentAppShell → ConversationSidePaneScaffold → ConversationPanePanel → PaneWorkbenchSection`，新增参数均带默认值，上游若改侧边栏结构按此链路重接；图标为 `ConversationPaneIcons.Mcp`（插头造型，等线宽风格）。

## 维护约定

- 每完成一个新功能或剔除，用 `git diff <基线>..<提交>` 生成新补丁并更新本 README。
- 全量补丁在每次推送前重新生成，保持与分支一致。
