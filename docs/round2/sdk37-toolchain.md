# SDK 37 消费者引导失败诊断

核对日期：**2026-10-27（Asia/Shanghai）**。

## 已复现的原因

[run 40384173898 / job 121789389672](https://github.com/Flyfish233/spake2/actions/runs/40384173898/job/121789389672)
的 SDK 安装步骤请求了 `platforms;android-37`，日志记录
`Failed to find package 'platforms;android-37'`。失败发生在消费者 Gradle
任务之前，不能据此认定 Maven 元数据或 Kotlin ABI 不兼容。

重新读取 Google 官方
[`repository2-3.xml`](https://dl.google.com/android/repository/repository2-3.xml)
后确认：

| 项目 | 官方索引中的实际值 |
| --- | --- |
| 与 Kadb `compileSdk = 37` 配套的平台包 | `platforms;android-37.0` |
| 平台修订 | revision 2，API 37.0，base extension 22 |
| Build Tools | `build-tools;37.0.0` |
| Command-line Tools | revision 23.0，Linux archive build `16111833` |
| 发布渠道 | 上述条目均为 `channel-0`（stable） |
| `platforms;android-37` | 不存在这个精确包名 |

旧 [`repository2-1.xml`](https://dl.google.com/android/repository/repository2-1.xml)
不含该新平台条目。当前索引也有 37.1，但本次按实际 Kadb 基线选择 37.0，
不静默提高 minor API。提取后的索引字段保存在
[package-catalog.json](evidence/sdk37/package-catalog.json)，没有把整个 Google
索引复制进仓库。

## 既有 Kadb 引导方式与修正

只读远端检查确认 `experiment/kmp-native-core` 仍指向
`49a88b14e9b731e4c086c920eb74664450c79d9f`。该版本的
[workflow](https://github.com/Flyfish233/Kadb/blob/49a88b14e9b731e4c086c920eb74664450c79d9f/.github/workflows/experiment-validation.yml)
已经使用以下方式；SPAKE2 的匹配工具链消费者应采用同样的版本与精确包名：

```yaml
- uses: android-actions/setup-android@9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407
  with:
    cmdline-tools-version: '16111833'
    packages: platform-tools
    accept-android-sdk-licenses: false
    log-accepted-android-sdk-licenses: false
- run: |
    sdkmanager --version
    sdkmanager 'platforms;android-37.0' 'build-tools;37.0.0'
```

**保持 `compileSdk = 37`、minSdk 23、JDK25、Gradle9.6.0、Kotlin2.4.20、
AGP9.4.0；这是安装引导修正，不是降低验收环境或运行时要求。** 不需要
为这三个稳定包添加 preview/canary 渠道。

## Action 输入与执行路径的独立检查

GitHub 的
[固定提交 API](https://api.github.com/repos/android-actions/setup-android/commits/9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407)
返回上述完整 SHA 和 `android-actions/setup-android` 仓库中的提交地址。
该提交的
[`action.yml`](https://github.com/android-actions/setup-android/blob/9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407/action.yml)
声明 `cmdline-tools-version` 输入；默认值是旧的 `12266719`，因此本次显式
指定 `16111833`。

进一步读取同一提交的
[`src/main.ts`](https://github.com/android-actions/setup-android/blob/9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407/src/main.ts)
确认该输入用于拼接 Google 官方下载地址，允许这里的数字 build ID。对于
没有旧映射条目的 `16111833`，安装目录是 `cmdline-tools/16111833`，随后
把该目录的 `bin` 加入 PATH。

因此后续步骤应调用 **PATH 中的 `sdkmanager`**，或显式使用该固定目录。
不能继续硬编码 `$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager`，因为
此 action 不保证替换 runner 预装的 `latest` 目录；硬编码可能绕过刚安装的
新版工具。该 action 是 `android-actions/setup-android` 上游项目，不把它
误称为 Google 官方维护的 action。

本诊断核对了失败日志、官方包目录和固定 action 源码，没有在本地安装完整
SDK或运行 Gradle，也没有证明支持这些新条目的最低旧 CLI 版本。修正后的
实际 SDK 安装与消费者构建结果应由后续 CI 单独记录。
