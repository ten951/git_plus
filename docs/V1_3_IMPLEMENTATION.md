# V1.3.0 实施与验证记录

基线681579a（V1.2.0），当前干净功能分支v1.1-stability。用户明确要求直接实现、测试、打包，无需反复确认；沿用现有工作目录，不修改用户Git配置，不push/合并。

计划：[2026-10-23-v1.3-commit-diff.md](superpowers/plans/2026-10-23-v1.3-commit-diff.md)。

选择：使用Git4Idea的GitChangeUtils获取原生Change，再调用ShowDiffAction。相比自行取文本构造Diff，可复用原生二进制、编码、首次提交及改名处理；相比打开整个Commit文件列表，直接定位用户悬浮的文件。默认第一父提交，未提交行无入口，后台加载与卡片生命周期分离。

Ruling: 用户已明确跳过重复确认，直接执行本计划；沿用当前功能分支，避免迁移既有SDK缓存/沙盒。重大范围限定为当前文件Diff，不加入设置页或整提交浏览。

## 实际结果

待按阶段填入实际命令、失败及修复、测试/验证/安装包结果。

阶段1：新接口实现前编译RED（pathAtCommit/CommitDiffLoader缺失），实现后253全量52测试通过（32s）。真实Git覆盖首次提交空前版本、修改且工作区另有未保存内容、改名前后路径/历史Blame路径、merge第一父提交、缺失对象/文件及非法请求。普通修改/merge同时读取原生ContentRevision内容，确认没有与工作区比较。

阶段2：卡片入口/独立控制器实现前RED（新增factory回调/Controller缺失）；完整59测试曾发现2项失败：Unix反斜杠文件名误转斜杠导致选不到Change，已改为仅转换当前系统分隔符；原生process路径标题读取LightFixture的temp://根失败，测试改用同一原生Producer的createSimpleRequest内容工厂（不更改生产展示路径）。最终253全量59测试通过（26s），含父提交/提交的原生左右Document内容、未提交无入口、失效/双击拒绝旧请求、关闭卡片不取消Diff、后台加载/EDT展示、释放取消、迟到结果拒绝和错误提示。
