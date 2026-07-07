# 一键生成分析报告功能 — 设计方案

## 场景概述

**场景 A：一键生成完整报告**
用户在界面点击"生成报告"按钮 → 系统自动填入"生成完整的分析报告"并发送给 Agent → Executor 分析数据 → 输出 `##NEEDS_REPORT##` → Synthesizer 持有 GenerateReportTool 执行固定流水线 → 输出结构化 Markdown 报告 → 前端展示并可供下载 .docx

**场景 B：从对话中导出报告**
用户在已有 AI 回答（含图表/表格）上点击"导出为报告" → 后端从 conversation_message 提取 content + chartOption → 渲染 ECharts 为图片 → 组装 .docx → 返回文件

---

## 架构设计

```
                    ┌─────────────────────────┐
                    │    ReportController      │
                    │  POST /report/generate   │ ← 场景 B：从现有消息导出
                    │  POST /report/download   │ ← 下载已生成的报告文件
                    └──────┬──────────────────┘
                           │
              ┌────────────┴────────────┐
              │                         │
      ┌───────▼────────┐      ┌─────────▼─────────┐
      │ GenerateReport │      │ ReportExportService│
      │ Tool           │      │ (从已有消息导出)    │
      │ (@Tool, 固定流) │      └───────────────────┘
      └───────┬────────┘
              │
   ┌──────────┼──────────┐
   ▼          ▼          ▼
┌──────┐ ┌─────────┐ ┌───────┐
│DuckDB│ │ChartOut-│ │ LLM  │
│ 查询  │ │putTool  │ │ 撰写  │
└──────┘ └─────────┘ └───────┘
              │
              ▼
      ┌──────────────┐
      │ FormatConv   │
      │ (md→docx/pdf)│
      └──────────────┘
```

---

## Agent 职责划分

```
Executor（数据分析者）
  ├─ DataLoadingTool     ← 加载数据
  ├─ DuckDbQueryTool     ← SQL 查询
  ├─ PythonRunnerTool    ← Python 统计分析
  └─ PreferenceTool

Synthesizer（输出装配者）
  ├─ ChartOutputTool     ← 生成图表配置
  ├─ GenerateReportTool  ← 生成完整报告 【新增，仅此持有】
  └─ PreferenceTool
```

- GenerateReportTool **只注册到 SynthesizerAgent**，ExecutorAgent 的 tool 列表没有它
- Agent 框架层面物理隔离，Executor 调用不存在的工具直接抛错，不需要靠 prompt 约束

---

## 标记系统

| 标记 | 含义 | 触发 Synthesizer | 前端过滤 |
|------|------|-----------------|---------|
| `##NEEDS_CHART##` | 需要可视化图表 | 是 → 调用 ChartOutputTool | 已过滤 |
| `##NEEDS_REPORT##` | 需要完整分析报告 | 是 → 调用 GenerateReportTool | **新增过滤** |
| `##CONCLUSION##` / `##END##` | 结论标记 | 否 | 已过滤 |

### 优先级规则（AgentServiceImpl.java）

```java
// executePipeline 中检测标记，二选一
if (response.contains("##NEEDS_REPORT##")) {
    // 走报告分支：构造 reportPrompt → synthesizeReport()
} else if (response.contains("##NEEDS_CHART##")) {
    // 走图表分支：现有逻辑
}
// 都没有 → 纯文本回答，不进 Synthesizer
```

### Executor 提示词新增规则

在 agent-executor.txt 中新增：

```
- 当用户要求"生成报告/完整分析/一键报告"时:
  输出分析结论后追加 ##NEEDS_REPORT## 标记
- 当用户要求"画图/可视化/图表"时:
  输出分析结论后追加 ##NEEDS_CHART## 标记
- 二选一，一次只输出一个标记。报告已包含图表，所以有报告就别加 ##NEEDS_CHART##
```

### Synthesizer 提示词重构

Synthesizer 当前是"只生成图表"的 agent，改为"输出装配师"双分支指令。

---

## GenerateReportTool 内部流水线

Tool 硬编码固定流水线，不走 Agent 循环。LLM 只在"文本撰写"这一步参与。

### Step 1: 数据概览 (纯代码)
```
- DuckDB: DESCRIBE → 列名/类型
- DuckDB: SELECT count(*) → 行数
- DuckDB: 每列的 null 值统计
- DuckDB: 数值列 → min/max/avg/p50/p95
- DuckDB: 类别列 → top 10 频率
- DuckDB: 如果有时间列 → 按年月聚合
```
**全量查询** — 不管用户要求如何，所有列都查（避免遗漏）
**耗时**：秒级

### Step 2: 图表生成 (纯代码)
```
- 遍历所有数值列 → 自动选图
  - 1 列 → 直方图
  - 2 列（维度+数值）→ 柱状图/折线图
  - 3+ 列 → 多维对比图
- 调用现有 ChartOutputTool 生成 ECharts JSON
```
**耗时**：毫秒级

### Step 3: 报告撰写 (LLM 一次调用)
```
输入：
  1. Step 1 输出的结构化统计数据（JSON）
  2. Step 2 生成的图表配置列表
  3. 用户原始需求（"重点看销售差异"）
  4. 分析深度级别（自动判断或从用户语义提取）

过程：
  - LLM 只做"把结构化数据写成自然语言段落"
  - 不调工具、不循环、不决策

输出：
  - 结构化 Markdown，遵循报告模板
  - 包含图表占位符引用

约束：
  - 输出必须包含 ##DATA_OVERVIEW##、##KEY_FINDINGS## 等区域标记
  - 便于后处理分段
```
**耗时**：数秒

### Step 4: 格式转换
```
- Markdown → Pandoc → .docx（服务端转换）
- 或者前端用 marked + 库渲染为可下载格式
```

---

## 报告模板（Markdown 结构）

```markdown
# 数据分析报告
> 生成时间: {date} | 数据文件: {filename}({rowCount}行×{colCount}列)

## 一、数据概览
{自动生成的表格：列名、类型、缺失率、基本统计}

## 二、核心发现
{2-3 个最重要的洞察，每个配图}
![图表1](chart://{chartId})

## 三、详细分析
{分维度的深入分析}
### 3.1 {维度名称}
{分析文本 + 图表}
### 3.2 {维度名称}
{分析文本 + 图表}

## 四、结论与建议
{总结性结论和业务建议}
```

---

## 前端变更要点

### 场景 A："生成报告"按钮
- 在 ChatInput 或文件区上方新增按钮
- 点击校验：有 attach 文件 → 自动填充"生成完整的分析报告"并发送
- 无 attach 文件 → 提示"请先上传数据文件"

### 场景 B："导出为报告"按钮
- 在 AI 消息气泡底部新增按钮
- 点击 → 调后端 API → 下载 .docx

### `##NEEDS_REPORT##` 过滤
- 在 ChatMessage.vue 的 `normalizeMarkdown` 和 `contentSegments` 中新增对该标记的 strip
- 与 `##NEEDS_CHART##` 同样的处理方式

---

## 关键保障机制

| 保障 | 措施 |
|------|------|
| 稳定性 | 固定流程非 Agent，LLM 只参与文本撰写不参与决策 |
| 降级 | 某步失败→跳过该部分，报告标注"此部分数据不可用" |
| 超时 | 整个 Pipeline 60s 硬超时，前端进度条分阶段显示 |
| 越权防护 | GenerateReportTool 只注册给 Synthesizer，物理隔离 |
| 数据灵活性 | Tool 内部全量查询所有列，用户要求做"文本侧重点调整" |
