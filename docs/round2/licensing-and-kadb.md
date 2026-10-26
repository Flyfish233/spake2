# 许可证、组合发行与发布政策

审查日期：**2026-10-27（Asia/Shanghai）**。本轮保持 **AGPL-3.0-only**，
没有变更许可证、添加链接例外或授权正式发布。本报告是基于许可原文、
发布者解释和实际依赖结构的工程判断，不是完整法律审计。

## 当前结论

当前许可证可以支持一个按强 copyleft 条件使用和分发的独立 KMP 库，也可以
用于 Kadb 的实验性组合。**它不满足“下游组合产物仍按 Apache-2.0-only
或闭源许可发行”的目标。** Apache-2.0 来源文件可以保留原许可证和通知，
但这不取消被直接链接的 AGPL 组件对组合程序的要求。[S1][S2][S3]

改成 Maven、AAR、JAR 或 KLIB 依赖，主要改善构建与版本管理；这里的 Kadb
仍然直接调用 Legacy 类，因此本轮按“组合程序”安排发行义务。不能把从
源码组合迁移到二进制依赖，当成获得了链接例外。[S4]

本轮允许的本地发布、CI 消费验证及源码交付可以继续；是否接受 AGPL
组合发行条件、选用哪个远端包仓库、是否启动另一项授权政策审查，是独立决策。

## 实际只读检查

检查对象为 Kadb 分支 `experiment/kmp-native-core` 的
[`49a88b14e9b731e4c086c920eb74664450c79d9f`](https://github.com/Flyfish233/Kadb/tree/49a88b14e9b731e4c086c920eb74664450c79d9f)。
它固定引用 SPAKE2 `b837aba27bebc1050965fa124c2efa54b4e4544a`。
六个 Kotlin 运行时文件和一份 LICENSE 与该 Git 版本逐字节一致，完整文件
清单也与 Kadb 的记录一致；本轮没有修改或构建 Kadb。
[机器可读核对记录](kadb-baseline.json)

| 材料 | 现有许可与范围 |
| --- | --- |
| SPAKE2 原创生产实现 | AGPL-3.0-only；无链接例外 |
| Kadb 原有代码及本次检查的集成代码 | 保留各自 Apache-2.0 文件级通知；组合发行另受 AGPL 条件约束 |
| 历史 Legacy 测试向量 | 原 GPLv3 来源通知继续保留，不重标为原创 AGPL |
| RFC、Wycheproof、构建及参考材料 | 各自来源和通知见 [THIRD_PARTY.md](../../THIRD_PARTY.md)；测试来源许可不冒充生产实现许可 |

SPAKE2 的 LICENSE 与从 GNU 官方地址实际取得的 AGPLv3 文本完全一致。
Kadb 已有的
[组合许可证说明](https://github.com/Flyfish233/Kadb/blob/49a88b14e9b731e4c086c920eb74664450c79d9f/docs/experiment/LICENSES.md)
也明确拒绝 Apache-only 组合发行的说法。本次没有发现需要改写这些源文件
许可证才能进行本地实验的理由。

## 许可规则与本项目的执行方式

| 规则 | 本项目的处理 |
| --- | --- |
| Apache-2.0 可以进入 GPLv3 兼容的组合；方向不能反过来理解成把强 copyleft 代码改标为 Apache。[S2][S3] | 保留 Apache 原文和原作者通知；组合发行说明采用明确的 AGPL 条件，不宣称重新拥有上游版权。 |
| AGPL 的对应源码包括修改、生成、安装和运行所需的源码及控制脚本；部分系统库、未修改的通用构建工具有规定的排除。[S1 §1] | 给出确切版本的源码与构建分发包，不能只保存源码分类 JAR 或一张 URL 清单。最终 Kadb 程序还需其实际运行依赖的来源覆盖。 |
| 传递修改源码和非源码形式适用 §§4–6；网络下载可采用 §6(d) 所规定的源码获取方式。[S1] | 将二进制、完整源包、通知、版本和构建证据对应起来。仓库地址或 Git SHA 是身份信息，不能自动替代接收者实际获取对应源码的安排。 |
| 若选择 §6(b) 的书面报价方法，文本有至少三年的期限条件；若采用 §6(d) 下载方式，应按其条款保持所需的等同获取安排。[S1] | 不把 CI 临时产物的保留期当作正式来源可用性政策，也不把“所有分发一律三年”当成通用规则。 |
| 含有适用 User Product 的传递可能需要安装信息；其适用范围有条件。[S1 §6] | 本轮不声称已解决商店条款、设备锁定或最终安装信息要求；具体 App/设备发行另行核对。 |
| Apache 发行要求包含许可、适用归属/NOTICE，并标注文件改动。[S3 §4] | 保留原文件归属，不以一个 AGPL POM 字段覆盖所有第三方材料。 |

本次 Maven 发布准备包含文档/许可材料和 SPAKE2 的完整源码与构建分发包。
这些覆盖的是本库；它们**不能单独充当整个 Kadb 静态 framework 或 Android
应用的对应源码包**。Kadb 现有
[源码收集器](https://github.com/Flyfish233/Kadb/blob/49a88b14e9b731e4c086c920eb74664450c79d9f/docs/experiment/SOURCE_BUNDLE.md)
已经处理更广的 Native/Android 来源，迁移时需更新它对旧源码组合的假设。
具体文件在 [迁移说明](kadb-migration.md) 列出。

### 网络条款的适用边界

AGPL §13 针对支持远程网络交互的修改版本要求向相关用户提供对应源码；
GNU FAQ 说明，判断不取决于把程序叫作“客户端”还是“服务器”。仅观察到
TCP、TLS 或 ADB 通信，不能代替对实际用户交互和部署方式的判断。[S1 §13][S4]

因此，不在 SPAKE25519 消息中添加许可字段，不改变 ADB 线缆语义。若应用
部署触发网络源码提供要求，应在应用/服务的适当用户入口处理。向他人
分发 APK/framework 的问题应按传递条款另行处理，不能因它是“客户端”
而一概豁免。纯粹个人本地运行，也不等于必须向全世界公开所有私有工作。[S1 §2][S4]

## 需要用户决定的政策

| 选择 | 收益 | 后果与前提 |
| --- | --- | --- |
| **A. 保持现有 AGPL，明确接受实验组合及未来发行的 copyleft 条件** | 不改本轮已验证的许可和实现；可以继续工程验收 | 对外发行时安排组合程序的许可、对应源码、通知和适用的网络义务；不能宣传为 Apache-only。 |
| **B. 单独启动原创运行时代码的附加授权/双授权/重许可审查** | 若长期目标是广泛被 Apache-only 或闭源下游采用，可以重新选择匹配的授权策略 | 必须先确认所需权利和贡献者许可，再取得明确批准；第三方数据和源码通知不能一起改写。已有 AGPL 版本不会因为新版本授权不同而消失。 |
| **C. 保持 SPAKE2 的 AGPL，同时让要求宽松发行的下游采用另一项合适授权的实现** | 保留本库的 copyleft 目标，并让下游独立选择 | 需要另做实现选择、协议兼容、测试和迁移成本；不能为方便而声称现有库自动变成宽松许可。 |

**本轮采用 A 的现有许可边界进行实验性工程验证，不代替用户批准正式发行。**
若 Kadb 的长期目标包含 Apache-only 组合发行，则应在正式分发前选择 B 或 C
并开展相应工作，而不是只修改 Maven POM 的名称。

## Maven 与 JitPack 的建议

推荐以标准 Maven 布局和 Gradle Module Metadata 为基线：每个 KMP 根模块
必须与其平台产物、传递依赖、sources、文档和许可证材料共同验证。
Kotlin 文档明确区分根元数据发布和目标产物发布；Android 也需要发布配置。
仅出现 `publishToMavenLocal` 任务，不足以证明所有 Native/Android 变体可消费。[S5][S6]

Kadb 保存的
[JitPack 失败记录](https://github.com/Flyfish233/Kadb/blob/49a88b14e9b731e4c086c920eb74664450c79d9f/validation/jitpack/README.md)
属于旧提交 b837aba：缺少发布任务、无可用模块/平台产物，并且没有执行 Android
变体消费者。JitPack 官方文档要求 Gradle 的 Maven 发布路径，并定义其多模块
坐标规则。[S7] 本轮修复标准发布能力不自动构成 JitPack 已通过的结论。

本轮不触发新候选版本的 JitPack 公开构建，不配置远端正式 Maven 凭据。
未来若决定支持 JitPack，应单独验证它重写后的坐标、模块元数据与完整
平台产物，包括实际 Apple 链接/运行。当前优先保留同一个固定 Maven
候选版本的本地/CI 消费证据。远端仓库、签名、版本命名、保留政策及正式
发布授权仍由用户决定。

## 主要来源

- **S1**：[GNU AGPLv3 正文](https://www.gnu.org/licenses/agpl-3.0.html)，§§1–6、13；
  [项目保留全文](../../LICENSE)。
- **S2**：[ASF 对 Apache-2.0 / GPLv3 兼容方向的说明](https://www.apache.org/licenses/GPL-compatibility.html)；
  [GNU 许可证列表的 Apache-2.0 条目](https://www.gnu.org/licenses/license-list.html#apache2)。
- **S3**：[Apache-2.0 正文](https://www.apache.org/licenses/LICENSE-2.0)，尤其 §4。
- **S4**：[GNU FAQ](https://www.gnu.org/licenses/gpl-faq.html)：
  `LinkingWithGPL`、`GPLStaticVsDynamic`、`AGPLv3CorrespondingSource`、
  `AGPLv3InteractingRemotely`、`AGPLv3ServerAsUser`。
- **S5**：[Kotlin Multiplatform 发布结构与平台要求](https://kotlinlang.org/docs/multiplatform/multiplatform-publish-lib-setup.html)。
- **S6**：[Gradle Module Metadata](https://docs.gradle.org/current/userguide/publishing_gradle_module_metadata.html)。
- **S7**：[JitPack 构建与多模块发布文档](https://docs.jitpack.io/building/)。

来源检索与源码核对日期均为 2026-10-27。引用的是发布者解释及许可文本；
对本项目直接链接关系的适用判断属于本报告说明的工程发行策略。
