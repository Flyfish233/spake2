# 第三轮执行检查点

本轮于 2026-11-01（Asia/Singapore）继续执行。所有结论以最终 DELIVERY 为准。

## 阶段 1：基线冻结（已完成）

- 先申请并获准沙箱外执行；未使用 PC Remote。
- 旧 SPAKE2 工作树干净，远端工作分支与本轮独立克隆均为 `e1b0e7f6e865eca6d229ad8f36de63883f808e8c`。
- 逐个读取并重算 13 个生产文件，均与既有 `source-manifest.json` 的 `5910529` 内容一致。
- 远端 `main` 为 `783299c166ed9613bb4e37d39fd7924205cf7c25`；PR #1 仍 open、Draft、未合并。
- Kadb 默认分支 `3d1c13e8df261cc4a2bb57b03aa8c392cb8386c3` 与实际审查实验分支分开；后者仍为 `49a88b14e9b731e4c086c920eb74664450c79d9f`。
- GitHub 官方 API 确认 `40388080241`、`40388080295`、`40388080222` 全部 completed/success，head_sha 均为 `59105290820bcbfd4e0336a2e7713006a461527f`。原始元数据和 job/step 结果已保存。
- 第二轮报告和 PR 正文遗留“进行中”字样；本轮只按真实 CI 记录补齐第二轮报告。

证据：[重新冻结记录](evidence/baseline/round3-start.json)、[CI job 记录](evidence/ci/)。

## 阶段 2：独立复核（已完成）

并行审查者分别负责协议/数学、状态机/侧信道、Legacy；主审查者负责 Kadb 与供应链，并交叉裁决重要发现。同一 AI 系统内部交叉审查，不代表外部专业安全审计。

- 三份独立报告及交叉质疑已完成，没有确认新的默认 HARDENED 生产漏洞。
- 独立 affine 参考构造 16 个 mask+torsion 退化输入；Legacy 模型重算 14 个确定性端点和 112 个异常/边界输入；有限状态模型检查 140 个可达状态并用历史错误作负对照。
- 从真实候选 Maven JAR 导出并审查 11 个类的 JVM 字节码；不计为 JIT/ART/Native 时序证明。
- 实际下载候选及原 CI XML，验证 GitHub artifact 摘要；33 个发布单元通过结构检查，三份相同的完整源码归档各 482 个文件与 Git 完全一致。

## 阶段 3：必要验证（核心已完成）

- 当前源码加新增退化点回归：JVM 81 方法、0 failure/error/skipped；其中新增一个方法覆盖 16 个攻击输入。
- Python 证据门禁 47 方法在普通及 `-O` 模式均通过。
- 环境失败记录完整保留：JRE 缺编译器；补齐官方 JDK17 后缺环境 CA；改用现有系统 truststore 后成功，未关闭 TLS 校验。
- 13 个生产文件未修改，因此没有虚构“修复前失败/修复后通过”的生产补丁链。
- Kadb 当前只读集成审查与有限协议模型实验由最终报告单独列出，完整应用/设备迁移仍未执行。

检查点 `88e0233` 通过 GitHub 已认证 API 保存为内容树完全相同的远端 `41d3e99`；CLI 没有 GitHub 写凭证。旧本地提交保留在 `audit/local-baseline-checkpoint`，未丢弃任何工作。

## 阶段 4：交付（已形成最终材料）

- [DELIVERY.zh-CN.md](DELIVERY.zh-CN.md)、[FINDINGS.json](FINDINGS.json) 和四方向报告已完成。
- Kadb有限真实候选+JCE模型5组通过；本地26个JVM production class与CI候选完全一致。
- 第二轮CI状态及Legacy精确泄露描述已按证据更正；13个生产源码文件仍保持不变。
- 新测试已发布为 `32190a93e37fe92dd436effa91eab8664b5c8d1e`。新增CI记录其观察时实际状态；不无限等待。
- Gate A/B为CONDITIONAL PASS，Gate C为NOT VERIFIED；后续工作限定实际Kadb迁移验证及生产发行前缺口，不自动启动下一轮。

## 收敛约束

只针对可证实的重要疑点追加最小复现和必要回归。环境失败保留原始记录，不无依据重试；无法完成的平台标 NOT VERIFIED。完成主要审查后形成 DELIVERY、结构化发现、提交和 Gate 裁决，不自动开启第四轮。
