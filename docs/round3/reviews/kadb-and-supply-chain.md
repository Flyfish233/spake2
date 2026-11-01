# Kadb 配对链与候选来源复核

审查日期：2026-11-01（Asia/Singapore）。主审查者与三个独立任务交叉裁决；不是外部专业审计。

## 输入与执行边界

Kadb 实际审查分支 `experiment/kmp-native-core` 仍为
`49a88b14e9b731e4c086c920eb74664450c79d9f`。默认分支是另一条线，HEAD 为
`3d1c13e8df261cc4a2bb57b03aa8c392cb8386c3`，不能拿默认分支代替实验分支。
本轮独立克隆 Kadb，只读源码；没有修改、构建或运行 Kadb。

SPAKE2 实物候选为 `0.1.0-dev.59105290820b`，来自 GitHub Actions
`40388080222` 的 artifact `12682137388`。当前13个生产源码文件与该候选对应提交一致。
另执行5组“真实候选 SPAKE2 JVM 字节码 + JCE 协议模型”实验，详见
[实验边界与结果](../evidence/kadb/BINDING_PROBE.md)。合成 exporter 不等于真实 TLS exporter 验证。

## 配对认证链逐项检查

下表的路径以固定 Kadb 提交为根；函数和关键行号可从该提交直接复现。

| 环节 | 位置 | 观察及裁决 |
| --- | --- | --- |
| 口令与通道绑定 | `kadb/src/commonMain/kotlin/com/flyfishxu/kadb/pair/PairingConnectionCtx.kt:73–88`，`setupTlsConnection` | 当前 TLS 启动后导出64 bytes，严格检查长度，使用 `pairingCodeBytes || exporter` 初始化 PAKE，随后清理 owned 临时数组。未发现固定/跨连接缓存 exporter 的实现路径。 |
| Android exporter | `.../androidMain/.../pair/PairingTlsConnection.jvmAndroid.kt:61–78` | Conscrypt 静态导出接口作用于当前 socket；标签精确为 `adb-label\0`、context=null、64 bytes，反射/导出异常失败，不回退固定字节。未在本轮 ART 实际调用。 |
| Native exporter | `.../nativeInterop/cpp/kadb_transport.c:629–698` | TLS最小和最大版本均设1.3，握手完成才设置 tls_ready；受锁检查closed/ready/长度后调用 `SSL_export_keying_material`，`sizeof(label)` 包含NUL，context为空/use_context=0。未在本轮运行该C桥。 |
| 身份/角色/模式 | `.../commonMain/.../pair/PairingAuthCtx.kt:20–23`；`PairingCrypto.kt:18–20` | Alice；`adb pair client\0` / `adb pair server\0`；显式 HARDENED，无自动RFC或旧模式回退。绑定的是当前配对口令持有者、角色和TLS会话，不自动提供未来连接的设备证书固定或预先约定GUID。 |
| Legacy key使用边界 | `PairingAuthCtx.initCipher:37–69`；`PairingConnectionCtx.start:49–55` | 64-byte输出只用于派生配对AES key，尚未返回配对成功。成功须经过后续GCM验证与PeerInfo语义检查；失败销毁，I/O worker拥有清理权。 |
| HKDF | `PairingCrypto.jvmAndroid.kt:17–21`；`kadb_crypto.c:216–219` | HKDF-SHA256、空salt、info精确为 `adb pairing_auth aes-128-gcm key`（不含NUL）、输出16 bytes。与固定AOSP源码一致。 |
| AES-GCM | `PairingCrypto.jvmAndroid.kt:23–40`；`kadb_crypto.c:221–232` | 16-byte key、12-byte nonce、16-byte tag、无AAD；验证失败返回null，临时output按普通路径覆写。应用状态在失败后终止。 |
| PeerInfo/反射 | `PairingConnectionCtx.kt:142–170,193–197` | tag验证后要求明文8192 bytes、type=1、非空且有NUL终止的GUID；客户端发出type=0的RSA公钥结构。原样反射可过内层GCM，但不能过此类型检查。GUID是opaque C字符串，不强加UUID格式。 |
| 封包/资源边界 | 同文件 `PairingPacketHeader.readFrom:238–251` | 版本固定1、type限定0/1、正payload且≤16384；后续SPAKE库另强制32-byte消息。未发现用无界peer长度分配或跳过消息阶段的成功路径。 |
| 销毁/取消 | 同文件 `start:42–68, close:173–179`；`PairingAuthCtx.crypt/destroy` | 关闭传输中断worker，活跃worker最后清理crypto；密钥失败后不重用nonce/上下文。这里只读复核，未重跑Kadb取消/原生生命周期测试。 |

Android `SSLContext("TLSv1.3")` 的socket协商结果、不同平台Conscrypt调用和Native导出函数的真实行为，仍需后续完整Kadb配对测试记录。现有源码或本轮合成64-byte输入不能代替实际握手证据。

### 不受预信任的TLS证书与MITM判断

Android `SslUtils` 使用接受未预信任证书的trust manager；Native配对路径使用 `SSL_VERIFY_NONE`。
这不是足以独立认证设备的TLS握手。当前配对链的认证依赖PAKE口令及其TLS exporter绑定：
终止两条独立TLS会话的攻击者不能默认两侧exporter相同；直接转发PAKE消息会因口令材料不同而无法完成后续认证。
本轮候选字节码实验确认“给定不同exporter字节”确实产生不同密钥且GCM拒绝。
它没有证明各平台真实exporter的所有实现条件，亦未搭建完整双TLS攻击代理。

没有确认本轮范围内新的真实MITM、无配对码认证绕过或RFC/Legacy协议混淆。
这个结论限定当前配对链及已检查能力，不能延伸为整个Kadb未来所有网络会话的设备身份审计。

### R3-K01：双向内层GCM使用相同key/nonce0

**Medium，确认的协议继承风险；没有复现当前N0/N1认证绕过。**

`PairingAuthCtx.kt:31–33,73–74,126–138` 使用单一secretKey与两个从0开始的计数器。
固定AOSP `1cf2f017d312f73b3dc53bda85ef2610e35a80e9` 的
`pairing_auth/aes_128_gcm.cpp` 和 `pairing_auth/include/adb/pairing/aes_128_gcm.h` 也具有这一设计。
[AOSP来源及交叉复核](../evidence/legacy/aosp-cross-review-sources.json)保留真实下载位置和摘要。

本轮真实候选+JCE实验直接确认双向8192-byte密文主体满足 `C1 xor C2 = P1 xor P2`。
若同一诚实配对TLS会话中的两条内层密文被获得，内层GCM不具备独立的nonce唯一性保证。
不能宣传这个配对AEAD层脱离TLS仍安全。

当前攻击条件需要分清：被动网络观察者看不到TLS内密文；终止两条TLS的MITM面对不同exporter与不同PAKE密钥；
已成功PAKE的恶意对端本来就知道该key。原样反射解出type0被拒绝；篡改type而不更新tag被GCM拒绝。
本轮没有从中得到可离线验证全部口令或当前网络攻击者可用的认证绕过。

处置：Kadb保持只读，不单边改nonce而破坏ADB互操作。把风险写入集成条件：必须保留TLS绑定、完整PeerInfo检查、单次配对用途；
不得复用此AEAD封装为通用双向应用通道。生产前需要针对具体部署确认该协议风险是否可接受，协议级修正需双方协商。

## 供应链：从外部来源到实际字节的核对

1. GitHub官方API独立确认run head为 `5910529`、workflow/job成功以及artifact ID/digest；通过已认证GitHub连接器下载ZIP。
   下载实物SHA-256与GitHub artifact metadata匹配，未以包内自报POM摘要作为来源认证。
2. 对实际下载的Maven目录重新执行 `tools/check_maven_repository.py`：33个单元通过，检查POM/GMM、变体、依赖、归档内容和许可/完整源码材料。
   RFC依赖Primitives，Legacy依赖Primitives，未引入RFC↔Legacy生产边；外部发布运行时坐标为固定Kotlin stdlib 2.2.21及其JS/Wasm变体。
3. 三份完整源码ZIP字节相同；每份482个文件逐项对照独立拉取的Git `5910529:path` 对象，0差异、0未跟踪附加文件。
4. 本轮从相同生产源码重新编译的26个JVM production class，与真实CI候选三个JVM JAR中的26个class逐字节一致。
   见 [JVM重建比对](../evidence/ci/jvm-class-rebuild-comparison.json)。这是JVM class级复建证据，不能扩大为全部JAR资源或AAR/KLIB/JS/Wasm可复现构建。
5. wrapper分发ZIP配置固定8.14.3且带SHA-256；实际wrapper JAR与Gradle官方公布摘要一致，见
   [wrapper来源](../evidence/ci/gradle-wrapper-origin.json)。workflow外部actions使用完整commit固定值；候选生成步骤从实际checkout获取完整revision。
6. 下载并重新解析原消费者XML/解析记录：基线工具链9个目标、Kadb工具链5个目标，每目标5方法通过。
   独立消费者使用隔离的Maven目录与exclusiveContent，并检查真实选择的组件/版本/变体，不能以源码工程替换伪装Maven消费。

证据：[候选下载](../evidence/ci/candidate-download.json)、[结构复验](../evidence/ci/maven-structure-verification.json)、
[源码对照](../evidence/ci/source-archive-vs-git.json)、[原CI重数](../evidence/ci/recount.json)、
[原始XML及解析记录归档](../evidence/ci/retained-results.tar.gz)。

### 尚未获得的来源保证

没有证明恶意/失陷GitHub runner或编译器仍会生成可信二进制；没有全平台独立可复现构建；没有正式签名发行或artifact attestation。
仓库没有为所有Gradle构建插件及传递依赖建立完整、独立可信的checksum-verification/lock基线。
这些是供应链保证缺口，不能由自报hash消除，也不能无具体反例改写成已确认的恶意产物。

CI候选artifact元数据给出的到期时间为2026-11-09；它不是长期正式Maven服务。后续迁移应保存经验证候选并锁定来源/摘要，
或从不可变提交重建并再次验收。最终报告/新测试提交不是已发布的新Maven版本。

## Kadb迁移裁决

**已具备进入受控、固定版本的真实Maven迁移验证的条件；尚未完成迁移。**
限定HARDENED、相同身份字节、可信CSPRNG、当前TLS exporter绑定、完整GCM/PeerInfo最终检查与一次性会话。
实际修改Kadb、真实Android/Native配对、双TLS反例、来源包更新以及设备/模拟器回归留到后续明确授权的Kadb任务。
正式发行与生产安全门禁不因此通过。
