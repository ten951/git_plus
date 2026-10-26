# V1.1 EAP 验收记录

日期：2026-10-15；目标：IntelliJ IDEA **263.6800.24**，macOS arm64，官方 JBR25。

## DVCS 缺类修复

263 的 DVCS API 和实现已经位于内置插件 `com.intellij.platform.vcs`，对应真实文件为：

- `plugins/platform-vcs-plugin/lib/modules/intellij.platform.vcs.dvcs.jar`：`Repository`。
- `plugins/platform-vcs-plugin/lib/modules/intellij.platform.vcs.dvcs.impl.jar`：`AbstractRepositoryManager`。

构建脚本中的 `bundledModule(...)` 只补齐编译依赖，没有为插件描述符补充对这个新版插件所有者的直接依赖。原描述符仅依赖 `Git4Idea`，Verifier 的该依赖解析路径未能覆盖两项 DVCS 类。对同一个未修改 SDK 和同一个插件二进制，仅在描述符补充 VCS 插件依赖，即可消除缺类；单独补充 DVCS 模块或旧 VCS 别名的对照未解决问题。

正式修复在 `META-INF/plugin.xml` 增加：

```xml
<depends optional="true" config-file="git-plus-vcs.xml">com.intellij.platform.vcs</depends>
```

253 尚无该独立插件 ID，因此使用可选依赖；`Git4Idea` 仍为必需依赖。配置片段 `git-plus-vcs.xml` 为空描述符，不添加条件功能。新增的是现有内置插件的显式依赖，不增加第三方库或修改 SDK。没有通过反射绕过 API 检查，也没有使用 ignored-problems、external-prefixes 或修改 Verifier。

## 最终构建与静态验证

| 验证 | 实测结果 |
| --- | --- |
| 253.33813.55 + JDK21：全量测试、buildPlugin、verifyPluginStructure | 成功，31 tests，0 failures，0 errors，32s |
| 263.6800.24 + JBR25：全量测试、buildPlugin、verifyPluginStructure、prepareSandbox_runIde | 成功，31 tests，0 failures，0 errors，18s |
| 原版 Verifier **1.410**：253 构建的 ZIP → 253.33813.55 | **Compatible** |
| 原版 Verifier **1.410**：同一 ZIP → 263.6800.24 | **Compatible**，无 DVCS 缺类或插件配置缺陷 |

两个 SDK 均使用 `--no-configuration-cache --offline`。263 使用 `-PlocalIdePath="/Users/didi/Applications/IntelliJ IDEA.app/Contents"`。Verifier 使用官方原版 `verifier-cli-1.410-all.jar`，`check-plugin -offline`，IDE 指向原始安装目录，各次运行使用独立缓存。SDK 本身仍有其他组件的 layout 路径警告；它们不再导致本插件的兼容或配置问题。

交付包为 `build/distributions/git_plus-0.0.1.zip`，采用最低 253 SDK 构建；同时保存 263 SDK 构建副本到 `build/validation`。双 SDK 的静态检查与最终界面复核使用同一个 253 构建包。

交付 ZIP SHA256：`7ca6ccc4b5de85557b3b6aaad48cf8cf95de27c65678cb645307a0a433daa2ac`。

本地证据：

- `build/dvcs-final-253.log`、`build/dvcs-final-263.log`。
- `build/reports/dvcs-final-253-junit`、`build/reports/dvcs-final-263-junit`。
- `build/reports/dvcs-final-verifier-253`、`build/reports/dvcs-final-verifier-263`，其中 `verification-verdict.txt` 均为 `Compatible`。

## EAP 实际界面验证

此前 `runIde` 的 JVM 窗口无法被 UI 工具连接。本轮在 `build` 中创建独立 `.app` 测试入口，复制官方原生启动器、链接原始 IDE 内容，并仅对该本地启动器做开发签名，以提供独立的应用标识。它仍运行原始 263 的 Java 代码与 JBR。配置、系统目录、插件目录、日志和测试仓库均使用本项目隔离沙箱，没有安装到用户日常 IDEA。

测试仓库：`build/eap-smoke`。首行提交时间固定为 `2026-10-01 12:34:56`，第二行为当天提交。通过 UI 工具实际打开和操作，截图在本次会话中观察，未以平台测试或启动日志代替渲染验收。

| 操作 | 界面实际观察 |
| --- | --- |
| 光标置于首行 | `V11 Smoke · 2026-10-01 12:34:56 · old timestamp check` |
| 移至第二行 | 首行提示消失；第二行显示 `V11 Smoke · …分钟前 · recent timestamp check` |
| 第二行键入 `unsaved ` | 当前行显示 `尚未提交` |
| 撤销编辑并返回首行 | 文档内容恢复，旧提交归属可再次显示 |
| 关闭并重新打开文件 | 编辑器关闭后行尾提示消失；重开后正常显示，没有观察到重复提示 |
| 在隔离测试仓库创建分支并提交第二行变更 | IDE 收到外部变更并刷新；第二行显示 `V11 Smoke · 刚刚/…分钟前 · branch refresh check` |
| 切回测试仓库原分支并激活窗口 | 内容和 `recent timestamp check` 归属恢复；IDE外部文件刷新为异步，不承诺后台窗口瞬时更新 |

最终交付 ZIP 的插件 JAR还被安装到该沙箱复核，其字节 SHA256 为 `fbcf7dce2256c31aa90605d83394d07a68983acf45b200fa4157f504c3fe4059`。最终包再次通过绝对/相对时间、编辑/撤销、关闭/重开、分支往返验证。运行日志`.intellijPlatform/sandbox/git_plus/IU-2026.3/log_nativeSmoke/idea.log`确认实际build为263.6800.24、加载Git Plus 0.0.1，检查期间没有ERROR或NoClassDefFoundError记录。

本次实测范围是以上功能冒烟，不是长期内存泄漏或所有主题、分屏、系统组合的穷尽验证。沙箱提示 Git Plus 尚未指定更新来源，这是开发安装提示，不是行尾功能异常；没有为此选择远程更新来源。

所有测试提交仅发生在隔离 smoke 仓库。主项目不 push，不修改用户现有 Git 配置；原有工作区改动保留。
