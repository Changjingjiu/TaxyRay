# README 展示素材

仅收录首页实际使用的图片 其余截图与生成过程留在本地工作目录

| 文件 | 来源 |
| --- | --- |
| `readme-cover.svg` | 原创 SVG 封面 沿用应用图标和松石绿配色 113元按13%拆分为计算示例 |
| `readme-cover-en.svg` | 同一封面的英文版 供 `README.en.md` 使用 |
| `screen-dashboard.png` | 0.3.3 总览 累计税额估算与最近账单 |
| `screen-scan-batch.png` | 0.3.3 连续识别复核列表 含快速核对卡片 |
| `screen-review.png` | 0.3.3 核对页 商品合计59.24元 实付59.20元 分摊4分优惠后税额6.36元 |
| `screen-detail.png` | 0.3.3 账单详情 实付113.00元 两项分别按13%和9%拆分 |
| `contribution-paper.png` | 0.3.3 保存图片功能导出的纸本 PNG 原图 1080 × 1691 |
| `contribution-forest.png` | 同一笔演示账单的松石绿 PNG 原图 1080 × 1691 |

页面截图由 Android 16 模拟器上的设备端截图测试生成 使用合成演示账单 生成后等比缩放到 720 像素宽
生成用例：`BillRecognitionCaptureTest`（总览与识别列表）、`ReceiptViewCaptureTest`（核对页与账单详情）、`ContributionExportTest`（贡献卡）
核对页与详情页为合成草稿 贡献卡使用实付242元 税额估算22元的演示账单

图片不包含真实账单 私人收据 测试密钥或内部测试编号
截图来自实际应用 没有通过图片编辑修改文字或金额
素材随项目使用 MIT 许可证
