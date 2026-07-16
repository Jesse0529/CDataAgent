# AGENTS.md

向 Codex 提供本项目的开发指引。

## 项目概述

CData Agent 后端服务。自部署单用户应用。用户上传 Excel/CSV，用自然语言描述分析目标，系统生成 ECharts 图表配置和分析结论。

## 技术栈

| 组件 | 版本/说明 |
|------|-----------|
| Spring Boot | 3.2.10 |
| JDK | 17 |
| 构建 | Maven |
| ORM | MyBatis-Plus 3.5.5 |
| 数据库 | **H2 嵌入式**（文件级，零安装） |
| 缓存/锁/数据共享 | Redis + Redisson 3.21.3 |
| AI 框架 | Spring AI + Alibaba Agent Framework 1.1.2.0 |
| AI 模型 | DeepSeek（DeepSeekChatModel），支持 OpenAI/CUSTOM |
| API 文档 | Knife4j 4.5.0 |
| 工具库 | Hutool 5.8.8, EasyExcel 3.1.1, fastjson2 2.0.32 |
| 分析引擎 | DuckDB（嵌入式列存） |
| 图表生成 | echarts-java 1.0.7 |
| 本地缓存 | Caffeine |
| 监控 | Actuator + Micrometer |

## 构建命令

```bash
mvn clean compile
mvn test
mvn test -Dtest=ClassName
mvn clean package -DskipTests
```

## 环境依赖

- **Redis**（必需）— 锁、共享数据、模型配置、偏好、Checkpoint
- **模型配置** — 通过模型配置接口写入 Redis；生产和开发启动均需提供 `MODEL_ENCRYPTION_KEY`，不得写入配置文件或提交到 Git

无需 MySQL、RabbitMQ。数据库为 H2 嵌入式文件，首次启动自动创建。

## 包结构

```
com.AIBI/
├── AgentTool/          # Agent 工具类（数据加载、查询、图表、偏好等）
├── agent/model/        # Agent 模型（ExecutionPlan、AnalysisState）
├── aop/                # 切面（LogInterceptor 请求日志）
├── config/             # 配置（Agent、DeepSeek、ModelManager、Redisson、DuckDB、沙箱）
├── constant/           # 常量（UserContextHolder=固定默认用户 ID）
├── controller/         # REST 接口（Agent、File、Model）
├── exception/          # 异常（BusinessException、GlobalExceptionHandler）
├── job/                # 定时任务（FileCleanupJob）
├── manager/            # Redis 限流、用户偏好
├── mapper/             # MyBatis-Plus Mapper（Conversation、ConversationMessage、DataFile）
├── model/
│   ├── entity/         # 实体（Conversation、ConversationMessage、DataFile）
│   ├── dto/            # 请求对象
│   └── vo/             # 视图对象（ConversationVO、MessageVO、DataFileVO）
├── service/&impl/      # 业务层（AgentService、ConversationService 等）
└── utils/              # SqlUtils 等
```

## 核心架构

### Agent 对话流程（Executor + Synthesizer 双层编排）

```
AgentController → AgentServiceImpl
  ├── Phase 1: ExecutorAgent  — 自主决策，逐步调用工具，结果存入 AnalysisState
  │     ├── DataLoadingTool   — 加载对话绑定文件到分析环境
  │     ├── DuckDbQueryTool   — SQL 查询（支持多文件 JOIN）
  │     ├── PythonRunnerTool  — 可选能力，默认关闭且不注册
  │     └── PreferenceTool    — 用户偏好读写
  │     └── 按展示计划决定是否触发 SynthesizerAgent
  └── Phase 2: SynthesizerAgent — 基于分析结果生成图表和结论
        ├── ChartOutputTool   — echarts-java 构建图表
        └── PreferenceTool
```

**核心设计**：
- **双层 Agent 编排**：Executor 自主决策 + Synthesizer 图表合成，通过 Tool Calling 与展示计划协调
- **H2 直写持久化**：用户消息和 Assistant 回复在对话流中同步写入 H2，无外部 MQ
- **结果索引化**：AnalysisState 仅保存小结果或查询索引；大结果从 Parquet 按需重算，不进入工作记忆
- **Redis Checkpoint**：对话记忆由 RedisSaver 管理，支持多轮对话
- **上下文压缩**：由 SummarizationHook 在 128k 阈值触发；不再叠加滑窗钩子
- **流式输出**：`/apis/agent/chat/stream`，SSE token 级推送
- **运行隔离**：Redisson 全局锁 + 对话锁，使用 watchdog 续租，防止单机异步运行串状态

### 数据表（3 张，H2 嵌入式）

| 表 | 说明 |
|------|------|
| `conversation` | 对话记录 |
| `conversation_message` | 对话消息（user + assistant） |
| `data_file` | 上传文件元数据 |

### 关键机制

| 机制 | 说明 |
|------|------|
| 多轮对话 | `RunnableConfig.threadId`（conversationId） |
| 流式输出 | SSE token 级推送 |
| 工具预算 | Executor 最多 50 次、Synthesizer 最多 20 次；展示计划与图表校验不计入额度 |
| SQL 安全 | 仅允许一条查询语句；大结果精确标记截断，禁止直接用于完整图表或结论 |
| 限流 | `RedisLimiterManager`，固定 key `agent_chat_default` |
| 超时保护 | 全局 120s + 子 Agent 工具级 |
| 重试 | DuckDB SQLException 1 次重试；沙箱仅在显式启用时参与重试 |
| 文件转换 | EasyExcel 流式 → CSV → DuckDB → Parquet；替换按“转换、元数据切换、清理旧文件”执行 |
| 多文件支持 | DuckDB 注册多 Parquet 为 VIEW，SQL 支持 JOIN |
| 文件清理 | FileCleanupJob，每日凌晨 3 点清理孤儿/卡住文件 |
| 模型配置 | Redis `model:config:default`，支持 DEEPSEEK/OPENAI/CUSTOM |

## 代码风格

- **依赖注入**：`@Autowired`
- **Lombok**：实体 `@Data`，类 `@Slf4j`
- **Controller**：`@RestController` + `@RequestMapping("/apis/...")`
- **配置类**：`@Configuration` + `@Bean`
- **API 响应**：`BaseResponse<T>` + `ResultUtils.success()/error()`
- **参数校验**：`ThrowUtils.throwIf(condition, ErrorCode, msg)`
- **异常处理**：`BusinessException` → `GlobalExceptionHandler`
- **注释**：中文、简洁、仅必要处

## 关键细节

- AI Prompt 分隔符：`【【【【【`（前半 ECharts option JSON，后半分析结论）
- 文件上传：≤60MB，仅 xlsx/xls/csv
- 图表生成用 **echarts-java 确定性构建**，非 LLM 手写 JSON
- 提示词文件：`classpath:prompts/*.txt`
- 自部署单用户模式，无登录/注册/权限体系

## 环境配置

- 根配置默认使用 `prod`；本地开发需显式设置 `SPRING_PROFILES_ACTIVE=dev`
- 本地开发配置仅保留在工作区，不提交 `application-dev.yml` 的本地变更
- Docker 默认仅暴露应用的本机回环端口；Redis 不发布宿主机端口，数据使用命名卷
- `.env`、加密密钥、数据目录和日志均不得提交

## 变更纪律

0. **最高优先级**：禁止擅自修改代码。仅当用户明确说"修改/完善/实现/重构/修复/改/写"时才能改；仅提问或查看时只给结论建议，不改代码。
1. 禁止擅自修改配置或依赖（`application-*.yml`、`pom.xml`）
2. 禁止还原或修改用户手动改动
3. 不明确需求先总结提问，不自行假设
4. 发现有任何报错点、隐患都应该在总结中提示用户，禁止自行修改或直接忽略
5. 修改结束后要自行审查，确保结果正确，且符合用户需求
