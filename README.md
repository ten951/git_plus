# Git Plus

IntelliJ IDEA 插件：在主光标所在行的末尾自动显示 Git Blame 信息。

```java
return result;  yangxingnong · 2天前 · fix: 修复查询逻辑
return result;  yangxingnong · 2026-10-10 11:30:05 · fix: 修复查询逻辑
```

提示使用随主题变化的灰色虚拟文字，不修改源码，也不会进入复制内容或 Git diff。

## 行为

- 仅在普通代码编辑器的主光标所在行显示，换行后更新。
- 提交距当前时间不超过 72 小时（含边界）显示相对时间；超过后显示 `yyyy-MM-dd HH:mm:ss`。
- 作者使用 Git 作者名；时间使用提交的 committer 时间，并按本机时区展示。
- 行尾提交说明只显示第一行。未保存、未提交或已暂存的修改行显示“尚未提交”。
- 文件其他行发生插入、删除或修改时，未修改行仍保留其提交归属。
- 非 Git、未跟踪、无提交仓库及二进制文件不显示提示；查询失败安静隐藏，诊断记录到 IDE debug 日志。
- 光标变化采用 150ms 防抖，Git 查询在后台执行，过期结果丢弃。
- 鼠标在行尾提示停留 400ms，显示作者、完整时间、完整提交 SHA 和完整提交说明；可选择、复制和滚动。未提交行显示说明，不查询提交详情。
- 鼠标移开、Escape、移动主光标到其他行、修改文档、滚动或关闭编辑器时关闭详情并取消后台任务。

## 安装

1. 下载或构建 `build/distributions/git_plus-1.2.0.zip`。
2. IDEA 的 Settings / Preferences → Plugins → 齿轮菜单 → Install Plugin from Disk，选择 ZIP。
3. 按 IDEA 提示重启，打开 Git 仓库中已提交的代码文件，移动光标即可。

需要启用 IDEA 内置 Git 插件，且 Settings → Version Control → Git 中的 Git 可执行文件有效。
默认编译 SDK 为 IDEA 2025.3.6.1，最低版本为 2025.3（build 253）；另设固定 2026.3 EAP（263.6800.24）构建测试门禁。
尚未发布到 Marketplace。

## 构建与测试

需要 JDK 21。首次构建会下载 Gradle、IDEA SDK 和测试依赖。

```sh
./gradlew test
./gradlew buildPlugin verifyPluginStructure
./gradlew verifyPlugin -PverificationIdePath=/path/to/IDE/Contents
./gradlew runIde
```

`runIde` 启动独立沙盒 IDEA。可通过 `-PlocalIdePath=/path/to/IDE/Contents` 使用本地 SDK；默认仍使用 2025.3.6.1。

测试包含时间边界、真实临时 Git 仓库、未保存编辑、暂存改动、HEAD 变化、非 UTF-8 文件、编辑器状态和异步刷新。测试中的提交仅发生在临时仓库。

`verifyPlugin` 可通过 `verificationIdePath` 复用本地 IDE 做 API 兼容性检查；未指定时下载推荐的验证版本。

## 实现与限制

通过 Git `blame --line-porcelain --contents -` 对当前文档快照查询归属，避免自行推测编辑后的行号；所有 Git 查询均为只读操作。查询缓存最多保留16个文件、合计10万行，以文件、HEAD、索引状态、内容和字符集区分，并在相关事件后失效。

提交说明按仓库和SHA按需查询，合并并发请求，最多缓存128项/100万字符；单条说明超过128000字符时显示加载失败提示，不静默截断。作者、时间及SHA复用Blame数据。仓库HEAD/index及mailmap/attributes变化使相关缓存失效。

查询前还原文件的换行符和 BOM；新增、删除主光标会重新定位提示。可见编辑器每分钟重新检查提交状态和时间。

当前不提供设置页或提交跳转（Commit Diff计划在V1.3实现）。超过 100 万字符的文档、超过 32 MB 的查询输出或单条命令耗时超过 15 秒时不显示提示，以限制后台资源使用。查询使用临时文件传递文档内容与输出，完成或取消后清理。

EAP 验证：用 JBR25 启动 Gradle，并传入 `-PlocalIdePath=/path/to/EAP/Contents`，或 `-PtargetIdeaVersion=263.6800.24`。目标 EAP 的编译/平台测试、静态 API 检查和实际 IDE 运行分别记录。

V1.2实施及验证结果见 [docs/V1_2_IMPLEMENTATION.md](docs/V1_2_IMPLEMENTATION.md)。
