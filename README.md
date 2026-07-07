# EchoMind Java 版本说明

EchoMind Java 是 Python 版 EchoMind 的 Java/Spring 技术栈重构版，目录位于：



当前版本已经覆盖智能客服主链路：对话请求、Redis 工作记忆、知识库检索、多 Agent 路由、Spring AI 模型调用、回答校验、评测、监控、Swagger 文档和 Docker 部署。

## 技术栈

| 类型 | 技术 |
|------|------|
| 语言 | Java 21 |
| Web 框架 | Spring Boot 3.5 |
| AI 框架 | Spring AI 1.1 |
| LLM Provider | Anthropic、DeepSeek |
| 文档处理 | LangChain4j DocumentSplitter |
| 记忆缓存 | Spring Data Redis |
| RAG | BM25 + 本地 hash vector + LLM rerank |
| 持久化 | Redis 工作记忆 + JSON 知识库/长期记忆/用户画像 |
| 监控 | Spring Boot Actuator、Micrometer、Prometheus |
| API 文档 | Springdoc OpenAPI、Swagger UI |
| 部署 | Docker、Docker Compose、Nginx、Prometheus |
| 构建 | Maven Wrapper |

## 核心链路

```text
POST /chat
  -> MemoryManager 读取 Redis 工作记忆、会话摘要、长期记忆、用户画像
  -> KnowledgeToolManager 做查询改写、并行召回、LLM rerank
  -> AgentOrchestrator 做意图识别和 Agent 路由
  -> General / Technical / Billing Agent 生成回复
  -> AnswerVerifier 校验回复是否可信、是否需要转人工
  -> 写入 Redis，并异步更新用户画像
```

相关实现：

- `src/main/java/com/echomind/api/EchoMindController.java`
- `src/main/java/com/echomind/memory/MemoryManager.java`
- `src/main/java/com/echomind/tool/KnowledgeToolManager.java`
- `src/main/java/com/echomind/agent/AgentOrchestrator.java`
- `src/main/java/com/echomind/agent/AnswerVerifier.java`

## Python 与 Java 版本对照

| 能力 | Python 版本 | Java 版本 | 当前状态 |
|------|-------------|-----------|----------|
| Web 框架 | FastAPI | Spring Boot MVC | 已对齐 |
| 模型调用 | Anthropic Async SDK | Spring AI ChatModel | 已对齐，调用栈不同 |
| DeepSeek | 未内置 | Spring AI DeepSeek profile | Java 增强 |
| Agent 类型 | General / Technical / Billing | General / Technical / Billing | 已对齐 |
| Agent 路由 | 意图路由 + 性能路由 + 降级 | 意图路由 + 性能路由 + 降级 | 已对齐 |
| 复合问题并行处理 | 支持 | 支持 | 已对齐 |
| 意图识别 | LLM + embedding/hash + pattern | LLM + char n-gram semantic + pattern | 基本对齐 |
| 工作记忆 | Redis | Redis | 已对齐 |
| 情景记忆 | ChromaDB `episodic` collection | JSON 持久化 + 本地向量检索 | 功能对齐，存储不同 |
| 用户画像 | ChromaDB `user_profile` collection | JSON 持久化 | 功能对齐，存储不同 |
| 知识库 | ChromaDB `knowledge_base` collection | JSON 持久化 + ChromaDB 可选召回 + Hybrid RAG | 已对齐并保留本地兜底 |
| 查询改写 | LLM 改写 | LLM 改写 | 已对齐 |
| 检索重排 | LLM rerank | LLM rerank，失败回退融合分 | 已对齐 |
| 工具框架 | 通用 MCPToolManager | AgentToolRegistry + MCP + knowledge_search | 已对齐 |
| 熔断/缓存/超时/fallback | 支持 | 支持 | 已对齐到知识库工具 |
| 评测 | Intent、Macro-F1、LLM Judge、baseline | Intent、Macro-F1、LLM Judge、baseline | 基本对齐 |
| 监控 | Prometheus client、Webhook、Z-score | Actuator、Micrometer、Webhook、阈值告警 | 部分对齐 |
| API 文档 | FastAPI `/docs` | Springdoc Swagger UI `/docs` | 已对齐 |
| Docker | App、Redis、ChromaDB、Prometheus、Nginx | App、Redis、ChromaDB、Prometheus、Nginx | 已对齐 |
| CLI | `api/main.py --cli` | 未实现 | Java 缺失 |

## Java 版已补齐的能力

### DeepSeek 兼容

Java 版通过 Spring profile 切换模型：

```env
SPRING_PROFILES_ACTIVE=deepseek
DEEPSEEK_API_KEY=your_key
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-chat
```

当前还做了启动容错：缺少真实 API Key 时使用 `echomind-local-placeholder` 避免 Spring AI 自动配置阶段直接失败。真实调用时如果没有配置真实 key，并且：

```env
LLM_FALLBACK_ENABLED=true
```

系统会返回本地降级回复。

Anthropic 配置：

```env
SPRING_PROFILES_ACTIVE=anthropic
ANTHROPIC_API_KEY=your_key
ANTHROPIC_BASE_URL=https://api.anthropic.com
ANTHROPIC_MODEL=claude-3-5-sonnet-20241022
```

### 记忆和知识库持久化

Python 版：

- Redis 保存工作记忆。
- ChromaDB 保存情景记忆、用户画像和知识库。

Java 版：

- Redis 保存工作记忆。
- `data/java/memory-store.json` 保存情景记忆和用户画像。
- `data/java/knowledge-store.json` 保存知识库片段。
- Docker 中保存到 `/app/data/java`，由 `app-data` volume 持久化。

配置项：

```env
ECHOMIND_DATA_DIR=data/java
KNOWLEDGE_STORE_PATH=data/java/knowledge-store.json
MEMORY_STORE_PATH=data/java/memory-store.json
EVAL_BASELINE_PATH=data/eval/baseline.json
```

当前用户可见效果已经对齐：应用重启后，导入的知识库、长期记忆和画像可以恢复。知识库会继续 JSON 持久化；当 Spring AI Chroma VectorStore 可用时，也会写入并参与语义召回，本地 hash vector/BM25 作为兜底。

### Hybrid RAG

Python 版知识库主要通过 ChromaDB 做语义检索。

Java 版当前检索链路：

```text
文档导入
  -> heading/paragraph-aware splitter
  -> 保留 source、heading_path、paragraph_start/end、previous/next chunk
  -> JSON 持久化
  -> 本地 documents 索引

查询
  -> LLM 查询改写
  -> 多子查询并行召回
  -> BM25 关键词得分
  -> ChromaDB / Spring AI VectorStore 语义召回（可用时）
  -> 本地 hash vector 语义得分
  -> 加权融合
  -> LLM rerank
  -> fallback 到融合分排序
```

这比 Python 版多了 BM25 + vector 融合检索，并且切块会保留标题层级、来源文件、段落位置和相邻 chunk id。配置了 Spring AI Chroma VectorStore 时，检索会把 ChromaDB 召回分并入融合分；Chroma 不可用时仍会回退到本地 hash vector。

当前 `/chat` 和 `/search` 会返回结构化知识来源与证据质量信号：

- `knowledge_sources` / `sources`：命中的知识片段，包含 `id`、`title`、`score`、`chunk`、`excerpt` 和 `metadata`；metadata 中包括 `source`、`heading_path`、`paragraph_start`、`paragraph_end`、`previous_chunk_id`、`next_chunk_id`。
- `context_sources`：`/search` 额外返回的上下文扩展片段，包含命中 chunk 及其前后窗口；`/chat` 的 `knowledge_sources` 使用同样的扩展上下文。
- `knowledge_sufficient`：是否达到可支撑回答的最低证据阈值。
- `knowledge_warning`：当证据不足时返回原因，并注入 Agent 上下文，要求避免编造未被知识库支持的政策细节。
- `verification_reason`：回答校验器返回的原因，便于排查为什么触发转人工或 grounding 失败。

相关阈值可通过环境变量调整：

```yaml
echomind:
  rag:
    min-relevant-score: ${RAG_MIN_RELEVANT_SCORE:0.18}
    min-sources: ${RAG_MIN_SOURCES:1}
    context-window: ${RAG_CONTEXT_WINDOW:1}
```

### 评测和监控

Java 版已经实现：

- Intent Accuracy
- Macro-F1
- per-class Precision / Recall / F1
- LLM-as-Judge 四维评分
- baseline 保存和回归检测
- `/monitor`
- `/metrics`
- `/actuator/prometheus`
- Webhook 告警
- Agent 路由惩罚反馈

相关实现：

- `src/main/java/com/echomind/evaluation/EndToEndEvaluator.java`
- `src/main/java/com/echomind/evaluation/LLMJudge.java`
- `src/main/java/com/echomind/monitor/PerformanceMonitor.java`

### Swagger / OpenAPI

Python 版 FastAPI 默认提供 `/docs`。

Java 版现在通过 Springdoc OpenAPI 提供同名入口：

- Swagger UI：`http://localhost:8080/docs`
- Nginx 代理：`http://localhost:8081/docs`
- OpenAPI JSON：`http://localhost:8080/v3/api-docs`

相关实现：

- `pom.xml`
- `src/main/resources/application.yml`
- `src/main/java/com/echomind/config/OpenApiConfig.java`
- `src/main/java/com/echomind/api/SwaggerDocsController.java`
- `src/main/java/com/echomind/api/EchoMindController.java`

`/docs` 页面会从 jsdelivr CDN 加载 Swagger UI 静态资源；如果部署在不能访问外网的环境，需要把 Swagger UI 静态资源放到项目本地。

## 当前仍然不同的地方

### 通用 MCPToolManager 未完整迁移

Python 版 `MCPToolManager` 可以注册任意 Tool，并统一处理：

- register / unregister
- JSON Schema 参数校验
- timeout
- circuit breaker
- TTL cache
- fallback
- rerank
- 工具统计

Java 版已经有统一工具层：`AgentToolRegistry` 暴露 MCP 工具和内部 `knowledge_search`，`AgentToolService` 负责工具路由、执行、结果注入和 trace 收集。`KnowledgeToolManager` 仍负责知识库工具本身的查询改写、并行召回、缓存、超时、熔断、fallback、rerank 和统计。

### ChromaDB 召回

Java 版保留 ChromaDB 容器，并引入 Spring AI Chroma VectorStore starter。知识库导入会尝试写入 Spring AI `VectorStore`；检索时会把 ChromaDB / VectorStore 的 `similaritySearch` 结果并入融合分。若 Chroma 或 EmbeddingModel 不可用，系统会记录 debug 日志并继续使用本地 BM25 + hash vector 检索。

后续可增强为：

```text
ChromaDB VectorStore semantic search
  + BM25 keyword recall
  + RRF / weighted fusion
  + LLM rerank
```

### CLI 模式未迁移

Python 版支持：

```bash
python api/main.py --cli
```

Java 版当前没有 CLI。如果需要对齐，可以使用 Spring Shell 或 `CommandLineRunner` 实现。

### 监控算法仍有差异

Python 版监控包含 Z-score 异常检测。

Java 版当前是：

- 成功率阈值告警
- 平均延迟阈值告警
- Webhook
- Micrometer 指标
- 路由惩罚反馈

可继续补：

- Z-score 异常检测
- 告警 resolved 状态
- 请求 latency histogram
- tool success rate gauge

## 主要接口

默认端口：`8080`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | 健康检查 |
| POST | `/chat` | 主对话接口 |
| POST | `/search` | 知识库检索 |
| POST | `/knowledge/add` | 批量添加知识文档 |
| POST | `/knowledge/upload` | 上传 `.txt` / `.md` / `.json` 文件 |
| GET | `/knowledge/stats` | 知识库统计 |
| GET | `/skills` | 查看当前已加载的 Skills |
| POST | `/skills/reload` | 运行时重新加载 Skills |
| GET | `/mcp` | 查看 MCP 协议客户端状态 |
| POST | `/mcp/reload` | 重载 MCP server 配置 |
| GET | `/mcp/tools` | 通过 MCP `tools/list` 列出工具 |
| POST | `/mcp/tools/{serverName}/{toolName}/call` | 通过 MCP `tools/call` 调用工具 |
| GET | `/monitor` | 监控摘要 |
| GET | `/metrics` | Prometheus 指标，兼容 Python 版路径 |
| GET | `/actuator/prometheus` | Spring Actuator Prometheus 指标 |
| POST | `/eval/run` | 运行评测 |
| GET | `/docs` | Swagger UI，可在线调用接口 |
| GET | `/v3/api-docs` | OpenAPI JSON |

Java 版配置了 Jackson `SNAKE_CASE`，响应字段会输出为：

```json
{
  "conversation_id": "...",
  "response": "...",
  "intent": "...",
  "agent_type": "...",
  "escalated": false,
  "latency_ms": 123,
  "knowledge_used": true,
  "knowledge_sources": [],
  "knowledge_sufficient": true,
  "knowledge_warning": "",
  "verified": true,
  "grounded": true,
  "verification_reason": ""
}
```

和 Python 版主要差异：

- Python 使用 `conv_id`。
- Java 使用 `conversation_id`。
- Java 额外返回 `verified` 和 `grounded`。

推荐请求字段：

```json
{
  "message": "我想申请退款",
  "user_id": "u1001",
  "conversation_id": "optional-conversation-id"
}
```

## 本地运行

### macOS / Linux

准备环境：

- JDK 21 或更高版本
- Docker Desktop 或 Docker Engine

启动依赖：

```bash
docker compose up -d redis chromadb
```

DeepSeek 启动：

```bash
export SPRING_PROFILES_ACTIVE=deepseek
export DEEPSEEK_API_KEY=your_key
./mvnw spring-boot:run
```

Anthropic 启动：

```bash
export SPRING_PROFILES_ACTIVE=anthropic
export ANTHROPIC_API_KEY=your_key
./mvnw spring-boot:run
```

健康检查：

```bash
curl http://localhost:8080/health
```

Swagger：

```text
http://localhost:8080/docs
```

### Windows PowerShell

启动依赖：

```powershell
docker compose up -d redis chromadb
```

DeepSeek 启动：

```powershell
$env:SPRING_PROFILES_ACTIVE="deepseek"
$env:DEEPSEEK_API_KEY="your_key"
.\mvnw.cmd spring-boot:run
```

Anthropic 启动：

```powershell
$env:SPRING_PROFILES_ACTIVE="anthropic"
$env:ANTHROPIC_API_KEY="your_key"
.\mvnw.cmd spring-boot:run
```

健康检查：

```powershell
curl http://localhost:8080/health
```

Swagger：

```text
http://localhost:8080/docs
```

## Docker 部署

复制配置：

```bash
cp .env.example .env
```

Windows PowerShell：

```powershell
Copy-Item .env.example .env
```

编辑 `.env`，选择模型 profile 并填写对应 API Key。

启动：

```bash
docker compose up -d --build
```

查看状态：

```bash
docker compose ps
```

查看日志：

```bash
docker compose logs -f echomind-java
```

停止：

```bash
docker compose down
```

Compose 服务和端口：

| 服务 | 容器名 | 地址 |
|------|--------|------|
| Java App | `echomind-java-app` | `http://localhost:8080` |
| Nginx | `echomind-java-nginx` | `http://localhost:8081` |
| Prometheus | `echomind-java-prometheus` | `http://localhost:9091` |
| ChromaDB | `echomind-java-chromadb` | `http://localhost:8002` |
| Redis | `echomind-java-redis` | `localhost:6380` |

常用验证：

```bash
curl http://localhost:8080/health
curl http://localhost:8081/health
curl http://localhost:8080/metrics
```

Swagger：

```text
http://localhost:8080/docs
http://localhost:8081/docs
```

## API 示例

### 对话

```bash
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "我想申请退款，订单号是 #12345",
    "user_id": "u1001"
  }'
```

也可以打开 Swagger UI，通过页面直接调用：

```text
http://localhost:8080/docs
```

### 检索

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
        "content": "大促期间退款审核时间可能延长到 3-5 个工作日。"
      }
    ]
  }'
```

### 上传知识库文件

```bash
curl -X POST http://localhost:8080/knowledge/upload \
  -F "file=@docs.md"
```

### MCP 协议调用

Java 版支持 stdio、HTTP 和 SSE 风格的 MCP JSON-RPC 调用，并执行 MCP `initialize`、`tools/list` 和 `tools/call`。默认关闭；配置示例：

```yaml
echomind:
  mcp:
    enabled: true
    auto-tool-enabled: true
    protocol-version: 2025-06-18
    max-tool-iterations: 2
    servers:
      demo:
        enabled: true
        transport: stdio
        command: node
        args:
          - path/to/mcp-server.js
        timeout-ms: 30000
        stdio-format: content-length # content-length（标准 MCP stdio，默认）或 json-lines
        allowed-tools:
          - search
          - read_file
        read-only-tools:
          - search
          - read_file
        blocked-tools:
          - delete_file
        dangerous-tools:
          - run_command
      remote:
        enabled: true
        transport: http # http 或 sse
        url: http://localhost:3000/mcp
        headers:
          Authorization: Bearer token
        allowed-tools:
          - search
```

启用 `auto-tool-enabled` 后，Agent 在回答前会读取 MCP `tools/list`，让模型判断是否需要调用工具；如果需要，会执行 `tools/call`，并把工具结果写回上下文后再生成最终回复。
自动工具调用只会看到策略允许的非危险工具；如果配置了 `read-only-tools`，自动调用只能使用这些只读工具。所有 `tools/call` 在发送前都会按 MCP `inputSchema` 做 required、类型、enum 和 `additionalProperties:false` 校验。
`/mcp/tools` 会同时返回每个工具的 `policy` 状态，包括 Agent 是否可见、自动调用是否允许、手动调用是否允许以及拒绝原因，便于排查工具为什么没有被 Agent 自动选择。
Agent 工具调用已抽到独立工具层：MCP 工具和内部 `knowledge_search` 都通过统一 Tool Registry 暴露给 Agent，工具调用 trace 会继续返回在 `/chat` 响应中。

查看 MCP 状态和工具：

```bash
curl http://localhost:8080/mcp
curl http://localhost:8080/mcp/tools
```

调用工具：

```bash
curl -X POST http://localhost:8080/mcp/tools/demo/tool_name/call \
  -H "Content-Type: application/json" \
  -d '{"arguments":{"input":"hello"}}'
```

### 运行评测

```bash
curl -X POST http://localhost:8080/eval/run \
  -H "Content-Type: application/json" \
  -d '{}'
```
