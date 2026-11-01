# 第三轮独立复核：Legacy SPAKE25519

审查者：本次 AI 团队的 Legacy 子审查者。该复核独立阅读源码并构造反例，
不构成外部专业密码学审计。审查初始 HEAD 为 `e1b0e7f6e865eca6d229ad8f36de63883f808e8c`；
实验执行时主控已保存文档检查点 `88e0233193ce4cf08d974fcd12ed0255624c6536`。
两者的生产源码与 `59105290820bcbfd4e0336a2e7713006a461527f` 相同。

## 结论

本方向没有确认新的默认 HARDENED 生产协议缺陷，也没有据此改动生产源码。
确认并具体复现了显式 `PRE_HARDENING_UNSAFE` 模式的已知口令信息泄露，
且推翻了“两个角色都精确泄露三位”的过度概括：固定参数下 Alice 泄露一位，
Bob 泄露三位。这是历史模式的风险细化，不是 HARDENED 回归。

本轮实际执行了 Python 整数/仿射群运算的独立复核，并重新下载固定版本
BoringSSL 原始源码进行逐项比对；**没有重编译或重新执行 C oracle**。
先前报告中的可执行文件路径在本环境不存在。原有 C oracle 执行证据属于
第一、二轮，当前 Kotlin 测试结果由主控的统一验证记录说明。

## 输入、方法与已执行证据

- BoringSSL 固定提交：`9e04aed2d1652441d2c84e85ed21acc05e053260`。
  原始 `spake25519.cc`、`curve25519.cc`、公共头文件的 URL 与实际下载摘要见
  [来源记录](../evidence/legacy/upstream-sources.json)，源码快照见
  [source/](../evidence/legacy/source/)。摘要用于识别本次输入，不自行认证供应链。
- 独立模型使用 Python 任意精度整数与 Edwards 仿射加法，未调用 Kotlin、
  C oracle 或它们的 limb/extended-coordinate 实现。
- [复现脚本](../evidence/legacy/independent_legacy_probe.py)、
  [完整结果](../evidence/legacy/independent-legacy-probe.json)、
  [原始输出](../evidence/legacy/independent-legacy-probe.log)。命令：

```sh
python3 docs/round3/evidence/legacy/independent_legacy_probe.py
```

| 本轮新执行的检查 | 实际结果 | 解释 |
| --- | --- | --- |
| 7 个既有确定性交换，双方共 14 个端点 | PASS | 逐字节检查私有标量、口令标量、完整 SHA-512、消息、unmasked/shared point、最终 64-byte key |
| 上述端点中的 bit 255 边界 | 3 个端点 PASS | 不把泛用 255-bit scalar 假设套到 Legacy；实际 `8*r` 必须保留最高位 |
| 4 个历史异常输入和 108 个后增异常/边界输入 | 112 个 PASS，55 个接受 | 独立数学模型与既有固定 C 期望的接受状态和完整 key 一致；非规范编码接受是显式历史语义 |
| 不安全 Bob 公共消息的低阶投影 | 7 个不同 mod-8 余数全部恢复 | 512 条 corpus 的旧 Bob 子集缺余数 5；模型同时验证投影表有完整 8 个不同元素 |
| 不安全 Alice 公共消息的低阶投影 | 偶/奇两类均恢复 | `[L]M` 是二阶点，只区分 mod 2 |
| HARDENED 公共消息对照 | 8 个对照均投影为单位元 | 两个角色的已知低阶投影泄露均消失 |
| 正/负零编码同点、不同原始字节 | PASS：派生 key 不同 | 验证 transcript 绑定原始 wire encoding，没有意外用规范化编码替换 |

第一次探针要求两种 mask 的投影都是八阶，实际失败输出保存在
[被反例推翻的假设](../evidence/legacy/initial-projection-assumption-failure.log)。
随后依据计算出的实际群阶分别验证两个角色，没有把探针假设失败记为库漏洞。

## 逐项源码核对

| 实现位置 | 固定 BoringSSL 对照 | 结果及边界 |
| --- | --- | --- |
| `LegacySpake25519.generateMessage`，71–99 行 | `spake25519.cc` 353–451 行 | 64-byte 随机输入按小端模 L，再乘 8；口令 SHA-512 模 L；默认 HARDENED；M/N 角色正确 |
| `shiftThree`，211–218 行；`Edwards25519.multiply`，140–174 行 | `left_shift_3` 313–324 行；curve25519 694–745、866–916 行 | Kotlin 处理完整 256 位，`8*(L-1) < 2^256` 不溢出。固定 C 的 base-multiply 注释列更窄前提，实际此协议允许的极少数最高位边界仍只产生最高 digit 8；既有 C 向量与本次仿射复核一致，不能由此推广成任意 256-bit C base-multiply 保证 |
| `hardenPasswordScalar`，219–238 行 | `spake25519.cc` 381–425 行 | 三次按位条件加 L、2L、4L 等价于选取 `s+kL` 的唯一 mod-8 为零代表，且小于 `8L`；不会错误地把最后结果再次约简为 L |
| `Edwards25519.decodeLegacy`/`decode`，44–79 行 | `x25519_ge_frombytes_vartime` 489–527 行 | 都允许非规范 y 和 x=0 的负号编码；不在曲线上仍失败。该路径与 RFC strict decoder 分离 |
| `deriveUnconfirmedKey`，107–143 行 | `SPAKE2_process_msg` 469–531 行 | 先解除对端角色 mask，再用含 cofactor 的私有标量乘；完整 SHA-512 口令 hash 和原始对端字节进入 TT；Alice/Bob 身份与消息排序相同 |
| `encodeTranscript`，239–249 行 | `update_with_length_prefix` 454–467 行 | 每字段 8-byte LE 长度；身份 65,535-byte 上界、消息/点/hash 固定长度使总长度不发生 Int 溢出 |
| 公共输出与销毁，26–39、103–105、130–141 行 | 公共头文件 159–176 行 | 64-byte 输出明确为 unconfirmed；没有 RFC confirmation 或错误口令检测承诺。成功消耗状态，失败和重复调用永久终止 |

Legacy 512-bit 输入模 L 的分布是固定历史算法；不得把它包装成 RFC 的
rejection-sampling 实现。输入字节必须由合格 CSPRNG 产生；“可输出 64 字节”
不证明熵质量。零熵/模 L 为零的历史行为也不能被解释为安全随机输入。

## 确认的重要风险

### R3-L01：显式历史模式允许从公开消息筛选口令候选

**严重程度：Medium；确认；既有、显式 opt-in 风险；默认 HARDENED 已隔离。**

位置：`LegacyPasswordScalarMode.PRE_HARDENING_UNSAFE`（14–19 行）及
`generateMessage`（80–89 行）。触发条件是调用方主动选择该模式，并让攻击者
取得对应公开消息。其余数泄露无需知道临时标量。

令 `s = littleEndian(SHA512(password)) mod L`，公开消息为 `x*G+s*M`
或 `y*G+s*N`。攻击者乘以 L，临时项消失，得到 `s*[L]M` 或 `s*[L]N`。
本次实测 `[L]M` 为二阶、`[L]N` 为八阶，分别恢复 `s mod 2` 和 `s mod 8`。
同一口令重复观察不会自动不断增加泄露位数，但可从候选字典排除不匹配余数。
双方都旧模式时 Bob 的三位已包含 Alice 的一位，不能相加成四位。

HARDENED 添加的 kL 使实际 mask 标量可被 8 整除，两个投影均为单位元。
无自动降级，Kadb 必须继续显式选择 HARDENED。本轮不删除历史互操作 API，
也不将该模式列入安全集成可接受条件。结构化记录见
[legacy-findings.json](../evidence/legacy/legacy-findings.json)。

### 接受低阶/退化点本身没有形成已复现认证绕过

通过选择 `peer = s_guess*N + T`（T 为 torsion），攻击者可以在猜中口令时
让解除 mask 后的共享点退化。但这是一次在线口令猜测，尚未得到可跨多个
候选离线验证的能力。随便发送纯 torsion 点 T 后仍有 `T-s*N` 项，不能把
“接受 T”直接等价成“共享点为单位元”。TT 的完整 password hash 与原始
消息、角色身份排序还需一致。

本结论经协议/数学审查者交叉质疑，未找到超出一次在线猜测的具体反例。
该判断不把未确认 key 升格为认证：应用必须验证双方认证流程，Kadb 必须
等 GCM 验证及合法 PeerInfo 检查完成后才能报告配对成功。

## 状态与集成交叉质疑

状态机审查者复核确认：成功后的 COMPLETE 虽然释放 gate，但后续调用先由
state 拒绝、清零并进入终态；没有发现重用秘密的路径。共享 lazy M/N 点被
`multiply` 复制，表销毁作用于副本；没有发现一个会话破坏其他会话参数的路径。
CAS 之后合法结果的返回可能晚于另一个 destroy 调用，不等于破坏线性化。

另对主控提出的 Kadb 双向 AES-GCM 同 key/nonce0 进行交叉质疑：固定 AOSP
`1cf2f017d312f73b3dc53bda85ef2610e35a80e9` 的 `aes_128_gcm.cpp` 37–78 行
与头文件 56–59 行确实使用同一 AEAD context、两个从零开始的计数器，属于
继承的协议设计。见 [AOSP 原始来源记录](../evidence/legacy/aosp-cross-review-sources.json)。

当前 Kadb 反射 client 密文可通过同 key/nonce 的 GCM 检查，但解出的是 type 0，
`PeerInfo.isValidDeviceGuid` 要求 type 1 与非空 NUL 终止 GUID，故原样反射被拒绝。
修改 type 需要有效新 tag。双 TLS MITM 的 exporter 不同，不能把两边密文当作
相同 key/nonce 的两条样本；成功知道 PAKE key 的恶意对端已在认证边界内。
没有复现当前网络攻击者可利用的认证绕过或离线口令攻击。
同一诚实 TLS 会话内双向内层密文若暴露，则独立 GCM 保证不成立；不能宣称
内层加密在脱离 TLS 的条件下仍安全，也不能单边改 nonce 而破坏 ADB 互操作。

## 本方向的 Gate 建议

- **Gate A，Legacy 功能/互操作部分：PASS（限定已执行/继承证据范围）**。
  本轮新执行的是独立数学重算，C oracle 历史执行和本轮 Kotlin 测试应分别归档。
- **Gate B，Legacy 集成部分：CONDITIONAL PASS**。要求 HARDENED、可信熵、
  正确角色身份/原始字节、应用确认边界和线程所有权契约；时序与下游风险由
  主控合并裁决。本方向不单独替全项目签发 Gate B。
- **Gate C：NOT VERIFIED**。没有外部专业审计、生产机器码恒定时间证明或
  物理内存擦除证明。本轮没有据此扩大生产保证。

该审查已经收敛；没有请求新增测试矩阵或整体加密实现替换。
