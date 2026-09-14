# 需求对应与明确调整

原始三份文档保留原样。本文记录首版实现对应关系，避免把“计划实现”当作“已验证”。

| 原需求 | 对应实现 |
| --- | --- |
| 原生Android、Kotlin2、Compose Material3、min26/target35 | `app/build.gradle.kts`、`MainActivity`、`ui/theme` |
| 离线手动录入、13/9/6/0/自定义、连续添加 | `ScannerReviewSheet`、`TaxLensViewModel.saveReceipt` |
| 高精度逐项价税分离 | `core/TaxCalculator`，Long分+Int基点 |
| Room事务、级联删除、历史编辑 | `data/local`、`ReceiptRepository` |
| 累计消费/税额/有效占比、近30天趋势、历史明细 | `DashboardScreen`、`ReceiptDetailSheet`、DAO查询 |
| 图片采样/方向/1920px与1MB | `ImageCompressor` |
| 自带API、强制Tool Calling、明确错误 | `VisionAgentService`、`VisionReceiptParser` |
| AI必须复核、改行、批量删、对账后入账 | `ScannerReviewSheet`、`ReceiptDraft.validationMessage` |
| 密钥加密、连接测试 | `SecurePreferences`、`SettingsScreen` |
| 贡献卡、构成条、PNG与系统分享 | `TaxContributionCard`、`ShareCardHelper`；按最新截图要求移除摘要ID、英文页眉及圆章 |
| JSON/CSV备份与导入、幂等/冲突处理 | `BackupCodec`、`ReceiptRepository.importReceipts`；导入/导出统一单文件5,000,000字节、10,000单、每单1000项 |
| 清空数据库和加密密钥 | `TaxLensViewModel.eraseEverything`、WAL截断与Keystore删除 |
| 冷灰/松石绿、票据虚线、等宽金额、深色 | Material3静态品牌色、双主题、两种贡献卡模板 |
| 数字动画、列表增删、抽屉 | 600ms数字位动画、Compose animateItem、原生ModalBottomSheet；遵循系统动画缩放 |
| 出票展开、税率滑块与生成反馈 | 380ms顶部裁剪、可见前12项25ms错差、48dp税率分段250ms共享高亮；按截图反馈改为生成贡献卡时一次触觉与Konfetti彩纸；关闭系统动画时跳过彩纸 |

## 为正确性做的调整

- PRD 的函数名与技术文档不一致；统一为 `parse_receipt_tax_items`，不保留双函数兼容路径。
- 金额先舍入税额，税前金额取差。避免独立舍入两边导致半分情况下不守恒。计算依据见 ALGORITHM.md。
- “0%”只是数值计算档位，免税与零税率不是同义词；税率推断不代表法定认定。运输一般9%，不能写成6%。F不作为全国统一免税证据；水产不普遍免税。
- 最新官方模型名为 `deepseek-flash`，对应DeepSeek V4.1 Flash并支持图片；设置页提供推荐，实际由用户配置。官方DeepSeek端点关闭默认思考模式，以便轻量测试与强制工具调用；文本连接成功不冒充视觉能力联调成功。
- `androidx.security:security-crypto` 已被官方弃用；采用系统 Keystore AES/GCM，不引入旧 alpha 版存储。
- 消费者估算不能证明商户实际缴税，贡献卡明确非完税证明。按2026-09-15截图要求移除估算章、英文页眉与底部摘要；不保留旧盖章或摘要实现。
- 虚拟长列表的错差限制在前12项，最多等待275ms；不会让1000项依次等待25秒。贡献卡内容绘制完成即可导出，彩纸覆盖层独立、不阻断点击、不写入图片。
- 品目存储增加持久化 position 保障排序；金额持久化用SQLite INTEGER分，避免SQLite REAL精度丢失；保留税前金额与推断理由。
- 为个人隐私，设置页启用 FLAG_SECURE；不集成整库相册读取权限。拍照通过系统相机及短期FileProvider URI完成。
- Android 8–9通过SAF保存图片；Android10+直接存入MediaStore相册，这是不同API的原生实现。
- 数据清除包括数据库行、WAL、密钥与应用临时图片；不宣传无法验证的闪存物理安全覆写。
- 为限制不可信备份解析的资源消耗，导入和导出统一限制为单文件5 MB（5,000,000字节）、10,000张账单、每单1000项。超过限额会拒绝导出，不生成本应用无法恢复的备份；当前没有分卷功能，大账本超限时无法完整导出。

## 验证边界

2026-09-15截图反馈还要求修复退出确认隐形模态层、默认商户名称搜索、总览顶栏、页脚居中和设置页信息密度；对应修改包含新的交互回归。0.2.0 接入固定仓库的稳定版检查、校验下载与系统安装，见UPDATES.md。

原文冷启动<800ms、写入<50ms、图片处理<500ms是目标，必须在明确的真机、数据量和测试条件下测量才能判定。首版构建、单元测试、模拟器验证不等同这些指标达成。真实模型识别率、低光/褶皱/长票质量以及真实设备系统分享与功耗仍需要实测。

本次实现按指定技术栈交付Android。没有声称实现iOS/PWA，没有服务器或联网税率自动更新；后续税法变化需更新规则及政策文档。退款负数、自动跨品目折扣分摊、销售方纳税身份判定不属于当前模型。

第三轮反馈新增结构化 AI 归类与票面时间读取、6% 快选、逐行可点击税率指南、空态文字删除、文案空格/换行分隔、GitHub 项目链接与检查更新入口。合成票据回归不等于真实图片模型准确率测试。
