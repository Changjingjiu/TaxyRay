# 开源发行检查 · 0.2.0

核对日期 2026-09-15。公开仓库为 [Changjingjiu/TaxLens](https://github.com/Changjingjiu/TaxLens)，项目链接标识 TaxyRay，应用名称 TaxLens。

- MIT 许可证、第三方许可、贡献说明、私密漏洞报告入口和隐私文档已准备。
- 版本为 0.2.0，versionCode 3。保持包名 io.github.taxray 和数据库 schema 1。
- GitHub Actions 配置 JVM 测试、lint、debug/release 构建和 API 35 模拟器测试。实际结果查看对应提交的 Actions，不能以本机构建代替。
- 发行使用仓库外的专用私钥及官方签名轮换链。签名脚本从最终 APK 生成更新清单与哈希，CI 不持有私钥。
- Manifest 声明联网和安装更新权限。安装需系统授权和确认，接收器不导出，没有静默安装或后台定时联网。
- GitHub 更新客户端与 AI 客户端独立。前者只访问固定公开发行仓库，不发送 API 配置或账本；后者只在用户确认后发送所选图片。
- 真实票据、个人账本、密钥、私有签名目录、设备截图和本机路径不得进入源码包。测试使用合成数据。
- JSON/CSV 备份统一限制为 5 MB、10,000 笔账单、每单 1,000 项，超限明确报错。
- 物理震动手感、各厂商系统交互、Android 8–9 图片保存及真实模型图像准确率仍需真机测试。受控性能指标没有宣称达标。

签名、更新流程见 [UPDATES.md](UPDATES.md)，本轮实际测试及限制见 [VERIFICATION.md](VERIFICATION.md)。
