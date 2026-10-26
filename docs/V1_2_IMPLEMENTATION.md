# V1.2.0 实施记录

基线 `1fc0bba`，沿用当前功能分支。用户授权合并完成V1.1.1稳定性收尾及V1.2.0悬浮详情，直接实现，无需重复确认。

## 范围与设计

- 保留主光标行、灰色Inlay、72小时规则、未提交提示；不增加点击Diff或设置页。
- 鼠标停留行尾Inlay后显示IDE原生悬浮卡片，作者/完整时间/完整SHA复用Blame元数据，完整提交说明按需后台加载。消息以纯文本显示，可选择、复制、滚动。
- 详情按仓库与revision缓存并合并并发请求；任务随光标、文档、Inlay和编辑器生命周期取消。Git不在EDT运行，失败有明确的详情提示。
- 修复mailmap/attributes漏VFS事件的缓存新鲜度；.git目录mtime不再作为仓库身份，定位标记只检查到已知仓库根。

## 执行顺序

1. 缓存修复与真实Git回归，独立提交。
2. 后台提交说明获取、缓存、取消测试，独立提交。
3. 悬浮卡片和生命周期整合、平台回归，独立提交。
4. 版本1.2.0、文档、双SDK测试、原版Verifier、EAP实际界面及性能复测，独立提交。

## 实际验证

阶段1：新增mailmap、attributes漏事件和.git目录mtime三项回归，修复前全部失败。其余结果随执行补齐。

阶段1完成：253全量36测试通过（14s），增加真实linked worktree、独立HEAD/index、detached HEAD、packed refs和在途失效不回填测试。attributes按真实目录识别macOS路径别名；定位标记止于最近的.git，目录仅检查身份，文件指针保留mtime/size检查。

阶段2完成：253全量39测试通过（19s）。提交说明按common Git目录+完整revision获取，复用消费者计数协调器，10次相同详情仅1个Git进程；取消一个消费者不影响另一个。消息缓存最多128项/100万字符，单说明超过128000字符明确按加载失败处理而不静默截断。支持按仓库失效与generation拒绝旧回填；非法revision不执行Git。

阶段3完成：253全量44测试通过（20s）。悬浮延迟400ms；作者、完整提交时间和SHA立即复用，说明后台补齐。使用原生JBPopup和纯文本JTextArea，支持选择复制、滚动、Escape、鼠标移开及窗口失焦关闭。光标/文档/Inlay失效、视口滚动、编辑器切换/释放都会取消任务；补充失败提示、取消加载、未提交不执行Git、延迟销毁及不可取消旧响应不覆盖新目标的回归。

## 1.2.0 发布验证（2026-10-15）

发布产物固定为253 SDK构建的 `build/distributions/git_plus-1.2.0.zip`；EAP SDK产物单独保留在 `build/v12-release-263/`，不覆盖交付包。

| 验证 | 实际结果 | 证据 |
| --- | --- | --- |
| 253.33813.55 / JDK21，cleanTest test buildPlugin verifyPluginStructure | 44测试，0失败，构建成功23s | `build/v12-253-release.log`、`build/v12-release-253/test-results/` |
| 263.6800.24 / JBR25，同样任务及prepareSandbox_runIde | 44测试，0失败，构建成功25s | `build/v12-263-release.log`、`build/v12-release-263/test-results/` |
| 原版Plugin Verifier 1.410，253构建的同一ZIP →253 | Compatible | `build/reports/v12-verifier-253/IU-253.33813.55/plugins/com.github.ten951.gitplus/1.2.0/verification-verdict.txt` |
| 原版Plugin Verifier 1.410，同一ZIP →263 | Compatible | `build/reports/v12-verifier-263/IU-263.6800.24/plugins/com.github.ten951.gitplus/1.2.0/verification-verdict.txt` |
| 独立全分支代码审查，1fc0bba..f84589b | 未发现已确认Critical/Important问题 | 本节记录结论；审查仅只读，未声称重跑测试 |

测试代码沿用的FileEditorManagerEvent构造器有弃用警告；本次生产代码无新增弃用API警告。Verifier原样运行，未替换验证器、修改SDK或过滤缺类错误。静态Compatible仅说明API检查通过。

### 实际EAP原生界面

使用官方263.6800.24 SDK，通过项目内原生app入口启动独立config/system/plugins沙盒；未替换用户IDE或修改用户Git配置。安装前逐个比较沙盒JAR与253发布ZIP内容，完全一致。启动日志确认加载 `Git Plus (..., 1.2.0)`。

| 场景 | 实际观察 |
| --- | --- |
| 打开已提交hover.txt | 当前主光标行灰色作者/相对时间/摘要正常显示 |
| 点击另一行 | 旧行提示清除，新行显示同一提交归属 |
| 在第一行插入未保存文本 | 当前行立即更新为“尚未提交” |
| Undo、保存、移动到第二行 | 原内容和已提交作者/时间/摘要恢复 |
| 当前启动段日志 | 无ERROR/SEVERE或插件缺类；出现本地沙盒安装来源无法识别的更新提示，不影响加载 |
| 原生悬浮卡片、鼠标通道、选中复制及Escape | **待人工交互验收**：当前CUA接口只有click/drag/scroll，没有纯mouse move/hover；scroll和drag尝试未能触发到真实卡片。未将这些操作尝试记为通过 |

验证结束后正常退出独立测试沙盒，保留用户原有IDE实例。

日志：`.intellijPlatform/sandbox/git_plus/IU-2026.3/log_nativeSmoke/idea.log`（本次启动从2026-10-15 11:24:38开始）。平台测试使用真实Editor/Inlay与可替换详情视图，覆盖延迟、正文、错误、取消和旧结果隔离，不能替代原生Popup实际交互。

人工验收步骤：主光标停在已提交行，将鼠标移入灰色Inlay停留400ms；核对作者、完整本地时间、40位SHA及多行正文；移入卡片后选择复制；按Escape关闭；重新悬浮后移出、切换行、编辑、滚动及关闭文件，分别确认卡片关闭。未提交行应仅显示未提交说明。

### 性能对比

相同JBR25、512MB堆、4万行/同一提交文件，V1基线14fd064与本次f84589b各运行3个独立JVM。原始数据：`docs/benchmarks/v1_2.json`。以下为中位数：

| 指标 | V1 | V1.2 |
| --- | ---: | ---: |
| 冷查询ms | 3392.148 | 3227.108 |
| 连续20次热查询ms | 693.688 | 8.966 |
| 20次热查询新增Git进程 | 40 | 0 |
| 冷查询Git进程 | 4 | 6 |
| 提交对象数 | 40000 | 1 |
| GC后保留堆字节增量 | 36616992 | 26072328 |

V1.2三次热查询新增进程均为0，热查询中位耗时下降约98.7%，保留堆增量下降约28.8%。冷查询仍需仓库状态/对象格式探测，进程数较V1增加；3次本机测量不足以承诺普遍冷启动提升。该基准测试Inline Blame，不代表原生弹窗延迟、长期堆泄漏或多仓库压力。

一次初始性能运行与SDK重编译重叠，编译期间classes被替换导致ClassNotFound失败，数据废弃；构建完成后独立重跑得到上述完整3组结果，未修改运行代码规避错误。

### 审查边界与保留限制

- 主光标在同一行内左右移动不重建Inlay，也不关闭详情；换行才失效，沿用V1规则。
- 本次增加仓库内.mailmap、祖先.gitattributes及Git目录info/attributes新鲜度检查；外部mailmap.file/core.attributesFile文件内容变化未新增主动签名追踪，属于已记录的范围限制。
- 单提交说明超过128000字符明确失败；缓存界限128条/100万字符。非法SHA不执行Git，正文始终按纯文本展示。
- 没有设置页、Commit Diff或Marketplace发布。Commit Diff按原计划留至V1.3；本次仅本地提交，不push或合并。

发布ZIP SHA-256：`5a8c94ae76f544160601eb3d2a213ac1eb63770f8a03b650c92b872c2319a109`。

最终追加鼠标事件入口回归：263全量45测试通过（28s），包含真实Editor内容组件MOUSE_MOVED事件、当前Inlay矩形命中和详情加载；原生Popup视图仍使用测试替身，因此上面的人工交互缺口保留。253最终全量45测试也通过（32s），日志为`build/v12-253-mouse-test.log`，263日志为`build/v12-263-mouse-test.log`。两份结果分别保存在相应`build/v12-release-*/test-results/`。新增仅为测试，不改变已验证ZIP的生产代码或字节。

Ruling: 保留当前功能分支和本地分阶段提交，不再询问合并菜单 — 用户已授权在当前会话直接完成、无需反复确认；push/合并未在本次范围内。原生悬浮验收未完成单独披露，不以静态检查替代。
