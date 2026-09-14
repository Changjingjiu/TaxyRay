# TaxyRay · TaxLens

一个离线优先的 Android 消费增值税估算与个人账本应用。开源项目标识 **TaxyRay**，Android 应用名称 **TaxLens**。

当前版本 **0.2.0**：结构化商品归类与票面时间读取，精简界面文案，加入 6% 快选和可展开的税率指南，接入 GitHub 在线更新。

[下载最新版 APK](https://github.com/Changjingjiu/TaxLens/releases/latest) · [源码仓库](https://github.com/Changjingjiu/TaxLens) · [更新与安装](docs/UPDATES.md)

基于 Kotlin、Jetpack Compose Material 3 和 Room。没有注册、服务器、广告 SDK 或遥测。手动记账、统计、备份与贡献卡生成可完全离线使用；可选的小票识别由用户自带 API Key，直接请求用户配置的 HTTPS 服务。

**这是个人消费端估算工具。消费小票通常不能证明销售方纳税身份、计税方法、实际适用优惠或实缴税额；输出不是发票、完税证明或报税依据。**

## 已实现

- 手动添加多项消费、13% / 9% / 6% / 0% / 自定义 0–100% 税率，保存并继续添加。
- 以 `BigDecimal(String)` 计算，以整数分与税率基点存储；逐项舍入、整单汇总、金额守恒。
- Room 本地账本，历史日期、新增/编辑/删除、搜索、近 30 天消费趋势、累计税额与有效占比。
- 系统拍照与相册选择，EXIF 方向处理、等比压缩至长边 ≤1920px、JPEG ≤1,000,000 字节。
- OpenAI-compatible Chat Completions + 强制 Tool Calling，严格响应校验、取消与超时、清晰异常恢复。
- AI 暂存复核：改名、改实付、改税率、逐项/批量剔除；明细与票面实付不符时阻止入账。只有明确确认后才写入数据库。
- Keystore AES-256-GCM 加密整个 API 配置；不跟随重定向、不记录请求体、密钥、模型响应或图片。
- 纸本与松石绿两套贡献卡，通过真实 Compose 图层导出 PNG，保存图片或唤起系统分享；生成时一次触觉与 Konfetti 彩纸反馈，不阻挡操作、不写入导出图片。
- 在备份限额内进行 JSON / CSV 全账本导出与校验导入；派生金额重算、相同记录幂等跳过、冲突整批回滚。CSV 做公式注入防护。
- 二次确认清空本机账本、API 密钥及应用临时图片；显式关闭 Android 云备份与设备数据转移。
- 设置中检查 GitHub 稳定版本，下载并校验大小、SHA-256、包名、版本和签名后，通过 Android 系统确认安装。
- 系统深色模式、Material 导航、安全区、键盘避让、字体缩放、可读语义与系统动画缩放。

## 构建与运行

需要 **完整 JDK 17**（不是仅含 `java` 的 JRE）、Android SDK 35、Build Tools 35.0.0。首次构建需下载开源依赖，此后本地缓存充足时可使用 Gradle 离线模式。

1. 用 Android Studio 打开仓库根目录；或配置 `JAVA_HOME` 指向 JDK 17。
2. 创建不纳入版本控制的 `local.properties`，填写 `sdk.dir=/你的/Android/sdk`；CI 使用 `ANDROID_HOME`。
3. 执行：

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。调试版本可直接安装：

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

在专用 Android 8.0+ 测试设备或模拟器上运行仪器测试。Gradle 的测试运行器会安装并自动卸载测试应用；**不要在保存真实账本的设备上执行下面的测试命令**：

```sh
./gradlew :app:connectedDebugAndroidTest
```

运行 `./gradlew :app:assembleRelease :app:lintRelease` 构建经过 R8 缩减的**未签名发行包**。发行包由仓库外的专用密钥与官方签名轮换链签名，打包命令见 [更新与签名](docs/UPDATES.md)。仓库不含私钥、签名密码或 API 凭证。调试 APK 仅用于开发。

## 算法约定

单项含税金额为 `G`（整数分），税率 `R` 为基点，13% 对应 1300：

```text
单项税额（分） = HALF_UP(G × R ÷ (10000 + R))
不含税金额（分） = G − 单项税额（分）
整单税额 = Σ 每一项已经舍入的税额
有效税额占比 = Σ税额 ÷ Σ实付 × 100%
```

例如 `¥35.00 / 13%` 得到税额 `¥4.03`、税前 `¥30.97`。`¥0.01 / 100%` 的精确税额是半分，舍入后税额一分、税前零分，合计仍为一分；分别舍入税前和税额会导致错误的两分合计。

每项金额大于零、最多两位小数，上限 `¥99,999,999.99`；税率百分数最多两位小数；每单最多 1000 项。非法输入、负数、退款、科学计数法和超精度会明确拒绝，不静默截断。整单优惠须反映在逐项折后实付中，不能用平均税率或让 AI 自行猜测分配。

详见 [算法与边界](docs/ALGORITHM.md)、[2026 税率依据及需求修订](docs/TAX_POLICY.md)。

## 使用 AI 识别

在“设置 → 小票识别AI”中填写 HTTPS 服务地址、模型名和 API Key。打开“自动追加 /chat/completions”时可填写根地址（已有该路径不会重复追加）；关闭时按填写的完整地址发送。界面始终预览最终请求地址。模型须支持图片输入与 Function Calling。

推荐 DeepSeek V4.1 Flash：地址 `https://api.deepseek.com`，模型 `deepseek-flash`，开启路径追加。官方 DeepSeek 端点的请求显式关闭思考模式，避免轻量连接测试的输出预算被思考占满，也满足强制工具调用要求。其他服务不发送 DeepSeek 专用参数。[DeepSeek 首次调用](https://api-docs.deepseek.com/zh-cn/)、[创建对话](https://api-docs.deepseek.com/zh-cn/api/create-chat-completion/)

“测试连接”仅验证轻量文本请求能成功返回。图片识别前还会显示将发送到的端点、模型及数据范围供确认。服务方如何存储或处理图片取决于用户选择的服务方。应用没有代理服务器，也不提供免费的内置识别额度。

模型提取结构化类别、短证据与识别问题，由客户端映射 13% / 9% / 6% 估算档位。清晰商品只展示简短类别；品名不完整、类别含糊等实际问题才提示具体操作。0% 仍需明确免税文字证据，字母 F 或“生鲜”不足以证明免税。票面日期完整时使用票面时间，否则明确提示当前时间可修改。这些规则不能证明模型看图准确，详见 [AI 识别规则](docs/AI_RECOGNITION.md)。

## 数据、隐私与备份

账本保存在 Android 应用私有 Room 数据库中；它不使用 SQLCipher，**不能宣称账本经过独立数据库加密**。API 配置以系统 Keystore 密钥加密；设置页禁止系统截图及最近任务快照。应用不需要读取整个相册、存储、相机或定位的广泛权限；拍照委托系统相机应用。

JSON 与 CSV 使用版本1格式，均包含恢复账本所需的完整原始与派生字段，不含密钥或照片。导出和导入使用相同限制：单个文件最多 5 MB（5,000,000 字节）、10,000 笔账单，每单最多 1000 项；只接受 UTF-8 与本应用定义的表头、结构。导出前会检查账单和文件大小，超出限制会明确报错，避免生成本应用无法恢复的备份。当前版本不提供分卷备份；数据超过上述限制时不能导出完整账本。导出与导入在系统文件选择器中进行，可自行选择本地位置。不要将真实个人备份提交到开源仓库。

Android 10 及以上可直接保存贡献卡到 `Pictures/TaxRay`；Android 8–9 使用系统文件保存对话框。清空本机数据不删除用户已导出的备份、已保存到相册的贡献卡或接收方已持有的副本。闪存、文件系统及系统缓存决定物理擦除边界，不能承诺取证级覆写。完整说明见 [隐私与安全](docs/PRIVACY.md)。

## 工程结构

```text
core/                 纯 Kotlin 金额算法、模型及单元测试
app/src/main/
  java/io/github/taxray/
    data/local/       Room 实体、DAO、数据库
    data/backup/      JSON / CSV 编解码及重算校验
    data/security/    Keystore AES-GCM 配置存储
    data/remote/      图像压缩、结构化归类、严格响应解析
    data/update/      GitHub 发行检查、校验下载、系统安装
    ui/               Material 3 界面、贡献卡与主题
    TaxLensViewModel  用户确认后的应用工作流
app/schemas/          Room schema，可审计数据库结构
docs/                 算法、税率、隐私、需求对应与验证记录
```

依赖版本在 Gradle 中明确固定；没有旧实现、迁移桥接或兼容分支。平台最低版本26的系统图片保存差异按系统 API 能力处理。`security-crypto` 已被 Android 官方弃用，因此使用系统 Keystore 作为长期密钥方案，而不引入文档中的过时 alpha 依赖。

## 开源与贡献

项目源代码使用 [MIT License](LICENSE)，依赖分别遵守其许可证，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。贡献流程见 [CONTRIBUTING.md](CONTRIBUTING.md)，漏洞反馈见 [SECURITY.md](SECURITY.md)。发行渠道为 GitHub Releases，未上架应用商店。

在线更新固定读取 `Changjingjiu/TaxLens` 的最新稳定 Release，不包含 GitHub Token，也不上传账本和 AI 配置。Fork 如需自己的更新渠道，应修改 `UpdateSource.REPOSITORY` 并建立独立签名身份。当前版本继续使用 `io.github.taxray`，数据库格式未改变；更新时应覆盖安装，保留现有数据。

修改算法时须同时给出独立参考计算、边界案例和政策依据；不得把商品分类建议包装为正式税务判定。原始三份需求文档保留在根目录，实施差异记录在 [需求对应表](docs/REQUIREMENTS.md)，实际测试范围见 [验证记录](docs/VERIFICATION.md)。
