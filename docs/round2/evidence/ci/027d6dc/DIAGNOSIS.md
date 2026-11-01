# 027d6dc：真实失败、局部运行结果与门禁回归

本目录对应源码 `027d6dc00f2c92502ca98b878d1940a358590be5`，保留失败记录，
不能将局部测试成功解释为整个候选版本验收通过。

- Maven 发布与消费者运行：<https://github.com/Flyfish233/spake2/actions/runs/40384173898>，最终失败。
- ART 运行：<https://github.com/Flyfish233/spake2/actions/runs/40384173908>，最终失败；API 35 作业成功，API 23 在运行测试前失败。
- `github-jobs.json` 保留作业及步骤状态；`verification.json` 记录五份完整解码日志及四份原始 XML 的 SHA-256。
- 原始 `job.log` 保留 GitHub 返回的字节，包括 BOM、ANSI 转义和行尾空格，未为格式检查改写。

## 失败事实

| 作业 | 已观察到的失败 | 解释边界 |
| --- | --- | --- |
| ART API 23，`121787925171` | KVM 步骤在写规则、reload、trigger 后退出 1，无额外 stderr | 原命令没有等待 udev 事件完成，且立即检查读写权限；权限更新竞争是合理推断，日志不足以证明具体是哪一个 `test` 失败。本次没有执行设备测试。 |
| 基线 Linux，`121789389801` | `verifyPublishedDependencies` 对 `debugUnitTestCompileClasspath` 读取原始 artifacts 时，无法在自身项目的 `android-classes-jar` 与 `android-lint` secondary variants 间选择 | 原始日志显示请求的编译和测试任务已经结束，失败发生在额外的来源核验步骤。本目录没有重新核验该作业的全部 XML，故不据此声明其测试全部通过。 |
| 现代 macOS Arm64，`121789389622` | `verifyPublishedDependencies` 报 `Project dependency substitution is forbidden ... project ':rfc-client'` | 完整日志显示 KGP 新的 SwiftPM 构建元数据任务涉及两个夹具模块。原核验器遍历全部已解析配置；原错误未打印具体配置名，不能仅凭该错误认定 SPAKE2 被源码替换。 |
| 现代 macOS x64，`121789389840` | 同样在最后的 `verifyPublishedDependencies` 拒绝 `project ':rfc-client'` | 两个 `macosX64Test` 和 `linkDebugTestIosArm64` 任务均已结束；此处未下载 x64 XML，因此只报告任务记录。 |
| 现代 Linux，`121789389672` | SDK 安装命令请求 `platforms;android-37`，SDK manager 报找不到该包 | 在消费者构建前失败。修正 SDK 包名及工具版本需要新的实际 CI；本次不能记作 Android 编译或运行成功。 |

## KVM 最小修正依据

systemd 官方 `udevadm` 文档区分普通 `trigger` 与 `trigger --settle`：后者等待
自己触发的事件完成；单独的 `settle` 则等待当前事件队列处理完毕。
`settle --timeout=30` 在超时后返回非零。重载规则本身不会立即修改已有设备，
新规则在下一次事件处理时生效。

来源：<https://www.freedesktop.org/software/systemd/man/latest/udevadm.html>，
`udevadm trigger`、`udevadm settle` 和 `udevadm control --reload`。

因此，在原 `trigger --name-match=kvm` 后加有界 `sudo udevadm settle --timeout=30`，
再输出 `ls -l /dev/kvm`、`id` 并保留原读写权限断言，是针对已观察到竞争窗口的
最小修正。这里没有通过无条件放宽权限检查或忽略错误获得成功状态；修正后仍需 CI 验证。

## 现代 Arm64 的实际 XML 证据

已下载并校验 `kadb-toolchain-consumer-darwin-arm64` 产物：

- Artifact ID：`12680820690`，91,122 字节。
- SHA-256：`fdcd24757ed76514eea26834a15fc748053478a6819ab492f0a1858f8c31ad7b`，与 GitHub API digest 一致。
- Artifact 和 job metadata 均绑定上述完整源码 SHA。
- `junit/` 保留四份未经内容修改的 XML；环境文件记录 JDK 25、Gradle 9.6.0、macOS、Xcode 和可用模拟器。

| 实际任务 | RFC 方法 | Legacy 方法 | 失败 / 错误 / 跳过 |
| --- | ---: | ---: | --- |
| `macosArm64Test` | 3 | 2 | 0 / 0 / 0 |
| `iosSimulatorArm64Test` | 3 | 2 | 0 / 0 / 0 |

这 10 次方法执行包括独立 RFC P-256、Edwards25519 预期值，Legacy HARDENED
固定 Oracle 输出，以及确认失败和销毁后的终止行为。此处是独立消费者的五项夹具，
不等于核心完整回归或全部 512 组 Legacy 向量。

`linkDebugTestIosArm64` 只证明 iOS 真机目标完成链接，未执行物理设备测试。
整体消费者作业仍然失败，依赖来源核验须修正后重新运行。

## 由真实 XML 复现的结果解析缺陷

本工具链的 XML 使用 `macosArm64Test.consumer.PublishedRfcTest` 等类名，
在原类名前附加所属任务。原解析器只去除方法名的 `[target]` 后缀，因而把
完整的真实测试报告误判为缺少五个方法。

先将真实四份 XML 加入回归测试，原实现发生一次预期失败，见
`consumer-xml-gate-before.log`。修正仅去除**当前请求任务的精确类名前缀**；
未放宽方法集合、异常计数或跳过计数。

修正后的以下两条命令各通过 8 项测试，日志分别保留：

```sh
python3 -m unittest discover -s tools/tests -p test_consumer_result_gate.py -v
python3 -O -m unittest discover -s tools/tests -p test_consumer_result_gate.py -v
```

回归覆盖基线格式、真实新格式、错误或重复的任务前缀、前缀和非前缀混用造成的
重复方法、缺少方法、错误类名和非法方法后缀。实际下载 XML 重新通过完整门禁，
记录于 `kadb-consumer-darwin-arm64/replayed-test-summary.json`。

可复制的原始 XML 另存于 `tools/tests/fixtures/gradle-9.6-kotlin-2.4.20/`，
没有放进被忽略的 `build/` 路径。测试在临时目录恢复真实报告布局后调用同一个 CLI，
因此源码分发包内也能复现该回归。
