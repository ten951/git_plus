# V1.3.0 实施与验证记录

基线681579a（V1.2.0），当前干净功能分支v1.1-stability。用户明确要求直接实现、测试、打包，无需反复确认；沿用现有工作目录，不修改用户Git配置，不push/合并。

计划：[2026-10-23-v1.3-commit-diff.md](superpowers/plans/2026-10-23-v1.3-commit-diff.md)。

选择：使用Git4Idea的GitChangeUtils获取原生Change，再调用ShowDiffAction。相比自行取文本构造Diff，可复用原生二进制、编码、首次提交及改名处理；相比打开整个Commit文件列表，直接定位用户悬浮的文件。默认第一父提交，未提交行无入口，后台加载与卡片生命周期分离。

Ruling: 用户已明确跳过重复确认，直接执行本计划；沿用当前功能分支，避免迁移既有SDK缓存/沙盒。重大范围限定为当前文件Diff，不加入设置页或整提交浏览。

## 实际结果

以下记录实际测试、失败及修复、双SDK/Verifier、用户辅助的原生界面验证和交付产物。

阶段1：新接口实现前编译RED（pathAtCommit/CommitDiffLoader缺失），实现后253全量52测试通过（32s）。真实Git覆盖首次提交空前版本、修改且工作区另有未保存内容、改名前后路径/历史Blame路径、merge第一父提交、缺失对象/文件及非法请求。普通修改/merge同时读取原生ContentRevision内容，确认没有与工作区比较。

阶段2：卡片入口/独立控制器实现前RED（新增factory回调/Controller缺失）；完整59测试曾发现2项失败：Unix反斜杠文件名误转斜杠导致选不到Change，已改为仅转换当前系统分隔符；原生process路径标题读取LightFixture的temp://根失败，测试改用同一原生Producer的createSimpleRequest内容工厂（不更改生产展示路径）。最终253全量59测试通过（26s），含父提交/提交的原生左右Document内容、未提交无入口、失效/双击拒绝旧请求、关闭卡片不取消Diff、后台加载/EDT展示、释放取消、迟到结果拒绝和错误提示。

独立审查发现并复现P1：浅克隆边界的git show隐藏真实父提交，原生Change误报首次添加。真实--depth 1克隆测试RED（不应展示空文件却接受了Change），修复后检查common Git shallow边界和raw commit父字段，明确提示历史不完整；真正root保留空前版本。审查者复核确认修复有效。最终253完整62测试（0失败/错误）、buildPlugin和verifyPluginStructure通过（26s）；另补真实root带shallow标记与linked worktree共享目录测试。

## 最终发布验证（2026-10-23）

| 检查 | 实际结果 | 证据 |
| --- | --- | --- |
| 253.33813.55 / JDK21，cleanTest test buildPlugin verifyPluginStructure | 62测试，0失败/错误，BUILD SUCCESSFUL，最终46s | `build/v13-253-modules-final.log`、`build/v13-release-253/test-results/` |
| 固定263.6800.24 / JBR25，同样任务及prepareSandbox_runIde | 62测试，0失败/错误，BUILD SUCCESSFUL，32s | `build/v13-263-final.log`、`build/v13-release-263/test-results/` |
| 原版Plugin Verifier 1.410，同一253 ZIP →253 | Compatible，6s | `build/reports/v13-verifier-253/IU-253.33813.55/plugins/com.github.ten951.gitplus/1.3.0/verification-verdict.txt` |
| 原版Plugin Verifier 1.410，同一253 ZIP →263 | Compatible，8s | `build/reports/v13-verifier-263/IU-263.6800.24/plugins/com.github.ten951.gitplus/1.3.0/verification-verdict.txt` |
| 原生Diff内容工厂 | 对真实Git修改构造原生SimpleDiffRequest；左侧old、右侧new，与dirty工作区内容无关 | CommitDiffLoaderTest |
| 独立分支审查 | 1个已确认P1浅克隆问题已RED→GREEN修复，审查者复核无新重要问题 | 上述阶段记录 |

EAP编译曾失败：新使用的VcsException/Change/ShowDiffAction在263不再由默认核心类路径提供；第一次补齐core/vcs/impl后仍缺少ChangeList，按实际所属JAR补上vcs.shared。最终显式声明vcs.core、vcs.shared、vcs、vcs.impl并在两个SDK重跑完整门禁。依赖均为SDK内置provided模块，无第三方依赖和额外打包。原版Verifier结果未被用来代替源码编译或实际运行。

生产代码本次无新增弃用API编译警告。测试保留旧FileEditorManagerEvent构造器警告；两处测试用VcsUtil.getFilePath(String)有弃用提示，不进入插件产物。原生Diff测试的LightFixture使用temp://根，不能测试真实窗口路径标题，因此内容测试调用同一原生Producer的createSimpleRequest；实际生产展示仍调用ShowDiffAction。

### 精确发布包与EAP沙盒

交付 `build/distributions/git_plus-1.3.0.zip`（约173KiB），固定使用253 SDK构建；263构建结果单独保存在`build/v13-release-263/`。补齐显式模块依赖后最终253包与Verifier检查/沙盒安装的ZIP字节完全相同。

SHA-256：`21b4f2bc1c010d4e6d7ad9828565da299b3886f269be398cbcbef2bb7cd69c04`。

在独立263 EAP config/system/plugins沙盒安装此253包，逐JAR比对通过。启动日志2026-10-23 17:27:51确认`Git Plus (com.github.ten951.gitplus, 1.3.0)`，本次启动段未发现ERROR/SEVERE或本插件异常。实际编辑器已打开临时Git仓库hover.txt的已提交修改行，保留灰色Inline Blame。未替换或重启用户IDE，临时提交使用命令级user.name/email，不修改用户Git配置。

原生按钮→Diff窗口的完整鼠标交互验收：**通过（用户辅助悬浮/点击，代理读取AX及截图核验）**。用户在测试文件第一行悬浮并点击卡片按钮后，实际出现IDEA原生双栏Diff页签、原生工具栏及“1 difference”标记。左侧修订64ce578e显示`hover details committed line`，右侧185d643c显示`V1.3 changed committed line`，第二行两侧均为`second untouched line`。已用真实Git命令确认185d643c的第一父提交就是64ce578e，左右内容完全对应。

该界面验证使用精确发布ZIP，无mock；root/改名/merge/浅克隆等边界由真实Git平台测试覆盖，未声称这些场景逐一进行了GUI点击。测试Diff窗口保持打开便于用户检查。

### 行为与限制

- 点击时固定原仓库、完整SHA和历史filename；比较该提交相对第一父提交的当前文件修改。Merge不提供父提交选择器。
- 真正初始提交左侧为空；浅克隆边界存在真实父提交时明确失败，用户自行补全历史后重试，不自动fetch。
- 使用Git4Idea原生Change/ContentRevision和Diff UI，复用其编码/二进制行为；行尾Blame仍不针对二进制文件显示。
- 文件/对象缺失时通知失败；未提交行无按钮。卡片关闭不取消已点击请求；编辑器/项目释放及新的Diff请求取消旧任务，不产生迟到窗口。
- 不新增设置页、整个Commit文件列表、远程提交网页或Marketplace发布；本次仅本地提交，不push/合并。

收尾：按verification-before-completion与finishing-a-development-branch核对产物、实际测试/Verifier结果、原生界面及工作区。用户已要求无需重复确认，本次保留功能分支的本地分阶段提交，不提供额外合并菜单，不push。

## PR #4：修复固定 EAP 的 CI 依赖解析（2026-10-26）

GitHub Actions 的 EAP job 在执行测试前失败，无法找到
`com.jetbrains.intellij.idea:idea:263.6800.24`。原因是构建脚本对 `263.*`
强制设置 `useInstaller = false`，将固定构建号交给 Maven SDK 仓库解析。
此前本机 EAP 验证使用 `localIdePath`，没有覆盖远程 SDK 获取路径。

修复：远程 SDK 统一使用 `useInstaller = true`，保留本地 SDK 入口和固定
`263.6800.24` 版本。未调整插件功能、Git 配置或 CI 检查门禁。

本次实际验证：

| 验证 | 结果 |
| --- | --- |
| 官方 Maven snapshot 仓库中的原坐标 POM | HTTP 404，与 CI 找不到依赖一致 |
| JetBrains 产品目录 | 列出 IIU / 2026.3 / build 263.6800.24，以及 Linux、macOS 安装包地址 |
| 官方 Linux 安装包 `https://download.jetbrains.com/idea/idea-263.6800.24.tar.gz` | HEAD 跟随重定向后 HTTP 200 |
| Gradle `dependencies --configuration intellijPlatformDependencyArchive -PtargetIdeaVersion=263.6800.24` | 成功，解析为 `idea:idea:263.6800.24`，无 FAILED 标记 |
| 默认 253 SDK / JDK21，`cleanTest test buildPlugin verifyPluginStructure --no-build-cache --no-configuration-cache --offline` | 成功，62 测试，0 失败、错误、跳过；25s |
| 本地固定 263 SDK / JBR25，`cleanTest test verifyPluginStructure -PlocalIdePath=... --no-build-cache --no-configuration-cache` | 成功，62 测试，0 失败、错误、跳过；1m53s |

EAP 首次离线检查因缺少 `test-framework` 缓存失败；使用命令级临时代理获取依赖后重跑成功。
两次完整远程 SDK 解析尝试在等待期间主动中止；最终安装包依赖图验证成功，
但未在本机完成一套全新远程 SDK 的下载、解包和测试，不将依赖图成功等同于完整 CI 成功。
Linux GitHub Actions 的结果需以该修复提交对应的远端运行结果为准。本次未重跑 GUI 或 Verifier。

本地日志：`build/ci-eap-installer-archive-resolution.log`、
`build/ci-installer-253-regression.log`、`build/ci-installer-263-online.log`；
测试 XML 保存于 `build/ci-installer-253-test-results/` 和 `build/ci-installer-263-test-results/`。
交付 ZIP 的 SHA-256 仍为 `21b4f2bc1c010d4e6d7ad9828565da299b3886f269be398cbcbef2bb7cd69c04`。
