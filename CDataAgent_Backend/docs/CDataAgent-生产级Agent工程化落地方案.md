# CData Agent — 生产级 Agent 工程化落地方案

> 版本：v1.0 | 日期：2026-06-27 | 作者：Jesse
>
> 本文档从零开始设计一个面向数据分析的 AI Agent 系统，覆盖架构设计、数据层、
> Agent 三层结构、记忆系统、容错与降级、质量保证、安全隔离、可观测性、部署运维
> 及实施路线图。不依赖现有代码，以生产级标准重新审视每一个设计决策。

---

## 目录

1. [概述与设计目标](#1-概述与设计目标)
2. [核心架构总览](#2-核心架构总览)
3. [数据层设计](#3-数据层设计)
4. [Agent 三层架构设计](#4-agent-三层架构设计)
5. [Agent 记忆系统](#5-agent-记忆系统)
6. [工具调用与可靠性设计](#6-工具调用与可靠性设计)
7. [模型降级与兜底方案](#7-模型降级与兜底方案)
8. [执行质量保证体系](#8-执行质量保证体系)
9. [安全与隔离设计](#9-安全与隔离设计)
10. [可观测性与监控](#10-可观测性与监控)
11. [部署与运维](#11-部署与运维)
12. [实施路线图](#12-实施路线图)

---

## 1. 概述与设计目标

### 1.1 产品定位

CData Agent 是一个**以对话为交互方式的数据分析平台**。用户上传数据文件，
通过自然语言描述分析目标，Agent 自主完成数据探查、统计分析、可视化图表生成，
并支持分析报告导出。

### 1.2 核心设计目标

| 目标 | 说明 | 衡量标准 |
|------|------|---------|
| **可靠执行** | Agent 能够稳定完成复杂多步骤分析任务 | 端到端成功率 ≥ 95% |
| **结果可信** | 输出结论基于真实数据，不编造、不遗漏 | 数据一致性校验通过率 100% |
| **体感流畅** | 用户无需关心技术细节，专注分析本身 | 首次响应 < 2s，总耗时 < 60s |
| **安全隔离** | 用户代码执行、数据访问完全隔离 | 零越权、零数据泄露 |
| **灵活配置** | 用户可自定义模型、API Key、分析偏好 | 配置即生效，无需重启 |
| **生产可运维** | 完善的监控、告警、降级、日志体系 | 故障自愈率 ≥ 80% |

### 1.3 与现有项目的差异

当前项目采用的是 **单 ReactAgent + MySQL 临时表** 架构。本文档提出的重构方案
在以下方面做了根本性改变：

| 维度 | 现有方案 | 新方案 |
|------|---------|--------|
| 数据存储 | MySQL 临时表 | Parquet + DuckDB（嵌入式列存） |
| 分析引擎 | SQL（MySQL） | DuckDB SQL + Python 沙箱（Docker） |
| Agent 架构 | 单 ReactAgent 平铺 14 个工具 | Plan-Execute-Synthesize 三层 Agent |
| 文件存储 | 数据库表 | 对象存储 / 本地磁盘 + 元数据索引 |
| 模型管理 | 系统硬编码 DeepSeek | 用户自定义 + 多级降级 |
| 上下文管理 | SummarizationHook 被动压缩 | 结构化状态 + 主动上下文预算 |

---

## 2. 核心架构总览

### 2.1 系统分层

```
┌──────────────────────────────────────────────────────────────────┐
│                        接入层 (Access Layer)                       │
│  REST API + SSE Streaming + WebSocket                            │
│  身份认证、限流、参数校验、文件上传                                  │
└────────────────────────────┬─────────────────────────────────────┘
                             │
┌────────────────────────────▼─────────────────────────────────────┐
│                      Agent 编排层 (Orchestration)                  │
│                                                                   │
│  ┌──────────┐    ┌──────────────┐    ┌──────────────────┐        │
│  │ Planner  │───▶│  Executor    │───▶│  Synthesizer     │        │
│  │ Agent    │    │  Agent       │    │  Agent            │        │
│  │          │    │              │    │                   │        │
│  │ 意图分类 │    │ 数据加载     │    │ 图表生成          │        │
│  │ 场景匹配 │    │ SQL/Python   │    │ 报告构建          │        │
│  │ 计划生成 │    │ 分析执行     │    │ 结论格式化        │        │
│  └──────────┘    └──────┬───────┘    └──────────────────┘        │
│                         │                                          │
│                    ┌─────▼──────┐                                  │
│                    │ 工具执行层  │                                  │
│                    │ Tool Layer │                                  │
│                    └────────────┘                                  │
└────────────────────────────┬─────────────────────────────────────┘
                             │
┌────────────────────────────▼─────────────────────────────────────┐
│                      数据与计算层 (Data & Compute)                  │
│                                                                   │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────────────┐      │
│  │ Parquet     │  │ DuckDB       │  │ Docker Sandbox      │      │
│  │ File Store  │  │ (嵌入式OLAP)  │  │ (Python 隔离执行)    │      │
│  └─────────────┘  └──────────────┘  └─────────────────────┘      │
└────────────────────────────┬─────────────────────────────────────┘
                             │
┌────────────────────────────▼─────────────────────────────────────┐
│                      基础设施层 (Infrastructure)                    │
│  Redis (状态/缓存/锁) · RabbitMQ (异步消息) · MySQL (用户/配置)    │
│  对象存储 COS (文件持久化) · Docker (沙箱运行时)                    │
└──────────────────────────────────────────────────────────────────┘
```

### 2.2 核心数据流

```
用户上传文件 → Parquet 转换 → 文件元信息索引
     │
用户发送分析请求
     │
     ▼
PlannerAgent: 理解意图 → 生成执行计划 JSON
     │
     ▼
ExecutorAgent: 按计划逐步执行
     ├── load_data(file_ref) → 注册到 DuckDB
     ├── get_schema() → 获取列信息（LLM可见）
     ├── run_duckdb(sql) → SQL 查询（80%场景）
     └── run_python(code, data_refs) → 复杂分析（20%场景）
     │
     ▼
SynthesizerAgent: 组装输出
     ├── generate_chart() → ECharts option JSON
     ├── generate_report() → 分析报告
     └── 格式化分析结论
     │
     ▼
SSE 流式输出 → 前端渲染
```

### 2.3 关键技术选型

| 组件 | 选型 | 理由 |
|------|------|------|
| 嵌入式分析数据库 | **DuckDB** | 零配置、列存、直接查文件、Python 原生、SQL分析函数齐全 |
| 文件存储格式 | **Parquet** | 列存压缩、类型保留、DuckDB/Pandas 原生支持 |
| 复杂分析引擎 | **Python (Docker Sandbox)** | pandas/scipy/statsmodels，仅用于 DuckDB 无法覆盖的场景 |
| Agent 框架 | **Spring AI Alibaba Agent Framework** | 已有集成经验，ReactAgent + AgentTool 原生支持三层编排 |
| 图表生成 | **echarts-java** | 确定性构建，非 LLM 手写 JSON |
| 模型抽象 | **RoutingChatModel (Spring AI ChatModel)** | 对 Agent 透明，支持动态切换 |
| 对象存储 | **腾讯 COS** | 已有集成，文件持久化和下载 |
| 消息队列 | **RabbitMQ** | 异步报告生成、文件转换 |

---

## 3. 数据层设计

### 3.1 为什么不使用 MySQL 存储文件数据

| MySQL 方案的缺陷 | 后果 |
|-----------------|------|
| 动态建表，schema 推断不准 | 日期变字符串、大数变科学计数法 |
| 数值含 $/¥/千分位 需预处理 | CAST 开销大、精度丢失 |
| 临时表生命周期管理复杂 | 过期清理遗漏导致存储膨胀 |
| SQL 分析能力有限 | 无相关性、无分布检验、无聚类 |
| 每个查询走网络 + SQL 解析 | 延迟高（尤其是复杂聚合） |

### 3.2 DuckDB + Parquet 方案

```
文件上传流程:
  .xlsx/.csv/.xls → Apache POI/EasyExcel 读取
                  → 类型推断（数值/日期/字符串）
                  → 写入 Parquet 文件（Snappy 压缩）
                  → 存储到本地磁盘 + 异步上传 COS
                  → 文件名 = {userId}/{sessionId}/{fileId}.parquet

DuckDB 使用方式:
  -- 注册 Parquet 文件为虚拟表
  CREATE OR REPLACE VIEW mydata AS SELECT * FROM 'path/to/file.parquet';
  
  -- 完整 SQL 分析（支持窗口函数、统计函数）
  SELECT 
    region,
    COUNT(*) AS cnt,
    AVG(sales) AS avg_sales,
    STDDEV(sales) AS std_sales,
    CORR(sales, profit) AS sales_profit_corr
  FROM mydata
  GROUP BY region
  ORDER BY avg_sales DESC;
```

**优势**：
- **零建表**：文件即表，schema-on-read
- **高性能**：列存 + 向量化执行，分析查询比 MySQL 快 10-100x
- **分析能力**：内置 corr, stddev, percentile, regr_slope 等分析函数
- **Python 互通**：DuckDB Python API 可直接在沙箱中查询相同 Parquet 文件
- **资源友好**：嵌入式，无独立进程，无连接池

### 3.3 Python 沙箱的定位

Python 沙箱**不是替代 DuckDB**，而是它的**补充**：

| 场景 | 使用工具 | 占比估计 |
|------|---------|---------|
| 聚合查询、分组统计 | DuckDB SQL | 50% |
| 描述性统计、排名 | DuckDB SQL | 20% |
| Top N、条件筛选 | DuckDB SQL | 10% |
| 相关性矩阵 | Python (pandas.corr) | 5% |
| 统计检验（t-test/卡方） | Python (scipy.stats) | 5% |
| 聚类/降维 | Python (sklearn) | 3% |
| 自定义复杂公式 | Python (numpy) | 7% |

### 3.4 文件生命周期

```
上传 → 类型推断 → Parquet 写入 → DuckDB 注册 → 分析使用
                                                    │
                                      ┌──────────────┘
                                      │
                              ┌───────▼────────┐
                              │ 对话结束时删除？  │
                              │ 用户主动删除？   │
                              │ TTL 自动过期？   │
                              └───────┬────────┘
                                      │
                       ┌──────────────┼──────────────┐
                       ▼              ▼              ▼
                  立即删除       保留 N 天       永久保留
                 (默认行为)    (用户可设置)    (付费功能)
```

---

## 4. Agent 三层架构设计

### 4.1 为什么是三层

数据分析任务的本质是 **Pipeline with known patterns**，不是开放式探索。
将 Agent 拆分为三个专职层，每个层解决一个明确的问题：

```
单 Agent 的问题:
  "我有 14 个工具，用户要做 RFM 分析..."
  → 先试试 queryAggregation
  → 再试试 computeBasicStats
  → 好像不太对，用沙箱写个 Python
  → 沙箱代码有问题，再试一次
  → ... 7 次沙箱调用后终于完成

三层 Agent:
  Planner: "用户要做 RFM 分析 → 场景匹配 → 工具链 = [queryAggregation, computeBasicStats, rankByMetric, buildChart]"
  Executor: "按计划逐步执行，每步验证结果"
  Synthesizer: "基于分析结果生成图表和结论"
  → 精确、高效、可审计
```

### 4.2 PlannerAgent — 意图理解与规划

**职责**：分类用户意图 → 匹配分析场景 → 输出执行计划 JSON

**不持有的工具**：无（纯推理 Agent）

**System Prompt 核心内容**：
```
你是数据分析规划专家。你的唯一职责是根据用户意图生成执行计划。

## 意图分类
将用户意图归类到以下场景之一：
| 场景 | 关键词 | 工具链 |
|------|--------|--------|
| 趋势分析 | 走势/变化/趋势/增长率 | [queryAggregation, detectTrend, buildChart] |
| 排名分析 | Top/N/排名/最高/最低 | [queryAggregation, rankByMetric, buildChart] |
| 构成分析 | 占比/构成/分布/比例 | [queryAggregation, buildChart(pie)] |
| 相关性分析 | 相关/关系/影响因素 | [querySample, runPython(corr), buildChart(heatmap)] |
| RFM/分层 | RFM/分级/分群/聚类 | [queryAggregation, computeStats, rankByMetric, buildChart] |
| 统计摘要 | 概览/摘要/基本情况 | [getSchema, computeStats, getDistribution] |
| ... | ... | ... |

## 输出格式（严格 JSON）
{
  "intent": "trend_analysis",
  "description": "分析各区域月度销售额趋势",
  "data_refs": ["file_abc123"],
  "steps": [
    {"tool": "runDuckdb", "params": {"sql": "...", "output_key": "monthly_sales"}},
    {"tool": "detectTrend", "params": {"data_ref": "monthly_sales", "date_col": "month", "value_col": "total"}},
    {"tool": "buildChart", "params": {"type": "line", "data_ref": "monthly_sales", ...}}
  ],
  "expected_output": "趋势折线图 + 趋势方向分析"
}

## 约束
- 每个 data_ref 在整个计划中只声明一次
- 优先用 runDuckdb，仅复杂统计用 runPython
- runPython 的 output_key 必须在后续步骤中被引用，否则不要加入计划
```

**关键设计**：
- Planner 不持有任何工具，防止它"忍不住先试试"
- 输出强制为结构化 JSON（用 Schema 校验），不可自由文本
- 执行计划必须声明 `output_key`，建立步骤间数据依赖

### 4.3 ExecutorAgent — 按计划执行

**职责**：接收执行计划 → 逐步执行每个工具调用 → 报告每步结果

**持有的工具**：
| 工具 | 作用 |
|------|------|
| `loadData(fileRef)` | 加载 Parquet 文件到 DuckDB，返回表结构 |
| `getSchema(fileRef)` | 获取列名、类型、采样值（LLM 可见） |
| `runDuckdb(sql, outputKey)` | 执行 SQL，结果存入状态，返回摘要 |
| `runPython(code, dataRefs, outputKey)` | 在沙箱执行 Python，结果存入状态 |
| `getAnalysisState()` | 查看当前已完成的步骤和产出物 |

**System Prompt 核心内容**：
```
你是数据分析执行专家。你的职责是按计划逐步执行工具调用。

## 执行规则
1. 严格按计划中的步骤顺序执行
2. 每完成一步，报告执行结果摘要（不超过 100 字）
3. 如果某一步失败，尝试一次修正后仍失败则标记为 SKIPPED，继续下一步
4. 禁止执行计划之外的步骤
5. 禁止修改计划的 data_ref 引用

## 工具使用
- runDuckdb: 80% 的查询场景。SQL 必须包含 LIMIT（默认 1000）
- runPython: 仅用于 DuckDB 无法完成的分析（相关性、检验、聚类）
  - 代码必须是完整可执行的 Python 脚本
  - 数据通过 dataRefs 参数传入（不要写文件路径）
  - 输出必须是 JSON 可序列化格式
```

**关键设计**：
- Executor 看到的是"被裁剪过的工具列表"——只保留执行相关工具
- `runPython` 内置超时和资源限制（见 9.2 节）
- 每步执行结果写入 AnalysisState，不依赖 LLM 记忆

### 4.4 SynthesizerAgent — 结果合成与输出

**职责**：接收分析结果 → 生成图表 + 结论 → 格式化输出

**持有的工具**：
| 工具 | 作用 |
|------|------|
| `buildChart(type, dataRef, config)` | echarts-java 构建 ECharts option |
| `validateChart(optionJson)` | 图表配置结构校验 |
| `generateReport(template, sections)` | 构建分析报告（Markdown/HTML/PDF） |
| `getAnalysisResults()` | 获取 Executor 产出的所有结果摘要 |

**System Prompt 核心内容**：
```
你是数据分析报告专家。你的职责是将分析结果转化为用户友好的输出。

## 输出规则
1. 有图表时：ECharts option JSON → 【【【【【 → 分析结论 Markdown
2. 无图表时：直接输出分析结论 Markdown
3. 图表类型选择原则：
   - 时间维度 → 折线图/面积图
   - 类别对比 → 柱状图
   - 占比 → 饼图
   - 关系 → 散点图
   - 综合评估 → 雷达图
4. 结论必须引用具体数值，不模糊表述
5. 如果分析结果不足以得出结论，诚实说明而非编造
```

### 4.5 Agent 间通信协议

```
Planner → Executor: ExecutionPlan (JSON)
  {
    "plan_id": "uuid",
    "intent": "trend_analysis",
    "steps": [
      {"id": "step_1", "tool": "runDuckdb", "params": {...}, "output_key": "monthly_data"},
      {"id": "step_2", "tool": "buildChart", "params": {"data_ref": "monthly_data"}, ...}
    ]
  }

Executor → Synthesizer: AnalysisResults (JSON)
  {
    "plan_id": "uuid",
    "status": "completed",
    "steps_results": [
      {"step_id": "step_1", "status": "success", "output_key": "monthly_data", "summary": "12条月度数据"},
      {"step_id": "step_2", "status": "success", "output_key": "chart_option", "summary": "折线图"}
    ],
    "data_summary": {
      "row_count": 12,
      "key_findings": ["销售额呈上升趋势", "12月为峰值"]
    }
  }
```

**为什么不用 AgentTool 直接嵌套调用？**

框架的 `AgentTool.getFunctionToolCallback()` 可以让父 Agent 把子 Agent 当作工具调用。
但这种方式存在两个问题：
- 子 Agent 的调用上下文完全由 LLM 决定（和调普通 tool 一样），无法施加"必须按计划执行"的约束
- 子 Agent 的输出直接返回给 LLM，可能被截断或误解

三层 Agent 通过**结构化 JSON 协议**而非 Tool Calling 嵌套，获得更精确的控制力。

### 4.6 实际实现方式

在 Spring AI Alibaba Agent Framework 中，三层架构有两种实现路径：

**路径 A：三次独立 Agent 调用（推荐）**

```java
// AgentServiceImpl 中编排三次调用
public Flux<String> chatStream(String userMessage, ...) {
    // 第1步：规划
    ExecutionPlan plan = runPlannerAgent(userMessage, context);
    
    // 第2步：执行（带超时和中断控制）
    AnalysisResults results = runExecutorAgent(plan, context);
    
    // 第3步：合成输出（流式）
    return runSynthesizerAgent(results, context);  // Flux<String>
}
```

**路径 B：单 Agent + Tool 中嵌入子 Agent（备选）**

如果框架限制必须用单 Agent，可以将 Planner 和 Synthesizer 内嵌为"计划生成 Hook"和"输出格式化 Hook"。但这不如路径 A 清晰。

**选择路径 A 的理由**：
- 每个 Agent 的 System Prompt 高度优化，不互相污染
- 可以针对不同阶段使用不同模型（Planner 用便宜模型，Executor 用强模型）
- 执行计划可审计、可缓存（相同意图复用计划）
- 三个阶段可以独立超时和错误处理

---

## 5. Agent 记忆系统

### 5.1 记忆分层模型

```
┌─────────────────────────────────────────────────────┐
│ 第0层：即时上下文 (Immediate Context)                  │
│ 存储位置：LLM 上下文窗口                               │
│ 内容：System Prompt + 当前轮用户输入 + 执行计划 + 当前步骤结果  │
│ 生命周期：单次推理                                      │
│ 容量：< 10K tokens                                    │
├─────────────────────────────────────────────────────┤
│ 第1层：工作记忆 (Working Memory)                       │
│ 存储位置：Redis (Hash, 按 threadId 分区)                │
│ 内容：当前对话的分析状态、已加载文件、已执行步骤、data_ref 映射 │
│ 生命周期：对话期间                                      │
│ 容量：无限制（仅存储元数据，不存储数据内容）               │
├─────────────────────────────────────────────────────┤
│ 第2层：对话记忆 (Conversation Memory)                   │
│ 存储位置：RedisSaver Checkpoint + MySQL 异步持久化       │
│ 内容：多轮对话的结构化消息历史                            │
│ 生命周期：对话期间 + 历史可回溯                           │
│ 容量：受 SummarizationHook 管理（阈值 20K tokens）       │
├─────────────────────────────────────────────────────┤
│ 第3层：持久记忆 (Persistent Memory)                     │
│ 存储位置：MySQL                                         │
│ 内容：用户偏好、历史分析模式、常用文件元信息               │
│ 生命周期：永久                                          │
│ 容量：无限制                                            │
└─────────────────────────────────────────────────────┘
```

### 5.2 工作记忆 (Working Memory) 详细设计

这是最关键的创新点——在 LLM 上下文之外维护一个**结构化的分析状态对象**：

```java
// AnalysisState — 存储在 Redis Hash 中，key = analysis:state:{threadId}
public class AnalysisState {
    // 已加载的文件
    List<LoadedFile> files;
    // 已执行的步骤及结果引用
    List<StepResult> completedSteps;
    // 当前执行计划
    ExecutionPlan activePlan;
    // 数据产物索引（output_key → data_ref）
    Map<String, DataRef> dataIndex;
}

public class LoadedFile {
    String fileId;
    String fileName;
    String parquetPath;
    String duckdbViewName;
    SchemaInfo schema;  // 列名+类型+采样统计（LLM可见）
    int rowCount;
}

public class StepResult {
    String stepId;
    String toolName;
    Status status;       // SUCCESS / FAILED / SKIPPED
    String outputKey;    // 结果在 dataIndex 中的引用
    String summary;      // 100字摘要（进入 LLM 上下文）
    String errorDetail;  // 失败原因（不进入 LLM 上下文，仅日志）
}

public class DataRef {
    String key;
    String type;         // duckdb_result / python_result / chart_option
    int rowCount;
    String sampleJson;   // 前3行采样（LLM 可见）
    String location;     // 实际数据存储位置（Redis/DuckDB/Disk）
}
```

**每次 Agent 推理前注入的上下文（精简版）**：

```
[分析状态]
已加载文件: 销售数据2024.xlsx (3196行, 列: 日期,区域,产品,销售额,利润)
已完成步骤:
  1. runDuckdb: 按月聚合销售额 → monthly_sales (12行)
  2. detectTrend: 检测到上升趋势，斜率 +1250
可用数据: monthly_sales (12行)
```

**这个设计的核心价值**：
- LLM 始终知道自己"拥有什么"，不需要通过工具调用来探索
- 状态压缩为 ~200 tokens，不会导致上下文溢出
- 新步骤的结果自动追加到状态中

### 5.3 对话记忆管理

**Checkpoint 策略**：
- 使用框架的 `RedisSaver` 持久化 Agent 对话状态
- TTL = 3 天（活跃对话自动续期）
- 对话删除时同步清理 checkpoint

**上下文预算管理**：
- 不使用 SummarizationHook 的被动压缩（压缩后丢失信息）
- 而是**主动控制进入上下文的内容量**：
  - System Prompt: ~1500 tokens
  - 分析状态摘要: ~200 tokens
  - 当前执行计划: ~300 tokens
  - 最近 3 轮对话历史: ~2000 tokens
  - **总计: ~4000 tokens（远低于 30K 阈值）**
- 当对话历史超过 10 轮时，自动将早期轮次压缩为摘要（由应用层控制，非被动触发）

### 5.4 用户偏好记忆

支持两类偏好：
- **显式偏好**：用户明确说"以后都用柱状图" → Agent 调用 `savePreference` 写入 MySQL
- **隐式偏好**：系统从历史行为中推断（如用户连续 5 次选择饼图）→ 后台任务更新偏好权重

偏好注入方式：在 Planner 的 System Prompt 尾部追加 `[用户偏好] chart_type=bar, output_style=detailed`，但明确标注"仅供参考，用户当前输入优先"。

---

## 6. 工具调用与可靠性设计

### 6.1 工具分类与超时策略

| 工具类型 | 工具 | 超时 | 重试策略 |
|---------|------|------|---------|
| 快速只读 | `getSchema`, `getAnalysisState`, `getPreferences` | 3s | 重试1次 |
| SQL 查询 | `runDuckdb` | 30s | 重试1次（修正SQL） |
| Python 执行 | `runPython` | 60s | 重试1次（修正代码） |
| 图表构建 | `buildChart`, `validateChart` | 5s | 不重试 |
| 报告生成 | `generateReport` | 30s | 不重试 |

**层级超时**：
```
请求级超时: 120s (agent.global-timeout-seconds)
  ├── Agent 级超时: 90s (单个 Agent 最大执行时间)
  │     └── 工具级超时: 3~60s (单个工具调用)
  └── 降级超时: 30s (超时后的降级响应时间)
```

### 6.2 执行计划验证

在 Executor 执行计划前，进行计划级校验：

```
计划校验规则:
1. 每个步骤的必填参数完整
2. output_key 在后续步骤中被引用（无孤立步骤）
3. data_ref 引用已存在的文件或之前步骤的 output_key
4. 不存在循环依赖
5. 步骤数 ≤ 10（过长的计划应拆分）
6. runPython 步骤 ≤ 2（沙箱使用有节制）
```

校验失败 → 返回 Planner 重新生成计划（最多 2 次）。

### 6.3 Python 代码质量保证

**三层校验**：

```
第1层：执行前 — AST 静态分析
  ├── 检测禁止的导入（os.system, subprocess, socket, etc.）
  ├── 检测禁止的模式（while True, exec(), eval(), __import__）
  └── 检测代码结构（必须有明确的输出语句 print/json.dumps）

第2层：执行中 — 沙箱隔离
  ├── Docker 容器：内存限制 512MB，CPU 限制 1核
  ├── 网络隔离：无外网访问
  ├── 文件系统：只读挂载数据目录 + 临时可写 /tmp
  └── 超时控制：60s 硬超时 → SIGKILL

第3层：执行后 — 输出校验
  ├── stdout 大小限制：≤ 100KB
  ├── JSON 格式校验（如果期望 JSON 输出）
  ├── 数值范围检查（如百分比在 0-100）
  └── 行数检查（如返回行数 ≤ 期望值）
```

### 6.4 死循环/失控防护

多层纵深防御：

```
┌─────────────────────────────────────────────────────┐
│ 第1层：LLM 层面                                      │
│ - Executor 必须按计划执行，禁止偏离                      │
│ - System Prompt 明确："禁止迭代式调试，一次完成"         │
├─────────────────────────────────────────────────────┤
│ 第2层：框架层面                                      │
│ - ToolRetryInterceptor: 单工具最多重试 1 次             │
│ - ToolErrorInterceptor: 异常统一格式化                  │
│ - Agent 级调用次数计数器: 单次对话工具调用 ≤ 15 次        │
├─────────────────────────────────────────────────────┤
│ 第3层：执行层面                                      │
│ - 硬超时: 工具 60s / Agent 90s / 请求 120s             │
│ - 专用线程池: shutdownNow() 强制中断                    │
│ - Docker 容器: --ulimit cpu=60 --memory=512m          │
├─────────────────────────────────────────────────────┤
│ 第4层：系统层面                                      │
│ - 健康检查: 线程池队列深度监控                           │
│ - 熔断: 连续 5 次超时 → 拒绝新请求 30s                   │
│ - 告警: 超时率 > 10% → 钉钉/邮件通知                    │
└─────────────────────────────────────────────────────┘
```

### 6.5 工具调用状态机

```
                    ┌─────────┐
                    │  IDLE   │
                    └────┬────┘
                         │ Planner 生成计划
                         ▼
                    ┌─────────┐
            ┌──────▶│ EXECUTE │
            │       └────┬────┘
            │            │ 单步工具调用
            │            ▼
            │    ┌──────────────┐
            │    │ 成功?  失败?  │
            │    └──┬────────┬──┘
            │       │        │
            │       ▼        ▼
            │  ┌────────┐ ┌──────────┐
            │  │ DONE   │ │ RETRY    │──▶ 重试仍失败 → SKIPPED
            │  └────────┘ └──────────┘
            │       │
            │       ▼
            │  还有下一步? ──Yes──┘
            │       │No
            │       ▼
            │  ┌──────────┐
            └──│ SYNTHESIZE│
               └──────────┘
```

### 6.6 工具调用审计日志

每次工具调用记录到结构化日志：

```json
{
  "traceId": "uuid",
  "threadId": "conv_123",
  "userId": "456",
  "toolName": "runDuckdb",
  "params": {"sql": "SELECT ...", "outputKey": "monthly_data"},
  "startTime": "2026-06-27T10:00:00Z",
  "endTime": "2026-06-27T10:00:01.5Z",
  "durationMs": 1500,
  "status": "SUCCESS",
  "resultSummary": "12 rows, 3 columns",
  "errorDetail": null,
  "retryCount": 0
}
```

用于：
- 实时监控：工具调用成功率、耗时分布
- 问题排查：按 traceId 追踪完整调用链
- 成本分析：各工具的调用频次和资源消耗

---

## 7. 模型降级与兜底方案

### 7.1 模型路由架构

```
用户请求 → 解析 userId → 查询用户模型配置
                │
         ┌──────▼──────┐
         │ 有自定义配置？ │
         └──┬───────┬──┘
            │Yes    │No
            ▼       ▼
    ┌──────────┐  ┌──────────────┐
    │ 健康检查  │  │ 系统默认模型   │
    └──┬───┬───┘  │ (DeepSeek v4) │
       │   │      └──────────────┘
       ▼   ▼
   通过  失败 → 自动降级到系统默认

RoutingChatModel:
  - 每 30s 检查用户自定义模型健康状态
  - 连续 3 次失败 → 标记 unhealthy → 自动切到系统默认
  - 恢复检测：每 60s 尝试一次 → 成功则切回
```

### 7.2 多级降级策略

```
┌─────────────────────────────────────────────────────┐
│ 第1级：用户自定义模型（正常路径）                         │
│ 如: Claude Sonnet 4.6 / GPT-4o / DeepSeek v4          │
├─────────────────────────────────────────────────────┤
│ 第2级：系统主模型（用户模型不可用时自动切换）               │
│ 如: DeepSeek v4                                       │
├─────────────────────────────────────────────────────┤
│ 第3级：系统备用模型（主模型也不可用时）                    │
│ 如: DeepSeek v3 / Qwen-Max                            │
├─────────────────────────────────────────────────────┤
│ 第4级：降级响应（所有模型不可用）                         │
│ 返回友好提示 + 建议稍后重试                              │
└─────────────────────────────────────────────────────┘
```

### 7.3 模型差异化使用策略

三层 Agent 可以使用不同的模型，优化成本和质量：

| Agent | 推荐模型 | 理由 |
|-------|---------|------|
| Planner | 便宜的模型 (DeepSeek v3 / GPT-4o-mini) | 意图分类和场景匹配不需要顶级推理能力 |
| Executor | 最强的模型 (Claude Sonnet / GPT-4o) | 代码生成、SQL 编写需要精确推理 |
| Synthesizer | 中等模型 (DeepSeek v4 / GPT-4o) | 格式化和结论撰写需要良好的语言能力 |

**成本优化效果**（假设 1000 次对话/天）：
- 全部用最强模型：~$50/天
- 差异化使用：~$18/天（节省 64%）

### 7.4 模型健康检查

```java
// 每个模型实例维护健康状态
class ModelHealthIndicator {
    // 滑动窗口（最近 60s）
    int totalCalls;
    int successCalls;
    int failureCalls;
    long avgLatencyMs;
    
    boolean isHealthy() {
        return (failureCalls / (double) totalCalls) < 0.1  // 失败率 < 10%
            && avgLatencyMs < 30_000;                        // 平均延迟 < 30s
    }
}
```

### 7.5 Token 预算管理

按模型设置不同的上下文预算：

```
Claude Sonnet (200K context):   max 100K tokens（留 100K 给工具调用结果）
GPT-4o (128K context):          max 64K tokens
DeepSeek v4 (128K context):     max 64K tokens
DeepSeek v3 (64K context):      max 32K tokens
```

预算不足时的策略：
1. 压缩早期对话历史 → 摘要
2. 裁剪工具返回结果（只保留关键数据）
3. 拒绝过大的文件（如 > 100列 或 > 10万行的表，提示用户采样或拆分）

---

## 8. 执行质量保证体系

### 8.1 分析结果质量校验

```
校验维度:
┌─────────────────────────────────────────────────────────────┐
│ 1. 数据一致性                                               │
│    - 图表中的数据是否与查询结果一致？                           │
│    - 分析结论中的数值是否可在查询结果中找到？                    │
│    - 百分比之和是否为 100%（饼图/构成分析）？                   │
├─────────────────────────────────────────────────────────────┤
│ 2. 逻辑完整性                                               │
│    - 用户要求的每个分析维度是否都有覆盖？                       │
│    - 结论是否有数据支撑？（每个论断有对应数值）                  │
│    - 是否存在相互矛盾的结论？                                  │
├─────────────────────────────────────────────────────────────┤
│ 3. 技术要求                                                 │
│    - 图表 ECharts option 是否通过 validateChart？             │
│    - 响应格式是否正确（分隔符、Markdown 规范）？               │
│    - 是否满足用户的回复风格偏好（精简/详细）？                   │
└─────────────────────────────────────────────────────────────┘
```

### 8.2 图表质量保证

echarts-java 构建的图表仍需校验：

```java
// 图表构建后自动执行的校验
ChartValidationResult validate(Chart chart, DataRef dataRef) {
    List<String> issues = new ArrayList<>();
    
    // 1. 结构完整性
    if (chart.getTitle() == null) issues.add("缺少标题");
    if (chart.getSeries() == null || chart.getSeries().isEmpty()) issues.add("缺少系列数据");
    
    // 2. 数据范围检查
    for (Series s : chart.getSeries()) {
        if (s.getData().size() > 80) issues.add("系列数据点 > 80，可能影响渲染");
        if (s.getData().stream().allMatch(v -> v == 0)) issues.add("系列全为 0，可能是数据错误");
    }
    
    // 3. 图表类型与数据匹配
    if (chart instanceof Pie && chart.getSeries().size() > 1) 
        issues.add("饼图不应有多个系列");
    if (chart instanceof Scatter && chart.getSeries().size() < 1)
        issues.add("散点图至少需要一个系列");
    
    return new ChartValidationResult(issues.isEmpty(), issues);
}
```

### 8.3 分析结论质量保证

在 Synthesizer 输出结论后，进行**后置校验**：

```
校验规则:
1. 数值引用检查：
   - 结论中出现的数值是否在分析结果中存在？
   - 例：结论说"销售额增长 23.5%"，但分析结果中最大增长是 15% → 标记为疑似幻觉

2. 趋势判断一致性：
   - 结论说"呈上升趋势"，但 detectTrend 结果是"平稳" → 标记为矛盾

3. 完整性检查：
   - 用户问了 3 个维度，结论只覆盖了 2 个 → 标记为不完整
```

这个校验不是用规则引擎暴力匹配，而是用一个**轻量的校验 LLM 调用**：
- 输入：分析结果数据 + Synthesizer 输出的结论文本
- 输出：`{"valid": true/false, "issues": [...]}`
- 如果校验失败，将 issues 反馈给 Synthesizer 重新生成（最多 1 次）

### 8.4 端到端测试用例

针对每种分析场景，维护标准测试用例：

```
测试用例: RFM_ANALYSIS_001
  输入文件: rfm_test_data.csv (1000 行, 列: user_id, order_date, amount)
  用户消息: "对所有用户做 RFM 分析"
  期望行为:
    - Planner 匹配到 RFM 场景
    - Executor 使用 runDuckdb（不用 runPython）
    - 输出包含 R/F/M 分层结果 + 柱状图
    - 沙箱调用次数 = 0
  验收标准:
    - 端到端耗时 < 30s
    - 工具调用 ≤ 6 次
    - 结论中有具体分层人数
    - 无幻觉数值
```

---

## 9. 安全与隔离设计

### 9.1 多层安全边界

```
┌─────────────────────────────────────────────────────────┐
│ 第1层：用户认证与鉴权                                     │
│ - Session 登录认证                                       │
│ - 所有接口需登录                                         │
│ - 用户只能访问自己的数据和对话                             │
├─────────────────────────────────────────────────────────┤
│ 第2层：文件安全                                          │
│ - 上传文件类型白名单：.xlsx, .xls, .csv                  │
│ - 文件大小限制：≤ 10MB                                   │
│ - 病毒扫描（可选，COS 侧）                                │
│ - 文件名随机化存储，不暴露原始路径                         │
├─────────────────────────────────────────────────────────┤
│ 第3层：SQL 注入防护                                       │
│ - DuckDB 使用参数化查询（PreparedStatement）              │
│ - 表名/列名白名单校验（仅允许已注册的 Parquet 文件）        │
│ - 禁止 DDL/DCL 语句（只允许 SELECT）                      │
├─────────────────────────────────────────────────────────┤
│ 第4层：Python 沙箱隔离                                    │
│ - Docker 容器执行                                        │
│ - 网络隔离（--network=none）                              │
│ - 文件系统只读挂载 + tmpfs 可写临时目录                    │
│ - 资源限制（CPU: 1核, 内存: 512MB, 磁盘: 100MB）         │
│ - 禁止系统调用（seccomp profile）                         │
│ - AST 静态代码检查（执行前）                              │
├─────────────────────────────────────────────────────────┤
│ 第5层：API Key 安全                                      │
│ - AES-256-GCM 加密存储                                   │
│ - 内存中用完即焚，不记录日志                               │
│ - API Key 脱敏显示（只显示前4后4位）                      │
└─────────────────────────────────────────────────────────┘
```

### 9.2 Python 沙箱详细配置

```yaml
# Docker 容器配置
sandbox:
  image: "python:3.11-slim"        # 预装 pandas/scipy/sklearn
  memory_limit: "512m"
  cpu_limit: "1.0"
  disk_limit: "100m"
  network: "none"                   # 完全网络隔离
  timeout:
    execution: 60s                  # 脚本执行超时
    container_life: 120s            # 容器最大存活时间
  
  # 文件系统挂载
  mounts:
    - type: "bind"
      source: "/data/parquet/{userId}/{sessionId}"
      target: "/data"
      mode: "ro"                    # 只读
    - type: "tmpfs"
      target: "/tmp"
      size: "50m"                   # 临时可写
  
  # 预装 Python 包（镜像构建时）
  packages:
    - pandas==2.1.0
    - numpy==1.24.0
    - scipy==1.11.0
    - scikit-learn==1.3.0
    - statsmodels==0.14.0
```

### 9.3 Python 代码 AST 检查

```python
# 执行前的安全扫描伪代码
FORBIDDEN_IMPORTS = {
    'os', 'subprocess', 'socket', 'requests', 'urllib',
    'shutil', 'sys', 'ctypes', 'multiprocessing', 'signal',
    'importlib', '__builtins__', 'eval', 'exec', 'compile'
}

FORBIDDEN_PATTERNS = [
    r'while\s+True',           # 无限循环风险
    r'__import__\s*\(',        # 动态导入
    r'open\s*\(',              # 文件操作
    r'\.system\s*\(',          # 系统调用
    r'subprocess\.',           # 子进程
]

# 如果检测到任何禁止模式 → 拒绝执行，返回具体原因
# 不直接告诉 LLM "你违规了"，而是说 "代码包含不允许的导入: os，请使用 pandas/numpy/scipy 替代"
```

### 9.4 数据隔离

```
每个用户的数据隔离层级:
  文件存储: COS / local: /data/{userId}/{sessionId}/
  DuckDB:   每个 session 独立的 in-memory 数据库实例
  Redis:    key 前缀包含 userId 和 sessionId
  沙箱:     每个 session 独立的 Docker 容器
```

---

## 10. 可观测性与监控

### 10.1 监控指标体系

| 类别 | 指标 | 采集方式 | 告警阈值 |
|------|------|---------|---------|
| **可用性** | Agent 请求成功率 | 应用埋点 | < 95% |
| **可用性** | 模型 API 可用率 | 健康检查 | < 99% |
| **延迟** | 端到端响应 P50/P95/P99 | 应用埋点 | P95 > 60s |
| **延迟** | 工具调用耗时分布 | 审计日志 | runPython P95 > 45s |
| **质量** | 计划生成成功率 | Planner 输出校验 | < 90% |
| **质量** | 图表校验通过率 | ChartValidator | < 95% |
| **质量** | 结论校验通过率 | Synthesizer 输出校验 | < 90% |
| **效率** | 单次对话工具调用次数 | 审计日志 | P95 > 10 |
| **效率** | 沙箱调用比例 | 审计日志 | > 30% |
| **资源** | DuckDB 内存使用 | JVM metrics | > 1GB |
| **资源** | Redis 连接池使用率 | Redisson metrics | > 80% |
| **资源** | Docker 容器数量 | Docker API | > 20 |

### 10.2 分布式追踪

```
一个用户请求的完整调用链:
                                    
Request (traceId=xxx)               
├── PlannerAgent.call()             
│   └── LLM API call (DeepSeek)     
│       ├── duration: 1.2s         
│       └── tokens: 1500 in, 300 out
├── ExecutorAgent.call()           
│   ├── Step 1: loadData           
│   │   └── duration: 0.3s         
│   ├── Step 2: runDuckdb          
│   │   └── duration: 1.5s         
│   ├── Step 3: runPython (sandbox) 
│   │   ├── AST check: 0.1s        
│   │   ├── Docker exec: 8.2s      
│   │   └── Output validation: 0.05s
│   └── LLM API call (Claude)      
│       ├── duration: 2.1s         
│       └── tokens: 2500 in, 500 out
└── SynthesizerAgent.call()        
    └── LLM API call (DeepSeek)    
        ├── duration: 1.8s         
        └── tokens: 2000 in, 600 out
                                    
Total: 15.75s                      
```

使用 OpenTelemetry + Jaeger/Zipkin 实现。

### 10.3 关键日志

```
级别  场景                          日志内容
INFO  请求开始                      [Agent] start: userId=456, msg="销售额趋势", sessionId=abc
INFO  Planner 完成                  [Planner] plan: intent=trend_analysis, steps=4, duration=1.2s
INFO  每步工具调用                   [Tool] runDuckdb: sql=SELECT..., rows=12, duration=1.5s
INFO  请求完成                      [Agent] done: userId=456, duration=15.7s, tools=5, tokens=4800
WARN  工具重试                      [Tool] retry: runPython, attempt=2/2, reason=TimeoutException
WARN  模型降级                      [Model] fallback: userId=456, userModel=Claude(unhealthy) → DeepSeek
ERROR 工具最终失败                   [Tool] failed: runPython, reason=Memory limit exceeded, step=SKIPPED
ERROR 请求超时                      [Agent] timeout: userId=456, duration=125s
```

### 10.4 告警规则

```
紧急告警（立即通知）：
  - Agent 成功率 < 90% 持续 5 分钟
  - 所有模型不可用
  - Docker 服务宕机

警告告警（工作时间通知）：
  - Agent P95 延迟 > 60s 持续 10 分钟
  - 沙箱调用比例 > 50% 持续 30 分钟
  - Redis 连接池使用率 > 80%

信息告警（日报汇总）：
  - 模型 API 调用量异常波动（> 2x 日均）
  - 文件上传失败率 > 5%
```

---

## 11. 部署与运维

### 11.1 部署架构

```
                    ┌──────────────┐
                    │   Nginx      │
                    │   Reverse    │
                    │   Proxy      │
                    └──────┬───────┘
                           │
              ┌────────────┼────────────┐
              │            │            │
         ┌────▼───┐  ┌────▼───┐  ┌────▼───┐
         │ App-1  │  │ App-2  │  │ App-N  │   ← Spring Boot (无状态)
         │ :8080  │  │ :8080  │  │ :8080  │
         └────┬───┘  └────┬───┘  └────┬───┘
              │            │            │
              └────────────┼────────────┘
                           │
         ┌─────────────────┼─────────────────┐
         │                 │                 │
    ┌────▼────┐      ┌────▼────┐      ┌─────▼─────┐
    │  Redis  │      │  MySQL  │      │ RabbitMQ  │
    │ Cluster │      │  (RDS)  │      │           │
    └─────────┘      └─────────┘      └───────────┘
         │
    ┌────▼────────────────────┐
    │  Docker Host            │
    │  ├── Sandbox Pool       │
    │  │   ├── container-1    │
    │  │   ├── container-2    │
    │  │   └── ...            │
    │  └── Cos Sync (cron)    │
    └─────────────────────────┘
```

### 11.2 沙箱容器池

预创建容器池，复用容器以减少冷启动延迟：

```java
// 容器池配置
SandboxPool:
  minSize: 2           // 最少保持 2 个空闲容器
  maxSize: 10          // 最多 10 个容器
  idleTimeout: 300s    // 空闲 5 分钟后回收
  maxLifetime: 1800s   // 容器最大存活 30 分钟
  
// 借出容器
1. 从池中取空闲容器（< 10ms）
2. 清理容器状态（删除 /tmp 下的文件）
3. 返回给 Executor 使用

// 归还容器
1. 检查容器健康（进程退出码、内存使用）
2. 健康 → 放回池中
3. 不健康 → 销毁重建
```

### 11.3 文件清理策略

```
Parquet 文件:
  对话结束后 24 小时 → 自动删除（标记为待删除）
  用户主动删除对话 → 立即删除

DuckDB 实例:
  每次对话独立 in-memory 数据库
  对话结束自动释放（Java 对象 GC）

Redis 分析状态:
  TTL = 对话 TTL + 1 天（留缓冲）
  对话删除时主动清理

Docker 容器:
  容器池自动管理，最多存活 30 分钟
  系统关闭时 clean shutdown
```

---

## 12. 实施路线图

### 12.1 阶段划分

```
Phase 0: 基础设施准备（1周）
├── DuckDB 集成与 Parquet 转换
├── Docker 沙箱镜像构建
├── RoutingChatModel 实现
└── 模型配置 API（CRUD）

Phase 1: 核心 Agent 重构（2周）
├── AnalysisState 工作记忆实现
├── PlannerAgent + System Prompt
├── ExecutorAgent + 工具裁剪
├── SynthesizerAgent + 输出校验
└── 三层编排逻辑（AgentServiceImpl 重写）

Phase 2: 可靠性增强（1周）
├── AST 代码安全检查
├── 容器池实现
├── 工具调用审计日志
├── 计划验证器
└── 结论质量校验 LLM

Phase 3: 生产就绪（1周）
├── 监控指标采集 + Grafana 面板
├── 告警规则配置
├── 熔断器实现
├── 端到端测试用例
└── 压力测试与调优

Phase 4: 高级功能（按需）
├── 分析报告导出（PDF/HTML）
├── 多文件关联分析
├── 定时分析任务
└── 分析模板市场
```

### 12.2 各阶段交付物

| 阶段 | 可交付的体验 |
|------|-------------|
| Phase 0 | 用户可上传文件并通过 DuckDB SQL 查询数据 |
| Phase 1 | 完整的"对话→分析→图表→结论"流程，三层 Agent 协同工作 |
| Phase 2 | 沙箱安全执行、执行计划可审计、结果可信 |
| Phase 3 | 生产级稳定性、可监控、可运维 |
| Phase 4 | 报告下载、高级分析能力 |

### 12.3 关键风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| DuckDB 在大数据量下性能不足 | 低 | 中 | Parquet 列存 + 采样策略，超大文件提示用户采样 |
| DeepSeek 在 Planner 角色表现不佳 | 中 | 高 | 允许 Planner 使用不同模型；System Prompt 内置场景速查表兜底 |
| Docker 沙箱不稳定（资源泄漏） | 中 | 中 | 容器池 + 最大存活时间 + 健康检查 + 监控告警 |
| 三层 Agent 串行执行延迟过高 | 中 | 中 | Planner 结果缓存；相同意图复用计划；不同阶段用不同速度的模型 |
| 用户自定义模型质量差 | 低 | 中 | 自动降级到系统默认；提供模型推荐列表 |

---

## 附录 A：工具清单完整定义

### A.1 数据工具（ExecutorAgent 持有）

| # | 工具名 | 参数 | 返回 | 超时 |
|---|--------|------|------|------|
| 1 | `loadData` | `fileRef: String` | `{viewName, schema, rowCount}` | 5s |
| 2 | `getSchema` | `fileRef: String` | `{columns: [{name, type, sample, stats}]}` | 3s |
| 3 | `runDuckdb` | `sql: String, outputKey: String` | `{outputKey, rowCount, columns, sample(3 rows)}` | 30s |
| 4 | `runPython` | `code: String, dataRefs: [String], outputKey: String` | `{outputKey, resultType, summary}` | 60s |
| 5 | `getAnalysisState` | 无 | `{files, completedSteps, dataIndex}` | 1s |

### A.2 输出工具（SynthesizerAgent 持有）

| # | 工具名 | 参数 | 返回 | 超时 |
|---|--------|------|------|------|
| 6 | `buildChart` | `type, title, dataRef, config` | `ECharts option JSON` | 5s |
| 7 | `validateChart` | `optionJson: String` | `{valid, issues}` | 2s |
| 8 | `generateReport` | `template, sections, chartRefs` | `{reportUrl, format}` | 30s |

### A.3 辅助工具（两个 Agent 均可访问）

| # | 工具名 | 参数 | 返回 | 超时 |
|---|--------|------|------|------|
| 9 | `savePreference` | `key, value` | `{success}` | 1s |
| 10 | `getPreferences` | 无 | `{prefs}` | 1s |

**总计: 10 个工具**（vs 当前方案的 14 个），每个 Agent 实际可用的更少（Executor 5 个，Synthesizer 3 个，辅助 2 个）。

---

## 附录 B：关键配置项

```yaml
# application.yml — Agent 相关配置
agent:
  # 三层 Agent 专用模型（可选覆盖，不配则使用 routing 模型）
  planner:
    model: deepseek-chat       # Planner 可用便宜模型
    timeout: 15s
  executor:
    model: null                # null = 使用 routing 模型（用户配置）
    timeout: 90s
    max-tool-calls: 15         # 单次对话最大工具调用次数
  synthesizer:
    model: null
    timeout: 30s
    conclusion-validation: true # 是否启用结论质量校验
  
  # 全局超时
  global-timeout-seconds: 120
  
  # 工具超时
  tools:
    duckdb-timeout: 30s
    python-timeout: 60s
    chart-timeout: 5s
  
  # 上下文管理
  context:
    max-history-rounds: 10     # 保留最近 N 轮对话
    analysis-state-inject: true # 是否注入分析状态摘要
  
  # 沙箱
  sandbox:
    enabled: true
    docker-image: python-analysis:3.11
    memory-limit: 512m
    cpu-limit: 1.0
    execution-timeout: 60s
    pool-min-size: 2
    pool-max-size: 10

# 模型路由
model:
  routing:
    default-provider: DEEPSEEK
    fallback-chain: [DEEPSEEK_V4, DEEPSEEK_V3]
    health-check-interval: 30s
    unhealthy-threshold: 3      # 连续失败 N 次标记为 unhealthy
    recovery-interval: 60s
```

---

## 附录 C：与现有方案的迁移路径

```
现有 MySQL 临时表 → Parquet + DuckDB:
  1. 新增 FileConversionService（xlsx/csv → Parquet）
  2. 新增 DuckDBDataSource（管理 DuckDB 连接）
  3. 修改 DataQueryTool → DuckdbQueryTool（SQL 语法基本相同）
  4. 保留 DataParseTool 的 describeTable/preview（改用 DuckDB DESCRIBE）
  5. 旧 MySQL 临时表代码保留 2 周作为回退

现有单 ReactAgent → 三层 Agent:
  1. 新增 PlannerPromptConfig（独立的 agent-planner.txt）
  2. 新增 ExecutorPromptConfig（独立的 agent-executor.txt）  
  3. 新增 SynthesizerPromptConfig（独立的 agent-synthesizer.txt）
  4. 修改 AgentServiceImpl.chatStream() 编排三次调用
  5. 新增 AnalysisState 管理
  6. 旧单 Agent Bean 保留作为降级路径（配置开关切换）

现有 14 工具 → 10 工具:
  移除: DataParseTool（合并到 getSchema + loadData）
  移除: DataQueryTool（合并到 runDuckdb）
  移除: SharedDataTool（AnalysisState.dataIndex 替代）
  保留: AnalysisTool 的计算能力（移到 Python 沙箱的标准库）
  保留: ChartGenTool（重命名为 buildChart + validateChart）
  保留: UserPreferenceTool（重命名为 savePreference + getPreferences）
```

---

> **下一步**：请审阅本方案，确认架构方向后，我们可以进入 Phase 0 的详细实施计划。
