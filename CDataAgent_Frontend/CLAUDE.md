# CLAUDE.md

## 项目概述

AIBI Vue 前端 — 基于 Vue 3 重新构建的 DataLens AI 数据分析平台前端。用户上传 Excel/CSV，用自然语言描述分析目标，系统通过 AI 生成图表配置与分析结论。

---

## 视觉设计系统 — "Taste Soft" 暗色+橙黄色

以下规范提取自 `datalens-landing-dark.html` 设计初稿，**所有页面和组件必须严格遵守**。

### 色彩系统 (CSS 变量)

```css
:root {
  /* 背景层级 */
  --bg: #0D0B09;                    /* 主背景 — 极深暖黑 */
  --surface: #1A1714;               /* 卡片/面板背景 */
  --surface-raised: #211D19;        /* 抬起/浮层背景（CTA 按钮、弹窗） */

  /* 前景/文字 */
  --fg: #EDEBE8;                    /* 主文字 — 暖白 */
  --muted: #9D9892;                 /* 次要文字 — 暖灰 */

  /* 强调色 — 橙黄/陶土 */
  --accent: #BC694A;                /* 主强调色 */
  --accent-light: #E8A87C;          /* 浅强调色（渐变终点、高亮） */
  --accent-glow: rgba(188, 105, 74, 0.28);       /* 强辉光（按钮 hover） */
  --accent-glow-soft: rgba(188, 105, 74, 0.10);  /* 弱辉光（图标背景） */

  /* 边框 — 极低透明度白色 */
  --border-soft: rgba(255, 255, 255, 0.06);       /* 外边框（卡片、导航） */
  --border-inner: rgba(255, 255, 255, 0.04);      /* 内边框（双边框卡片） */
}
```

**色彩原则**：
- 强调色仅使用橙黄系（`--accent` / `--accent-light`），不得引入第二强调色
- 边框始终使用 `rgba(255,255,255, xxx)` 半透明白色，不使用纯白或纯黑边框
- 文字信息层级：`--fg`（主要）→ `--muted`（次要）→ `rgba(255,255,255,0.25)`（页脚/禁用）

### 排版

| 属性 | 值 |
|------|-----|
| 字体栈 | `-apple-system, BlinkMacSystemFont, 'SF Pro Display', 'Segoe UI', system-ui, sans-serif` |
| 字体平滑 | `-webkit-font-smoothing: antialiased; -moz-osx-font-smoothing: grayscale` |
| 基础行高 | `1.5` |

**字号与字重规范**：

| 层级 | 字号 | 字重 | 字间距 | 用途 |
|------|------|------|--------|------|
| Hero 标题 | `clamp(48px, 6vw, 72px)` | 700 | `-0.03em` | 首屏主标题 |
| 区块标题 | `40px` | 700 | `-0.02em` | section h2 |
| 卡片标题 | `22px` | 600 | `-0.01em` | feature/step 卡 h3/h4 |
| CTA 按钮 | `18px` | 600 | — | 主要行动按钮 |
| 副标题/描述 | `18px`（区块）`16px`（卡片） | 400 | — | 段落文字 |
| Logo | `22px` | 700 | `-0.02em` | 导航品牌名 |
| 导航按钮 | `15px` | 500 | — | 登录等操作 |
| 徽章/标签 | `15px` | 500 | `-0.01em` | hero-badge |
| 页脚 | `14px` | 400 | — | 版权信息 |

**排版原则**：
- 大标题使用负字间距（`-0.02em` ~ `-0.03em`），营造紧凑精致感
- 中文字体不指定特殊字体，依赖系统默认
- 所有文字颜色必须来自 `--fg` 或 `--muted`，不直接写死颜色值

### 圆角

| Token | 值 | 用途 |
|-------|-----|------|
| `--radius-sm` | `20px` | 小元素（标签、小卡片） |
| `--radius-md` | `32px` | 标准卡片、导航栏 |
| `--radius-lg` | `40px` | 大面板、模态框 |
| 全圆角 | `999px` | 按钮、徽章、药丸形元素 |
| 特殊小圆角 | `10px` | Logo 标记 |
| 图标容器 | `18px` | feature-icon |
| 步骤编号 | `24px` | step-number |

### 投影

```css
/* 默认卡片投影 */
--shadow-card: 0 2px 12px rgba(0,0,0,0.3), 0 8px 32px rgba(0,0,0,0.2);

/* Hover 卡片投影 — 含强调色辉光 */
--shadow-card-hover: 0 4px 24px rgba(0,0,0,0.4), 0 16px 56px rgba(188,105,74,0.12);
```

**投影原则**：
- 所有投影使用黑色 `rgba(0,0,0, xxx)`，不跟随元素颜色
- Hover 状态必须叠加一层强调色辉光（`--accent-glow` 或 `rgba(188,105,74,0.12)`）
- 投影应包含两层：近距离小偏移 + 远距离大偏移

### 动画曲线

```css
--spring: cubic-bezier(0.34, 1.56, 0.64, 1);      /* 弹性/弹簧效果 — 用于 scale transform */
--ease-out-expo: cubic-bezier(0.16, 1, 0.3, 1);    /* 平滑减速 — 用于 opacity/shadow 过渡 */
```

**动画原则**：
- 缩放动画（`transform: scale()`）必须使用 `--spring`
- 阴影、透明度、边框颜色过渡使用 `--ease-out-expo`
- 过渡时长：按钮 `0.28s~0.35s`，卡片 `0.35s~0.4s`
- 不得使用 CSS `animation` 做持续循环动画，交互反馈仅用 `transition` + hover

---

## 核心 UI 组件规范

### 1. 环境光网格背景

```css
body::before {
  content: '';
  position: fixed;
  inset: 0;
  background-image:
    radial-gradient(circle at 25% 20%, rgba(188,105,74,0.08) 0%, transparent 50%),
    radial-gradient(circle at 75% 65%, rgba(188,105,74,0.04) 0%, transparent 55%),
    radial-gradient(circle at 50% 90%, rgba(188,105,74,0.05) 0%, transparent 40%),
    url("data:image/svg+xml, ... 网格十字 SVG");
  pointer-events: none;
  z-index: 0;
}
```

- 固定定位，不随滚动
- 由 3 个 `radial-gradient`（强调色暖光斑） + 1 个低透明度 SVG 十字网格组成
- `pointer-events: none` 确保不阻挡交互

### 2. 毛玻璃导航栏 (.top-nav)

- **布局**：`position: fixed; top: 24px; left: 50%; transform: translateX(-50%)` — 居中悬浮
- **宽度**：`min(92%, 1280px)`
- **背景**：`rgba(26, 23, 20, 0.72)` + `backdrop-filter: blur(24px)` — 半透明模糊
- **圆角**：`var(--radius-md)` = 32px
- **边框**：`1px solid rgba(255,255,255,0.06)`
- **内边距**：`14px 28px`
- **Hover**：边框变亮、投影增强（含强调色辉光）
- **Logo**：左侧，22px/700 字重，含 32×32 的 `--accent` 背景图标

### 3. 双边框卡片 (.card-double)

这是项目的**标志性 UI 模式**，所有卡片必须遵循：

```css
.card-double {
  background: var(--surface);
  border-radius: var(--radius-md);   /* 32px */
  border: 1px solid var(--border-soft);   /* 外边框 */
  /* 内边框通过 ::before 实现 */
}
.card-double::before {
  content: '';
  position: absolute;
  inset: 6px;
  border-radius: calc(var(--radius-md) - 6px);
  border: 1px solid var(--border-inner);
  pointer-events: none;
}
```

- **Hover 交互**：`translateY(-6px) scale(1.02)` + 投影增强 + 两层边框同时变亮
- 必须设置 `position: relative` 以支持 `::before` 伪元素

### 4. CTA 按钮（按钮中套按钮）

```css
.cta-button {
  /* 外层容器 */
  background: var(--surface-raised);
  border-radius: 999px;
  border: 1px solid rgba(255,255,255,0.08);
  position: relative;
  overflow: hidden;
}
.cta-button::after {
  /* 内层渐变 — hover 时淡入 */
  content: '';
  position: absolute;
  inset: 3px;
  border-radius: 999px;
  background: linear-gradient(135deg, var(--accent), #D4895E);
  opacity: 0;  /* hover → 1 */
}
.cta-button span {
  position: relative;
  z-index: 1;  /* 文字位于伪元素之上 */
}
```

- **Hover**：`scale(1.04)` + 内层渐变淡入 + 辉光投影 + 箭头右移 `4px`
- 文字必须 `position: relative; z-index: 1` 以保证在 `::after` 之上

### 5. 登录/次要按钮 (.login-btn)

- 全圆角 `999px`
- 默认：`--surface` 背景 + 半透明白边框
- Hover：`--accent` 背景 + 白色文字 + `scale(1.04)` + 强调色辉光
- 使用 `--spring` 曲线

### 6. 渐变文字

```css
background: linear-gradient(135deg, var(--accent), var(--accent-light));
-webkit-background-clip: text;
-webkit-text-fill-color: transparent;
background-clip: text;
```

- 仅用于 Hero 标题中的关键词高亮
- 渐变方向 135deg

### 7. 区块标题 (.section-heading)

- 居中布局
- h2：40px / 700 / `-0.02em`
- 副标题 p：18px / `--muted` / `margin-top: 12px`
- 与下方内容间距 `48px`（`margin-bottom`）

### 8. 功能卡片网格 (.features-grid)

- **3 列网格**：`grid-template-columns: repeat(3, 1fr)`，间距 `32px`
- **≤1024px** 时切换为单列
- 每张卡片包含：icon（56×56 / `--accent-glow-soft` 背景 / `18px` 圆角）→ 标题 → 描述

### 9. 步骤卡片 (.step-card)

- **水平排列**（flex），间距 `32px`
- 每步包含：渐变编号圆角方块（64×64 / `24px` 圆角 / `--accent` → `--accent-light` 渐变）→ 标题 → 描述
- 编号使用 `--accent-glow` 投影

---

## 布局规范

| 属性 | 值 |
|------|-----|
| 内容最大宽度 | `1280px` |
| 导航最大宽度 | `min(92%, 1280px)` |
| 页面内边距 | `120px 40px 80px`（桌面端） |
| 区块间距 | `100px`（flex gap） |
| 导航距顶部 | `24px` |
| 栅格列数（功能卡片） | 3 列 → ≤1024px 时 1 列 |
| 步骤排列 | flex row → ≤1024px 时 flex column |

---

## 内容与交互约定

- **页脚**：居中、`rgba(255,255,255,0.25)` 文字、顶部分隔线 `1px solid rgba(255,255,255,0.06)`
- **Loading / 空态**：暂未在设计稿中定义，需保持与整体暗色风格一致
- **图标**：内联 SVG，`stroke` 使用 `currentColor` 继承文字颜色，不引入图标库
- **SVG 参数**：线宽 `1.8`，线条端点 `round`，连接 `round`

---

## 响应式断点

| 断点 | 行为 |
|------|------|
| `max-width: 1024px` | 功能网格 3→1 列；步骤横排→竖排 |
| 小屏（待定义） | 导航内边距、页面内边距适当缩小 |

---

## 开发约束

- **CSS 优先使用 CSS 变量** (`var(--xxx)`)，禁止硬编码颜色/数值
- **Vue 组件命名**：待确定（推荐 PascalCase，与设计初稿风格一致）
- **组件拆分粒度**：导航、卡片（双边框模式）、CTA 按钮、区块标题应抽取为独立可复用组件
- **禁止**引入与 Taste Soft 不兼容的第三方 CSS 框架默认样式（如 Bootstrap 默认亮色主题）
- 设计初稿位于 `datalens-landing-dark.html`，任何视觉决策应首先参考该文件
