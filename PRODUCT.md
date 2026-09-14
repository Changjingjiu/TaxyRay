# TaxLens

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

三份用户需求指定 Kotlin 2、Jetpack Compose Material 3、Room、OkHttp、离线优先。工程目录 TaxRay，公开仓库 Changjingjiu/TaxLens，项目链接标识 TaxyRay，应用名称 TaxLens。

## Users

希望了解消费中增值税估算构成的个人消费者、日常记账用户，以及需要数据自主权的开源用户。

## Product Purpose

用户可离线记录消费、逐项价税分离、复核视觉识别结果、浏览历史、导出账本与贡献卡。

## Capabilities and Constraints

按用户提供的《完整产品功能需求说明(PRD).md》《软件技术栈说明.md》《UX风格规范文档.md》实现 Android 版本。金额使用十进制计算和整数分存储。图像识别需用户主动启用自己的端点、模型与密钥；没有账号、服务器或遥测。估算不能证明商户实缴税额，分享图不是法定完税凭证。先交付可独立运行的离线闭环，完整纳入复核、数据备份和 BYOK。

## Brand Commitments

文档已指定 Modern Fiscal：冷灰底色、松石绿、秩序化排版、等宽金额与票据虚线；遵循原生 Material 交互。中性、正向、准确的中文文案。

## Evidence on Hand

三份根目录需求文档及2026-09-15用户提供的真机截图反馈。临时测试凭据仅用于指定 DeepSeek 服务的轻量诊断，不写入工程或日志。测试账单均为合成数据；模拟器验证与真机触觉、完整真实小票识别验收分开报告。

## Product Principles

1. 先保证账目、舍入和合计可核验。
2. 所有 AI 结果由用户确认后才入库。
3. 数据与密钥留在设备，发送图片前说明目的端点。
4. 开源工程可独立构建，依赖、算法和政策边界透明。

## Accessibility & Inclusion

支持系统深色模式、字体缩放、TalkBack 内容描述、48dp 点击区域与系统动画缩放设置。
