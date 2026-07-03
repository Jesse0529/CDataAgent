# Agent 对话持久化 — 技术总结

## 技术思路

### 问题
Agent 对话上下文由 Spring AI Alibaba Agent Framework 在**内存**中通过 `threadId` 维护，Session 过期或服务重启后全部丢失。

### 方案
引入两张 MySQL 表持久化对话历史，在 `AgentServiceImpl.chat()` 中拦截每次对话，自动存储 Q&A 记录。恢复对话时从 DB 回注历史上下文到 prompt。

### 数据模型
- `conversation`：对话元数据（id, userId, title, messageCount）
- `conversation_message`：每条消息（conversationId, role, content）

### 接口
| 方法 | 路径 | 功能 |
|------|------|------|
| POST | `/apis/agent/chat` | 对话（已有，内部自动持久化） |
| GET | `/apis/agent/conversations` | 对话列表（分页） |
| GET | `/apis/agent/conversations/{id}/messages` | 查看完整消息历史 |
| POST | `/apis/agent/conversations/{id}/resume` | 切换到历史对话，标记需要注入上下文 |
| DELETE | `/apis/agent/conversations/{id}` | 物理删除对话及关联消息 |

### 上下文注入
Resume 后在**首次 chat 时**从 DB 加载历史消息，拼接为上下文前缀注入 prompt：
> `"以下是我们之前的对话历史：\n用户: ...\nAI: ...\n\n用户最新问题: ..."`

同 Session 内后续对话不重复注入（框架内存状态尚在）。

### 关键决策
- 关键词拦截的问候消息**不持久化**（非真实对话）
- threadId 对齐为 `conversationId.toString()`，方便 resume 后框架状态对应
- 消息表 `content` 用 `mediumtext`（Agent 回复可能含长 ECharts 配置）

## 可行性

- **技术栈兼容**：MyBatis-Plus 实体模式完全复用现有架构，零依赖新增
- **编译验证通过**：`mvn clean compile` BUILD SUCCESS，86 个源文件无编译错误
- **无回归**：4 个测试中 3 个通过，1 个失败为已有问题（RedisLimiterManagerTest 限流测试）
- **核心流程不受影响**：`chat()` 的 Agent 调用、关键词拦截、路由清洗逻辑零改动

## 不足与风险

1. **框架内存状态丢失**：服务重启后框架内部的 `OverAllState` 消息列表丢失。Resume 注入的是文本上下文，LLM 能"看到"历史但框架层面的 `threadId` 消息链表不完整。如果 Agent 框架内部依赖完整消息历史做路由决策，可能有影响。

2. **上下文注入是 prompt 拼接，非框架原生支持**：将历史消息拼到用户输入前，增加了 prompt 长度，且格式与框架原生消息不一致。如果对话很长可能超 token 限制（当前限制最近 10 轮 Q&A）。

3. **不支持多用户并发同一对话**：conversationId 绑定到 Session，同一时刻一个 Session 只能操作一个对话。跨设备 resume 同一对话时，两个 Session 各自追加消息（不会冲突，但各自有独立的 threadId 上下文）。

4. **删除为物理删除**：`DELETE FROM` 直接删库记录。删除后不可恢复，需提醒用户确认操作。

5. **无消息搜索功能**：当前仅支持按对话查看消息列表，不支持跨对话搜索消息内容。

6. **Token 消耗未实际记录**：`conversation_message.tokenUsage` 字段预留但当前未填充（Agent 框架不暴露单次调用的精确 token 数）。
