# EchoMind Java

EchoMind Java 是一个基于 Spring Boot 的智能客服 Agent 项目。它围绕多 Agent 路由、知识库检索、动态 Skills、MCP 工具调用、会话记忆、回答校验、评测和监控构建，目标是把客服对话从“单轮问答”升级为可追踪、可扩展、可观测的 Agent 系统。

当前项目已经支持：

- General / Technical / Billing 多 Agent 路由
- Redis 工作记忆、长期记忆和用户画像持久化
- Hybrid RAG：BM25、hash vector、可选 ChromaDB 召回、LLM rerank
- 知识库来源引用、上下文窗口扩展、证据不足降级
- 动态 Skills 注入
- MCP stdio / HTTP / SSE 工具调用
- MCP allowlist / denylist / read-only / dangerous 工具策略
- MCP tool `inputSchema` 参数校验
- 统一 Agent Tool Registry，内置 `knowledge_search` 工具
- 工具调用 trace、错误、耗时返回
- Prometheus / Actuator 监控、评测接口、Swagger UI
- Docker Compose 部署 Redis、ChromaDB、Prometheus、Nginx 和 Java App

## 技术栈

| 模块 | 技术 |
|---|---|
| Runtime | Java 21 |
| Web | Spring Boot 3.5, Spring MVC |
| LLM | Spring AI Anthropic, Spring AI DeepSeek |
| RAG | BM25, local hash vector, Spring AI Chroma VectorStore, LLM rerank |
| Memory | Redis + JSON store |
| Tools | AgentToolRegistry, MCP stdio/http/sse |
| API Docs | Springdoc OpenAPI, Swagger UI |
| Observability | Spring Boot Actuator, Micrometer, Prometheus |
| Build | Maven Wrapper |
| Deploy | Docker, Docker Compose, Nginx |

## 架构概览

```text
POST /chat
  -> MemoryManager
     读取 Redis 工作记忆、长期记忆、用户画像
  -> KnowledgeToolManager
     查询改写、并行召回、缓存、熔断、rerank
  -> KnowledgeBaseService
     BM25 + ChromaDB 可选召回 + hash vector 融合
  -> AgentOrchestrator
     意图识别、Agent 路由、复合问题并行处理
  -> BaseAgent
     构造 Agent prompt
  -> AgentToolService
     选择工具、执行工具、注入工具结果
  -> AnswerVerifier
     校验 grounded / pass / escalation
  -> MemoryManager
     写入会话，更新用户画像
```

核心实现位置：

| 能力 | 文件 |
|---|---|
| 对话入口 | `src/main/java/com/echomind/api/EchoMindController.java` |
| 知识库 API | `src/main/java/com/echomind/api/KnowledgeController.java` |
| MCP / Skills API | `src/main/java/com/echomind/api/SkillMcpController.java` |
| 监控和评测 API | `src/main/java/com/echomind/api/MonitorEvalController.java` |
| Agent 路由 | `src/main/java/com/echomind/agent/AgentOrchestrator.java` |
| 工具规划执行 | `src/main/java/com/echomind/agent/AgentToolService.java` |
| 工具注册表 | `src/main/java/com/echomind/agent/AgentToolRegistry.java` |
| RAG 检索 | `src/main/java/com/echomind/knowledge/KnowledgeBaseService.java` |
| MCP 客户端 | `src/main/java/com/echomind/mcp/` |
| Skills 加载 | `src/main/java/com/echomind/skill/SkillManager.java` |

## 快速开始

### 1. 准备环境

- JDK 21+
- Docker Desktop 或 Docker Engine
- Maven Wrapper 已包含在项目中

### 2. 启动依赖

```bash
docker compose up -d redis chromadb
```

Windows PowerShell:

```powershell
docker compose up -d redis chromadb
```

### 3. 配置模型

复制环境变量文件：

```bash
cp .env.example .env
```

Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

默认 profile 是 Anthropic：

```env
SPRING_PROFILES_ACTIVE=anthropic
ANTHROPIC_API_KEY=your_key
ANTHROPIC_MODEL=claude-3-5-sonnet-20241022
```

切换 DeepSeek：

```env
SPRING_PROFILES_ACTIVE=deepseek
DEEPSEEK_API_KEY=your_key
DEEPSEEK_MODEL=deepseek-chat
```

如果只想本地启动调试，可以保留：

```env
LLM_FALLBACK_ENABLED=true
```

缺少真实 API Key 时，项目会尽量使用本地 fallback 回复，避免启动阶段直接失败。

### 4. 启动应用

macOS / Linux:

```bash
./mvnw spring-boot:run
```

Windows PowerShell:

```powershell
.\mvnw.cmd spring-boot:run
```

### 5. 验证

```bash
curl http://localhost:8080/health
```

Swagger UI:

```text
http://localhost:8080/docs
```

## Docker 部署

```bash
docker compose up -d --build
```

常用端口：

| 服务 | 地址 |
|---|---|
| Java App | `http://localhost:8080` |
| Nginx | `http://localhost:8081` |
| Swagger UI | `http://localhost:8080/docs` |
| Prometheus | `http://localhost:9091` |
| ChromaDB | `http://localhost:8002` |
| Redis | `localhost:6380` |

查看日志：

```bash
docker compose logs -f echomind-java
```

停止：

```bash
docker compose down
```

## API

默认端口：`8080`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 健康检查 |
| POST | `/chat` | 主对话接口 |
| POST | `/search` | 知识库检索 |
| POST | `/knowledge/add` | 批量添加知识文档 |
| POST | `/knowledge/upload` | 上传 `.txt` / `.md` / `.json` 文件 |
| GET | `/knowledge/stats` | 知识库统计 |
| GET | `/skills` | 查看已加载 Skills |
| POST | `/skills/reload` | 重新加载 Skills |
| GET | `/mcp` | MCP 状态 |
| POST | `/mcp/reload` | 重载 MCP 配置 |
| GET | `/mcp/tools` | 列出 MCP 工具和策略 |
| POST | `/mcp/tools/{serverName}/{toolName}/call` | 手动调用 MCP 工具 |
| GET | `/monitor` | 监控摘要 |
| GET | `/metrics` | Prometheus 文本指标 |
| GET | `/actuator/prometheus` | Actuator Prometheus 指标 |
| POST | `/eval/run` | 运行评测 |
| GET | `/docs` | Swagger UI |
| GET | `/v3/api-docs` | OpenAPI JSON |

### 对话示例

```bash
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "我想申请退款，订单号是 12345",
    "user_id": "u1001"
  }'
```

响应使用 `SNAKE_CASE`：

```json
{
  "conversation_id": "...",
  "response": "...",
  "intent": "billing",
  "agent_type": "billing",
  "escalated": false,
  "latency_ms": 123,
  "knowledge_used": true,
  "knowledge_sources": [],
  "knowledge_sufficient": true,
  "knowledge_warning": "",
  "verified": true,
  "grounded": true,
  "verification_reason": "",
  "mcp_tools_used": false,
  "tool_calls": [],
  "tool_errors": [],
  "tool_latency_ms": 0
}
```

### 知识库检索

```bash
curl -X POST "http://localhost:8080/search?query=退款多久能到账&topK=3"
```

### 添加知识库

```bash
curl -X POST http://localhost:8080/knowledge/add \
  -H "Content-Type: application/json" \
  -d '{
    "documents": [
      {
        "title": "退款补充政策",
        "content": "# 大促退款\n\n大促期间退款审核时间可能延长到 3-5 个工作日。"
      }
    ]
  }'
```

### 上传知识库文件

```bash
curl -X POST http://localhost:8080/knowledge/upload \
  -F "file=@docs.md"
```

## RAG 和知识库

文档导入链路：

```text
文档
  -> heading / paragraph aware splitter
  -> metadata: source, heading_path, paragraph_start, paragraph_end
  -> previous_chunk_id / next_chunk_id
  -> JSON 持久化
  -> Spring AI VectorStore 写入（可用时）
```

查询链路：

```text
用户问题
  -> LLM 查询改写
  -> 多子查询并行召回
  -> BM25 关键词得分
  -> ChromaDB / Spring AI VectorStore 语义召回（可用时）
  -> 本地 hash vector 兜底召回
  -> 加权融合
  -> LLM rerank
  -> 命中 chunk 前后窗口扩展
  -> 返回 knowledge_sources / context_sources
```

相关配置：

```yaml
echomind:
  rag:
    top-k: ${RAG_TOP_K:4}
    bm25-weight: ${RAG_BM25_WEIGHT:0.45}
    vector-weight: ${RAG_VECTOR_WEIGHT:0.55}
    min-relevant-score: ${RAG_MIN_RELEVANT_SCORE:0.18}
    min-sources: ${RAG_MIN_SOURCES:1}
    context-window: ${RAG_CONTEXT_WINDOW:1}
```

当知识证据不足时：

- `/chat` 返回 `knowledge_sufficient=false`
- `/chat` 返回 `knowledge_warning`
- Agent prompt 注入 Knowledge Safety 提示
- `grounded` 会被压成 `false`
- 对话会倾向转人工确认，避免编造政策细节

## Skills

Skills 位于 `skills/` 目录，每个 Skill 使用 `SKILL.md`：

```text
skills/
  billing_support/SKILL.md
  general_customer_service/SKILL.md
  technical_support/SKILL.md
```

Skill front matter 示例：

```markdown
---
name: 技术支持处理规范
description: 技术排障流程
keywords: 报错,500,error
agents: technical
enabled: true
---

# 技术支持处理规范

请先收集错误码、发生时间、设备信息和复现步骤。
```

运行时接口：

```bash
curl http://localhost:8080/skills
curl -X POST http://localhost:8080/skills/reload
```

## MCP 工具调用

MCP 默认关闭：

```yaml
echomind:
  mcp:
    enabled: false
```

支持的 transport：

- `stdio`
- `http`
- `sse`

stdio 示例：

```yaml
echomind:
  mcp:
    enabled: true
    auto-tool-enabled: true
    protocol-version: 2025-06-18
    max-tool-iterations: 2
    servers:
      filesystem:
        enabled: true
        transport: stdio
        command: node
        args:
          - path/to/mcp-server.js
        stdio-format: content-length # content-length 或 json-lines
        timeout-ms: 30000
        allowed-tools:
          - read_file
          - search
        read-only-tools:
          - read_file
          - search
        blocked-tools:
          - delete_file
        dangerous-tools:
          - run_command
```

HTTP / SSE 示例：

```yaml
echomind:
  mcp:
    enabled: true
    servers:
      remote:
        enabled: true
        transport: http # 或 sse
        url: http://localhost:3000/mcp
        headers:
          Authorization: Bearer token
        allowed-tools:
          - search
```

MCP 调用链路：

```text
AgentToolService
  -> AgentToolRegistry
  -> McpClientManager
  -> StdioMcpClient / HttpMcpClient
  -> tools/list / tools/call
  -> McpSchemaValidator
  -> ToolCallTrace
```

查看 MCP 状态：

```bash
curl http://localhost:8080/mcp
curl http://localhost:8080/mcp/tools
```

手动调用 MCP 工具：

```bash
curl -X POST http://localhost:8080/mcp/tools/remote/search/call \
  -H "Content-Type: application/json" \
  -d '{"arguments":{"query":"hello"}}'
```

## 记忆

记忆系统由 `MemoryManager` 管理：

- Redis 保存工作记忆
- JSON 文件保存长期记忆和用户画像
- `MEMORY_TTL_SECONDS` 控制 Redis 记忆 TTL
- `MEMORY_WORKING_MAX` 控制工作记忆窗口
- `MEMORY_COMPRESS_AT` 控制压缩触发阈值

配置：

```yaml
echomind:
  memory:
    ttl-seconds: ${MEMORY_TTL_SECONDS:86400}
    working-max: ${MEMORY_WORKING_MAX:20}
    compress-at: ${MEMORY_COMPRESS_AT:15}
```

## 监控和评测

监控接口：

```bash
curl http://localhost:8080/monitor
curl http://localhost:8080/metrics
```

评测接口：

```bash
curl -X POST http://localhost:8080/eval/run \
  -H "Content-Type: application/json" \
  -d '{}'
```

当前包含：

- Intent Accuracy
- Macro-F1
- per-class Precision / Recall / F1
- LLM-as-Judge 四维评分
- baseline 保存和回归检测
- 成功率阈值告警
- 延迟阈值告警
- Webhook 告警
- Agent 路由惩罚反馈

## 测试

运行全部测试：

```bash
./mvnw test
```

Windows PowerShell：

```powershell
.\mvnw.cmd test
```

当前测试覆盖：

- Skills 加载和匹配
- Agent 工具调用 trace
- 统一 Agent Tool Registry
- MCP allowlist / denylist / read-only / dangerous 策略
- MCP JSON Schema 参数校验
- MCP stdio 集成测试
- MCP HTTP / SSE 测试
- RAG 切块、metadata、上下文扩展

## 目录结构

```text
src/main/java/com/echomind
  agent/       Agent、路由、工具规划执行
  api/         Controller 和 DTO
  config/      Spring 配置
  evaluation/ 评测
  intent/      意图识别
  knowledge/   知识库和 RAG
  llm/         LLM 网关
  mcp/         MCP 客户端、策略、Schema 校验
  memory/      会话记忆和用户画像
  monitor/     监控
  skill/       动态 Skills
  tool/        内部工具
```

## 常用配置

| 变量 | 默认值 | 说明 |
|---|---|---|
| `SERVER_PORT` | `8080` | 服务端口 |
| `SPRING_PROFILES_ACTIVE` | `anthropic` | 模型 profile |
| `ANTHROPIC_API_KEY` | 空 | Anthropic API Key |
| `DEEPSEEK_API_KEY` | 空 | DeepSeek API Key |
| `LLM_FALLBACK_ENABLED` | `true` | LLM 失败时是否使用本地降级回复 |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | `echomind123` | Redis password |
| `CHROMA_HOST` | `http://localhost` | Chroma host |
| `CHROMA_PORT` | `8001` | Chroma port |
| `ECHOMIND_DATA_DIR` | `data/java` | 数据目录 |
| `MCP_ENABLED` | `false` | 是否启用 MCP |
| `MCP_AUTO_TOOL_ENABLED` | `true` | Agent 是否自动选择 MCP 工具 |

## 当前限制

- ChromaDB 召回依赖 Spring AI `VectorStore` bean。若当前模型 profile 没有可用 EmbeddingModel，系统会自动回退到本地 BM25 + hash vector。
- MCP HTTP / SSE 当前按 JSON-RPC POST 兼容常见服务端；更复杂的长连接 session 管理可继续增强。
- 当前尚未提供 CLI 模式。
- 监控侧还可以继续增强更细粒度的异常检测、趋势分析和告警降噪。
