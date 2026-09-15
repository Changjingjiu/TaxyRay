# 构建与测试

## 本地开发

使用完整 JDK 17、Android SDK 35、Build Tools 35.0.0。首次构建需要下载依赖。可通过 Android Studio 打开仓库，或配置 `JAVA_HOME` 和 `ANDROID_HOME` 后运行 Gradle。

本机 SDK 也可写入未跟踪的 `local.properties`：

```properties
sdk.dir=/path/to/Android/sdk
```

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

生成的调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。只在开发设备安装调试包，正式用户使用 GitHub Releases 的签名 APK。

```sh
adb -s YOUR_TEST_DEVICE install -r app/build/outputs/apk/debug/app-debug.apk
```

## 验证

| 层级 | 内容 | 命令 |
| --- | --- | --- |
| 核心算法 | 精确金额 舍入 金额守恒 边界值 | `./gradlew :core:test` |
| 应用逻辑 | 备份解析 识别协议 端点策略 更新校验 | `./gradlew :app:testDebugUnitTest` |
| 静态检查与构建 | Debug / Release lint 与 APK 构建 | `./gradlew :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease` |
| Android 集成 | Room Keystore 图片 记账交互 贡献卡 系统更新边界 | `./gradlew :app:connectedDebugAndroidTest` |

仪器测试只在专用测试设备或模拟器上执行。Gradle 会安装和卸载测试应用，不要使用保存真实账本的设备，也不要在连接多台设备时无目标地运行测试。

[GitHub Actions](https://github.com/Changjingjiu/TaxyRay/actions/workflows/android.yml) 运行 JVM 测试、lint、构建以及 API 35 模拟器测试。每次发行的具体结果与实际设备验证范围记录在 [Release 说明](https://github.com/Changjingjiu/TaxyRay/releases)。

界面截图不能替代交互验证。真实小票识别、不同厂商的系统安装与分享、物理震动手感仍须在对应设备上检查。

## 工程结构

```text
core/                   金额算法 模型 单元测试
app/src/main/
  java/io/github/taxray/
    data/local/         Room 实体 DAO 数据库
    data/backup/        JSON / CSV 校验与重算
    data/security/      Keystore 加密配置
    data/remote/        图片处理 识别协议 响应校验
    data/update/        GitHub 更新 校验下载 系统安装
    ui/                 Compose 页面 贡献卡 主题
app/src/test/           应用 JVM 测试
app/src/androidTest/    Android 集成测试
app/schemas/            Room schema
scripts/                发行打包
docs/                   使用边界 算法 隐私 开发文档
```

## 仓库内容

提交源码、测试、Gradle Wrapper、Room schema、CI、必要文档和 README 实际使用的公开展示图片。第三方许可证随相应代码与资源保留。

原始需求稿、内部设计记录、临时验证报告与本地 Agent 规则不纳入版本控制。构建产物、演示截图的工作目录、个人账本、API 凭证和签名密钥同样不提交。APK 和更新清单作为 Release 附件发布。

README 图片应使用演示数据，在公开前检查画面中的账号、密钥和账单内容。图片来源及版本见 [展示素材说明](assets/README.md)。

发行签名保存在仓库外，命令和版本要求见 [更新与签名](UPDATES.md)。贡献约定见 [CONTRIBUTING.md](../CONTRIBUTING.md)。
