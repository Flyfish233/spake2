# Kadb 从固定源码组合迁移到独立 Maven 依赖

核对日期：**2026-10-27（Asia/Shanghai）**。本轮只读检查 Kadb，**没有修改、
构建或运行 Kadb**。下述步骤供下一项明确授权的 Kadb 迁移执行；本轮的独立
消费者证据不能冒充完整 Kadb/ADB 端到端迁移已经通过。

## 已核对的下游基线

Kadb 分支 `experiment/kmp-native-core` 的
[`49a88b14e9b731e4c086c920eb74664450c79d9f`](https://github.com/Flyfish233/Kadb/tree/49a88b14e9b731e4c086c920eb74664450c79d9f)
目前通过 `third-party/spake2` 项目编译六个 SPAKE2 Kotlin 文件，并固定到
`b837aba27bebc1050965fa124c2efa54b4e4544a`。本轮逐个核对该目录的完整
文件清单、SHA-256 记录及对应上游 Git blob，七个文件（含 LICENSE）均一致。
详见 [只读基线记录](kadb-baseline.json)。

| 项目 | Kadb 实际配置 |
| --- | --- |
| Gradle / Kotlin | 9.6.0 / 2.4.20 |
| Android Gradle 插件 | 9.4.0，`com.android.kotlin.multiplatform.library` |
| Java toolchain | 25 |
| Android SDK | compileSdk 37，minSdk 23 |
| Native 目标 | `linuxX64`、`iosArm64`、`iosSimulatorArm64`、`macosArm64`、`macosX64` |
| 生产 JVM 目标 | 无；保留 Android host tests 不等于添加 desktop JVM 生产目标 |
| 当前 SPAKE 依赖 | `implementation(project(":spake2-legacy"))` |
| 独立 Java 测试 oracle | `libs.spake2` → `com.github.Flyfish233:spake2-java:1.1.1` |

以上事实分别来自该提交的 `gradle/wrapper/gradle-wrapper.properties`、
`gradle/libs.versions.toml`、`kadb/build.gradle.kts`、`settings.gradle.kts` 与
`third-party/spake2/build.gradle.kts`。源码组合在 Kadb 的编译器版本上重编译，
Maven 迁移则会消费上游生成的 KLIB/元数据，因此必须保留这个实际工具链
配置的独立消费者验证，不能只用上游自己的编译器运行一次。

## 迁移入口与版本要求

Kadb 的生产依赖只需要以下 KMP 根坐标：

```text
io.github.flyfish233:spake2-legacy:<已验证的固定候选版本>
```

它以 `api` 依赖同一版本的 `io.github.flyfish233:spake2-primitives`，公共的
`EntropySource`、`Role`、`Spake2Exception` 随传递依赖可见。不要在
`commonMain` 选择 `-jvm`、`-android` 或某个 Native 后缀，也无需加入 RFC
根模块 `io.github.flyfish233:spake2`。平台变体由 Gradle Module Metadata 选择。

**迁移应固定最终验证记录中的候选版本、完整源码 SHA 与产物来源。**
本轮没有向远端 Maven 发布正式版本；下面的版本占位符必须替换成实际
生成并通过验证的候选值，不能直接照抄为一个已发布版本。禁止用 `latest`、
`+`、移动分支或可覆盖的 `SNAPSHOT` 代替这次验收的身份。

本地验收可以消费 CI/本机导出的完整 Maven 目录。正式远端地址的选择与
发布需要另行批准。一个只含根 `.module`、缺少相应 AAR/KLIB 或传递模块的
目录不能作为迁移来源。[发布结构依据](https://kotlinlang.org/docs/multiplatform/multiplatform-publish-lib-setup.html)

## 建议的最小 Gradle 变更

以下是未来 Kadb 补丁的具体范围；本轮没有应用这些变更。

### 1. 增加独立的版本目录别名

在 `gradle/libs.versions.toml` 的现有节中增加条目，保留已有 `spake2` Java
oracle 版本和库别名：

```toml
[versions]
spake2KmpCandidate = "<已验证的固定候选版本>"

[libraries]
spake2LegacyKmp = { module = "io.github.flyfish233:spake2-legacy", version.ref = "spake2KmpCandidate" }
```

在 `kadb/build.gradle.kts` 将唯一的生产项目依赖替换为：

```kotlin
commonMain.dependencies {
    implementation(libs.spake2LegacyKmp)
}
```

`androidHostTest` 的 `implementation(libs.spake2)` 继续指向旧 Java oracle。
它是独立回归依据，不能用新的待测 Maven 模块替换，也不能因为生产模块
不再需要 JitPack 就顺带删除其仓库配置。

### 2. 为候选模块设置唯一来源

在 `settings.gradle.kts` 的 `dependencyResolutionManagement.repositories`
中，为这一组候选模块设置明确来源。例如本地完整 Maven 目录：

```kotlin
exclusiveContent {
    forRepository {
        maven {
            name = "spake2ValidatedCandidate"
            url = uri(providers.gradleProperty("spake2Repository").get())
            metadataSources {
                gradleMetadata()
                mavenPom()
            }
        }
    }
    filter { includeGroup("io.github.flyfish233") }
}
```

执行构建时传入 `-Pspake2Repository=/绝对路径/完整候选Maven目录`。这条规则
使此组坐标不会悄悄从全局 `mavenLocal()`、其他仓库或不同测试遗留版本解析。
应保留原项目其他依赖需要的仓库，并在验收日志中检查实际组件来源、版本和
选中变体。不要使用 `includeBuild`、dependency substitution 或把上游源码
加入 sourceSet 来“修复”发布产物缺失；那会失去独立 Maven 消费的证据。

### 3. 去除源码组合的生产入口

从 `settings.gradle.kts` 删除：

```kotlin
include(":spake2-legacy")
project(":spake2-legacy").projectDir = file("third-party/spake2")
```

待依赖解析、运行回归和下述来源收集迁移完成后，再从该未来 Kadb 补丁移除
旧 `third-party/spake2` 生产组合目录。不能同时保留两份相同包名的 Kotlin
实现。此前用于编译上游内部实现的 `InternalSpake2Api` opt-in 不需要为了
消费下面列出的公共 API 而移入 Kadb 的适配层。

## 公共 API 与 ADB 行为边界

当前 `kadb/src/commonMain/kotlin/com/flyfishxu/kadb/pair/PairingCrypto.kt`
直接使用以下五项公共 API；独立 Maven 依赖保持这些调用方式：

```kotlin
import io.github.flyfish233.spake2.EntropySource
import io.github.flyfish233.spake2.Role
import io.github.flyfish233.spake2.Spake2Exception
import io.github.flyfish233.spake2.legacy.LegacyPasswordScalarMode
import io.github.flyfish233.spake2.legacy.LegacySpake25519
```

```kotlin
val spake = LegacySpake25519(
    Role.ALICE,
    myName,
    theirName,
    EntropySource(::securePairingRandomBytes),
    LegacyPasswordScalarMode.HARDENED,
)
val message = spake.generateMessage(password)
val unconfirmedKey = spake.deriveUnconfirmedKey(peerMessage)
// 按现有 ADB 配对流程继续处理，结束或失败时调用 destroy()。
```

迁移仅替换依赖入口，不需要为 Kadb 添加上游默认身份或新协议模式。
保留的协议约束来自已检查版本的 `PairingCrypto.kt`、`PairingAuthCtx.kt`、
`PairingSpakeVectorTest.kt`：

- 明确 Alice 角色与 `LegacyPasswordScalarMode.HARDENED`，不启用隐式 RFC
  回退，也不改用 `PRE_HARDENING_UNSAFE`。
- 两个身份字符串分别为 `adb pair client\u0000`、`adb pair server\u0000`，
  尾部 NUL 是字节内容的一部分；不得 trim、重新编码或换序。
- 配对口令组合、TLS exporter 以及双方消息顺序沿用现有 ADB 流程。
  Legacy 公共消息是 32 字节，`deriveUnconfirmedKey` 输出为 64 字节。
- 此 64 字节结果没有 RFC 的双向密钥确认。现有应用层以其派生的 AES-GCM
  密钥验证对端 PeerInfo，并检查协议约束，不能提前把该结果当成已认证会话。
- Kadb 继续使用空 salt、精确的 `adb pairing_auth aes-128-gcm key` info 做
  HKDF-SHA256，派生 16 字节 AES 密钥。其平台 HKDF/AES/TLS 提供者不属于
  上游 SPAKE2 核心，也不因替换此依赖而改动。
- 保留调用方安全熵来源、异常到配对失败的映射、一次性状态与销毁路径。
  无效熵或异常失败不能退回弱随机源或重用已终止对象。

依据上游公共 API 与当前 Kadb 调用点的静态核对，**不需要改写 `PairingCrypto`
接口来替换这项依赖**。实际端到端兼容仍需下一节的下游回归确认。

## 不能遗漏的 CI 与对应源码收集变更

现有 Kadb 已经建立对应源码收集机制，迁移时必须更新其输入，不能删除旧
校验后宣称来源验证仍然通过。

| 文件 / 位置 | 当前与源码组合绑定的假设 | Maven 迁移需要的替代 |
| --- | --- | --- |
| `.github/workflows/experiment-validation.yml` | 对 `third-party/spake2/SHA256SUMS` 执行 `sha256sum --check` | 校验固定 Maven 版本、完整上游 revision、解析来源、两个模块及各目标产物；保留证据 |
| 同一 workflow 的生产目标检查 | 调用 `:spake2-legacy:verifyProductionTargetScope` | 移除已不存在的项目任务，换成外部依赖解析/变体门禁；保留 Kadb 与 mDNS 自身目标检查 |
| `scripts/source_bundle.py` 的 `git_identity()` | 从 `third-party/spake2/UPSTREAM_COMMIT` 读取并比对来源 | 从显式候选锁定记录取得完整版本/revision，并与实际消费产物及源码分发包相互核对 |
| 同脚本的 `required_kadb` | 要求 Kadb 源码归档包含 `third-party/spake2/upstream/LICENSE` | 要求收集到相同候选的上游源码与构建包、完整许可材料；不可只去掉文件检查 |
| `validation/source-bundle/dependencies.lock.json` | `spake2_revision` 固定 b837；运行时图包含 `project ':spake2-legacy'` | 更新固定 revision，记录 Legacy 与 Primitives 的 Maven 组件、传递图、实际 source-distribution 文件及校验值 |
| `docs/experiment/LICENSES.md`、`SOURCE_BUNDLE.md`、`ANDROID_SOURCE_COVERAGE.md` | 说明当前固定源码目录的归属和收集方式 | 说明二进制模块来源、完整源码覆盖及最终二进制对应关系；保留 AGPL/Apache 组合边界 |

上游每个 KMP 根发布的 `source-distribution` 分类 ZIP 用于携带本库的完整
源码、构建脚本、wrapper、测试/参考材料与许可。迁移应实际取得并收集
匹配版本的包，验证内部文件和来源身份。只把 `-sources.jar` URL 或 Git SHA
放入清单不等于已经交付完整的对应源码。

新的 `spake2-primitives` 发布单元比原来的六文件源码组合更完整，来源收集
不能继续硬编码“运行时只有六个上游文件”的假设。Kadb 的最终源码包还需
保留自身修改、实际 Native/Android 依赖与构建/安装所需材料；上游 ZIP
不会自动覆盖其 BoringSSL、Kotlin runtime、Okio、coroutines 等部分。
详见 [许可证与发行说明](licensing-and-kadb.md)。

## 下一项 Kadb 迁移应实际执行的验证

以下命令依据 Kadb `49a88b14` 中的任务和 CI 整理，**不是本轮的执行结果**。
它们应在完成未来迁移补丁并传入候选仓库属性后执行。Android SDK、JDK25、
Native BoringSSL 构建环境及已启动的 iOS simulator 按 Kadb 原 CI 准备。

### Android 编译与 host 回归

```sh
./gradlew :kadb:verifyProductionTargetScope :kadb-mdns:verifyProductionTargetScope \
  :kadb:testAndroidHostTest :kadb-mdns:testAndroidHostTest \
  :kadb:bundleAndroidMainAar :kadb-mdns:bundleAndroidMainAar \
  :validation-android-fixture:assembleDebug :validation-android-runtime:assembleDebug \
  -Pspake2Repository="$SPAKE2_REPOSITORY" --no-build-cache --console=plain --stacktrace
```

另行保留原 Android ART/runtime 验证步骤；host 测试和 AAR/APK 构建不能代替
设备执行。不得改变 `PairingSpakeVectorTest` 中固定的独立 BoringSSL 预期值、
高位保留案例及历史恶意输入行为，也应保留旧 Java oracle 对照。

### Linux 与 Darwin 链接/运行

```sh
./gradlew :kadb:linuxX64Test :kadb-mdns:linuxX64Test \
  :validation-native-adbd:linkDebugExecutableLinuxX64 \
  :validation-native-adbd:linkPairingDebugExecutableLinuxX64 \
  :validation-pairing-peer:writePeerClasspath \
  -Pspake2Repository="$SPAKE2_REPOSITORY" --no-build-cache --console=plain --stacktrace
```

在相应 macOS runner 上运行 `:kadb:macosArm64Test` / `:kadb:macosX64Test`，
同时保留 mDNS 测试、相应 Kadb framework 和 `validation-native-adbd` 的普通/
配对可执行文件链接。不能用 macOS 编译通过替代 iOS simulator 运行：

```sh
./gradlew :kadb:iosSimulatorArm64Test :kadb-mdns:iosSimulatorArm64Test \
  :kadb:linkDebugFrameworkIosArm64 :kadb:linkDebugFrameworkIosSimulatorArm64 \
  :validation-native-adbd:linkPairingDebugExecutableIosSimulatorArm64 \
  -PiosSimulatorDevice="$IOS_SIMULATOR_DEVICE" \
  -Pspake2Repository="$SPAKE2_REPOSITORY" --no-build-cache --console=plain --stacktrace
```

保留 Kadb 原有 Native ADB、TLS exporter 和配对 peer 执行证据。当前上游
独立消费者覆盖库本身的解析、链接与固定向量；它不含 Kadb 的整个 TLS、
AES-GCM、网络与设备组合。

### 实际 Android 运行时图与来源包

```sh
./gradlew -I validation/android-runtime/runtime-classpath.init.gradle \
  :validation-android-runtime:recordRuntimeClasspath --no-configuration-cache \
  -Pspake2Repository="$SPAKE2_REPOSITORY"
python3 scripts/source_bundle.py verify-android-runtime \
  --lock validation/source-bundle/dependencies.lock.json \
  --runtime-report validation/android-runtime/build/reports/debug-runtime-classpath.json \
  --output evidence/android-runtime-source-verification.json
```

更新后的来源收集器应有旧格式/缺失候选源码/错 revision/错传递版本的拒绝
测试，并把生成的源码包与实际 APK、framework、可执行文件关联。完整
来源包收集、测试计数门禁和异常终止回归应继续存在。

## 验收判断的范围

只有实际 Kadb 工具链的独立 Maven 消费者成功解析所需平台变体、链接运行
固定预期值回归，才支持“上游产物可替代旧源码组合的依赖入口”这一判断。
仅验证生产者自己的 JVM 测试不够；缺少 macOS 变体时也不能覆盖当前 Kadb
目标清单。本轮相应执行结果应以最终提交和 CI 证据为准。

下一项 Kadb 迁移还需按本说明完成仓库内修改、对应源码门禁与原有完整
回归。在此之前，准确表述应是**已有供迁移评审的独立依赖证据，Kadb 本身
仍保持原固定源码组合**。技术上可作实验依赖、可公开正式发行、经过独立
生产安全审计，是三项分别判断的结论；Maven 发布准备不自动获得后两项。
