# 第三轮独立复核：状态机、秘密生命周期与侧信道

审查者：本轮独立任务 `state_sidechannel`（同一 AI 系统内的分工复核，非外部专业审计）。
输入：`e1b0e7f6e865eca6d229ad8f36de63883f808e8c`；生产源码与 `59105290820bcbfd4e0336a2e7713006a461527f` 相同。
采用 `THREAT_MODEL.zh-CN.md` 的 C0 / T0 / T1 / M0 模型。先阅读已有结论，再独立逐行检查两套会话、群运算、ModMath、SHA/HMAC/HKDF 与现有并发/生命周期测试。

## 结论与本次证据

本方向目前没有确认新的 Critical / High / Medium 实现漏洞，也没有确认认证绕过、失败后的密钥重用或普通异常导致的会话秘密未清理。这个结论仅覆盖下面实际检查的路径，不能扩展成运行时恒定时间或物理清零保证。

- **已执行**：[有限并发模型](../evidence/state-sidechannel/gate_model.py)；[结果](../evidence/state-sidechannel/gate-model-result.json)。四个模型合计 140 个可达状态、0 个不变量违反。故意退回历史单次 CAS 的负对照找到 1 条“失败获取后漏掉 owner release，session 仍可用”的反例，证明模型能捕获原 SR-01 错误类型。
- **静态审查**：当前 Kotlin 源码及现有 JVM 并发、common 生命周期用例；不是本审查者已经重跑这些 Kotlin 测试。协调执行结果由最终交付统一记录。
- **已执行的产物静态审查**：对本轮从 GitHub Actions 取得的 `0.1.0-dev.59105290820b` 三个 Maven JVM JAR，使用 Temurin JDK `17.0.21+9` 的 `javap -c -p` 导出 11 个类，并人工检查下述关键路径。[脚本](../evidence/state-sidechannel/inspect_jvm.py)、[JAR/class/输出摘要及命令](../evidence/state-sidechannel/jvm-bytecode-manifest.json)、[原始反汇编](../evidence/state-sidechannel/bytecode.tar.gz)。这是**真实候选产物的静态检查**，不是 JVM/JIT/ART 的计时实验。

模型假设顺序一致的原子操作，已有一个 owner 和 1/2 个已经获取失败的 contender；枚举 owner 正常/异常结束、独立 load/CAS、清理和终态发布之间的交错。它没有执行 Kotlin，也没有验证编译器/Native 内存模型、任意线程数或源代码到模型的形式化细化关系。模型中的 erasure/operation body 是单步抽象；不计进程中断、OOM 或违反所有权契约的恶意数组别名。

## 状态机与线性化

| 检查点 | 具体代码 | 反例尝试与裁决 |
| --- | --- | --- |
| 正常输出的发布 | `Rfc9382Session.kt:155–173`；`LegacySpake25519.kt:151–169` | operation 先计算独立结果，只有 CAS `1→0` 成功才返回；被毒化后擦除待返回结果并进入终止清理。没有找到绕开该 CAS 的公开密钥返回。 |
| 失败获取时 owner 已释放 | RFC `poisonConcurrentCall:176–189`；Legacy `invalidateConcurrentAccess:172–184` | 重新读取 gate，看到 0 时 CAS `0→4` 独占清理，看到 1 时 CAS `1→2` 毒化；CAS 失败后重读。该循环覆盖最初 CAS 失败与 poison 之间的释放竞争。 |
| 清理发布顺序 | RFC `erase:149–153` / catch / poison；Legacy `clear:187–194` / catch / poison | gate=3 仅在持有清理所有权并完成 owned-buffer 覆写后发布；看到 gate=2/4 的其他失败调用不会并行改写 owner 的秘密。 |
| RFC 确认前密钥释放 | RFC `processPeerMessage:74–95`、`confirm:98–110` | process 只返回本方 cA/cB；只有固定长度对端 MAC 检查成功后复制 pendingKey。前置状态检查阻断提前或重复 confirm。 |
| Legacy 完成后 gate 回到 0 | Legacy `deriveUnconfirmedKey:107–143`、`generateMessage:71–99` | COMPLETE 后 buffer 已 clear。下一调用即使取得 gate，也先被 state 检查拒绝并永久终止；不会继续用已清零 scalar 派生第二把钥匙。 |
| 重入和 destroy | RFC `destroy:112–123`；Legacy `destroy:145–149` | 回调重入任一 guarded 方法会毒化 owner；即使应用回调捕获内层异常，owner 的最终 CAS 仍失败。destroy 没有恢复初始状态的路径。 |
| 数据可见性 | RFC AtomicInt gate/status；Legacy gate + private state | Legacy state 虽为普通字段，但其读写在同一原子 gate 的所有权内。Kotlin 2.2 官方文档说明 JVM 使用 AtomicInteger、Native 原子读写具 volatile 效果；JS/Wasm 使用单线程实现，不能由 JVM 线程测试推出其多线程保障。 |

**发布边界的精确含义：**owner CAS 成功后可能在实际 return 前被调度暂停；另一个 destroy 可以随后完成，而旧调用之后才返回先前已发布的独立结果。这是可线性化的重叠调用，不能把“destroy 返回以后绝不再收到先前调用结果”作为本 API 的承诺。现有 API 要求调用者串行使用每个会话。RFC `state` 是阶段观测值，也不能替代成功返回的 `confirm` 作为应用授权依据。

构造、peer message 和 confirmation 均复制输入；长度检查先于有界复制。RFC 校验的是已经拥有的 w 副本，避免校验后再复制的 TOCTOU。复制本身并非对任意并发写数组的原子快照；entropy array 的所有权转移及禁止保留可变别名仍是输入契约，未把违反它的应用行为伪装成远程攻击。

## 秘密相关运算追踪

| 路径 | 当前源码观察 | 不能据此声称的性质 |
| --- | --- | --- |
| P-256 `multiply:103–115`、`add:123–167` | 完整 256-bit 扫描；每步 double/add/select；相等/逆元/identity 使用计算后的 mask 选择。 | 固定源码调度不保证 JIT/ART/Native 机器码、分配器或微架构恒定时间。 |
| Edwards `multiply:140–174` | 64 个四位窗口，每个窗口扫描 table[1..15]；index 来自公开循环计数，nibble 仅影响算术 mask。 | 当前没有编译器各优化配置的机器码/缓存/分支测量。 |
| ModMath `add/subtract/multiply/select` | 16-limb 固定上界；carry、borrow 用移位和 mask；最终约简无秘密相关循环；乘法为 Int，避免旧 Kotlin/JS boxed-Long multiply 路径。 | 源码 mask 可能受编译变换影响；未证明所有 CPU 的整数指令时延。 |
| scalar sampling / reduction | reduction 扫完固定输入长度每一 bit；RFC rejection sampling 的接受分支存在，但接受候选在正确独立 CSPRNG 前提下均匀，采样尝试数不是秘密标量的位长循环。 | byte 数量、范围和上限不能证明 entropy quality。 |
| SHA-256/SHA-512 | compression 固定轮数和公开数组索引；分块/padding 分支依赖长度。SHA-512 byte 先 `and 255` 再转 Long，修正仍在。 | Legacy 口令长度未隐藏；JS boxed Long 对象副本不等于可完整擦除内存。 |
| HMAC/HKDF | HMAC 的长 key 分支只依赖 key 长度；RFC 实际 MAC key 固定 16 bytes。HKDF 公共输出长度控制块数；每块 input buffer finally 清除。 | 不承诺这些 internal bridge 的任意外部调用都隐藏输入长度。 |
| peer validation / shared identity / confirmation | 解码错误取决于对端输入；共享 identity 检查有结果分支；MAC 在相同长度下逐字节累积后做最终判断。 | 不能把 RFC §7 的输入独立时序要求从功能向量直接判为 PASS。 |

协议/数学审查者交叉核对后也未找到合法、非退化 scalar 运算的秘密长度 early exit。选择 `peer = w_guess * mask + torsion` 可触发与一次口令猜测相关的退化判断；一次会话只验证一个猜测，属于主动在线猜测能力。这里没有复现离线口令验证或同一会话多次猜测的新增能力。

## 实际 JVM 候选字节码

这次检查的是第二轮固定候选 `5910529` 的 CI Maven 产物；本轮生产代码与它逐文件相同。产物来源关联由[下载记录](../evidence/ci/candidate-download.json)、[源码归档与 Git 对照](../evidence/ci/source-archive-vs-git.json)支持；本节摘要不能独立认证构建生产者。第一次调用发现默认 PATH 中没有 javap，随后使用主控已下载并校验的 JDK 17，成功完成一次 11 类导出；没有反复重试同一环境失败。

| 实际检查路径 | 原始字节码事实 | 结论范围 |
| --- | --- | --- |
| `ModMath.multiply` | `imul` 位于 bytecode offsets 55、120、155；固定循环分支只比较公开索引；最终约简为 `ishr/iand/ixor/ior`。 | 已检查该产物不存在旧 boxed-Long 乘法调用；不代表 JIT 机器码恒定时间。 |
| `ModMath.select/zeroMask/equalMask` | select 的选择位走 `ineg/iand/ixor`，唯一循环分支检查 16 次界限；zero/equal 遍历全部 limbs 后算 mask。 | 选定 bytecode 未将 mask 编译成秘密位条件跳转或秘密索引。 |
| `P256.multiply` | offset 63 / 79 的双循环分别扫描 32 字节和 8 bits，offset 107 / 109 提取 bit 后调用 select；double/add 均先执行。 | 该字节码没有 scalar 最高有效位扫描提前退出。 |
| `Edwards25519.multiply` | offset 138 / 151 / 218 控制 64 windows、4 doubles、15 项扫描；offset 247 的 `ArrayList.get` 参数是扫描变量，nibble 只进入 mask 算术。 | 非秘密 table index 得到产物级支持。 |
| Legacy `hardenPasswordScalar` | 3×32-byte 加法与 public order doubling；低位只形成 `ineg` mask。 | 本产物 hardening 没有把三个秘密低位直接编译成分支。 |
| `Sha512.digest` | bytecode 252 `baload` → 256 `iand` → 257 `i2l`；compression 使用 primitive Long 指令；数组索引/轮数公开。 | 与 source 的 mask-before-widen 一致。SHA-256/HMAC/HKDF 分支分别来自长度、固定循环和成功/异常清理。 |
| RFC `constantTimeEquals` | offset 16 检查公开长度；offset 34 扫描全部 bytes；offset 56 在累积完后决定结果。 | 未发现逐字节 mismatch 提前返回。 |
| 两套 `guarded` / poison / RFC `confirm` | 实际字段为 `java.util.concurrent.atomic.AtomicInteger`，调用 compareAndSet/get/set；confirm 的 pendingKey copy 在 MAC 成功之后，offset 209 最后释放 gate，失败分支先 wipe result。 | 关键发布/清理顺序在该字节码仍存在。未把抽象模型自动等同于 JVM 内存模型证明。 |

| 平台 | 本方向本轮实际证据 | 尚未验证 |
| --- | --- | --- |
| JVM | 上述候选 JAR 字节码；有限 gate 模型；协调测试另见交付 | JIT 优化后机器码、不同 CPU、统计时序/缓存行为、擦除存储保留 |
| Android ART | common 源码与 JVM 字节码参考；本轮主控核实的历史 ART CI 另计 | ART AOT/JIT 机器码、Android 实机侧信道 |
| JS | 当前 common 源码与旧 `js-runtime-review.md` 对照 | 本轮未重新获取/检查 release/minified JS 或 V8 优化代码；旧 emitted-JS 结论仅适用其记录的产物 |
| Wasm | common 源码审查；历史功能结果另计 | 实际 Wasm 指令和引擎机器码的独立审查 |
| Native | common 源码审查；Kotlin 原子语义文档；历史功能结果另计 | 当前 LLVM 优化产物、ABI、机器码、Native 并发调度与内存擦除 |

重现字节码导出：

```sh
python3 docs/round3/evidence/state-sidechannel/inspect_jvm.py \
  --maven-root /path/to/candidate/maven \
  --version 0.1.0-dev.59105290820b \
  --artifact-source-commit 59105290820bcbfd4e0336a2e7713006a461527f \
  --jdk-home /path/to/jdk-17
```

## 秘密生命周期边界

普通完成/失败路径的 owned session buffers、transcript secrets 和 hash/KDF 临时数组有显式覆写。数学局部临时对象、`Rfc9382Core.derive` 中异常前已经分配的数组、GC 移动前副本、boxed Long、寄存器以及编译器可能删除的擦除仍不能获得物理零化保证。特别地，若 OOM 出现在函数建立 finally 范围之前，不能用 session 的 `erase()` 声称所有局部复制都已擦除；这是既有明确限制，本轮没有把它报成新复现的远程漏洞。

两个 Legacy 固定 mask Point 为共享 lazy 对象，但 `Edwards25519.multiply` 首先复制输入到私有 table，运算不会写入原 point；finally 仅销毁 table 中的副本。与 Legacy 审查者交叉检查后，未发现正常跨会话运算会破坏共享常量的路径。

## 分层建议

- **本方向功能/状态不变量：CONDITIONAL PASS。**现有源码、限定模型和协调测试可以支持串行 API 契约下的逻辑安全，不覆盖任意物理故障。
- **RFC §7 全平台严格时序：NOT VERIFIED。**已有特定 JS 产物的历史检查不能扩展到当前全部平台、release/minified/JIT 机器码或不同 Kotlin 版本。
- **生产运行时物理零化与外部专业审计：NOT VERIFIED。**本轮没有制造新的等价替代证据。

最终 Gate A/B/C 必须结合协议、Legacy、Kadb 和候选供应链结果裁决。本方向不建议为了获得绿色结论在本轮大规模替换数学实现或无限追加时序样本。

## 原始依据

- [RFC 9382 §7](https://www.rfc-editor.org/rfc/rfc9382.html#section-7)：点校验、随机值和时序要求。
- [Kotlin 2.2 AtomicInt 官方 API](https://kotlinlang.org/api/core/2.2/kotlin-stdlib/kotlin.concurrent.atomics/-atomic-int/index.html)：JVM/Native/JS/Wasm 平台语义。此处只引用文档给出的同步契约，不把它当作本库机器码验证。
- 仓库 `docs/security-review.md`、`docs/final-api-review.md`、`docs/js-runtime-review.md`：作为待挑战的历史记录；当前判断同时基于源代码独立阅读。
