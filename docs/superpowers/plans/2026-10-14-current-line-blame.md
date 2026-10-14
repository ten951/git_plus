# 光标行 Git Blame Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 IDEA 光标所在行末尾显示作者、提交时间和提交说明。

**Architecture:** 项目级服务管理编辑器监听、后台查询和行尾 inlay。先核实目标 SDK，优先复用 Git annotation 和工作区行映射；使用纯 Kotlin 格式化模块隔离时间逻辑。

**Tech Stack:** Kotlin、IntelliJ Platform、Git、Gradle、JUnit。

**Spec:** ../specs/2026-10-14-current-line-blame-design.md

## Global Constraints

- 仅主光标所在行显示；虚拟提示不修改源码。
- 不超过 72 小时（含边界）显示相对时间，超过后显示 `yyyy-MM-dd HH:mm:ss`，采用系统本地时区。
- 本地及已暂存未提交的修改显示“尚未提交”。
- 后台查询、约 150ms 防抖；文档或 Git 状态变化使缓存失效；过期查询不得更新 UI。
- 不 commit、push 或发布；在当前会话实施。

## Review Focus

1. 未保存的插入和删除不能将旧提交误配到当前行。
2. 已暂存修改不能被当成已提交内容。
3. 多编辑器、快速换行和关闭编辑器不能产生过期提示。
4. 无 HEAD、未跟踪文件、查询失败必须安静隐藏。
5. 深浅主题和软换行下文字应可读，且不会改变源文件内容。

### Task 1: 时间与展示文本

**Files:** 创建 `src/main/kotlin/com/github/ten951/gitplus/blame/BlamePresentation.kt`、`src/test/kotlin/com/github/ten951/gitplus/blame/BlamePresentationTest.kt`。

**Interfaces:** 提供 `formatCommitTime(committedAt: Instant, now: Instant, zone: ZoneId): String` 和提交信息单行格式化入口。

- [x] 编写测试：刚刚、分钟、小时、天、恰好 72 小时、72 小时加一秒、本地时区、未来日期、说明只取第一行。
- [x] 运行测试确认缺少实现造成失败，然后实现并运行至通过。

### Task 2: Git 行归属查询

**Files:** 创建 `blame/BlameProvider.kt` 及对应 `BlameProviderTest.kt`；按 SDK 需要修改 `build.gradle.kts` 和 `plugin.xml` 的 Git 依赖。

**Interfaces:** 查询输入为文件与当前文档内容/版本，输出为提交信息、未提交状态或无提示；查询结果带版本供 Task 3 校验。

- [x] 核实目标 SDK 的 annotation、行映射和 inlay API；尝试现有构建，记录依赖或环境问题。
- [x] 使用临时 Git 仓库编写测试：已提交行、未保存插入/删除、已暂存修改、文件其他行修改、未跟踪文件、无 HEAD 和查询失败。
- [x] 先验证测试失败，再实现后台查询与版本缓存；若平台映射不满足准确性，使用 Git 结构化输出配合当前文档映射，记录选择依据。
- [x] 运行查询及格式化测试，确认修改行和未修改行的结果准确。

### Task 3: 编辑器集成与交付

**Files:** 创建 `blame/CurrentLineBlameService.kt`、`blame/BlameInlayRenderer.kt` 及平台测试；替换 `startup/MyProjectActivity.kt`，清理模板工具窗口、服务、测试和相关注册；更新 `README.md`、插件描述和项目名称。

**Interfaces:** 项目启动初始化服务；服务调用 Task 2 查询并以 Task 1 格式渲染。只在编辑器、文件、行与文档版本仍匹配时展示结果。

- [x] 编写并验证失败的平台测试：换行移除旧提示、文档修改清除提示、过期结果丢弃、多编辑器和释放时清理、源码不变。
- [x] 实现事件监听、防抖、后台查询、UI 渲染及生命周期清理；覆盖 Git 状态变化和主题更新。
- [x] 运行全量测试、`./gradlew buildPlugin` 及可用的平台验证任务；对失败记录具体原因，不能用单元测试替代沙盒视觉验证。
- [ ] 在可用沙盒中检查软换行、深浅主题和截图效果；若不能完成，交付时明确标注尚未验证的项目。
- [x] 更新安装和使用说明，提供构建产物路径、验证结果和剩余限制。

## 执行方式

建议由当前会话直接实施，不启用子代理。按三个任务顺序执行，先写测试再写实现；除非遇到需要用户选择的范围变化，否则持续推进到交付。
