# 第二轮验收记录（进行中）

本轮继续 `work/spake2-implementation` 和 Draft PR #1。当前文档是工程检查点，
**尚未宣告第二轮跨平台及 Maven 消费验收完成**。最终结果将以实际 CI、产物和测试
记录更新，不把已编写的测试任务算作已通过。

## 已落地的修复

`b46b288896ffd83b15660c8faa4078e767b1ad6b` 修复了 Legacy Oracle 验证工具
对截短记录及 `python -O` 的漏检风险。6 项回归测试、修复前后原始记录、固定
BoringSSL 可执行文件的完整重放见 [安全复核](security-review.md)。
13 个生产 `commonMain` 文件保持与 `b837aba` 相同。

## 本轮新增验收范围

- 本地 Maven 完整发布：3 个 umbrella 模块及每个模块的 10 个目标，共 33 个坐标。
- 校验实际 POM、GMM、依赖边界、源码/文档/许可及完整可构建源码归档。
- 两套隔离消费者：原 Kotlin 2.2.21 工具链，以及实际 Kadb 使用的 Kotlin 2.4.20 /
  AGP 9.4.0 / Gradle 9.6.0 / JDK 25 工具链。
- Android API 23/35 ART 全量公共回归，及从 Maven 产物构建的消费者测试。
- macOS arm64/x64 和 iOS 模拟器回归；物理 iOS 设备不在当前可访问环境内。

初次本地发布已经暴露并保留了错误的 `primitives-android` 传递坐标；发布验证器
能够拒绝该实际 POM。后续结果必须来自修正后的全量发布和独立消费者，不能用
Gradle 命令本身退出成功替代元数据及消费验收。

## 边界及后续记录

仓库可见性在本轮检查时为 Public；本轮没有更改可见性。没有修改 Kadb、合并 main、
创建 Release、向正式包仓库上传或改变 AGPL-3.0-only 许可证。

参阅 [本地发布说明](../PUBLISHING.md)、[Kadb 迁移步骤](kadb-migration.md)、
[许可证审查与政策选项](licensing-and-kadb.md)。测试成功也不等于严格恒定时间、
可靠物理零化或外部生产安全审计。
