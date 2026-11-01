# RFC 9382 协议与数学独立复核

审查者：`protocol_math`，同一 AI 系统内的独立工作线程，**不是外部专业安全审计**。
审查输入：`e1b0e7f6e865eca6d229ad8f36de63883f808e8c`，生产文件与
`59105290820bcbfd4e0336a2e7713006a461527f` 保持相同。
固定源码及实际执行环境见 [review-manifest.json](../evidence/protocol-math/review-manifest.json)。

## 结论与执行边界

在本次独立阅读的协议、群运算、标量约简和点解码路径中，**没有确认新的生产漏洞**。
这项结论来自源码与规范逐项复核及下述有限反例；不能推广为密码学证明或全平台恒定时间保证。
没有修改生产实现。既有官方向量、BigInteger 差分、Wycheproof 子集和跨平台结果继承其
原始 SHA/执行范围，本审查者没有重复运行那些大矩阵。

本审查者实际执行了独立 Python affine 整数参考，构造并验证全部 16 个
`42 * peerMask + T` 恶意消息。Kotlin 公共 API 探针交给主控统一运行，实际执行结论以
本轮主控保存的 Gradle 日志/XML 为准；**生成参考输入本身不算 Kotlin 测试通过**。

## 独立审查范围与结果

| 检查 | 具体位置与规范依据 | 结果/边界 |
| --- | --- | --- |
| 标量采样 | `Rfc9382Session.sampleScalar` 125–143；RFC §3.3、§7 | P-256 从 256-bit 均匀字节拒绝 `>=q`；Edwards 屏蔽公开的三个未用位后从 253-bit 区间拒绝 `>=L`。接受集合恰为 `[0,q)`，包括零。128 次上限造成可见失败概率，不造成成功输出分布偏置；依赖调用者真实均匀熵。 |
| 密码标量/宽约简 | `Rfc9382Suite.reduceWideScalar` 26–30；`Rfc9382Core.isCanonicalScalar` 23–30；RFC §3.2 | 固定 32-byte big-endian canonical w；40-byte 最小输入满足相对 256-bit 上界的 64-bit 统计余量。约简没有替调用者选择 MHF，输入不能自动获得均匀性。 |
| Montgomery 加减乘 | `ModMath.add` 108–127、`subtract` 130–145、`multiply` 160–196 | 独立重算 `65535²+65535+65535=2³²−1` 的无符号内循环上界；`ushr` 保留正确进位。`T<m²<mR` 给出 REDC `<2m`，额外高 limb 加一次 masked subtraction 足够。依赖 canonical 内部操作数及受测后端的 32-bit wrapping 语义。 |
| P-256 | `P256.decode` 56–78、`multiply` 103–116、`add` 123–168、`double` 170–202 | 解码拒绝非 65-byte uncompressed SEC1、坐标 `>=p` 与离曲线点；曲线 cofactor 为 1。256-bit 固定扫描，等点/逆点/identity 通过 mask 合并。未发现 carry、identity 分支选择或 scalar 高位截断错误。 |
| Edwards 解码与群语义 | `Edwards25519.decode` 47–80、`multiply` 140–174、`multiplyCofactor` 177–185；RFC §3.2、§3.3、§6 | 严格模式检查 canonical y、平方根存在、x=0 sign 规则；扫描全部 256 bits，逐窗口完整扫描表。RFC 与 Legacy decoder 显式分离。混合阶输入必须在去 mask 后清 cofactor，不能误以只拒绝纯低阶即可。 |
| RFC 共享点 | `Rfc9382Core.sharedPoint` 67–103；RFC §3.3、§7 | Edwards 顺序为严格 decode → 检查接收点非纯低阶 → 去 mask → ×8 → ×x → 拒绝 identity。末端检查覆盖 `w*mask+T`；P-256 也拒绝去 mask 后的 identity 共享值。 |
| TT/密钥表 | `Rfc9382Core.derive` 110–123、`transcript` 125–137；RFC §3.3、§4 | `A,B,pA,pB,K,w` 顺序正确，各加 8-byte LE 长度；w 固定 32-byte BE；精确 peer wire bytes 被绑定。SHA-256 前半为 Ke、后半为 Ka，HKDF info 为精确 `ConfirmationKeys||AAD`，KcA/KcB 各16 bytes，双向 HMAC 各32 bytes。 |
| 确认与释放 | `Rfc9382Session.processPeerMessage` 74–95、`confirm` 98–110；RFC §3.1、§4 | 处理 peer 只返回本地确认值；本地 Role 决定独立的预期对端方向。只有确认比较成功才复制 Ke；失败进入终止清理。AAD 仅影响确认派生，未直接进入 Ke，符合规范。 |

## 新增反例：合法混合阶点解除 mask 后退化

攻击者可选规范的 Edwards 编码：

`peer = w_guess * peerMask + T`，其中 `T` 遍历完整 8 元 torsion 子群。

当猜中 w 时，peer 自身有非零 prime-order 分量，因此仅做 `isSmallOrder(peer)` 的
检查会通过；解除 mask 后得到 T，乘 cofactor 后变成 identity。若实现继续派生，
可能把退化共享值交给后续步骤。当前实现还有 `sharedPoint` 95 行的 identity 检查，
因此静态路径预期在返回任何确认值前失败。

本轮新生成器没有导入生产 Kotlin 数学，也没有调用以前的向量生成器。它从 RFC
曲线等式和 M/N 编码构造 Python affine 群运算，从小 y 搜索合法点并乘 L 得到
order-8 generator；明确检查八个 torsion 元互异、两个 RFC mask 非零且 prime order、
每个攻击消息非低阶，以及所有解除 mask/乘 `8*x` 的结果均为 identity。

证据：

- [独立生成器](../evidence/protocol-math/generate_masked_torsion.py)
- [固定 16 个消息及参考断言](../evidence/protocol-math/masked-torsion-vectors.json)
- [实际 Python 原始输出](../evidence/protocol-math/generation.log)
- [Kotlin 公共 API 回归探针](../evidence/protocol-math/Round3MaskedTorsionTest.kt)

该探针覆盖 Alice/Bob 两角色各八个消息，要求处理消息失败、`FAILED`、owned buffers
归零、后续确认和重复发起均失败。它用于反证现有防线，不是一个已确认生产漏洞的
“修复前失败”。主控在实际运行后应单独保留 XML/执行日志，不将预期写成已执行。

## 交叉质疑与风险分类

1. **退化输入可提供一次在线口令猜测的错误信号**：`w_guess*mask+T` 猜中时的拒绝
   可以被恶意对端观察，但该构造每次交换只试一个猜测；本轮没有从中构造新增离线
   字典攻击、一次多猜测或无口令认证绕过。不能仅凭出现异常就写成 High 漏洞。
2. **Legacy 接受非 canonical/低阶输入不自动等于绕过**：与 Legacy 审查者交叉核对，
   HARDENED 临时标量含 ×8，纯 torsion 输入在解除 mask 后通常不是 identity。
   强制退化需命中密码 mask，且 transcript 含 passwordHash、身份和精确消息；输出
   仍是 unconfirmed。是否认证完成必须查看 Kadb 的 AES-GCM/PeerInfo 边界。
3. **源码固定调度不是运行时证明**：与状态机/侧信道审查者核对，合法非退化群运算中
   没发现新的秘密长度循环、秘密索引或 early exit。decode、退化值和最终确认会按结果
   分支；JIT、ART、Native、JS/Wasm lowering 需要各自证据。RFC §7 全部时序条件
   不能因本表或向量通过而记成已满足。
4. **一般限制，不是本轮确认漏洞**：物理清零、OOM/异常分配前的局部副本、调用者
   提供可预测熵、应用不正确选择 MHF/隐含身份，分别保留原威胁模型的边界。

## 对门禁的建议

- **Gate A**：测试执行闭环后可给功能/协议字节路径 `PASS`；包含 RFC §7 完整
  运行时要求的总体结论至多 `CONDITIONAL PASS`，不得宣告全 RFC 安全符合性。
- **Gate B**：本方向未发现新的已确认阻断漏洞。还需结合其他方向的 Kadb/供应链/
  状态机证据裁决，不能单独签发 PASS。
- **Gate C**：本方向 `NOT VERIFIED`；没有外部审计、生产环境全平台时序证明或
  物理内存清除保证。

规范：[RFC 9382](https://www.rfc-editor.org/rfc/rfc9382.html)，本次实际取得的
[原文](../evidence/protocol-math/rfc9382.txt) 摘要见 manifest；群公式另与既有 EFD
引用和公开曲线方程独立对照。本轮不追加新的大矩阵或下一轮审计。
