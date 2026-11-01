# SPAKE2 第三轮安全审计与交付

日期：**2026-11-01（Asia/Singapore）**。范围为工作分支 `work/spake2-implementation` 与 Draft PR #1。
本轮已经收敛；没有自动启动第四轮审计。

## 1. 结论

**未确认新的默认 HARDENED 生产实现漏洞，没有为追求改动数量修改密码学实现。**
本轮确认并细化了显式旧模式的口令余数泄露，确认Kadb/AOSP配对内层双向GCM复用key/nonce的协议风险，
并分别记录隔离条件及真实攻击前提。没有把它们改写成已复现的默认PAKE认证绕过。

| 门禁 | 裁决 | 证据与条件 |
| --- | --- | --- |
| **Gate A：功能、规范符合性与互操作性** | **CONDITIONAL PASS** | 功能字节路径、既有固定互操作向量与本轮必要回归通过。当前JVM 81方法通过；原候选跨平台/ART/独立Maven消费者证据重验通过。RFC9382 §7全部运行时时序条件仍NOT VERIFIED，新增测试提交的全平台CI尚在运行，故不能写成完整规范安全保证PASS。 |
| **Gate B：当前候选的安全集成条件、实现不变量与风险处置** | **CONDITIONAL PASS** | 可用固定可信候选进入实验性Kadb Maven迁移验证。限定HARDENED、可信CSPRNG、精确身份/角色/通道绑定、完整PeerInfo最终认证、串行一次性会话，以及本报告列出的运行时/AEAD边界。没有已确认的当前默认网络认证阻断漏洞；不表示Kadb迁移已经完成。 |
| **Gate C：生产运行时安全与专业审计** | **NOT VERIFIED** | 未进行外部专业安全审计，未证明全平台严格恒定时间、物理零化或全部二进制可复现来源；真实Kadb设备/平台提供者端到端验证未执行。生产发行不能据此放行。 |

Gate A中功能/互操作子项为PASS，严格运行时保证另列NOT VERIFIED；不能用“CI全绿”覆盖这一区别。
同一AI系统内的三个独立任务加主审查者交叉复核，**不代表外部独立专业密码学审计**。

## 2. 基线、分支与提交

开始前依用户要求申请并获准沙箱外执行，未使用PC Remote。旧工作树只读检查且干净；本轮创建独立克隆，未覆盖其他会话工作。

| 身份 | 不可变记录及用途 |
| --- | --- |
| 第一轮生产基线 | `b837aba27bebc1050965fa124c2efa54b4e4544a` |
| 真实二进制候选 / 第二轮最终CI | `59105290820bcbfd4e0336a2e7713006a461527f`；Maven版本 `0.1.0-dev.59105290820b` |
| 本轮开始时远端HEAD | `e1b0e7f6e865eca6d229ad8f36de63883f808e8c`；仅有第三轮威胁模型和清单 |
| 阶段1远端检查点 | `41d3e990c22b13dae6da8c124c73f393de3fdd18`；冻结真实Git/CI状态 |
| 本轮新增回归提交 | `32190a93e37fe92dd436effa91eab8664b5c8d1e`；加入两角色共16个mask+torsion退化点案例 |
| 本报告及证据交付 | 在上条测试提交之上的文档提交；其精确HEAD由本文件Git permalink及最终聊天记录标识。没有新生产实现或正式Maven版本。 |
| Kadb只读审查 | `experiment/kmp-native-core`，`49a88b14e9b731e4c086c920eb74664450c79d9f`，远端未变化 |
| 两仓库main | SPAKE2 `783299c166ed9613bb4e37d39fd7924205cf7c25`；Kadb `3d1c13e8df261cc4a2bb57b03aa8c392cb8386c3`；均未修改 |

13个生产 `commonMain` 文件与5910529及既有manifest逐个相同。
报告明确区分生产源码身份、测试提交身份与最终文档HEAD。
Git CLI无写凭证，因此通过已认证GitHub连接器提交，使用expected SHA及非force更新分支；本地对齐前核对内容树完全一致。
本地阶段提交88e0233与6f5dbab分别对应相同树的远端41d3e99与32190a9，旧本地对象保留在检查点分支。

原始记录：[开始快照](evidence/baseline/round3-start.json)、[执行检查点](PROGRESS.zh-CN.md)、
[Kadb只读输入](evidence/kadb/source-scope.json)。最终工作树检查另由交付清单及最终聊天报告给出。

## 3. 四个方向实际完成的工作

### 协议与数学

独立阅读RFC9382、ModMath、P-256、Edwards、core/session：检查固定宽度标量、采样接受集合、carry上界、
点成员与编码、低阶/混合阶、cofactor、TT字段/端序、AAD、双向确认和密钥发布。
关键新反例为 `peer = w_guess * peerMask + T`：接收点规范且非低阶，解除mask后才退化。
独立Python affine参考构造两个角色全部8个torsion分量；新增Kotlin回归实际证明16案均在返回确认值前失败、擦除owned buffers并禁止后续重用。

[独立报告](reviews/protocol-math.md) · [参考输入](evidence/protocol-math/masked-torsion-vectors.json) ·
[执行XML](evidence/runtime/jvm-results/rfc/TEST-io.github.flyfish233.spake2.Round3MaskedTorsionTest.xml)

### 状态机与侧信道

重新审查CAS发布点、延迟contender、毒化、独占清理、重入、destroy、输入复制与异常终止。
有限抽象模型共140个可达状态、0个不变量违反；恢复历史单次CAS逻辑的负对照确实给出反例。
它是顺序一致性假设下的模型，实际JVM并发测试另包含于81方法中，不能冒充任意线程数的形式化证明。

对下载的真实候选三个JVM JAR导出11个类的字节码并人工检查关键路径：固定循环、公开索引、mask选择、原子操作、MAC比较和key发布次序。
完整原始反汇编已归档。JIT/ART/Native/JS/Wasm的生产优化代码、统计时序与微架构测量未完成。
正常路径owned数组覆写有证据，GC旧副本、寄存器、OOM与物理内存清零没有保证。

[独立报告](reviews/state-sidechannel.md) · [模型与负对照](evidence/state-sidechannel/gate-model-result.json) ·
[真实产物身份](evidence/state-sidechannel/jvm-bytecode-manifest.json) · [原始反汇编归档](evidence/state-sidechannel/bytecode.tar.gz)

### Legacy SPAKE25519

重新取得固定BoringSSL `9e04aed2d1652441d2c84e85ed21acc05e053260` 原文，核对乘八、高位标量、hardening、身份排序、
原始消息编码、历史解码与unconfirmed输出。新Python affine重算14个确定性端点（含3个bit255边界）和112个异常/边界peer，均与固定C期望一致。
同时复现显式旧模式的低阶投影泄露；HARDENED对照没有该投影。

旧C oracle可执行路径在本环境已不存在，**本轮没有重跑C oracle**。前两轮真实C运行证据被继承；本轮Kotlin固定向量由实际JVM回归执行。
最初“两个mask投影都是八阶”的审查假设被计算推翻，错误假设及修正依据原样保留，未伪装成生产漏洞。

[独立报告](reviews/legacy.md) · [新模型结果](evidence/legacy/independent-legacy-probe.json) · [固定上游来源](evidence/legacy/upstream-sources.json)

### Kadb与供应链

只读完整配对链：当前socket/Native连接exporter、配对码拼接、Alice/Bob含NUL身份、HARDENED、HKDF、AES-GCM、PeerInfo与成功返回/清理路径。
新增5组实验实际加载已下载Maven候选的Legacy/Primitives JVM字节码：同exporter正例、不同exporter、错误口令、身份/NUL错误以及nonce重用/反射/篡改。
全部符合预期。HKDF/GCM使用SunJCE，exporter是合成64-byte数组，**不等于真实Kadb/TLS/ART/Native执行**。

供应链不仅比对包内自报hash：独立GitHub API关联run/revision/artifact，校验下载ZIP；重新检查33个发布单元；
三个完整源码归档各482文件与独立Git对象一致；本地重编译26个production JVM class与CI候选26个class逐字节一致；
wrapper JAR另与Gradle官方摘要相同。其余平台二进制可复现性、正式签名/attestation及完整传递构建依赖锁仍未建立。

[Kadb与供应链报告](reviews/kadb-and-supply-chain.md) · [5组真实候选实验](evidence/kadb/BINDING_PROBE.md) ·
[源码逐项比对](evidence/ci/source-archive-vs-git.json) · [26个class复建比对](evidence/ci/jvm-class-rebuild-comparison.json)

## 4. 重要发现、处置与剩余风险

全部结构化条目见 [FINDINGS.json](FINDINGS.json)，包含严重程度、位置、触发条件、证据、处置与剩余风险。

| 编号 | 严重程度 / 确认状态 | 处置和影响 |
| --- | --- | --- |
| **R3-L01** | **Medium，已确认的显式历史模式风险** | PRE_HARDENING_UNSAFE：Alice透露reduced SHA512标量mod2，Bob透露mod8，可过滤口令候选；不是双方各3位，更不是相加4位。默认HARDENED及Kadb显式HARDENED已隔离，无自动降级。旧模式从Gate B可接受范围排除，兼容性文档更正。 |
| **R3-K01** | **Medium，已确认的协议继承风险，有条件暴露** | 双向首个PeerInfo同key/nonce0；实测8192-byte密文XOR关系。原样反射被type0拒绝，改type的tag失败。没有复现被TLS/exporter保护的当前N0/N1认证绕过。Kadb未改动；保留TLS绑定及完整PeerInfo检查，不把该封装用作通用双向通道，生产前需要协议风险处置。 |
| **R3-A01** | 保证缺口，非已确认利用 | 严格恒定时间、全平台优化机器码及物理零化NOT VERIFIED；阻断生产级安全保证。 |
| **R3-S01** | 保证缺口，非已确认恶意产物 | 来源核对和JVM class级复建已完成；没有全部平台可复现构建、正式签名或完整可信依赖锁。CI artifact到期2026-11-09，不是长期正式仓库。 |
| **R3-E01** | Low，文档状态缺陷 | 第二轮交付报告仍写“进行中”；已依据真实run/job/artifact/XML更正。PR正文未重写，仍可能显示旧历史状态。 |

**没有新的Critical/High确认发现，也没有未修复的默认生产协议正确性Medium发现。**
没有生产补丁，因此不存在本轮“生产修复前失败→修复后通过”的提交链。实际发生的是新增反例成功击中防御、
历史不安全模式/继承协议风险复现，以及文档状态和精确描述修正。不得为了凑修复记录虚构失败。

## 5. 测试与证据分层

### 本轮新执行

| 项目 | 结果 | 原始证据 |
| --- | --- | --- |
| JVM完整回归 | **81方法，0 failure/error/skipped**；包含新增方法内16攻击案例 | [执行日志](evidence/runtime/jvm-verified-truststore.log)、[命令/环境](evidence/runtime/jvm-verified-truststore.json)、[XML计数](evidence/runtime/jvm-summary.json) |
| Python证据门禁 | **47方法**普通模式与 **47方法** `-O`模式均通过 | [普通](evidence/runtime/python-gates.log)、[优化](evidence/runtime/python-gates-optimized.log) |
| 独立协议/Legacy参考 | 16退化输入；14确定性端点；112异常peer；已知旧模式泄露及HARDENED对照 | 各审查报告及模型原始日志 |
| 有限并发模型 | 140可达状态无违反；历史错误负对照1条反例 | [结果](evidence/state-sidechannel/gate-model-result.json) |
| 实际候选+JCE绑定实验 | **5组通过**，包括nonce重用实际后果及反射/篡改拒绝 | [日志](evidence/kadb/binding-jvm.log)、[环境/类加载及命令](evidence/kadb/binding-result.json) |
| Maven实物 / 来源复验 | 33发布单元；482源码文件×3相同ZIP；26/26 JVM class一致 | [结构](evidence/ci/maven-structure-verification.json)、[源码](evidence/ci/source-archive-vs-git.json)、[class](evidence/ci/jvm-class-rebuild-comparison.json) |

初次JVM尝试因系统JRE无javac而失败，第二次因官方新JDK缺当前环境CA而失败；分别保留
[首次日志](evidence/runtime/jvm-baseline.log)及[证书错误日志](evidence/runtime/jvm-round3.log)。
随后使用校验后的Temurin17.0.21+9和系统现有truststore成功，未关闭TLS验证、未修改构建配置以跳过测试。

### 继承并在本轮重新取得、重数的第二轮CI

三个run的head_sha均为5910529，全部SUCCESS。

| 原CI | 已核对结果 |
| --- | --- |
| [40388080241 核心](https://github.com/Flyfish233/spake2/actions/runs/40388080241) | JVM80；Linux Native/JS/Wasm各71；macOS arm64/x64及iOS arm64/x64 simulator各71。重数XML无失败/错误/跳过。Android build/host job亦success，其artifact本轮未重下。 |
| [40388080295 ART](https://github.com/Flyfish233/spake2/actions/runs/40388080295) | API23/35各72核心/运行时见证+5 Maven消费者方法，设备执行无失败/错误/跳过。 |
| [40388080222 发布/消费者](https://github.com/Flyfish233/spake2/actions/runs/40388080222) | 33发布单元；基线工具链9目标、Kadb工具链5目标，每目标5方法通过。 |

原始run/job/artifact清单、下载校验、XML、消费者解析记录分别在
[ci目录](evidence/ci/)、[重数结果](evidence/ci/recount.json)、[原始归档](evidence/ci/retained-results.tar.gz)。
这些记录不包含本轮新增方法，不能写成新测试已经在所有平台运行。
第二轮报告已同步更正：[docs/round2/DELIVERY.zh-CN.md](../round2/DELIVERY.zh-CN.md)。

### 新提交的CI与未执行范围

新增测试提交32190a9已触发：

- [核心41266667768](https://github.com/Flyfish233/spake2/actions/runs/41266667768)
- [ART41266667756](https://github.com/Flyfish233/spake2/actions/runs/41266667756)
- [Maven/消费者41266667812](https://github.com/Flyfish233/spake2/actions/runs/41266667812)

报告冻结时这三项均为 **IN PROGRESS**，未记为PASS。精确观察时间见
[状态快照](evidence/ci/new-test-commit-runs.json)。最终文档使用skip-ci避免因归档改变无限重跑；
不等待这些长任务结束，也不将旧CI冒充新SHA的结果。

本轮未执行：新的Android ART、Native、JS/Wasm运行；Kadb真实Maven迁移、真实TLS双连接攻击实验、Android/Native平台配对与设备验证；
新C oracle构建；生产时序/缓存/功耗/物理零化测试；外部专业审计。

## 6. Kadb迁移判断与后续优先级

**可以进入真实Maven迁移验证，范围是固定候选的受控实验。Kadb尚未完成迁移，也未获得生产放行。**

1. **首先完成新增测试提交的CI结果登记，并保留可信候选。** 若出现真实功能回归，再针对该失败处理；环境失败按证据区分。不要把最终文档HEAD假装成已有新Maven版本。
2. **下一项Kadb任务才修改实际依赖入口。** 固定 `io.github.flyfish233:spake2-legacy:0.1.0-dev.59105290820b` 及相同版本Primitives、完整revision、候选来源/摘要，保持HARDENED、精确NUL身份、TLS exporter及PeerInfo检查。按[既有迁移说明](../round2/kadb-migration.md)更新源码包/解析门禁，去除旧源码组合入口后运行实际Kadb工具链。
3. **执行完整配对集成验证。** Android/Native真实exporter语义、同连接一致与跨连接区别、错误口令、双TLS转发、PeerInfo类型/长度、认证失败终止、取消/销毁，以及至少一次真实设备正向配对。此次5组JCE模型不能替代它们。
4. **正式发行前处理生产安全与来源保证。** 对具体运行时完成秘密相关机器码/时序审查，取得外部专业审计；对内层GCM继承风险作协议层处置或清楚限定；建立可长期取得的固定产物、可信依赖校验、签名/来源证明与完整对应源码交付。

这些是后续必要行动，不是本轮继续无界扩张的许可。本轮没有修改Kadb/main、合并PR、发布Release或正式Maven版本、改变许可证/可见性，亦未整体替换纯Kotlin实现。

## 7. 复现入口

```sh
./gradlew --no-daemon jvmTest --console=plain
python3 -m unittest discover -s tools/tests -v
python3 -O -m unittest discover -s tools/tests -v
python3 docs/round3/evidence/protocol-math/generate_masked_torsion.py
python3 docs/round3/evidence/legacy/independent_legacy_probe.py
python3 docs/round3/evidence/state-sidechannel/gate_model.py
python3 docs/round3/evidence/kadb/run_binding_probe.py --jdk /path/to/jdk-17 --maven /path/to/validated/maven
```

使用记录中的Kotlin2.2.21/Gradle8.14.3/JDK17环境；环境特定truststore参数只在确有该证书链需要时使用。
全部秘密均为公开固定向量或合成测试值。原始CI XML与字节码分别在tar.gz中，解包后文件路径对应manifest。
每项结论的执行范围、SHA、命令和限制均按上文证据保留。
