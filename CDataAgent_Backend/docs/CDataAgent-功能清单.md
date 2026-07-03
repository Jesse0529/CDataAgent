# CData Agent — 功能清单与迭代路线

> 版本：v1.1 · 日期：2026-07-02 · 基于 `dev6.0` 分支代码状态
>
> 状态标识：✅ 已完成 · 🔶 骨架就绪待补全 · ⬜ 未开始 · 🔧 有 Bug/待修复 · ❌ 已废弃/移除

---

## 0. 基础设施与运维（DevOps）

| # | 功能点 | 文件/位置 | 状态 |
|---|--------|----------|------|
| 0.1 | Docker Compose 部署（Redis + App） | `docker-compose.yml` | 🔶 端口映射与沙箱 volume 已配置，尚缺沙箱镜像 |
| 0.2 | Dockerfile 多阶段构建 | `Dockerfile` | ✅ 完成 |
| 0.3 | 数据库建表脚本（H2 初始化） | `sql/create_table.sql` | 🔶 尚含已废弃的 chart/table_expire 表，需更新 |
| 0.4 | 建表脚本 — 新表同步 | `sql/` | ⬜ 缺少 v2 迁移 SQL |
| 0.5 | 环境差异化配置 (dev/prod/test) | `application-*.yml` | ✅ 完成（MySQL/RabbitMQ 依赖已移除） |
| 0.6 | .env API Key 管理 | `.env.example` | 🔶 只有 DeepSeek API Key，缺少多模型配置模板 |
| 0.7 | Actuator 健康检查端点 | `application.yml` | 🔶 依赖已加，但 HealthIndicator Bean 未实现 |
| 0.8 | deploy.sh 部署脚本 | `deploy.sh` | ✅ 已有 |
| 0.9 | Sandbox Docker 镜像构建 (python-analysis:3.11) | Dockerfile (sandbox) | ⬜ 缺少沙箱镜像的 Dockerfile |

---

## 1. 数据层（Data Layer）

| # | 功能点 | 文件/位置 | 状态 |
|---|--------|----------|------|
| 1.1 | DuckDB 连接工厂（in-memory + 线程配置） | `config/DuckDbConfig.java` | ✅ 完成 |
| 1.2 | 文件上传接口（xlsx/xls/csv → Parquet） | `controller/FileController.java` | 🔶 上传已完成，xlsx→CSV 转 EasyExcel 流式实现，DuckDB spatial 扩展未使用 |
| 1.3 | Parquet 转换服务 | `service/FileConversionService.java` | ✅ EasyExcel 流式 + DuckDB read_csv_auto → Parquet |
| 1.4 | DuckDB SQL 查询服务（安全校验 + 结果限制） | `service/DuckDbQueryService.java` | ✅ 完成（含白名单关键字校验、多语句拦截、危险模式过滤） |
| 1.5 | 数据文件实体与 Mapper | `model/entity/DataFile.java` + `mapper/` | ✅ 完成 |
| 1.6 | 数据文件列表/删除 API | `controller/FileController.java` | ✅ 完成 |
| 1.7 | 文件存储目录自动创建与按日期隔离 | `FileConversionService` | ✅ 按 dateDir 隔离 |
| 1.8 | 文件过期自动清理 | `job/FileCleanupJob.java` | ✅ 每日凌晨 3 点清理孤儿 Parquet |
| 1.9 | 大数据文件采样策略（>10万行降采样） | `FileConversionService` | ⬜ 未实现 |
| 1.10 | 多表关联查询支持（同一对话多个文件同时注册 JOIN） | `DuckDbConfig` | ✅ 通过 `createConnection(List<FileRef>)` 实现，SQL 中用 viewName 引用 |
| 1.11 | CSV 编码自动检测（UTF-8/GBK） | `FileConversionService.tryReadCsvAuto()` | ✅ UTF-8 → GBK 两级回退已实现 |

---

## 2. Agent 核心（Agent Core）

| # | 功能点 | 文件/位置 | 状态 |
|---|--------|----------|------|
| 2.1 | ExecutorAgent — 自主调用工具执行分析 | `config/AgentConfig.java` | ✅ 完成（含 5 个数据工具，ToolRetryInterceptor） |
| 2.2 | SynthesizerAgent — 结果合成 + 图表 + 结论 | `config/AgentConfig.java` | ✅ 完成（含 buildChart + validateChart） |
| 2.3 | 两层编排（Executor → Synthesizer） | `service/impl/AgentServiceImpl.java` | ✅ Executor 自主决策 → 检测 `##NEEDS_CHART##` 触发 Synthesizer |
| 2.4 | Executor Prompt — 执行纪律 | `prompts/agent-executor.txt` | ✅ 完成（含场景判断、四步流程、输出规范、安全边界） |
| 2.5 | Synthesizer Prompt — 输出规范 | `prompts/agent-synthesizer.txt` | ✅ 完成（含图表类型选择、错误处理、结论规范） |
| 2.6 | 对话记忆 — RedisSaver Checkpoint | `config/AgentConfig.java` | 🔶 TtlRedisSaver 装饰器已有（TTL 3 天），实际多轮对话效果需测试 |
| 2.7 | 上下文管理 — SummarizationHook | `config/AgentConfig.java` | 🔶 阈值 20K tokens，messagesToKeep=6，实际触发效果需测试 |
| 2.8 | 对话并发控制（分布式锁防并发消息） | `AgentServiceImpl.chatStream()` | ✅ Redisson RLock 实现（等5s/租期350s） |
| 2.9 | Agent 调用次数计数器（防无限 Tool Calling） | `config/AgentConfig.java` | ⬜ executor.max-tool-calls 配置未在代码中强制执行 |

---

## 3. 工具层（Tools）

### 3.1 数据加载工具

| # | 功能点 | 文件 | 状态 |
|---|--------|------|------|
| 3.1.1 | `loadData` — 加载文件到分析环境 | `AgentTool/DataLoadingTool.java` | ✅ 完成 |
| 3.1.2 | `getSchema` — 获取表结构（列名/类型/采样） | `AgentTool/DataLoadingTool.java` | ✅ 完成 |
| 3.1.3 | `getAnalysisState` — 查看当前分析进度 | `AgentTool/DataLoadingTool.java` | ✅ 完成 |

### 3.2 查询工具

| # | 功能点 | 文件 | 状态 |
|---|--------|------|------|
| 3.2.1 | `runDuckdb` — SQL 查询（SELECT only） | `AgentTool/DuckDbQueryTool.java` | ✅ 完成（含 Caffeine 缓存 + 回合内去重 + TableEvent） |
| 3.2.2 | `queryStatistics` — 批量数值统计（AVG/STDDEV/PERCENTILE） | `AgentTool/DuckDbQueryTool.java` | ✅ 完成 |
| 3.2.3 | SQL 安全校验（禁 DDL/多语句/危险函数） | `DuckDbQueryService.validateSql()` | ✅ 白名单校验 + 前导注释剥离 + 多语句拦截 + 危险模式过滤 |

### 3.3 沙箱工具

| # | 功能点 | 文件 | 状态 |
|---|--------|------|------|
| 3.3.1 | `runPython` — Docker 沙箱执行 Python | `AgentTool/PythonRunnerTool.java` | 🔶 委托给框架 ToolCallback，实际效果需 Linux+Docker 验证 |
| 3.3.2 | Python 代码 AST 安全检查 | `PythonRunnerTool.validateCode()` | 🔶 做了 import 和模式检查，但覆盖不够全面 |
| 3.3.3 | 沙箱容器池 | `config/SandboxConfig.java` | 🔶 池逻辑已写，但框架 SandboxService 本身管理容器，池可能多余 |
| 3.3.4 | 沙箱不可用时自动降级提示 | `PythonRunnerTool` | ✅ 完成 |
| 3.3.5 | Python 输出大小限制（100KB） | 无 | ⬜ 配置加了 output-max-size 但未在代码中使用 |

### 3.4 图表工具

| # | 功能点 | 文件 | 状态 |
|---|--------|------|------|
| 3.4.1 | `buildChart` — 9 种图表类型 (bar/line/area/pie/scatter/radar/funnel/gauge/heatmap) | `AgentTool/ChartOutputTool.java` | ✅ 完成 |
| 3.4.2 | `validateChart` — ECharts 结构校验 | `AgentTool/ChartOutputTool.java` | ✅ 完成（含业务含义系列名校验） |
| 3.4.3 | 图表数据一致性校验（数值范围/类型匹配） | 无 | ⬜ 缺少 ChartValidator（独立于 Agent 的后置校验） |

### 3.5 偏好工具

| # | 功能点 | 文件 | 状态 |
|---|--------|------|------|
| 3.5.1 | `getPreferences` — 读取用户偏好 | `AgentTool/PreferenceTool.java` | ✅ 完成 |
| 3.5.2 | `savePreference` — 保存用户偏好 | `AgentTool/PreferenceTool.java` | ✅ 完成 |
| 3.5.3 | 偏好自动注入到 Agent 上下文 | `AgentServiceImpl.injectContext()` | ✅ 完成 |

---

## 4. 模型管理层（Model Management）

| # | 功能点 | 文件/位置 | 状态 |
|---|--------|----------|------|
| 4.1 | 系统默认模型配置 (DeepSeek) | `config/DeepSeekConfig.java` | ✅ 完成 |
| 4.2 | UserModelConfig — 用户自定义模型实体 | `model/entity/UserModelConfig.java` | ✅ 实体已建 |
| 4.3 | 用户模型配置 CRUD API | 无 | ⬜ 缺少 `/apis/user/model-config` 接口 |
| 4.4 | API Key AES 加密存储 | 无 | ⬜ 实体注释写了但未实现加密 |
| 4.5 | 模型差异化使用（Executor 当前使用全局 ChatModel） | `application-dev.yml` | 🔶 model-override 配置已加但 AgentConfig 未读取使用 |
| 4.6 | Token 用量统计与限制 | 无 | ⬜ conversation_message 有 tokenUsage 字段但未写入 |

---

## 5. 分析状态管理（Analysis State）

| # | 功能点 | 文件 | 状态 |
|---|--------|------|------|
| 5.1 | AnalysisState — 内存工作记忆 | `agent/model/AnalysisState.java` | ✅ 完成 |
| 5.2 | 分析状态自动注入到 Agent 上下文（~200 token 摘要） | `AgentServiceImpl.injectContext()` | ✅ 完成 |
| 5.3 | 状态持久化到 Redis（跨请求保持） | 无 | ⬜ 当前仅 ConcurrentHashMap 内存，请求间不持久化 |
| 5.4 | 对话结束后状态自动清理 | `AgentServiceImpl.doFinally()` | ✅ 完成 |
| 5.5 | 可用数据索引（outputKey → dataJson 映射） | `AnalysisState.dataIndex` | ✅ 完成 |
| 5.6 | dataIndex 数据量控制（LRU 淘汰） | 无 | ⬜ 未做限制，需关注 |

---

## 6. 用户系统（User System）

| # | 功能点 | 文件 | 状态 |
|---|--------|------|------|
| 6.1 | 用户注册/登录 | `controller/UserController.java` | ✅ 已有 |
| 6.2 | Session 认证 + 登录拦截器 | `interceptor/LoginInterceptor.java` | ✅ 已有 |
| 6.3 | 密码 BCrypt 加密 | `config/PasswordEncoderConfig.java` | ✅ 已有 |
| 6.4 | 用户偏好管理（Redis） | `manager/UserPreferenceManager.java` | ✅ 已有 |
| 6.5 | 限流（2次/秒） | `manager/RedisLimiterManager.java` | ✅ 已有 |
| 6.6 | 全局异常处理 | `exception/GlobalExceptionHandler.java` | ✅ 已有 |
| 6.7 | AOP 日志拦截 | `aop/LogInterceptor.java` | ✅ 已有 |

---

## 7. 对话系统（Conversation）

| # | 功能点 | 文件 | 状态 |
|---|--------|------|------|
| 7.1 | 对话创建/列表/删除/详情 | `AgentServiceImpl` | ✅ 已有 |
| 7.2 | SSE 流式对话（5 种事件类型） | `controller/AgentController.java` | ✅ 已有（message/table/chart/status/complete） |
| 7.3 | 对话标题自动生成（取首条消息前 100 字） | `AgentServiceImpl` | ✅ 已有 |
| 7.4 | 对话删除时清理 Redis Checkpoint | `AgentServiceImpl` | ✅ 已有 |
| 7.5 | 对话删除时清理 AnalysisState | `AgentServiceImpl` | ⬜ 未关联 |
| 7.6 | 对话导出（Markdown/JSON） | 无 | ⬜ 未实现 |
| 7.7 | 对话共享（生成分享链接） | 无 | ⬜ 未实现（未来功能） |

---

## 8. 图表与输出（Charts & Output）

| # | 功能点 | 文件/位置 | 状态 |
|---|--------|----------|------|
| 8.1 | ECharts v5 option JSON 构建（9 种类型） | `ChartOutputTool` | ✅ 完成 |
| 8.2 | 图表数据维度值上限控制（80 个） | `ChartOutputTool` | ✅ 完成 |
| 8.3 | 系列名业务含义校验（拒绝 "series1"/"数据"） | `ChartOutputTool.validateChart()` | ✅ 完成 |
| 8.4 | SSE complete 事件结构化输出 (chartOption + conversationId) | `AgentController` | ✅ 完成 |
| 8.5 | 图表 JSON 暂存 → 持久化到消息表 | `AnalysisState` + `AgentServiceImpl` | ✅ 完成 |
| 8.6 | 分析报告生成（Markdown/HTML/PDF） | 无 | ⬜ 未实现 |
| 8.7 | 报告下载 API | 无 | ⬜ 未实现 |
| 8.8 | 图表数据导出（CSV/Excel） | 无 | ⬜ 未实现 |
| 8.9 | 分析结论质量校验（数值引用/趋势一致性/完整性） | 无 | ⬜ 未实现 |

---

## 9. 前端（Frontend — CDataAgent_Frontend）

| # | 功能点 | 文件 | 状态 |
|---|--------|------|------|
| 9.1 | 登录/注册页 | `pages/auth/LoginPage.vue, RegisterPage.vue` | ✅ 已有 |
| 9.2 | Landing 页 | `pages/landing/index.vue` | ✅ 已有 |
| 9.3 | 工作区（对话列表侧边栏 + 分析主区域） | `pages/workspace/AnalysisPage.vue` | ✅ 已有 |
| 9.4 | 对话输入框（含文件上传） | `components/analysis/ChatInput.vue` | 🔶 需适配新文件上传 API（/apis/file/upload） |
| 9.5 | 对话消息渲染（Markdown + ECharts） | `components/analysis/ChatMessage.vue` | ✅ 已有 |
| 9.6 | SSE 流式接收 + chartParser | `services/api.ts` + `utils/chartParser.ts` | ✅ 已有 |
| 9.7 | 对话历史面板 | `components/analysis/ChatHistoryPanel.vue` | ✅ 已有 |
| 9.8 | 文件栏（显示已上传文件） | `components/analysis/FileBar.vue` | 🔶 需适配新 DataFile API（/apis/file/list） |
| 9.9 | 欢迎页 | `components/analysis/WelcomeScreen.vue` | ✅ 已有 |
| 9.10 | 图表预览弹窗 | `components/analysis/ChartPreviewModal.vue` | ✅ 已有 |
| 9.11 | Naive UI 暗色/亮色双主题 | `styles/naive-theme.ts` | ✅ 已有 |
| 9.12 | 模型配置页面（用户自定义 API Key/模型） | 无 | ⬜ 缺少前端页面 |
| 9.13 | 分析报告下载按钮 | 无 | ⬜ 缺少前端交互 |
| 9.14 | Token 用量/费用展示 | 无 | ⬜ 未实现 |
| 9.15 | 响应式适配（移动端） | 无 | ⬜ 未验证 |

---

## 10. 质量保证（Quality Assurance）

| # | 功能点 | 文件/位置 | 状态 |
|---|--------|----------|------|
| 10.1 | 单元测试 — Agent 工具 | `test/` | ⬜ 仅有框架测试，需为工具层补写 |
| 10.2 | 单元测试 — ToolResultUtils | `test/.../ToolResultUtilsTest.java` | ✅ 30 个用例（覆盖错误类型/边界/null） |
| 10.3 | 单元测试 — DuckDbQueryService | `test/.../DuckDbQueryServiceTest.java` | ✅ 30 个用例（覆盖 SQL 校验/查询/多文件 JOIN/边界） |
| 10.4 | 集成测试 — RFM/趋势/构成分析端到端 | `test/` | ⬜ 未实现 |
| 10.5 | Python 代码安全检查测试（AST 校验） | `test/` | ⬜ 未实现 |
| 10.6 | FileConversionService 测试（xlsx/csv→Parquet） | `test/` | ⬜ 未实现 |
| 10.7 | 编译通过（mvn clean compile） | — | ✅ 通过 |
| 10.8 | 压力测试（并发对话） | 无 | ⬜ 未实现 |

---

## 11. 可观测性（Observability）

| # | 功能点 | 状态 |
|---|--------|------|
| 11.1 | Micrometer 指标采集（Actuator 已加依赖） | 🔶 依赖已加，AgentMetricsCollector 未实现 |
| 11.2 | 工具调用耗时/成功率监控 | ⬜ |
| 11.3 | Agent 请求 P50/P95/P99 延迟 | ⬜ |
| 11.4 | 模型 API 调用延迟和错误率 | ⬜ |
| 11.5 | 沙箱调用次数/成功率 | ⬜ |
| 11.6 | DuckDB 查询耗时分布 | ⬜ |
| 11.7 | Grafana 仪表盘模板 | ⬜ |
| 11.8 | 告警规则 | ⬜ |
| 11.9 | 结构化审计日志（TraceId + ToolName + Duration） | 🔶 SLF4J 已有，但无 TraceId 传递 |
| 11.10 | Token 消耗追踪（按用户/按对话） | ⬜ |

---

## 12. 安全加固

| # | 功能点 | 状态 |
|---|--------|------|
| 12.1 | API Key AES-256-GCM 加密存储 | ⬜ |
| 12.2 | API Key 日志脱敏 | ⬜ |
| 12.3 | DuckDB SQL 注入防护（注释剥离/多语句拦截/危险模式过滤） | ✅ 已加固（白名单关键字 + 前导注释剥离 + 多语句拒绝 + 危险函数拦截） |
| 12.4 | Python 沙箱 seccomp profile（系统调用白名单） | ⬜ |
| 12.5 | Python 沙箱资源限制增强（磁盘 IO 限制） | ⬜ |
| 12.6 | 文件上传病毒扫描（集成 ClamAV） | ⬜ |
| 12.7 | CORS 安全配置 | ✅ CorsConfig 已有 |

---

## 13. 高级功能（Advanced — 未来迭代）

| # | 功能点 | 描述 |
|---|--------|------|
| 13.1 | 多文件关联分析 | 上传多个文件后，Agent 自动识别关联列执行 JOIN 分析 |
| 13.2 | 分析模板市场 | 预置常用分析模板（RFM/漏斗/留存/同环比），一键执行 |
| 13.3 | 定时分析任务 | 设定定时任务，自动拉取数据→分析→推送报告 |
| 13.4 | 自然语言生成完整报告 | "生成 2024 年 Q4 销售分析报告" → 自动规划→执行→输出完整报告 |
| 13.5 | 对话式可视化编辑 | "把这个柱状图改成折线图"/"去掉利润这个系列" |
| 13.6 | 分析历史回溯 | 查看历史分析的所有步骤和中间结果，支持"回退到某步重新分析" |
| 13.7 | 协作分析 | 多人共享同一对话，共同编辑分析 |
| 13.8 | 数据源连接器 | 直接连接数据库/API/数据仓库，无需上传文件 |
| 13.9 | Agent Skill 市场 | 用户自定义分析 Skill，上传后 Agent 自动加载 |
| 13.10 | 多模型 A/B 对比 | 同一问题同时发给 2 个模型，对比分析质量 |

---

## 迭代路线建议

### P0 — 可运行 MVP ✅（已完成）
```
✅ 0.2  Dockerfile
✅ 2.1~2.2 两层 Agent（Executor + Synthesizer）
✅ 1.3  文件上传 xlsx→Parquet（EasyExcel 流式 + DuckDB）
✅ 1.4  SQL 安全校验（已加固）
✅ 1.8  文件过期清理 Job（每日凌晨3点）
✅ 2.8  对话并发锁（Redisson RLock）
✅ 10.2~10.3 核心模块测试（60 个测试用例）
```

### P1 — 生产可用（2 → 4 周）
```
⬜→✅  4.3  用户模型配置 CRUD API
⬜→✅  4.4  API Key 加密存储
⬜→✅  4.5  模型差异化使用
⬜→✅  8.6  分析报告生成（Markdown）
⬜→✅  8.7  报告下载 API
⬜→✅  2.9  Agent 调用次数硬限制
⬜→✅  5.3  AnalysisState Redis 持久化
⬜→✅  9.12 前端模型配置页
⬜→✅  9.13 前端报告下载按钮
⬜→✅  10.4 端到端集成测试（3 个核心场景）
⬜→✅  11.1~11.6 监控指标采集
```

### P2 — 体验完善（4 → 8 周）
```
⬜→✅  8.9  分析结论质量校验
⬜→✅  3.4.3 图表数据一致性校验
⬜→✅  1.9  大数据文件采样
⬜→✅  7.6  对话导出
⬜→✅  11.7  Grafana 面板
⬜→✅  11.8  告警规则
⬜→✅  10.8  压力测试
⬜→✅  9.15 响应式适配（移动端）
⬜→✅  9.14 Token 用量展示
```

### P3 — 高级功能（8 周+）
```
13.1~13.10 按优先级逐个实现
```

---

## 修复项速览

| # | 问题 | 文件 | 状态 |
|---|------|------|------|
| F1 | DuckDB xlsx 读取依赖 `spatial` 扩展可能不可用 | `FileConversionService` | 🔶 EasyExcel 流式替代，已无 spatial 依赖，需验证 |
| F2 | AnalysisState 仅内存存储，请求间状态丢失 | `AnalysisState` | ⬜ 待实现 Redis 持久化 |
| F3 | SQL 注入仅检查 SELECT 前缀，不够安全 | `DuckDbQueryService` | ✅ 已修复（白名单关键字 + 多语句拦截 + 危险模式过滤） |
| F4 | 同一对话无并发控制，checkpoint 可能被覆盖 | `AgentServiceImpl` | ✅ 已修复（Redisson RLock 实现） |
| F5 | `chart` 和 `table_expire` 表仍在建表脚本中但代码已删除 | `sql/create_table.sql` | 🔶 待清理 |
| F6 | Python 沙箱 AST 校验不够全面（getattr 可绕过） | `PythonRunnerTool` | 🔶 待增强 |
| F7 | 前端 FileBar 和 ChatInput 未适配新 `/apis/file/` API | `CDataAgent_Frontend` | 🔶 待适配 |
| F8 | SandboxConfig 容器池与框架容器管理可能冲突 | `SandboxConfig` | 🔶 待验证 |
| F9 | docker-compose.yml 未声明数据文件目录 volume | `docker-compose.yml` | ⬜ 待补充 |
