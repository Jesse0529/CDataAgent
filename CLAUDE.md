# CLAUDE.md

项目级指南。本项目由前后端两个独立子项目组成。

---

## 项目概述

CData Agent（AI Business Intelligence）— 上传 Excel/CSV，用自然语言描述分析目标，AI 生成 ECharts 图表配置与分析结论。前后端分离。

## 项目结构

```
CDataAgent/
├── CDataAgent_Backend/     # Spring Boot 3.2.10 + JDK 17 + Maven
│   ├── CLAUDE.md           # 后端专属指南（一级入口）
│   ├── AGENTS.md           # 后端架构详解（含包结构、流程、设计原理）
│   └── docs/               # 后端设计文档存放处
├── CDataAgent_Frontend/    # Vue 3 + Vite + TypeScript + Naive UI
│   ├── CLAUDE.md           # 前端专属指南（含设计系统 + 开发规范）
│   ├── AGENTS.md           # 前端架构详解
│   └── docs/               # 前端设计文档存放处
└── docs/                   # 暂未使用（建议统一放子项目内 docs/）
```

---

## 文档管理约定

1. **系统和业务产出的设计文档统一放到子项目下的 `docs/` 文件夹管理**（`CDataAgent_Backend/docs/` 或 `CDataAgent_Frontend/docs/`）
2. `docs/` 以外的独立 `.md` 文件应仅限 README 等根级说明
3. 跨项目共享的设计文档可放在父级 `.claude/` 中

---

## 变更汇报规范

代码改动后汇报格式：

```
改了什么：
  文件：/controller/AgentController.java
  内容：新增重置对话接口，POST /apis/agent/conversations/{id}/reset
前后对比：
  前：无此接口，重置需清空再重建
  后：单接口原子操作，返回新 conversationId
潜在缺陷：
  重置后旧消息立即不可恢复，未提供确认机制
```

**规则**：
1. 文件路径使用最简相对路径（从子项目根算起），如 `/controller/AgentController.java`
2. 内容说明"改了什么、前后对比、潜在缺陷"，不贴完整代码
3. 发现隐患必须提示，禁止自行修改或忽略

---

## 子项目入口

| 子项目 | 指南文件 | 用途 |
|--------|---------|------|
| 后端 | `CDataAgent_Backend/CLAUDE.md` → `AGENTS.md` | 架构、构建、API、代码风格 |
| 前端 | `CDataAgent_Frontend/CLAUDE.md` → `AGENTS.md` | 设计系统、组件、API 对接 |

---

## 变更纪律

0. **最高优先级**：禁止擅自修改代码。仅当用户明确说"修改/完善/实现/重构/修复"时才能改；仅提问或查看时只给分析建议。
1. 禁止擅自修改配置或依赖（`application-*.yml`、`pom.xml`、`package.json`）
2. 禁止还原或修改用户手动改动
3. 需求含糊先总结提问，不自行假设
4. 修改结束自行审查确保正确
5. 前后端独立，改一端不动另一端
6. **测试后关闭服务器**，禁止残留端口
7. **测试代码任务结束清理**，临时配置和代码必须回滚

---

## 工作流规范

```
1. 确认分支 → git status / git log --oneline -3
2. 检查差异 → git log --oneline master..feat/xxx（若有则先合并）
3. 代码修改 → 确保在最新分支上
4. 编译测试 → mvn clean compile + mvn test
5. 真实测试 → mvn spring-boot:run
   ├── 编写正例：正常业务流程
   ├── 编写反例：错误输入/异常场景
   └── 编写边界：模糊/极限情况
   → 分析并汇报结果
6. 提交推送 → 用户确认后 git add + git commit + git push
```

---

## 错误记录

- **2026-06-12：遇错不报、擅改方案**。遇到阻塞错误应先报告根因和所有可选方案，等用户决策后再执行，禁止擅自切换方案。
- **2026-06-14：改表名前缀只改导入侧，漏了过期清理 Job 的正则**。`buildTableNameFromFile` 去掉了日期前缀（`Data_YYYYMMDD_{userId}_{hex}` → `Data_{userId}_{hex}`），但 `TableExpireCleanupJob` 的正则没同步更新，导致新格式临时表永不清理。教训：**修改任何共用的格式/协议/标识符规则时，必须全文搜索所有引用点，每个都核对一遍**。
