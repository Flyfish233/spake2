# 实际候选 JVM 配对绑定实验

结果：**5 组全部 PASS**。这是在现有第三轮范围内追加的有界验证，不构成新的
审计轮次，也没有修改、编译或运行 Kadb 仓库。

## 实际执行边界

使用已下载的候选 Maven 仓库中的以下 JVM JAR，以 Java 公共调用直接执行
候选的纯 Kotlin Legacy/Primitives 字节码；没有把 SPAKE2 换成 Java 重实现：

- `io.github.flyfish233:spake2-legacy-jvm:0.1.0-dev.59105290820b`
- `io.github.flyfish233:spake2-primitives-jvm:0.1.0-dev.59105290820b`

实际运行环境为 JDK `17.0.21+9`，HKDF 中的 HMAC-SHA256 和 AES-128-GCM
使用 SunJCE；Kotlin 标准库 `2.2.21` 从固定 Maven Central URL 下载到临时
目录。原始运行日志记录了 Legacy、Primitives、stdlib 的实际类加载来源；
结果 JSON 记录 JAR、Java 源码、执行脚本的 SHA-256、命令、退出码及环境。
这些摘要用于追踪本实验输入；候选下载来源与完整产物验证沿用主控的发布证据。

**没有真实 TLS 握手、没有调用 TLS exporter，也没有执行 Kadb 的 Android/
Native HKDF、AES 或 TLS 提供者。** 实验把合成的 64-byte exporter 字节拼在
合成配对码后，使用实际候选 API 执行双方 SPAKE25519，再以 JCE 实现下游
HKDF/GCM 模型。PeerInfo 检查是与当前 Kadb 源码相同的长度、type=1、非空
NUL 终止字符串规则；它不证明实际 Kadb 网络生命周期或平台提供者执行成功。

## 五组结果

| 组 | 已执行结果 | 可以支持的结论 |
| --- | --- | --- |
| 同配对码、同 exporter，精确 Alice/Bob 身份及尾部 NUL | 双方 64-byte unconfirmed key 相同，派生 AES key 相同；双向 GCM 解密成功，server PeerInfo 有效 | 候选和模型的正路径成立 |
| 同配对码、不同 exporter | unconfirmed key 和 AES key 不同；对方 GCM tag 被拒绝 | 在给定不同 exporter 字节时，通道绑定数据确实影响候选派生结果 |
| 同 exporter、错误配对码 | key 不同；GCM 认证失败 | 该具体错误口令不能通过模型最终认证 |
| 去掉 server 身份尾部 NUL；另一个不同 server 身份 | 两个负例均产生不同 key，GCM 认证失败 | 固定身份字节和尾部 NUL 参与 transcript 绑定 |
| 双向同 key、nonce0 | 8,192 个 ciphertext-body 字节的 XOR 等于对应 plaintext XOR；client 密文原样反射可解密但被 type0 拒绝；翻转 type 对应密文字节导致 tag 失败 | 确认内层 nonce 重用的代数后果，并验证当前 PeerInfo 规则阻止这一原样反射路径 |

最后一组并不证明网络攻击者能取得被 TLS 保护的两条内层密文。
不同 TLS 连接的 exporter 是否正确导出、是否不同，属于真实 TLS 集成验证；
本实验没有用“输入不同的字节”替代那个验证。

## 复现及证据

```sh
python3 docs/round3/evidence/kadb/run_binding_probe.py \
  --jdk /path/to/jdk-17 \
  --maven /path/to/downloaded-candidate/maven
```

可用 `--stdlib /path/to/kotlin-stdlib-2.2.21.jar` 指定现有标准库。
构建目录和下载的临时标准库在退出时清理，不会将外部代码加入生产 sourceSet。
没有调用 Gradle，也没有修改候选 JAR。

- [Java 源码](KadbBindingProbe.java)
- [执行脚本](run_binding_probe.py)
- [结构化结果及输入身份](binding-result.json)
- [javac 原始输出](binding-javac.log)
- [JVM 原始输出](binding-jvm.log)
