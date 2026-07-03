# CData Agent — 重构影响范围与实施方案

> 版本：v1.0 | 日期：2026-06-27 | 基于现有项目 `./`（项目根目录）

本文档对现有项目进行逐文件分析，明确重构的影响范围（修改/删除/新建）、
预期效果（按生产级标准评审）、以及风险与可行性分析。

---

## 目录

- [一、影响范围总览](#一影响范围总览)
- [二、逐文件影响明细](#二逐文件影响明细)
  - [A. 删除的代码（完全废弃）](#a-删除的代码完全废弃)
  - [B. 修改的代码（保留骨架，逻辑重写）](#b-修改的代码保留骨架逻辑重写)
  - [C. 新增的代码](#c-新增的代码)
  - [D. 保留不变的代码](#d-保留不变的代码)
- [三、分阶段实施步骤](#三分阶段实施步骤)
- [四、预期效果评审](#四预期效果评审)
- [五、风险与可行性分析](#五风险与可行性分析)

---

## 一、影响范围总览

```
现有 Java 文件:  68 个（含 src/main + src/test）
现有资源文件:  5 个（prompts + yml）

重构后:
  删除:  10 个 Java 文件 + 2 个资源文件
  修改:  14 个 Java 文件 + 3 个配置文件
  新建:  18 个 Java 文件 + 5 个资源文件
  保留:  44 个 Java 文件（完全不动）
```

---

## 二、逐文件影响明细

### A. 删除的代码（完全废弃）

#### A1. Agent 工具层（5 个文件 → 功能被新工具替代）

| 文件 | 原始职责 | 废弃原因 |
|------|---------|---------|
| `AgentTool/DataParseTool.java` | listAvailableTables / describeTable / previewTableData | 改为 DuckDB `DESCRIBE` + `getSchema()`，不再需要 MySQL 表 |
| `AgentTool/DataQueryTool.java` | queryAggregation / querySample / queryStatistics | 改为 `runDuckdb()`，直接执行 DuckDB SQL |
| `AgentTool/SharedDataTool.java` | getSharedData / getSharedDataMeta | 数据共享改为 `AnalysisState.dataIndex`（内存 + Redis），不再需要 Redis data_key 间接层 |
| `AgentTool/AnalysisTool.java` | computeBasicStats / getValueDistribution / detectTrends / rankByMetric | 分析功能拆分：简单统计用 DuckDB SQL 内置函数，复杂分析用 Python 沙箱。Java 手写统计方法不再需要 |
| `AgentTool/ChartGenTool.java` | buildChart / validateEChartsJson | 功能保留但**完全重写**为 `ChartService`，接口从 `@Tool` 变为普通 Service。表格中标记删除旧的 `@Tool` 版本 |

#### A2. 工具支撑层（3 个文件 → 功能被替代）

| 文件 | 原始职责 | 废弃原因 |
|------|---------|---------|
| `utils/AgentQueryUtils.java` | MySQL 聚合查询 + SQL 校验 | 改为 DuckDB，SQL 语法和连接方式完全不同，重写比重用更清晰 |
| `utils/SharedDataCacheManager.java` | Redis data_key 读写（包装 JSON + 元数据） | 改为 `AnalysisState.dataIndex`，状态管理方式根本不同 |
| `utils/ChartStagingUtils.java` | MySQL 临时表列查询 + CSV 预览 | DuckDB 替代：`DESCRIBE view` 获取列信息，`SELECT * LIMIT 20` 预览。文件级 CSV 预览逻辑移到新的 `FileConversionService` |

#### A3. 实体与 Job（2 个文件 → 不再需要）

| 文件 | 原始职责 | 废弃原因 |
|------|---------|---------|
| `model/entity/TableExpire.java` | MySQL 临时表过期记录实体 | 不再建临时表，不再需要过期记录 |
| `job/TableExpireCleanupJob.java` | 定时清理过期 MySQL 临时表 | 没有临时表需要清理。Parquet 文件由新的 `FileCleanupScheduler` 管理 |

#### A4. Mapper 层（1 个文件 → 表不需要了）

| 文件 | 废弃原因 |
|------|---------|
| `mapper/TableExpireMapper.java` | 不再有 `table_expire` 表 |

#### A5. 资源文件（2 个）

| 文件 | 废弃原因 |
|------|---------|
| `prompts/agent.txt` (197行) | 拆分为 3 个独立 prompt 文件 |
| `mapper/UserMapper.xml` | 检查实际用途后再决定。如果只是通用 CRUD 则无需 XML Mapper |

> **注意**: `TableExpireMapper` 在 `AgentServiceImpl.buildTableContext()` 和 `DataParseTool` 中有引用，这些都会一并修改，不会残留编译错误。

---

### B. 修改的代码（保留骨架，逻辑重写）

#### B1. 核心 Agent 层（重写）

| 文件 | 当前行为 | 修改后 |
|------|---------|--------|
| **`config/AgentConfig.java`** | 创建单 ReactAgent，平铺所有 Tool | 创建 3 个 Agent Bean (plannerAgent / executorAgent / synthesizerAgent)，各自裁剪工具列表 |
| **`config/AgentPromptConfig.java`** | 加载 `agent.txt` 一个 prompt | 加载 3 个 prompt：`agent-planner.txt` / `agent-executor.txt` / `agent-synthesizer.txt` |
| **`service/impl/AgentServiceImpl.java`** | `streamMessages()` 直接调单 Agent，注入表上下文 | 重写 `chatStream()`：① Planner 生成计划 → ② Executor 按计划执行 → ③ Synthesizer 合成输出。新增 `AnalysisState` 管理、计划缓存 |
| **`service/AgentService.java`** | 接口定义 | `chatStream()` 签名不变，内部行为变。新增 `generateReport()` 方法 |
| **`controller/AgentController.java`** | SSE 流式输出，分隔符解析 | SSE 输出逻辑保留，但 `complete` 事件的字段增加 `reportUrl`（可选报告下载链接） |

#### B2. 模型与配置层（改造）

| 文件 | 当前行为 | 修改后 |
|------|---------|--------|
| **`config/DeepSeekConfig.java`** | 手动创建 `DeepSeekChatModel` Bean | 改为创建 `RoutingChatModel` Bean（实现 `ChatModel` 接口），内部按 userId 动态路由。原 `DeepSeekChatModel` 作为 fallback 保留 |
| **`config/DeepSeekProperties.java`** | DeepSeek 配置属性 | 扩展为 `ModelRoutingProperties`：增加 fallback 链、健康检查间隔、降级阈值 |
| **`config/SandboxConfig.java`** | Docker 沙箱 + `PythonRunnerTool` | ① 改为容器池模式（`SandboxPool`）② 增加 AST 安全检查 ③ 增加 `runPython` 工具（仅 ExecutorAgent 持有）④ Python 版本升级到 3.11 + 预装更多分析库 |

#### B3. 文件处理层（扩展）

| 文件 | 当前行为 | 修改后 |
|------|---------|--------|
| **`controller/FileController.java`** | 仅上传头像到 COS | 改为统一的文件上传接口：① 头像上传（保留）② 数据文件上传（xlsx/xls/csv）→ Parquet 转换 → 本地存储 + COS 备份。文件大小限制从 5MB 放宽到 10MB |
| **`controller/TableController.java`** | 管理临时表（列表/删除） | 改为管理已上传的数据文件（列表/删除/预览 schema），不再操作 MySQL 表 |

#### B4. 配置层

| 文件 | 修改内容 |
|------|---------|
| `resources/application.yml` | 新增 `agent:` / `model:` / `sandbox:` / `duckdb:` 配置段 |
| `resources/application-dev.yml` | 新增 dev 环境 DuckDB 路径、沙箱配置 |
| `resources/application-prod.yml` | 新增 prod 环境配置 |
| `resources/application-test.yml` | 新增 test 环境配置 |
| `pom.xml` | 新增依赖: `duckdb_jdbc`, `parquet-avro`, `parquet-hadoop` (shaded), `commons-pool2` |

#### B5. 其他微调

| 文件 | 修改内容 |
|------|---------|
| `manager/UserPreferenceManager.java` | 保持接口不变，内部增加内存缓存（Caffeine）减少 Redis 访问 |
| `model/vo/TableMetadataVO.java` | 字段不变，可能微调 |

---

### C. 新增的代码

#### C1. 数据层 — DuckDB + Parquet（6 个文件）

```
config/DuckDbConfig.java                 # DuckDB 连接工厂（每 session 一个 in-memory 实例）
service/FileConversionService.java       # xlsx/csv → Parquet 转换（类型推断 + 列存写入）
service/DuckDbQueryService.java          # DuckDB 查询执行（SQL 校验 + 结果限制 + JSON 序列化）
model/entity/DataFile.java               # 用户数据文件实体（替代 TableExpire，存到 MySQL + 索引）
mapper/DataFileMapper.java               # MyBatis-Plus Mapper
model/vo/DataFileVO.java                 # 前端展示用的文件 VO
```

#### C2. Agent 层 — 三层 Agent（5 个文件）

```
agent/PlannerAgentConfig.java            # Planner Bean 创建（含 System Prompt 注入 + 工具列表）
agent/ExecutorAgentConfig.java           # Executor Bean 创建（含 5 个数据工具）
agent/SynthesizerAgentConfig.java        # Synthesizer Bean 创建（含 3 个输出工具）
agent/model/ExecutionPlan.java           # 执行计划结构体（intent / steps / dataRefs）
agent/model/AnalysisState.java           # 分析状态管理器（Redis Hash + 内存缓存）
```

#### C3. 工具层 — 重构后的 10 个工具（5 个文件）

```
AgentTool/DataLoadingTool.java           # loadData / getSchema / getAnalysisState（3 个 @Tool）
AgentTool/DuckDbQueryTool.java           # runDuckdb（1 个 @Tool，替换 DataQueryTool）
AgentTool/PythonRunnerTool.java          # runPython（1 个 @Tool，含 AST 检查 + 沙箱调用）
AgentTool/ChartOutputTool.java           # buildChart / validateChart（2 个 @Tool，重写自 ChartGenTool）
AgentTool/PreferenceTool.java            # savePreference / getPreferences（2 个 @Tool，重写自 UserPreferenceTool）
```

> 工具从 14 个（6 个文件）精简到 10 个（5 个文件），每个 Agent 只看到其职责范围内的工具（Executor 5 个，Synthesizer 3 个，辅助 2 个）

#### C4. 模型层（2 个文件）

```
config/ModelRoutingProperties.java       # 模型路由配置属性
model/RoutingChatModel.java              # 实现 ChatModel，按 userId → 用户自定义模型 / 系统默认
```

#### C5. 沙箱与安全（3 个文件）

```
sandbox/SandboxPool.java                 # Docker 容器池（预创建/借出/归还/健康检查）
sandbox/PythonCodeValidator.java         # Python 代码 AST 静态检查（禁止导入/无限循环/系统调用）
sandbox/SandboxHealthChecker.java        # 容器健康检查（内存/CPU/响应探活）
```

#### C6. 质量保证（2 个文件）

```
quality/ChartValidator.java              # 图表数据一致性校验（数值范围/系列完整性/类型匹配）
quality/ConclusionValidator.java         # 分析结论校验（数值引用/趋势一致性/完整性），使用轻量 LLM
```

#### C7. 报告生成（2 个文件）

```
service/ReportGenerationService.java     # 报告构建（模板引擎 → Markdown → HTML/PDF）
controller/ReportController.java         # 报告下载 API（/apis/report/{reportId}/download）
```

#### C8. 监控与健康检查（2 个文件）

```
monitor/AgentMetricsCollector.java       # Micrometer 指标采集（工具调用耗时/成功率/模型延迟）
config/HealthIndicatorConfig.java        # Agent 健康端点（模型可用性/沙箱池状态/DuckDB 状态）
```

#### C9. 资源文件 — Prompts（3 个文件）

```
resources/prompts/agent-planner.txt      # Planner System Prompt（~50行，意图分类+场景匹配+计划格式）
resources/prompts/agent-executor.txt     # Executor System Prompt（~40行，执行纪律+工具使用+失败策略）
resources/prompts/agent-synthesizer.txt  # Synthesizer System Prompt（~35行，输出格式+图表选择+结论规范）
```

#### C10. 资源文件 — SQL（1 个文件）

```
resources/sql/v2_migration.sql           # 迁移 SQL：① 新增 data_file 表 ② 新增 user_model_config 表
                                         # ③ 删除 table_expire 表（数据迁移后） ④ 清理旧 Redis key
```

#### C11. 测试文件（2 个文件）

```
test/.../agent/AgentIntegrationTest.java       # Agent 端到端集成测试（RFM/趋势/构成 3 个场景）
test/.../sandbox/PythonCodeValidatorTest.java  # Python 代码安全检查单元测试
```

---

### D. 保留不变的代码

以下文件**完全不动**，因为它们的职责与本次重构无关：

```
配置类 (不变):
  config/CorsConfig.java              # CORS 跨域
  config/CosClientConfig.java         # COS 对象存储
  config/JsonConfig.java              # JSON 序列化
  config/Knife4jConfig.java           # API 文档
  config/LoginMvcConfig.java          # 登录拦截器注册
  config/MyBatisPlusConfig.java       # MyBatis-Plus
  config/PasswordEncoderConfig.java   # 密码加密
  config/RabbitConfig.java            # RabbitMQ（消息持久化）
  config/RedissonConfig.java          # Redisson
  config/RedissonProperties.java      # Redisson 属性

基础设施 (不变):
  config/TtlRedisSaver.java           # Checkpoint TTL 装饰器
  manager/CosManager.java             # COS 上传管理
  manager/RedisLimiterManager.java    # Redis 限流
  mq/ConversationPersistConsumer.java # 对话消息异步持久化

业务层 (不变):
  service/UserService.java            # 用户服务接口
  service/impl/UserServiceImpl.java   # 用户服务实现
  service/ConversationService.java    # 对话服务接口
  service/impl/ConversationServiceImpl.java
  service/ConversationMessageService.java
  service/impl/ConversationMessageServiceImpl.java
  controller/UserController.java      # 用户 API

实体与 Mapper (不变):
  model/entity/User.java
  model/entity/Conversation.java
  model/entity/ConversationMessage.java
  mapper/UserMapper.java
  mapper/ConversationMapper.java
  mapper/ConversationMessageMapper.java
  model/dto/user/*.java               # 用户 DTO（4 个）
  model/vo/UserVO.java
  model/vo/LoginUserVO.java
  model/vo/ConversationVO.java
  model/vo/MessageVO.java

通用层 (不变):
  common/*.java                       # BaseResponse, ErrorCode, ResultUtils, PageRequest, DeleteRequest
  exception/*.java                    # BusinessException, GlobalExceptionHandler, ThrowUtils
  constant/*.java                     # CommonConstant, UserConstant, UserContextHolder
  interceptor/LoginInterceptor.java
  aop/LogInterceptor.java
  utils/SqlUtils.java                 # 保留（如果只用于用户/对话查询）
  utils/ToolCacheManager.java         # 保留，缓存确定性工具调用结果
  MainApplication.java
```

---

## 三、分阶段实施步骤

### Phase 0: 基础设施（1 周，可独立验证）

```
第1步: 依赖与配置
  ├── pom.xml: 添加 duckdb_jdbc, parquet-avro, commons-pool2
  ├── application-*.yml: 添加 duckdb/sandbox/model 配置段
  └── 验证: mvn clean compile（依赖无冲突）

第2步: 数据层
  ├── 新建 DataFile 实体 + Mapper + VO
  ├── 新建 sql/v2_migration.sql
  ├── 新建 FileConversionService (xlsx→Parquet)
  ├── 新建 DuckDbConfig + DuckDbQueryService
  ├── 修改 FileController（增加数据文件上传）
  ├── 修改 TableController（改为数据文件管理）
  └── 验证: 上传文件 → Parquet 生成 → DuckDB 查询（单元测试）

第3步: 模型路由层
  ├── 新建 ModelRoutingProperties
  ├── 新建 RoutingChatModel
  ├── 修改 DeepSeekConfig → RoutingChatModel Bean
  ├── 修改 DeepSeekProperties → ModelRoutingProperties
  └── 验证: 不同用户配置不同模型 → 请求路由正确
```

### Phase 1: Agent 重构（2 周）

```
第4步: Prompt 准备
  ├── 新建 agent-planner.txt / agent-executor.txt / agent-synthesizer.txt
  ├── 修改 AgentPromptConfig（加载 3 个 prompt）
  └── 验证: 加载不报错

第5步: 工具层重构
  ├── 新建 DataLoadingTool（loadData / getSchema / getAnalysisState）
  ├── 新建 DuckDbQueryTool（runDuckdb）
  ├── 新建 PythonRunnerTool（runPython + AST 检查）
  ├── 新建 ChartOutputTool（buildChart / validateChart，重写自 ChartGenTool）
  ├── 新建 PreferenceTool（savePreference / getPreferences，重写自 UserPreferenceTool）
  ├── 删除 DataParseTool / DataQueryTool / SharedDataTool / AnalysisTool / 旧 ChartGenTool
  └── 验证: 每个工具单独测试

第6步: 三层 Agent 配置
  ├── 新建 PlannerAgentConfig（无工具，纯规划）
  ├── 新建 ExecutorAgentConfig（5 个数据工具）
  ├── 新建 SynthesizerAgentConfig（3 个输出工具）
  ├── 新建 ExecutionPlan + AnalysisState 模型
  ├── 修改 AgentConfig.java（3 个 Bean，替代原单 Agent Bean）
  └── 验证: 编译通过

第7步: 编排逻辑
  ├── 修改 AgentServiceImpl.chatStream()（三段式编排）
  ├── 修改 AgentService 接口（增加 generateReport）
  ├── 修改 AgentController（增加 reportUrl 字段 + 报告下载 API）
  └── 验证: 端到端集成测试（RFM/趋势/构成）
```

### Phase 2: 生产就绪（1 周）

```
第8步: 沙箱增强
  ├── 新建 SandboxPool（容器池）
  ├── 新建 PythonCodeValidator（AST 检查）
  ├── 新建 SandboxHealthChecker
  ├── 修改 SandboxConfig（池模式）
  └── 验证: 容器池借出/归还/回收 + AST 拒绝危险代码

第9步: 质量保证
  ├── 新建 ChartValidator（数据一致性校验）
  ├── 新建 ConclusionValidator（结论幻觉检测）
  └── 验证: 故意构造异常数据 → 校验捕获

第10步: 监控与报告
  ├── 新建 AgentMetricsCollector
  ├── 新建 HealthIndicatorConfig
  ├── 新建 ReportGenerationService + ReportController
  └── 验证: 指标采集 → Grafana 面板 + 报告下载

第11步: 清理
  ├── 删除 TableExpire + TableExpireMapper + TableExpireCleanupJob
  ├── 删除 AgentQueryUtils / SharedDataCacheManager / ChartStagingUtils
  ├── 删除旧 @Tool 类（已在第5步标记）
  ├── sql/v2_migration.sql 正式执行
  └── 验证: 编译无误 + 旧代码无引用
```

---

## 四、预期效果评审

按生产级数据分析 Agent 的维度进行评审：

### 4.1 可靠性（Reliability）

| 维度 | 当前状态 | 重构后 | 改进 |
|------|---------|--------|------|
| 单次对话成功率 | ~80%（常见：沙箱超时/SQL报错/SummarizationHook 后迷失） | 目标 ≥ 95% | 三层 Agent 各司其职，不会"试探性调用" |
| 工具调用次数 | P95 = 14 次（含 7 次重复） | P95 ≤ 6 次（计划明确、无重复） | 执行计划提前确定工具链，不边做边想 |
| 沙箱滥用率 | ~35% 的工具调用走了沙箱 | 目标 ≤ 15% | DuckDB 覆盖 80% 场景，沙箱用于真正的复杂分析 |
| 死循环/超时 | 偶发（SummarizationHook → 失忆 → 重复 → 永远跑不完） | 硬超时 + 熔断 + AnalysisState 防重复 | 多层纵深防护 |
| 模型不可用 | 无降级，直接报错 | 自动降级链（用户模型→主模型→备模型→友好提示） | 用户无感知切换 |

### 4.2 数据准确性（Data Integrity）

| 维度 | 当前状态 | 重构后 | 改进 |
|------|---------|--------|------|
| 数据类型保留 | MySQL 临时表可能丢失日期格式、数值精度 | Parquet 列存保留原始类型 | 不再有 "2024-01-15 变字符串" |
| 分析结果可验证 | 无法回溯（数据在临时表，过期即删） | Parquet 文件 + AnalysisState 完整记录 | 每步结果可复现 |
| 结论幻觉率 | 偶发（LLM 编造数值） | ConclusionValidator 后置校验 | 引用数值与原始数据交叉比对 |
| 图表数据一致性 | 无校验 | ChartValidator 自动校验（范围/系列/类型） | 图表必须与数据一致 |

### 4.3 可维护性（Maintainability）

| 维度 | 当前状态 | 重构后 | 改进 |
|------|---------|--------|------|
| Prompt 复杂度 | 1 个 197 行单体 prompt | 3 个独立 prompt（50+40+35 行） | 每个 prompt 职责单一，修改不互相影响 |
| 工具数量 | 14 个工具平铺给 1 个 Agent | 10 个工具分属 3 个 Agent（5+3+2） | LLM 决策空间大幅缩小 |
| 代码耦合 | Tool 直接依赖 MySQL + Redis data_key | Tool → Service 层抽象 → DuckDB/Redis | 替换组件不影响 Tool 接口 |
| 测试覆盖 | 仅 2 个测试类 | 新增 3 个集成测试 + 工具单元测试 | 核心路径有覆盖 |

### 4.4 用户体验（User Experience）

| 维度 | 当前状态 | 重构后 | 改进 |
|------|---------|--------|------|
| 首次响应时间 | ~3-5s（Agent 启动 + SummarizationHook 初始化） | ~1-2s（Planner 快速生成计划后即可推送状态） | 用户更快看到反馈 |
| 分析耗时 | 30-90s（含沙箱反复调用） | 15-40s（DuckDB 毫秒级 + 沙箱仅在必要时） | 大部分查询秒级完成 |
| 多轮对话记忆 | SummarizationHook 压缩后可能失忆 | AnalysisState 跨轮保持结构化的分析状态 | 追问不会"从头再来" |
| 文件重复上传 | 跨对话需要重新上传 | 文件绑定用户，跨对话复用 | 不需要反复上传 |
| 模型自定义 | 不支持，硬编码 DeepSeek | 用户可配置任意 OpenAI 兼容模型 | 灵活性大幅提升 |
| 报告下载 | 不支持 | 一键生成 Markdown/HTML/PDF 报告 | 分析结果可离线使用 |

### 4.5 可观测性（Observability）

| 维度 | 当前状态 | 重构后 | 改进 |
|------|---------|--------|------|
| 工具调用追踪 | 仅 SLF4J 日志 | 结构化审计日志 + OpenTelemetry | 可追踪每次调用的参数/结果/耗时 |
| 告警 | 无 | 成功率/延迟/沙箱比例 实时告警 | 故障快速响应 |
| 模型使用 | 无统计 | 按用户/按模型的调用量和延迟分布 | 成本优化有数据支撑 |

---

## 五、风险与可行性分析

### 5.1 技术风险

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| **DuckDB 大文件性能** | 低 | 中 | Parquet 列存 + Snappy 压缩（100MB xlsx → ~20MB parquet）；10 万行以上提示用户采样 |
| **DuckDB JDBC 与 Spring Boot 3.2 兼容性** | 低 | 中 | DuckDB 有官方 JDBC 驱动；需验证版本兼容；如不兼容则降到 DuckDB CLI + Java ProcessBuilder |
| **Docker 沙箱资源泄漏** | 中 | 中 | 容器池 + maxLifetime 30 分钟 + 资源限制 + 监控；<br>如果 Docker 不可用（Windows 开发环境），降级为 `ProcessBuilder` 本地 Python 执行 |
| **三层 Agent 串行延迟累加** | 中 | 中 | Planner 结果缓存（相同意图复用计划，30 分钟 TTL）；<br>Executor 和 Synthesizer 阶段可并行（图表构建不依赖结论撰写） |
| **DeepSeek 在 Planner 角色表现不佳** | 中 | 高 | System Prompt 内置**场景速查表**作为兜底（即使 LLM 推理弱，也能根据关键词匹配场景）；<br>允许 Planner 使用不同的模型 |
| **Python 沙箱在 Windows 开发环境不可用** | 高 | 低 | `@ConditionalOnProperty("sandbox.enabled")` 可选启用；<br>开发环境默认关闭沙箱，DuckDB 覆盖 80% 场景 |
| **用户自定义模型质量差（低配模型）** | 中 | 低 | 模型推荐列表 + 健康检查自动降级；<br>关键路径（Executor）始终可用系统主模型 |

### 5.2 迁移风险

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| **现有对话数据兼容** | 低 | 高 | 消息持久化格式不变（conversation_message 表结构不动）；<br>旧 checkpoint（RedisSaver）格式不变；<br>新旧版本对话可共存 |
| **数据丢失** | 低 | 致命 | MySQL 临时表数据迁移到 Parquet 文件后再删除表；<br>file_expire 表仅清理、不删除历史记录（保留 30 天）；<br>COS 备份始终保留 |
| **API 兼容** | 低 | 中 | `/apis/agent/chat/stream` 接口签名不变（参数和 SSE 格式完全兼容）；<br>新增接口用新路径（`/apis/report/`），不冲突 |
| **前端适配** | 中 | 高 | `complete` 事件增加 `reportUrl` 字段（可选），前端增量适配，不破坏现有逻辑；<br>旧字段 `chartOption` 和 `analysis` 保持不变 |

### 5.3 人员与进度风险

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|---------|------|
| **工期超预期** | 中 | 中 | 分阶段交付（Phase 0 即可验证 DuckDB + Parquet，Phase 1 即可验证三层 Agent）；<br>每阶段独立可上线，不阻塞 |
| **DuckDB 学习成本** | 低 | 低 | SQL 语法与 MySQL 高度兼容；<br>核心差异仅在建表（文件虚拟表 vs CREATE TABLE）和统计函数（新增） |
| **回退复杂度** | 低 | 低 | 保留旧代码的分支；AgentConfig 中保留旧单 Agent Bean（通过 `@ConditionalOnProperty` 切换）；<br>回退成本 < 1 小时 |

### 5.4 可行性总结

```
技术可行性: ★★★★☆ (4/5)
  ✅ DuckDB JDBC 成熟稳定，Spring AI Alibaba Agent Framework API 清晰
  ⚠️ Docker 沙箱在 Windows 开发环境受限（有条件降级）
  ⚠️ 三层 Agent 编排逻辑需仔细设计（无现成参考，属于首创）

兼容性: ★★★★★ (5/5)
  ✅ API 接口完全兼容，前端最小改动
  ✅ 数据库表结构增量变更，可回滚
  ✅ 消息持久化格式不变

团队能力: ★★★★☆ (4/5)
  ✅ 已有 Spring AI Alibaba Agent Framework 实战经验
  ✅ 已有 Docker + Python 沙箱集成经验
  ⚠️ DuckDB + Parquet 需学习（约 2-3 天）

综合: 可行，建议分阶段实施，每阶段独立可交付
```

---

## 附录：文件变更速查表

```
                        删除 (10 Java + 2 资源)
                        =====================
AgentTool/DataParseTool.java          → 功能并入 DataLoadingTool + DuckDB
AgentTool/DataQueryTool.java          → 功能并入 DuckDbQueryTool
AgentTool/SharedDataTool.java         → 功能并入 AnalysisState.dataIndex
AgentTool/AnalysisTool.java           → 统计功能并入 DuckDB + Python
AgentTool/ChartGenTool.java           → 重写为 ChartOutputTool
utils/AgentQueryUtils.java            → DuckDB 原生查询
utils/SharedDataCacheManager.java     → AnalysisState 替代
utils/ChartStagingUtils.java          → FileConversionService 替代
model/entity/TableExpire.java         → DataFile 实体替代
job/TableExpireCleanupJob.java        → FileCleanupScheduler 替代
mapper/TableExpireMapper.java         → DataFileMapper 替代
prompts/agent.txt                     → 拆为 3 个 prompt

                        修改 (14 Java + 3 配置)
                        =====================
config/AgentConfig.java               → 3 个 Agent Bean
config/AgentPromptConfig.java         → 3 个 prompt 加载
config/DeepSeekConfig.java            → RoutingChatModel Bean
config/DeepSeekProperties.java        → ModelRoutingProperties
config/SandboxConfig.java             → 容器池模式
service/AgentService.java             → 增加 generateReport()
service/impl/AgentServiceImpl.java    → 三段式编排
controller/AgentController.java       → 增加 reportUrl
controller/FileController.java        → 增加数据文件上传
controller/TableController.java       → 改为文件管理
manager/UserPreferenceManager.java    → 增加 Caffeine 缓存
model/vo/TableMetadataVO.java         → 字段微调
model/entity/User.java                → 新增 modelConfig 关联(可选)
pom.xml                               → 新增 DuckDB/Parquet 依赖
application.yml                       → 新增 agent/model/sandbox 配置
application-{dev,prod,test}.yml      → 环境差异化配置

                        新建 (18 Java + 5 资源)
                        =====================
config/DuckDbConfig.java
config/ModelRoutingProperties.java
config/HealthIndicatorConfig.java
service/FileConversionService.java
service/DuckDbQueryService.java
service/ReportGenerationService.java
model/entity/DataFile.java
model/vo/DataFileVO.java
mapper/DataFileMapper.java
model/RoutingChatModel.java
agent/PlannerAgentConfig.java
agent/ExecutorAgentConfig.java
agent/SynthesizerAgentConfig.java
agent/model/ExecutionPlan.java
agent/model/AnalysisState.java
sandbox/SandboxPool.java
sandbox/PythonCodeValidator.java
sandbox/SandboxHealthChecker.java
quality/ChartValidator.java
quality/ConclusionValidator.java
monitor/AgentMetricsCollector.java
AgentTool/DataLoadingTool.java
AgentTool/DuckDbQueryTool.java
AgentTool/PythonRunnerTool.java
AgentTool/ChartOutputTool.java
AgentTool/PreferenceTool.java
controller/ReportController.java
resources/prompts/agent-planner.txt
resources/prompts/agent-executor.txt
resources/prompts/agent-synthesizer.txt
resources/sql/v2_migration.sql
test/.../agent/AgentIntegrationTest.java
test/.../sandbox/PythonCodeValidatorTest.java
```

---

> **关键决策点需要你确认**：
> 1. Docker 沙箱在 Windows 开发环境的降级方案（ProcessBuilder 本地 Python vs 仅 DuckDB 模式）？
> 2. 三层 Agent 是合并在一个 `AgentConfig` 文件中还是拆分到 3 个独立配置类？
> 3. Parquet 文件存储：仅本地磁盘 vs 本地 + COS 双写？
> 4. 是否保留旧单 Agent 作为降级开关（`@ConditionalOnProperty`）？
