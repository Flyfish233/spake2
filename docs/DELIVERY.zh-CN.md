# spake2 工程交付报告

验证日期：**2026-10-26**。状态：**已交付可构建、具备独立测试预期值的实验性实现；尚未完成外部专业安全审计。**

## 交付入口与版本

- 私有仓库：[Flyfish233/spake2](https://github.com/Flyfish233/spake2)。
- 开发分支：`work/spake2-implementation`。
- [Draft PR #1](https://github.com/Flyfish233/spake2/pull/1) 汇总提交、实现、测试和已知限制。
- 最终经过完整 CI 验证的代码提交：[b837aba27bebc1050965fa124c2efa54b4e4544a](https://github.com/Flyfish233/spake2/commit/b837aba27bebc1050965fa124c2efa54b4e4544a)。
- 对应 Git tree：`9144c7480ec823844a590bcb304d0d8f5b553128`。
- [最终代码 CI：40294194097](https://github.com/Flyfish233/spake2/actions/runs/40294194097)，Linux、Android、iOS Simulator 三个作业全部成功。

`main` 保留最小初始化提交 `783299c166ed9613bb4e37d39fd7924205cf7c25`。
全部实际开发在开发分支进行。仓库保持 Private，PR 保持 Draft；
没有合并、发布 Release、推送公共包或修改其他仓库。

本报告所在的最终提交只增加验证资料及修正文档，源码、测试、构建、
工作流及依赖锁与上述已测试提交相同。该报告提交使用 `[skip ci]`，
不重复运行相同代码；绿色 CI 证据明确指向代码提交 b837aba，
不宣称报告提交本身又执行了一轮测试。

## 提交与阶段成果

| 阶段 | GitHub 提交 | 实际成果 |
| --- | --- | --- |
| 首次初始化 | [783299c](https://github.com/Flyfish233/spake2/commit/783299c166ed9613bb4e37d39fd7924205cf7c25) | 使用 GitHub 文件创建接口建立 main 首个提交 |
| M0 | [bd3c732](https://github.com/Flyfish233/spake2/commit/bd3c7326cae9f1dffdb78730235ca8bb58a2b92b) | README、完整 AGPL-3.0-only 许可证、KMP 工程及测试基础 |
| M1 | [6c05267](https://github.com/Flyfish233/spake2/commit/6c052676a376d4658d7f43b233d5a5b8a40f0a64) | 纯 Kotlin 哈希、HMAC、HKDF、定宽模算术和 P-256，独立预期值 |
| M2 / M3 | [dc6cd08](https://github.com/Flyfish233/spake2/commit/dc6cd085393f650d8dfe7c59761d094acfaf1f3f) | 两套 RFC SPAKE2、双向确认、官方及独立转录向量 |
| M4 | [6a79f65](https://github.com/Flyfish233/spake2/commit/6a79f6561d29ad2ef5362bc7c36143f72d21ad4b) | 独立 Legacy API、固定 BoringSSL oracle 和历史语义回归 |
| M5 | [9ce242f](https://github.com/Flyfish233/spake2/commit/9ce242f80f61ab3bd9b822394ba101de5dbf3588) | 三个模块、跨平台 CI、初次平台实测及性能记录 |
| 审计修复 | [b837aba](https://github.com/Flyfish233/spake2/commit/b837aba27bebc1050965fa124c2efa54b4e4544a) | 完整标量采样区间、JS 算术路径修正、补充回归及 API 文档 |
| M6 | 本报告所在的文档与证据提交 | 最终平台记录、分工复核、来源核对、已知限制及 PR 总结 |

GitHub 提交通过已授权的文件/Git database API 创建。环境中的本地提交
与远端提交可能具有不同作者元数据及父提交标识；交付时按 Git tree
逐一核对相同内容，未将本地 SHA 冒充远端提交。

## 实现范围

### 纯 Kotlin 核心与模块隔离

| 模块 | 职责 | 生产依赖 |
| --- | --- | --- |
| 根模块 `spake2` | RFC 套件、规范标量输入、协议状态机与确认 | `:primitives`、Kotlin 标准库 |
| `:primitives` | SHA-256、SHA-512、HMAC-SHA256、HKDF、有限域、两种曲线、熵接口 | Kotlin 标准库 |
| `:legacy` | 显式 Legacy 协议及独立状态机 | `:primitives`、Kotlin 标准库 |

13 个生产源文件中的密码学运算均位于 `commonMain`。没有通过
`expect/actual`、JNI、JCA/JCE、OpenSSL、BoringSSL、libsodium 或
CryptoKit 执行核心算法；没有引入二进制密码学运行依赖。
Gradle Wrapper 的构建启动 JAR 与测试时使用的外部参考程序不属于
库的密码学运行后端。Android AAR 及其 classes.jar 的内容也进行了检查。

`EntropySource` 要求调用者提供新鲜、独立、均匀的 CSPRNG 字节，
失败时抛出异常。返回缓冲区的所有权交给库并被清理。
库不选择平台随机后端，不使用 `kotlin.random.Random`，
也不能检测字节数正确但可预测的随机源。

### RFC 9382

实现并验证：

- `SPAKE2-P256-SHA256-HKDF-HMAC`。
- `SPAKE2-edwards25519-SHA256-HKDF-HMAC`。

两者具有相同的公共状态语义：生成本地消息、处理对端消息并产生本地
确认 MAC、验证对端确认、一次性释放 16 字节会话密钥。公共 API
不会在确认成功前返回认证会话密钥或内部 K、Ka、KcA、KcB。

`w` 由应用提供，采用 32 字节大端规范标量编码；密码归一化、MHF、
盐和成本参数由应用协议定义。数学约简接口没有被包装成默认密码哈希。
身份、双方消息、共享点和规范 w 进入转录，AAD 进入确认密钥派生。
长度前缀、固定 M/N、曲线编码、cofactor 位置及确认方向均有固定预期值。

RFC 解码器检查长度、编码规范、曲线方程和相关群条件。
Edwards 测试覆盖全部八个纯低阶元素，并以独立参考验证混合阶输入下
的 cofactor 处理和确切线缆字节绑定。不能把“接受某些混合阶点”
误写成缺少小子群处理，具体条件见 [符合性说明](conformance.md)。

RFC 临时标量采样包含规范零值。零值不会被悄悄重采样；
随后退化的公共点或共享点使会话终止，不能释放密钥。
这并不能补救可预测随机源造成的泄漏。

### Legacy BoringSSL SPAKE25519

使用独立的 `LegacySpake25519` API 和模块，参考固定提交
`9e04aed2d1652441d2c84e85ed21acc05e053260`。
已经覆盖旧 M/N、密码 SHA-512 与特殊标量修正、64 字节熵的
小端约简、乘 cofactor 后保留最高位、旧点解码、Alice/Bob 排序、
旧 SHA-512 转录及 64 字节输出。

输出方法名为 `deriveUnconfirmedKey`：它返回**未确认**密钥。
周边应用协议必须另行实现双向确认。没有自动回退、协议协商捷径或
RFC/Legacy 状态混用。

默认的显式 Legacy 实例采用历史 `HARDENED` 标量修正。
`PRE_HARDENING_UNSAFE` 仅在调用者明确选择时使用，并保留旧版
小子群泄漏风险。旧解码器对部分非规范编码及 unmask 后单位元的接受，
是固定版本兼容行为，不代表 RFC 符合性。

Kotlin API 对失败后的复用更严格：失败消耗会话；身份和密码具有资源上限；
返回固定完整 64 字节，而不复现 C 的所有输出缓冲区约定。
这些差异已在 [Legacy 文档](LEGACY_COMPATIBILITY.md) 和
[符合性说明](conformance.md) 中公开。没有 Kadb 专用接口或默认参数。

## 独立验证依据

| 检查 | 实际预期值与结果 |
| --- | --- |
| RFC P-256 官方向量 | 附录 B 全部 4 组；逐项检查 pA、pB、K、TT、Hash(TT)、Ke、Ka、KcA、KcB、双方确认 MAC；参考生成器核对全部 17 个输入/输出字段 |
| RFC Edwards 向量 | python-ecdsa 0.19.1 加独立 hashlib/hmac 产生 12 组；同一参考密钥派生过程先核对官方 P-256 向量 |
| Edwards 低阶与混合阶 | 单独的 Python 仿射大整数实现保留完整扭点群，检查 8 个低阶元素和 7 个混合阶案例 |
| Legacy 历史向量 | 512 组历史交换全部通过；独立重建固定 C oracle 后重新生成并比较全部 1,024 个端点结果 |
| Legacy 补充语料 | 7 组确定性交换、4 个历史对端案例、24 组新交换、108 个畸形/边界对端输入及 3 个零/阶边界熵案例 |
| 哈希/KDF 差分 | 671/671 对 Python hashlib/hmac；另有标准 SHA、RFC 4231、RFC 5869、填充分块边界及百万 a 向量 |
| 有限域/P-256 差分 | 4,000 个 BigInteger 比较和 60 个独立 Python/OpenSSL 点乘预期值，修正后再次全部通过 |
| 算术进位边界 | 12 个 commonTest 原始 Montgomery 预期值和 40 个 JVM 原始乘积比较 |
| 额外曲线语料 | 固定版本 Wycheproof 的 87 个选择案例，包括无效点与编码策略；并非完整 Wycheproof 套件 |

哈希差分引导程序实际使用 Gradle 捆绑的 Kotlin 2.0.21 编译器；
项目及最终跨平台构建使用固定 Kotlin 2.2.21。两类证据分别记录，
没有用引导检查替代正式项目测试。

错误与边界测试还覆盖密码、身份、AAD、角色、确认消息错误，非法长度、
非规范点和标量，熵源异常/错误长度/越界候选，重复调用、销毁后调用、
数组别名、重入、JVM 真实线程重叠以及异常后的自有缓冲区清理。
有限长度 fuzz 与明确的恶意点语料分别描述，不夸大为穷尽性 fuzz。

完整版本、生成命令和来源见 [reference-vectors.md](reference-vectors.md)
及 [LEGACY_COMPATIBILITY.md](LEGACY_COMPATIBILITY.md)。
外部 Python、BigInteger、OpenSSL 与 C oracle 仅用于验证。

## 最终平台实测

下表均对应最终代码 b837aba。测试数为方法数；一个方法可能迭代整套向量。

| 平台 | 结果 | 验证边界 |
| --- | --- | --- |
| JVM | 80/80，通过 | JDK 17；包含 JVM 专属数学、Wycheproof、真实线程并发误用测试 |
| Linux Native x86-64 | 71/71，通过 | 实际执行三个 Native 测试程序 |
| JS / Node | 71/71，通过 | 实际 Node 执行；未执行浏览器引擎测试 |
| Wasm / Node | 71/71，通过 | 实际 KotlinWasmNode 执行 |
| Android | 71/71，通过；三个 AAR 构建成功 | 主机 JVM 单元测试；未运行设备、模拟器或 ART |
| iOS Simulator Arm64 | 71/71，通过 | macOS 26.6.2 Apple Silicon 实际模拟器测试 |
| iOS Arm64 设备目标 | 三个模块编译成功 | 编译 KLIB；未运行真机 |
| iOS Simulator x86-64 | 只配置 | 现有 macOS runner 为 Arm64，没有该架构的实测结论 |

所有已执行方法均为零失败、零错误、零跳过。Linux CI 的四目标命令
耗时 10m 3s；Android 构建及主机测试 58s；iOS 编译及模拟器测试 5m 37s。
这不是各密码操作的性能测量。
本地 JS/Wasm 使用 Node 22.0.0；CI 日志没有单独披露测试运行时的 Node
版本，因此不把本地版本直接当作 CI 版本。

原始日志、JUnit 或逐方法 JSON、源码清单及下载产物 SHA-256 已保存。
CI 使用的临时 PR test merge 与代码提交 Git tree 相同，已通过独立
GitHub 比较检查；这不意味着开发分支已合并。
本地干净构建的各平台结果单独记录于
[final-platform-validation.md](final-platform-validation.md)。
该完整命令实际退出码为 0，总耗时 33m 4s，122 个任务中 106 个执行、
16 个 up-to-date；四个目标的测试均实际执行并通过。

早期 68、77、69 项测试记录对应历史快照，保留在台账中。
初次 CI 的 Linux 作业被新提交触发的并发策略取消，只保留其确实完成的
部分结果。一次增量编译曾报告重复声明；同一冻结源码进行 clean
重试后通过，准确内部原因尚未确定。失败日志保留，没有虚构为测试通过，
也没有改协议来绕过编译问题。

## 审计中实际发现并修正的问题

[安全审查](security-review.md) 按 SR-01 至 SR-10 记录严重度、修复与证据。
关键修复包括：

- 并发获取失败与持有者释放之间的竞争可能使会话重新可用；终止和清理
  所有者状态修复了该路径，并有阻塞线程及重入回归。
- 确认值应先检查长度再复制；密码标量应复制后验证自有副本；清理完成
  前不能发布已销毁状态。
- 清理路径不再通过临时引用数组触发额外分配；普通失败路径验证缓冲区清理。
- RFC 标量采样从排除零改为完整规范区间，并添加独立 M/N 边界预期值。
- 实际 Kotlin/JS 2.2.21 的 boxed Long 乘法存在按值分支。共享域乘法改为
  具有明确最大无符号和上界的 Int 累加，SHA-512 在拓宽前屏蔽符号。
  检查了实际生成并被 Node 使用的 JS：Int32Array、Math.imul、32 位回绕、
  无符号进位及相关哈希路径；所有目标执行了对应回归。
- 纠正 RFC 9382 所属 IRTF stream 的许可说明，保留适用的原始通知，
  没有把 RFC 5869 的 BSD 代码组件条款直接套用到 RFC 9382。

数学、协议和生成代码采用不同实现人员复核，但都属于同一 AI 辅助工程
团队；**不是外部专业密码学审计或形式化证明**。检查生成 JS 也不等于
证明 JIT 或 CPU 的严格常数时间。各结论的来源和范围分别保留在
[math-review.md](math-review.md)、[math-int-carry-review.md](math-int-carry-review.md)、
[js-runtime-review.md](js-runtime-review.md) 和 [final-api-review.md](final-api-review.md)。

## 性能、内存与尚存限制

39 次完整双端操作均比对独立预期密钥，其中各模式 3 次预热、10 次计量。
JVM 共享主机上的结果为：

| 完整双端操作 | 中位耗时 | 调用线程累计分配中位数 |
| --- | ---: | ---: |
| RFC P-256，含双方确认 | 182.76 ms | 28.24 MiB |
| RFC Edwards25519，含双方确认 | 84.07 ms | 12.98 MiB |
| Legacy HARDENED，未确认输出 | 78.48 ms | 12.81 MiB |

分配量是操作期间累计分配，不是常驻内存；数值仍较高。
共享主机、有限预热和单组输入不构成正式吞吐或侧信道基准。
没有用堆快照证明无泄漏，也不能把这些 JVM 数据推断为移动端性能。
见 [performance.md](performance.md) 的原始样本、运行参数和来源哈希。

仍需明确保留的限制：

1. JVM/ART、Native、JS、Wasm 均没有严格常数时间保证或机器码级侧信道认证。
2. 清理是自有数组的尽力覆写；运行时复制、寄存器、优化器、崩溃与极端
   分配失败下的物理擦除未被保证。
3. 并发回归覆盖具体竞争，不是所有调度的模型检查；没有 Native 线程压力认证。
4. Android ART/真机、iOS 真机、Intel iOS 模拟器和浏览器引擎没有执行。
5. 调用者负责 CSPRNG、RFC 密码预处理及应用协议策略；Legacy 必须另做确认。
6. 尚未获得外部专业安全审计；保持实验性，不标注生产安全认证。
7. P-384、P-521、Edwards448、SHA-512 套件和 CMAC 套件未实现，也未标记为支持。

## 许可证与复现入口

原创实现为 **AGPL-3.0-only**，没有宽松链接例外。
第三方 fixture、RFC 数据、Wycheproof、参考项目及 Gradle 构建工具各自保留
来源、版权和许可；没有统一重新声明为原创 AGPL。
见 [THIRD_PARTY.md](../THIRD_PARTY.md) 和 `third-party/`。

`spake2-java` 仅用于只读比较及回归来源；未修改其仓库。
本项目没有 Kadb 依赖、固定身份或专用兼容捷径。

普通环境的复现命令：

```sh
./gradlew --no-daemon clean jvmTest linuxX64Test jsNodeTest wasmJsNodeTest
./gradlew --no-daemon -PenableAndroid=true assembleDebug testDebugUnitTest
# macOS Apple Silicon:
./gradlew --no-daemon compileKotlinIosArm64 iosSimulatorArm64Test
```

Android 需要 SDK 35；使用 JDK 17、Gradle 8.14.3、Kotlin 2.2.21。
参考向量重建、环境故障处理和逐次构建结果见 [validation.md](validation.md)。
API 用法、状态与所有权契约见 [API.md](API.md) 和 [README](../README.md)。
