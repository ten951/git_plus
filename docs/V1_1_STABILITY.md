# Git Plus V1.1 稳定性实施记录

基线：`14fd064`；依据：`CODE_REVIEW.md` 与实际源码。分支 `v1.1-stability`。

**最新状态（2026-10-15）：** 原版 Verifier 1.410 对253交付包在253、263两端均为 Compatible，EAP实际界面冒烟已完成。DVCS修复和详细证据见 [V1_1_EAP_VALIDATION.md](V1_1_EAP_VALIDATION.md)。下方初次执行中的“待验收”是当时的历史状态，现已由该后续记录闭环。

## 已确认边界

- 保留最低 253 支持基线，增加固定 263 EAP 构建、测试及实际运行验证。
- 保留当前主光标行显示、72 小时时间规则和未提交提示；不增加 Hover、Diff 或设置页。
- 混合换行优先采用保留原始字节并映射未保存编辑的快照编码方案。
- 六个阶段各自验证并提交，不修改用户 Git 配置，不 push。

## 阶段与验收

1. EAP 依赖：补齐 DVCS，253/263 构建测试，固定 EAP CI 门禁。
2. 事件：按文件/仓库筛选、合并刷新、隐藏会话延迟查询、主题仅重绘。
3. 缓存：共享最新文件快照、合并相同请求、暖命中零 Git 进程、缓存失效代数。
4. 解析：按 revision 复用元数据、可取消解析、I/O 与 CPU 分离。
5. 编码：混合换行/BOM 原样及未保存插入/删除/替换，保留源文件。
6. 验证：回归、同环境性能比较、打包、EAP 实际运行及未验证项。

具体方案沿用用户确认的六阶段计划。大文件限制和命令超时保持 V1 边界。性能比较使用同一 JVM/堆/输入，进程次数及对象复用使用确定性断言。

## 实际执行

阶段 1 开始。先验证 `intellij.platform.vcs.dvcs` 的显式模块依赖是否同时适用于 253 和 263。

阶段 1：263.6800.24 本地 SDK + JBR25 显式 DVCS 模块后通过编译、19 个测试、打包和结构检查（48s）。第一次离线运行因未缓存测试依赖失败，下载依赖后的执行通过。CI 增加固定 build 263.6800.24 的独立测试 job；远程 CI 尚未运行。EAP 实例 UI 验证留到最终阶段，当前结果仅为编译/平台测试。

阶段 1：253.33813.55 全量19测试、打包、结构检查通过（18s）。两个 SDK 的 `intellij.platform.vcs.dvcs` 显式模块均可解析，不需要增加未验证的 plugin.xml 依赖。

阶段 2：按文件/父目录和 HEAD/index/refs/config 等状态文件筛选；忽略其他项目、构建输出、objects；批次50ms合并。隐藏会话标记dirty，选中后刷新；主题复用Inlay重绘。253全部22测试通过（11s）。曾发现253的packedRefsPath非公开，改用两个SDK都公开的refsHeadsFile；测试补齐真实selection事件的provider字段。

优化前基线：JBR25、512m、40,000行单提交、同一个探针执行3次：冷查询2871/2932/3352ms；20次暖查询518/575/686ms；保留堆增量约12.85MB；提交对象40,000个；冷4个Git进程，暖20次共40个。冷计时不包括测量后的GC等待。探针不修改用户Git配置，仅在临时仓库以命令参数指定测试身份。

阶段 3：253全量25测试通过（2m52s）。目录/HEAD状态缓存采用文件身份、mtime、size和60s兜底；缓存命中不启动Git。项目内共享快照，最多16文件、10万归属行，每文件只保留最新版本；共享Deferred按消费者计数，最后消费者离开才取消。Provider选择移到EDT，旧缓存回填以文件generation校验。进程临时资源创建纳入finally，分别清理，并保留有界stderr。暖缓存10次零进程、共享取消和既有HEAD更新测试通过。

阶段 4：253全量27测试通过（JUnit XML：27 tests，0 failures，0 errors）。同一revision复用提交对象，解析每256行检查取消；异步查询将Git及文件检查放在IO、porcelain解析放在Default。新增对象身份复用、解析中途取消测试。构建期间存在较长墙钟延迟，不作为性能测量。

阶段 5：新增3项真实Git混合换行回归，修复前3项均因错误归属失败；修复后253全量30测试通过（12s）。归一化内容未变时直接使用磁盘字节；未保存编辑通过平台行diff保留未改行的分隔符，改动行使用编辑器默认分隔符。覆盖插入、删除、替换、重复行、BOM、GBK、无末尾换行，并验证磁盘原文不变。读取最多8MB，diff超过平台限制时省略结果而不猜测归属；读取/Git在IO，diff/解析在Default。

## 阶段 6：最终回归与验收限制（2026-10-15）

独立审查覆盖 `14fd064..01f8bef`。确认隐藏编辑器的document/caret回调仍可绕过延迟查询：扩展真实平台测试后先失败，再在schedule入口及延迟结束后统一检查可见性，deactivate取消隐藏请求并保留dirty；可见split继续查询。补充最后一个消费者取消、下一次相同请求重启测试。

最终EAP编译另发现阶段2引入的GitRepositoryManager父类位于DVCS impl模块；检查253/263实际jar后增加`intellij.platform.vcs.dvcs.impl`显式构建依赖。此修复计入最终集成提交，不重写前五阶段历史。

| 实际命令与环境 | 结果 |
| --- | --- |
| JDK21，`./gradlew cleanTest test buildPlugin verifyPluginStructure --no-configuration-cache --offline`，253.33813.55 | 成功，31测试，0失败/错误，16s |
| JBR25，`./gradlew test buildPlugin verifyPluginStructure -PlocalIdePath="/Users/didi/Applications/IntelliJ IDEA.app/Contents" --no-configuration-cache --offline`，263.6800.24 | 成功，31测试，0失败/错误，15s |
| Verifier 1.410，同一253构建包对253.33813.55，offline | Compatible；报告在`build/reports/v11-verifier-253` |
| Verifier 1.410，253构建包对263.6800.24，offline | **未通过**：1项compatibility problem，未解析Repository与AbstractRepositoryManager；不能写成Compatible |
| `runIde`，263.6800.24，隔离沙箱+`build/eap-smoke`仓库 | 实际进程启动、插件加载、测试项目打开；**界面行为未验收** |

两次测试的JUnit XML分别保存在本地`build/reports/v11-253-junit`和`build/reports/v11-eap-junit`。对应构建日志为`build/stage6-253-final.log`和`build/stage6-eap-final.log`。SDK编译及平台测试不等于EAP真实界面验证，远程CI未运行。

EAP实际运行：首次offline启动缺少Compose hot-reload代理，联网下载后启动成功。沙箱日志`.intellijPlatform/sandbox/git_plus/IU-2026.3/log_runIde/idea.log`记录加载Git Plus 0.0.1并打开eap-smoke项目；采样期间未发现本插件ERROR。UI工具能枚举该JBR进程，却无法通过bundle ID、显示名或运行时路径连接沙箱窗口，因此没有观察到行尾文字、光标移动或未提交提示，不以主IDE窗口冒充沙箱验证。

263静态问题仍待核实：SDK真实jar中存在上述两个类，Git后端模块也声明了DVCS模块依赖；Verifier却将这些依赖解析为product module并报告缺类，同时报告大量SDK layout路径缺失。这提示SDK模块布局/Verifier解析差异，但尚未证明只是工具误报。保留报告`build/reports/v11-verifier`，不屏蔽告警、不为消除告警修改SDK或降低兼容声明。

### 性能对比

可复现探针为`tools/benchmark_blame.py`及`tools/BlameBenchmark.java`。先构建classes，再执行：

```sh
python3 tools/benchmark_blame.py \
  --ide '/Users/didi/Applications/IntelliJ IDEA.app/Contents' \
  --java-home '/Users/didi/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home'
```

原始样本保存在[benchmarks/v1_1.json](benchmarks/v1_1.json)。探针在build临时目录编译归档V1 Provider，不切换当前分支；两个版本使用相同完整依赖classpath、JBR25、512m堆、40,000行单提交文件，各3个独立JVM。计时不包含采样前后GC等待；保留堆是GC后估算，包含类加载开销，不能视为IDE整体内存。该统一classpath测量替代上方早期基线，二者内存数据不能混用。

| 指标 | V1 | V1.1 |
| --- | --- | --- |
| 冷查询ms，3次 | 2954 / 3098 / 3502 | 2835 / 2874 / 3582 |
| 20次暖查询ms，3次 | 542 / 622 / 686 | 16 / 55 / 10 |
| 保留堆增量中位数 | 36,641,984 bytes | 25,911,464 bytes |
| 同一revision的提交对象 | 40,000 | 1 |
| 冷Git进程 | 4 | 6 |
| 20次暖查询Git进程，3次 | 40 / 40 / 40 | 0 / 3 / 0 |

暖查询中位数约减少97%，保留堆估算约减少29%；冷查询仍有明显波动，初次仓库定位多2个进程，不宣称冷启动有稳定加速。一次暖测出现3次重新定位，原因未单独追踪，不能把全部样本描述为零进程；稳定未变仓库的10次暖查询零进程确定性测试已通过。探针范围仅为Provider，不代表编辑器端到端延迟。

### 待验收与已知风险

- **尚未全部完成验收**：EAP行尾渲染/移动/编辑/关闭的界面验证，以及263 Verifier缺类报告的最终定性。
- 审查低优先级风险：VFS正常事件覆盖mailmap/attributes，但Provider fingerprint不含这些辅助文件；若事件丢失，60秒HEAD重查仍可能复用旧归属。此条件性风险保留后续处理，没有宣称事件遗漏时也保证新鲜。
- 最后消费者取消的测试缺口已补齐；未发现需要新增Hover/Diff架构的理由。

所有变更仅本地提交，无push，没有修改用户现有Git配置。测试身份配置仅在隔离临时仓库内使用，新增探针以单条Git命令参数提供身份。
