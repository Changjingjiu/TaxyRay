# TaxLens 设计系统与 UI/UX 风格规范文档 (Design System & Motion Guidelines)

| 规范版本 | 设计代号 | 视觉基调 | 核心场景 |
| :--- | :--- | :--- | :--- |
| v1.1.0 | **Modern Fiscal (现代财政秩序风)** | 极简、票据质感、高可信度、公民尊严 | 移动端 / 响应式 Web / 离线单机 |

---

## 一、 风格定位与设计理念 (Design Philosophy)

TaxLens 的核心诉求是将“日常消费中的隐形税负”透明化，并唤起“公民建设者”的自豪认同。UI 设计必须避开两种极端：
- **坚决避免“过度娱乐化 / 廉价记账软件风”**：严禁无意义的弹跳表情包、圆滚滚的玩具式按钮或过多杂色，那会削弱“税收与公共财政”的严肃法定属性。
- **坚决避免“激进对抗 / 赛博朋克风”**：严禁刺眼的霓虹对比色、警报式闪烁，避免在视觉上造成对抗情绪或给审核造成阻力。

### 核心设计哲学
1. **秩序美学 (Order & Rigor)**：参考现代高端财经刊物（如 Bloomberg、The Economist）的版式，排版严密、克制、字距考究。
2. **拟真票据感 (Tactile Receipt)**：细节处融入轻微的票据纹理、热敏纸等宽排版、打孔锯齿虚线，让用户有“审计账目”的真实感。
3. **数字仪式感 (Ceremony of Contribution)**：将税额计算结果打造成“公共贡献凭证”，赋予数字重量，让用户感知“自己是在支撑城市运转”。

---

## 二、 动效与交互过渡系统 (Motion Design System)

动效绝不是装饰性的花哨噱头，而是**增强物理真实感、提供明确操作反馈、赋予纳税数字仪式感**的系统性语言。

### 2.1 动效基本原则
1. **功能先于表现 (Functional First)**：每一次形变、位移都必须解释“物体从哪里来、到哪里去”，动效总时长严格控制在合理区间内，严禁拖慢操作节奏。
2. **机械阻尼感 (Mechanical Precision)**：拒绝橡皮筋式的轻浮回弹，采用类似精密钟表齿轮、老式打字机键帽的“低回弹、高阻尼（Spring Physics）”物理特性。
3. **数字平滑性 (Numeric Continuity)**：涉及金额变动，必须具有连续滚动的数值过渡，严禁生硬跳字。
4. **无障碍适配 (Reduced Motion)**：检测到系统开启 `prefers-reduced-motion` 时，自动降级为纯透明度（Opacity Fade）微过渡或直接切换。

### 2.2 缓动曲线与时间标准 (Timing & Easing Tokens)

| Token 名称 | 时长 | 缓动曲线 (Bezier / Spring) | 适用场景 |
| :--- | :--- | :--- | :--- |
| `motion-fast` | **150ms** | `cubic-bezier(0.2, 0, 0, 1)` | 按钮按下缩放、标签悬浮、开关切换 |
| `motion-normal` | **250ms** | `cubic-bezier(0.16, 1, 0.3, 1)` | 抽屉拉出、卡片展开、税率胶囊滑动 |
| `motion-slow` | **380ms** | `cubic-bezier(0.05, 0.7, 0.1, 1)` | 票据出票展开、凭证全屏呈现 |
| `motion-spring` | 阻尼比 0.85, 刚度 280 | Spring (弹簧动力学) | 条目删除后列表回弹、印章盖压反馈 |

---

### 2.3 核心场景动效规范

#### 场景 1：数字拨盘滚动 (Rolling Number Ticker)
- **触发时机**：首页大盘加载、单次记账完成税额汇总、人工复核修改商品金额时。
- **动效特征**：
  - 数字拆解为独立的垂直滚轮列表（0~9），仅发生变化的位发生平滑纵向滑动。
  - 小数点固定不动，首位货币符号（¥）带有微弱的透明度渐现。
  - **总时长**：600ms 完成，采用强减速曲线（Deceleration Curve），末尾沉稳刹停，体现数字落定的分量。

#### 场景 2：出票展开动效 (Receipt Unfolding Transition)
- **触发时机**：AI 小票扫描完成、点击历史小票下钻详情时。
- **动效特征**：
  - **遮罩裁剪展开**：卡片并不是简单放大，而是以顶部为锚点，沿 Y 轴自上而下通过 `clip-path` 逐行向下展开，模拟收银机吐纸的物理过程。
  - **内容微错差 (Stagger)**：小票内的品名行以每行 **25ms** 的错差依次淡入并上移 4px。
  - **虚线绘制**：底部打孔锯齿边在出票完成瞬间，伴随一条极细的灰色虚线由左至右平滑延展。

#### 场景 3：税率胶囊滑动形变 (Pill Segmented Morphing)
- **触发时机**：人工复核修改商品税率（13% / 9% / 0%）时。
- **动效特征**：
  - 选中高亮底色块采用类似 iOS Segmented Control 的**平滑滑动（Layout Morphing）**，而不是瞬间变色。
  - 切换瞬间，右侧的“不含税价”与“贡献税额”触发局部的透明度微变（Fade 100ms）并就地刷新，杜绝整个页面因刷新带来的跳动。

#### 场景 4：清单增删 FLIP 动效 (List Layout Transition)
- **触发时机**：剔除错误识别的杂项、手动添加新消费项。
- **动效特征**：
  - **删除项**：向左滑动超过 30% 阈值后，该项以 `scale(0.95)` 并在 180ms 内迅速向左滑出视野，透明度归零。
  - **列表重排**：下方条目通过 FLIP 技术（First-Last-Invert-Play）以平滑弹性向上填补空位（时长 220ms），视觉无生硬断层。

#### 场景 5：纳税贡献凭证“钢印下压” (The Seal Stamp Effect)
- **触发时机**：点击“生成纳税贡献卡”弹窗完成的一瞬间。
- **动效特征**：
  - 凭证卡片从底部轻柔滑出（300ms）。
  - 卡片完全展开停稳后，右上角的圆形印章（“VERIFIED CITIZEN / 已依法纳税”）在 **120ms** 内从 `scale(1.25)` 迅速收缩至 `scale(1.0)` 并落地，伴随透明度从 0% 冲至 100%。
  - 印章落定瞬间触发系统轻微触觉反馈（Haptic Tick），模拟物理印鉴加盖的法定仪式感。

---

## 三、 色彩系统 (Color Palette)

以**冷灰（Slate/Zinc）**为基石，以**公帑松石绿（Emerald/Forest）**为精神主色，体现公共利益与建设活力。

### 3.1 基础色盘 (Tailwind 色系对应)

| 语义层级 | 浅色模式 (Light Mode) | 深色模式 (Dark Mode) | 视觉说明 |
| :--- | :--- | :--- | :--- |
| **主背景 (Canvas)** | `#F8F9FA` (`zinc-50`) | `#09090B` (`zinc-950`) | 浅色取票据柔和冷白，深色为沉浸纯黑 |
| **卡片底色 (Surface)** | `#FFFFFF` (`white`) | `#18181B` (`zinc-900`) | 承载小票与账目，微弱层次反差 |
| **主强调色 (Primary)** | `#047857` (`emerald-700`) | `#10B981` (`emerald-500`) | 象征税收贡献、公共设施投入、正面荣誉 |
| **主文本 (Text Primary)** | `#0F172A` (`slate-900`) | `#F8FAFC` (`slate-50`) | 极致清晰的高对比度字体色 |
| **次级文本 (Text Secondary)** | `#64748B` (`slate-500`) | `#94A3B8` (`slate-400`) | 用于计算公式、单位、小票副标题 |
| **边框与分割 (Border)** | `#E2E8F0` (`slate-200`) | `#27272A` (`zinc-800`) | 1px 极细边框，配合虚线表现票据撕口 |

### 3.2 税率档位功能色 (Tax Rate Semantic Tags)

| 税率档位 | 标签背景色 (Light/Dark) | 文字颜色 (Light/Dark) | 适用含义 |
| :--- | :--- | :--- | :--- |
| **13% 标准税率** | `blue-50` / `blue-950/40` | `blue-700` / `blue-400` | 现代工业制成品、日用消费 |
| **9% 民生税率** | `emerald-50` / `emerald-950/40` | `emerald-700` / `emerald-400` | 土地产出、粮食初级生鲜、书本 |
| **0% 免税项** | `zinc-100` / `zinc-800` | `zinc-600` / `zinc-400` | 法定直采生鲜免税 |
| **自定义税率** | `purple-50` / `purple-950/40` | `purple-700` / `purple-400` | 灵活拓展项 |

---

## 四、 字体与排版规范 (Typography)

**界面描述信息用无衬线体，所有金额、税率、计算公式一律使用等宽数字（Tabular Figures）。**

### 4.1 字体栈推荐
- **UI 无衬线字体**：
  - iOS / macOS：`system-ui`, `-apple-system`, `SF Pro Display`, `PingFang SC`
  - Android：`Roboto`, `Noto Sans SC`
  - Web 通用：`Inter`, `PingFang SC`, sans-serif
- **金额 / 数据等宽字体**：
  - `JetBrains Mono`, `SF Mono`, `Fira Code`, `ui-monospace`, monospace
  - **强制规则**：全局必须配置 `font-variant-numeric: tabular-nums`，确保数值滚动与上下对齐时小数点完全对齐，避免数字因宽度变动产生左右晃动。

### 4.2 字体排阶表 (Scale)

| 层级 | 字号 / 行高 | 字重 (Weight) | 典型应用场景 |
| :--- | :--- | :--- | :--- |
| **Hero 巨幅数字** | 36px / 44px | 700 (Bold) | 看板顶部累计贡献税额（滚动数字） |
| **H1 页面标题** | 22px / 28px | 600 (SemiBold)| 账本明细、设置中心标题 |
| **H2 账单小计** | 17px / 24px | 600 (SemiBold)| 单次小票实付金额、税额合计 |
| **Body 正文** | 14px / 20px | 400 (Regular) | 商品名称、设置项描述、公式说明 |
| **Caption 辅助说明**| 12px / 16px | 500 (Medium)  | 税率标签、时间戳、流水单号 |
| **Legal 法律/微字** | 10px / 14px | 400 (Regular) | 贡献凭证底部的微缩公信力寄语 |

---

## 五、 核心界面组件设计详述

### 5.1 顶部大盘指标看板 (The Ledger Header)
- **视觉形式**：扁平化卡片，去掉厚重的卡片阴影，采用 `1px` 微细边框包裹。
- **排版结构**：
  ```
  ┌────────────────────────────────────────────────────────┐
  │  累计为公共事业贡献增值税 (CNY)                          │
  │  ¥ 1,428.65                                            │
  │                                                        │
  │  总消费支出 ¥12,480.00   │   实际综合税负率 11.45%     │
  └────────────────────────────────────────────────────────┘
  ```

### 5.2 拟真票据卡片 (The Receipt Card)
- **卡片边缘**：卡片顶底边使用 **虚线打孔效果 (`border-dashed`)**，模拟从收银机撕下热敏纸的触感。
- **交互展开**：点击卡片平滑滑出折叠区域，展示拆解公式（动效 250ms）：
  `¥35.00 ÷ 1.13 × 13% = ¥4.03`

### 5.3 纳税人公共贡献凭证 (The Certificate Card - 核心传播组件)
```
┌────────────────────────────────────────────────────────┐
│  TAX CONTRIBUTION CERTIFICATE                          │
│  纳税人公共贡献凭证                                     │
│  NO. 2026-0914-8842                     [ 城市微缩水印 ]│
│  ────────────────────────────────────────────────────  │
│  消费场所: 盒马鲜生 (江桥店)                            │
│  消费时间: 2026-09-14 18:24:05                         │
│                                                        │
│  消费结算总计                        ¥ 328.50          │
│  含税不含税基数                      ¥ 294.10          │
│  ────────────────────────────────────────────────────  │
│                                                        │
│  本次为您所在的城市建设与公共事业贡献增值税:             │
│                                                        │
│  ★ ¥ 34.40 ★                                         │
│                                                        │
│  [■■■■■■■■■■■■■■■□□□] 综合税负率 10.47%              │
│   (13%工业制成品 ¥28.20  |  9%民生农产品 ¥6.20)         │
│                                                        │
│  ────────────────────────────────────────────────────  │
│  “每一笔理性消费，都在支撑社会前行。”                   │
│  Generated by TaxLens · 纯本地隐私运算                  │
└────────────────────────────────────────────────────────┘
```

---

## 六、 配置文件模板 (`tailwind.config.js` 包含动效注入)

包含色彩、字体与动效缓动曲线完整配置：

```javascript
/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        fiscal: {
          canvas: 'var(--fiscal-canvas)',
          surface: 'var(--fiscal-surface)',
          border: 'var(--fiscal-border)',
          accent: '#10B981',
          accentDark: '#047857',
        },
        tax: {
          standard: { bg: '#EFF6FF', text: '#1D4ED8', border: '#BFDBFE' },
          agricultural: { bg: '#ECFDF5', text: '#047857', border: '#A7F3D0' },
          exempt: { bg: '#F4F4F5', text: '#52525B', border: '#E4E4E7' },
        }
      },
      fontFamily: {
        sans: ['Inter', '-apple-system', 'BlinkMacSystemFont', 'PingFang SC', 'sans-serif'],
        mono: ['JetBrains Mono', 'SF Mono', 'ui-monospace', 'monospace'],
      },
      transitionTimingFunction: {
        'fiscal-fast': 'cubic-bezier(0.2, 0, 0, 1)',
        'fiscal-smooth': 'cubic-bezier(0.16, 1, 0.3, 1)',
        'fiscal-decel': 'cubic-bezier(0.05, 0.7, 0.1, 1)',
      },
      transitionDuration: {
        '150': '150ms',
        '250': '250ms',
        '380': '380ms',
        '600': '600ms',
      },
      keyframes: {
        stamp: {
          '0%': { transform: 'scale(1.25)', opacity: '0' },
          '100%': { transform: 'scale(1.0)', opacity: '1' },
        },
        receiptUnfold: {
          '0%': { clipPath: 'inset(0 0 100% 0)', opacity: '0' },
          '100%': { clipPath: 'inset(0 0 0 0)', opacity: '1' },
        }
      },
      animation: {
        'stamp': 'stamp 150ms cubic-bezier(0.16, 1, 0.3, 1) forwards',
        'receipt-unfold': 'receiptUnfold 380ms cubic-bezier(0.05, 0.7, 0.1, 1) forwards',
      }
    },
  },
  plugins: [],
}
```