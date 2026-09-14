# 安全问题反馈

请通过 [GitHub 私密漏洞报告](https://github.com/Changjingjiu/TaxLens/security/advisories/new) 联系维护者。

涉及密钥泄露、未确认图片上传、跨域重定向、备份解析越界、交易完整性或私有文件访问的问题，请通过上述私密漏洞入口报告。不要把真实 API Key、消费图片或账本放入公开 Issue。

报告尽量提供可复现的最小步骤、应用版本、Android版本、预期与实际行为，以及脱敏日志。维护者不应索要真实 API Key。

API 凭证由 Android Keystore 保护；Room 账本依靠应用沙盒与设备系统保护，而非独立数据库加密。已经解锁或 root 的设备、被注入的进程、不可信 API 服务及用户主动导出的副本不在应用可以独立控制的范围内。完整设计见 docs/PRIVACY.md。
