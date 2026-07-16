# CLAUDE.md — CDataAgent Backend

后端专属指南。详细架构设计见 `AGENTS.md`。

---

## 快速入口

```
AGENTS.md         → 架构、API、数据表、机制、代码风格（一号入口）
docs/             → 设计文档（功能清单、扩展设计、测试计划等）
```

## 核心约束

1. **系统/业务设计文档统一放 `docs/` 目录**
2. **变更汇报**：按模板（改了什么/前后对比/潜在缺陷），路径用最简相对路径
3. **禁止**擅自修改配置、依赖、还原用户改动
4. **测试后必须关闭服务**，临时代码必须清理
5. **测试随功能提交**；不提交日志、临时数据、真实密钥和本地开发配置

## 构建命令

```bash
mvn clean compile
mvn test
mvn clean package -DskipTests
```

## 当前运行约定

- 根配置默认使用生产安全 Profile；本地运行显式设置 `SPRING_PROFILES_ACTIVE=dev`
- 模型密钥经 Redis 加密保存，启动所需的 `MODEL_ENCRYPTION_KEY` 仅从环境变量或未提交的 `.env` 读取
- 单机 Agent 运行通过全局锁与对话锁隔离；AnalysisState 仅保存工作索引和小结果
- 提交按功能拆分，使用中文提交信息；推送前须获得用户明确授权

---

详细内容见 `AGENTS.md`。
