# CData Agent Backend

后端服务 — 自部署单用户应用

**核心能力**：用户上传 Excel/CSV，用自然语言描述分析目标，AI 自主编排分析流程，生成 ECharts 图表配置与分析结论。

---

## 技术栈

| 组件 | 版本/说明 |
|------|-----------|
| 框架 | Spring Boot 3.2.10 |
| 语言 | JDK 17 |
| 构建 | Maven |
| ORM | MyBatis-Plus 3.5.5 |
| 数据库 | **H2 嵌入式**（文件级，零安装） |
| 缓存/锁 | Redis + Redisson 3.21.3 |
| AI 框架 | Spring AI + Alibaba Agent Framework 1.1.2.0 |
| AI 模型 | DeepSeek（DeepSeekChatModel），支持 OpenAI/CUSTOM |
| 分析引擎 | DuckDB（嵌入式列存，多文件 JOIN） |
| 图表生成 | echarts-java 1.0.7（确定性构建，非 LLM 手写 JSON） |
| API 文档 | Knife4j 4.5.0 |
| 工具库 | Hutool 5.8.8, EasyExcel 3.1.1, fastjson2 2.0.32 |
| 本地缓存 | Caffeine |
| 监控 | Actuator + Micrometer |

## 环境依赖

- **Redis**（必需）— 锁、共享数据、模型配置、Checkpoint
- **DeepSeek API Key** — 环境变量 `DEEPSEEK_API_KEY`

> 无需 MySQL、RabbitMQ、Elasticsearch。数据库为 H2 嵌入式文件，首次启动自动创建。

## 快速开始

### 本地开发

```bash
# 1. 复制环境变量配置
cp .env.example .env
# 编辑 .env，填入 DEEPSEEK_API_KEY

# 2. 启动 Redis（本地需安装）
redis-server

# 3. 编译
mvn clean compile

# 4. 运行（默认 dev 配置，自动建表）
mvn spring-boot:run

# 5. 访问 API
# http://localhost:8080
# Knife4j 接口文档: http://localhost:8080/doc.html
```

### Docker 部署

```bash
# 1. 配置环境变量
cp .env.example .env
# 填入真实的 DEEPSEEK_API_KEY

# 2. 启动
chmod +x deploy.sh && ./deploy.sh
```

## 核心架构

### Agent 对话流程（Executor + Synthesizer 双层编排）

```
用户输入 → AgentController → AgentServiceImpl
  ├── Phase 1: ExecutorAgent（自主决策）
  │   ├── DataLoadingTool    — 加载对话绑定文件到分析环境
  │   ├── DuckDbQueryTool    — SQL 查询（支持多文件 JOIN）
  │   ├── PythonRunnerTool   — Docker Python 沙箱（可选）
  │   └── PreferenceTool     — 用户偏好读写
  │   └── 检测 ##NEEDS_CHART## → 触发 Synthesizer
  └── Phase 2: SynthesizerAgent
      ├── ChartOutputTool    — echarts-java 构建图表
      └── PreferenceTool
```

### 关键机制

| 机制 | 说明 |
|------|------|
| 多轮对话 | `RunnableConfig.threadId`（conversationId）+ Redis Checkpoint |
| 流式输出 | SSE token 级推送（`/apis/agent/chat/stream`） |
| 限流 | `RedisLimiterManager`，固定 key `agent_chat_default` |
| 超时保护 | 全局 300s + 子 Agent 工具级超时 |
| 文件转换 | EasyExcel 流式 → CSV → DuckDB → Parquet |
| 多文件分析 | DuckDB 注册多 Parquet 为 VIEW，SQL 支持 JOIN |
| 文件清理 | FileCleanupJob，每日凌晨 3 点清理过期临时文件 |
| Token 精确记录 | TokenRecordingChatModel 拦截器，精确计数每轮消耗 |
| 上下文压缩 | 基于精确 Token 消耗的上下文压缩策略 |
| 持久化 | 消息直写 H2（conversation + conversation_message 表） |

## API 概览

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/apis/agent/chat/stream` | 流式对话（SSE） |
| GET | `/apis/agent/conversations` | 对话列表（分页） |
| GET | `/apis/agent/conversations/{id}/messages` | 消息历史 |
| DELETE | `/apis/agent/conversations/{id}` | 删除对话 |
| POST | `/apis/agent/conversations/{id}/resume` | 恢复历史对话 |
| POST | `/apis/file/upload` | 上传数据文件 |
| GET | `/apis/file/list` | 文件列表 |
| DELETE | `/apis/file/{id}` | 删除文件 |
| GET | `/apis/model/config` | 模型配置查询 |
| POST | `/apis/model/config` | 更新模型配置 |

## 项目结构

```
src/main/java/com/AIBI/
├── AgentTool/          # Agent 工具类（@Tool 注解）
├── agent/model/        # 模型对象（ExecutionPlan、AnalysisState）
├── aop/                # 切面（LogInterceptor 请求日志）
├── config/             # 配置类（Agent、DeepSeek、ModelManager、DuckDB 等）
├── constant/           # 常量定义
├── controller/         # REST 接口
├── exception/          # 异常处理
├── job/                # 定时任务
├── manager/            # Redis 限流、Token 记录、偏好管理
├── mapper/             # MyBatis-Plus Mapper
├── model/              # 实体、DTO、VO
├── service/            # 业务接口与实现
└── utils/              # 工具类
```

## 配置说明

主配置：`src/main/resources/application.yml`

环境配置：
- `application-dev.yml` — 本地开发
- `application-prod.yml` — Docker 生产
- `application-test.yml` — 测试

模型加密密钥通过 `model.encryption.key` 配置（开发环境默认值已在配置中，生产环境务必修改）。

## 构建命令

```bash
mvn clean compile        # 编译
mvn test                 # 运行测试
mvn test -Dtest=ClassName # 运行单个测试
mvn clean package -DskipTests  # 打包
```

## 设计文档

设计文档存放在 `docs/` 目录：

- [功能清单](docs/CDataAgent-功能清单.md) — 完整功能列表与追踪
- [测试计划](docs/test-plan.md) — 测试策略与用例
- [工程化方案](docs/CDataAgent-生产级Agent工程化落地方案.md) — 生产部署架构
- [重构方案](docs/CDataAgent-重构影响范围与实施方案.md) — 重构范围与计划
