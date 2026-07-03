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

## 构建命令

```bash
mvn clean compile
mvn test
mvn clean package -DskipTests
```

## 当前分支

`dev6.0`（领先 master 多个提交）。

---

详细内容见 `AGENTS.md`。
