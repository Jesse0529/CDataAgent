# 单元测试完整规划

## 1. 测试框架选择

| 组件 | 选型 | 说明 |
|------|------|------|
| **测试框架** | JUnit 5 (Jupiter) | spring-boot-starter-test 已自带 |
| **Mock 框架** | Mockito + `@MockBean` | 同上自带，Spring Boot 3.2.10 集成 Mockito 5 |
| **断言** | AssertJ | 同上自带，fluent 断言 `.isEqualTo()` `.contains()` |
| **参数化测试** | `@ParameterizedTest` + `@CsvSource` | 测试多组输入/错误组合 |
| **嵌入式 DB** | H2（已有） | 测试用 DuckDB 内置 in-memory |
| **测试配置** | `src/test/resources/application-test.yml` | **需要创建**，隔离测试环境 |

**不需要引入新依赖** — 所有工具已在 classpath 中。

---

## 2. 测试覆盖目标

共 **6 个被测模块**，预估 40-55 个测试方法：

### 2.1 ToolResultUtils（4 个测试方法）

| 编号 | 测试方法 | 验证点 |
|------|---------|--------|
| TRU-01 | `jsonTypedError` 格式 | `{"error":"syntax","message":"xxx"}` |
| TRU-02 | `jsonError` 包装 | 等价于 jsonTypedError("system", msg) |
| TRU-03 | `isError / isTransientError` | 正确识别 system/timeout/syntax |
| TRU-04 | `jsonValidResult` 单条/多条 | `{"valid":true,"issues":["ok"]}` |

**边界**：null/空字符串输入、多层嵌套的 `"error"` 子串误判

### 2.2 DuckDbQueryService（10-12 个测试方法）

| 编号 | 测试方法 | 验证点 |
|------|---------|--------|
| DQS-01 | 正常 SELECT 返回 JSON 数组 | 结果以 `[{...}]` 格式返回 |
| DQS-02 | 自动追加 LIMIT 1000 | SQL 不含 LIMIT 时追加 |
| DQS-03 | 保留已有 LIMIT | SQL 含 `LIMIT 3` 时不变 |
| DQS-04 | 拒绝 DML 语句 | `INSERT`/`DELETE`/`DROP` → syntax error |
| DQS-05 | 拒绝非 SELECT/DESCRIBE | `ALTER` → syntax error |
| DQS-06 | SQL 语法错误 → syntax type | `SELECT FROM` → `{"error":"syntax",...}` |
| DQS-07 | 列名不存在 → syntax type | `SELECT nonexistent_col` → syntax |
| DQS-08 | 空文件列表 → syntax type | files=null/empty → syntax |
| DQS-09 | 空结果集 → `[]` | WHERE 条件不匹配 |
| DQS-10 | 数值类型转换 | 整数→Long，浮点→Double |
| DQS-11 | SQL 带尾部分号 | `SELECT 1;` → 正常执行 |
| DQS-12 | 连接异常 → system type | 模拟 DuckDB 连接失败 |

**边界**：空字符串 SQL、超长 SQL（>1000 字符）、多文件 JOIN、特殊字符列名

### 2.3 DuckDbQueryTool（10-12 个测试方法）

| 编号 | 测试方法 | 验证点 |
|------|---------|--------|
| DQT-01 | 正常 runDuckdb 流程 | 调用 executeQuery → 存入 analysisState |
| DQT-02 | outputKey 去重 | 同 outputKey → `cached: true` 摘要 |
| DQT-03 | Caffeine 缓存命中 | 同 SQL+files → 不调用 executeQuery |
| DQT-04 | Caffeine 缓存 TTL 过期 | sleep 后 key 失效 |
| DQT-05 | system 错误自动重试 | `isTransientError=true` → 调用两次 executeQuery |
| DQT-06 | syntax 错误不重试 | `isTransientError=false` → 调用一次 |
| DQT-07 | 异常 → addStepResultFailed | catch 块记录失败状态 |
| DQT-08 | 没有已加载文件 → syntax | `{"error":"syntax","message":"..."}` |
| DQT-09 | 正常 queryStatistics | 生成正确 SQL，存入 analysisState |
| DQT-10 | queryStatistics 去重 | 同 columns → 跳过 executeQuery |
| DQT-11 | buildCacheKey 一致性 | 同 refs+sql → 同 hash |
| DQT-12 | buildCacheKey 降级 | SHA-256 不可用时 fallback hashCode |

**边界**：outputKey 为 null、SQL 含特殊字符、跨文件的缓存 key 区分

### 2.4 DataLoadingTool（8 个测试方法）

| 编号 | 测试方法 | 验证点 |
|------|---------|--------|
| DLT-01 | loadData 正常 | 返回文件列表 JSON |
| DLT-02 | loadData 无 activeFileIds 且无已有 | syntax error |
| DLT-03 | loadData 无 activeFileIds 有已有 | 返回已有结果摘要 |
| DLT-04 | loadData 异常 → addStepResultFailed | catch 块记录 |
| DLT-05 | getSchema 正常 | 返回列信息 JSON |
| DLT-06 | getSchema fileRef 无效 | syntax error |
| DLT-07 | getSchema 文件不在 active 范围 | syntax error |
| DLT-08 | getSchema 异常 → addStepResultFailed | catch 块记录 |

**边界**：多个文件加载、conversationId null、columnMeta 为空

### 2.5 ChartOutputTool（6 个测试方法）

| 编号 | 测试方法 | 验证点 |
|------|---------|--------|
| COT-01 | buildChart 正常 | 返回"图表已生成" 存入 chartOptions |
| COT-02 | buildChart 参数校验失败 | 空参数 → syntax error |
| COT-03 | buildChart dataRef 不存在 | syntax error |
| COT-04 | buildChart system 错误自动重试 | 重试一次 |
| COT-05 | validateChart 校验通过 | `{"valid":true}` |
| COT-06 | validateChart 校验失败 | `{"valid":false,"issues":[...]}` |

**边界**：9 种图表类型全覆盖、超过 80 维度的截断

### 2.6 AnalysisState（6 个测试方法）

| 编号 | 测试方法 | 验证点 |
|------|---------|--------|
| AST-01 | 正常 addStepResult | status=SUCCESS，rowCount 正确 |
| AST-02 | addStepResultFailed | status=FAILED，errorMessage 存储 |
| AST-03 | toContextString 成功步骤 | 格式 `工具 → key (N行)` |
| AST-04 | toContextString 失败步骤 | 含 `❌` 前缀 + errorMessage |
| AST-05 | clear | 清除所有 threadId 数据 |
| AST-06 | resetByConversation | 特定 threadId 清除 |

**边界**：超过 MAX_RECENT_STEPS 的截断、多 threadId 隔离

---

## 3. 覆盖率矩阵与缺口

### 3.1 已覆盖的层级
```
ToolResultUtils (格式定义层)        → 全覆盖
DuckDbQueryService (服务层)         → SQL 安全、异常类型分支、边界值全覆盖
DuckDbQueryTool (工具层)            → 缓存、重试、错误追踪全覆盖
DataLoadingTool (工具层)            → 加载流程、错误分支全覆盖
ChartOutputTool (工具层)            → 图表构建、校验、重试全覆盖
AnalysisState (状态管理层)          → 状态追踪、清理、上下文渲染全覆盖
```

### 3.2 未覆盖的缺口及原因

| 缺口 | 原因 | 是否可接受 |
|------|------|-----------|
| **PythonRunnerTool** | 需要 Docker 沙箱 | ✅ 可接受，沙箱不可用是已知约束 |
| **PreferenceTool** | 逻辑极简（读/写 Redis） | ✅ 可接受，不值得单独测试 |
| **Agent 编排流程**（AgentServiceImpl） | 需要真实 LLM API 调用 | ✅ 可接受，集成测试成本过高 |
| **ReactAgent 拦截器链** | 框架 1.1.2.0 的限制 | ✅ 可接受，拦截器已确认是死代码 |
| **Caffeine 缓存 + 文件删除一致性** | 无法模拟文件系统竞争 | ⚠️ 已知风险，TTL 兜底 |

---

## 4. Agent 决策质量评估方法

这是衡量改动是否真正有效的关键。以下从三个维度评估：

### 4.1 Prompt 引导效果（定性）
- **验证方法**：用 `application-test.yml` 配置测试环境，启动后端，通过 curl/httpie 发送分析消息
- **成功标准 A**：故意写错列名（如 `SELECT not_exist`），LLM 返回的错误消息包含 `{"error":"syntax","message":"..."}` 且 LLM 后续调用 `getSchema` 修正
- **成功标准 B**：上传无数据的文件，LLM 返回提示用户上传文件，而非执行空分析
- **成功标准 C**：连续两次同一数据分析，观察 Caffeine 缓存日志 `Caffeine 缓存命中`

### 4.2 错误格式一致性（定量）
- **自动化检查**：遍历 `DuckDbQueryTool`、`DataLoadingTool`、`ChartOutputTool`、`PythonRunnerTool` 所有 error return path
- **断言**：每个 error path 的返回值都符合 `{"error":"type","message":"..."}` 格式（其中 type 为 syntax/timeout/not_found/system 之一）
- **工具**：正则 `/^\{"error":"(syntax|timeout|not_found|system)","message":".*"\}$/`

### 4.3 重试有效性（定量）
- **DuckDB 自动重试**：模拟 `DuckDbQueryService.executeQuery` 首次返回 `{"error":"system","message":"..."}`，第二次返回正常数据。验证 `DuckDbQueryTool.runDuckdb` 返回正常结果而非错误
- **图表自动重试**：模拟 `buildBar` 首次返回错误 JSON 字符串（非抛异常），第二次返回正常 option。验证返回值为成功

### 4.4 不回归验证
- **现有功能不破坏**：正常数据 → 正常分析 → 正常图表，完整链路验证
- **现有测试不破坏**：`MainApplicationTests` 必须通过

---

## 5. 测试基础设施准备

### 5.1 需要创建的文件

```
src/test/resources/
├── application-test.yml           # 测试专用配置（H2文件库 + 本地Redis）
├── test-data/
│   ├── sales.csv                  # 测试用数据（10行）
│   └── sales.parquet              # 对应的 Parquet 文件
└── com/AIBI/
    └── BaseTest.java              # 测试基类
```

### 5.2 application-test.yml 示例（基于已有配置调整）

```yaml
spring:
  datasource:
    url: jdbc:h2:file:/tmp/cdata-test/h2/cdata;MODE=MySQL
  data:
    redis:
      host: localhost
      port: 6379
      password: 123456
      database: 1

duckdb:
  query:
    max-result-rows: 100
    timeout-seconds: 30
    threads: 2
    memory-limit: 512MB

agent:
  global-timeout-seconds: 60
  context:
    inject-analysis-state: true

data:
  file:
    storage-dir: /tmp/cdata-test-files
    max-file-size: 10MB
```

### 5.3 测试基类约定

```java
// 纯单元测试（无 Spring 上下文）
@ExtendWith(MockitoExtension.class)
class DuckDbQueryToolTest { ... }

// 集成分片测试（需要 DuckDB 但不需完整上下文）
class DuckDbQueryServiceTest {
    private DuckDbConfig config = new DuckDbConfig(...);
    private DuckDbQueryService service = new DuckDbQueryService(config);
}

// 完整 Spring 上下文测试（需要 H2 + Redis + Agent 配置）
@SpringBootTest
@ActiveProfiles("test")
class AnalysisStateTest { ... }
```

---

## 6. 实施建议

### 优先级
| 优先级 | 模块 | 预估工时 | 理由 |
|--------|------|---------|------|
| P0 | DuckDbQueryService 测试 | 2h | 核心 SQL 引擎，改动最多 |
| P1 | DuckDbQueryTool 测试 | 2h | 缓存+重试新功能 |
| P2 | ToolResultUtils 测试 | 0.5h | 新创建的公共工具类 |
| P3 | AnalysisState 测试 | 1h | 状态追踪改动 |
| P4 | DataLoadingTool + ChartOutputTool | 1.5h | 模式匹配的重复改动 |

### 能否在 CI 中运行
- 需要 Redis 实例（本地或 Testcontainers）
- DuckDB 完全嵌入式，无需外部进程
- 建议为团队 CI 添加 Testcontainers Redis
