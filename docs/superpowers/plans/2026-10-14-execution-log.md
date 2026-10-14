# Git Plus 实施记录

计划：2026-10-14-current-line-blame.md

- 用户明确要求当前会话直接实现，无需重复确认；在现有 checkout 工作，不 commit 或 push。
- Task 1 → Task 2/3：共享 BlameCommit 与时间格式化；Task 2 → Task 3：查询输出及文档版本校验，无接口冲突。
- 基线：模板 XML 测试依赖未声明的 XML SDK；按计划删除无关模板测试，替换为功能测试。目标 SDK 首次下载及全局缓存需要沙盒审批；并行尝试构建造成配置缓存等待，随后使用无配置缓存执行，停止本次停滞的重复构建。
- Task 1: complete。实现 72 小时含边界规则、未来时间、本地时区和说明第一行；先观察缺少实现的编译失败，再实现并通过测试。
- Task 2: complete。真实临时 Git 仓库验证插入、删除、替换、暂存、HEAD 变化、未跟踪、无 HEAD、取消及 GBK。
- Ruling: 采用备选 Git --contents 文档快照方案，保留换行符/BOM — 由 Git 完成当前文档归属映射，避免依赖 IDE 内部映射 API — 代价是需要本地 Git 及临时 I/O。
- Ruling: 文档限制 100 万字符、输出限制 32 MB、命令限制 15 秒，缓存四个快照 — 限制后台资源占用 — 超限文件隐藏提示，已写入 README。
- Task 3: complete（代码、自动测试、打包）。真实 MAIN_EDITOR 配合项目单例服务验证首次显示、换行、编辑及再次换行；早期集成测试分别因虚拟目录、macOS VFS 根目录限制、UNTYPED 编辑器及重复实例产生失败，逐一根据错误和临时边界诊断修正测试设置，诊断输出已删除。
- Final review: 独立只读审查发现 CRLF/BOM 与新增/删除主光标两项 Important；均以真实失败测试复现，修复后通过全量套件。
- Final: minor (deferred): 应用级 VFS 事件尚未逐仓库/文件过滤，可能在大量无关文件变更时产生重复刷新。
- Final: Ruling: 主题对比度、软换行视觉位置和交互式关闭项目未做人工沙盒检查 — 已验证平台编辑器状态及资源释放，未据此声称人工视觉验收通过 — 视觉效果仍需安装后体验。
- 验证：./gradlew test buildPlugin verifyPluginStructure --no-configuration-cache，BUILD SUCCESSFUL，19 tests / 0 failures。
- 兼容性：Plugin Verifier 1.410，对 IU-253.33813.55（2025.3.6.1）离线检查，verification-verdict.txt = Compatible，无 compatibility-problems。线上验证等待非必要的可选依赖网络下载，离线完成后停止本次在线验证进程。
- 产物：build/distributions/git_plus-0.0.1.zip；报告：build/reports/tests/test/index.html、build/reports/pluginVerifierOffline/IU-253.33813.55/report.html。
