# EchoMind Java

EchoMind Java 是一个基于 Spring Boot 的智能客服 Agent 项目。它围绕多 Agent 路由、知识库检索、动态 Skills、MCP 工具调用、会话记忆、回答校验、评测和监控构建，目标是把客服对话从“单轮问答”升级为可追踪、可扩展、可观测的 Agent 系统。

当前项目已经支持：

- General / Technical / Billing 多 Agent 路由
- Redis 工作记忆、长期记忆和用户画像持久化
- 独立用户凭证、会话归属和并发租约、Redis 用户配额
- Hybrid RAG：BM25、EmbeddingModel / hash vector、可选 ChromaDB 召回、RRF 融合、LLM rerank
- 知识库来源引用、上下文窗口扩展、证据不足降级
- 外置意图样本、Embedding 语义匹配和动态 Skills 注入
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
| Security | Spring Security（管理 API Key + 独立用户凭证、会话授权） |
| LLM | LangChain4j Anthropic、OpenAI 兼容 DeepSeek（BOM 1.22.0） |
| Embedding | 阿里云百炼 `text-embedding-v4`（与 ChatModel 独立配置） |
| RAG | BM25, LangChain4j EmbeddingModel / Chroma EmbeddingStore, local hash vector fallback, RRF/weighted fusion, LLM rerank |
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
     BM25 + EmbeddingModel / hash vector + ChromaDB 可选召回 + RRF 融合
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
| 文本向量 | `src/main/java/com/echomind/knowledge/TextEmbeddingService.java` |
| 模型配置 | `src/main/java/com/echomind/config/LangChain4jConfig.java` |
| 模型网关 | `src/main/java/com/echomind/llm/LangChain4jLlmGateway.java` |
| 意图样本 | `src/main/resources/intent-samples.json` |
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

### 3. 配置模型与凭证

复制环境变量文件：

```bash
cp .env.example .env
```

Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

为业务接口设置独立用户凭证；需要调用管理接口时另设管理凭证：

```env
ECHOMIND_USER_KEYS={"u1001":"replace-with-a-long-random-user-secret"}
ECHOMIND_API_KEY=replace-with-a-different-long-random-admin-secret
```

请替换示例值，用户和管理凭证必须不同。Docker Compose 会读取 `.env`；
应用从项目根目录直接启动时也会读取该文件，同名进程环境变量优先。
客户端调用 `/chat`、`/search` 时发送 `X-User-Key`，完整规则见下方“用户身份、会话与配额”。

默认 profile 是 Anthropic：

```env
SPRING_PROFILES_ACTIVE=anthropic
ANTHROPIC_API_KEY=your_key
ANTHROPIC_MODEL=claude-3-5-sonnet-20241022

# EmbeddingModel 独立使用阿里云百炼
DASHSCOPE_API_KEY=your_dashscope_key
DASHSCOPE_BASE_URL=https://dashscope.aliyuncs.com
DASHSCOPE_EMBEDDING_MODEL=text-embedding-v4
DASHSCOPE_EMBEDDING_DIMENSIONS=1024
CHROMA_COLLECTION=echomind_knowledge_dashscope_te4_1024_v1
```

切换 DeepSeek：

```env
SPRING_PROFILES_ACTIVE=deepseek
DEEPSEEK_API_KEY=your_key
DEEPSEEK_MODEL=deepseek-chat

# 切换 ChatModel 不影响独立的百炼 EmbeddingModel
DASHSCOPE_API_KEY=your_dashscope_key
DASHSCOPE_EMBEDDING_MODEL=text-embedding-v4
```

如果只想本地启动调试，可以保留：

```env
LLM_FALLBACK_ENABLED=true
```

`LLM_FALLBACK_ENABLED` 只控制聊天调用的本地降级；结构化调用失败仍向上抛出异常。
配置 `DASHSCOPE_API_KEY` 后才装配百炼 EmbeddingModel；未配置时使用本地 hash 向量，
并跳过 Chroma。需要真实语义检索时请提供有效 Key，只通过环境变量传入。

LangChain4j 迁移说明见 [docs/langchain4j-migration.md](docs/langchain4j-migration.md)。
现有 Anthropic / DeepSeek profile 和环境变量继续有效；原先直接设置的 `spring.ai.*`
配置需改为 `application.yml` 中的 `langchain4j.*`。可设 `CHROMA_ENABLED=false` 只使用本地检索。

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

> 鉴权说明：`/health` 与 Swagger 文档公开访问；`/chat`、`/search` 需要
> 请求头 `X-User-Key: <用户凭证>`，身份来自服务端配置的用户映射。
> 未配置用户凭证时，业务接口拒绝访问。
> 其余管理接口（`/knowledge/**`、`/skills/**`、`/mcp/**`、`/monitor`、`/metrics`、`/eval/**`）
> 需要请求头 `X-API-Key: <ECHOMIND_API_KEY>`。未配置 `ECHOMIND_API_KEY` 时，
> 管理接口一律拒绝访问（fail-closed）。

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
| POST | `/eval/run` | 提交评测任务（异步，返回 job_id） |
| GET | `/eval/status/{jobId}` | 查询评测任务状态与报告 |
| GET | `/docs` | Swagger UI |
| GET | `/v3/api-docs` | OpenAPI JSON |

### 对话示例

```bash
curl -X POST http://localhost:8080/chat \
  -H "X-User-Key: $ECHOMIND_USER_KEY" \
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
curl -X POST "http://localhost:8080/search?query=退款多久能到账&topK=3" \
  -H "X-User-Key: $ECHOMIND_USER_KEY"
```

### 添加知识库

```bash
curl -X POST http://localhost:8080/knowledge/add \
  -H "X-API-Key: $ECHOMIND_API_KEY" \
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
  -H "X-API-Key: $ECHOMIND_API_KEY" \
  -F "file=@docs.md"

> 上传仅支持 `.md` / `.txt` / `.json`，单文件不超过 10MB。
```

## 用户身份、会话与配额

服务端通过环境变量 `ECHOMIND_USER_KEYS` 配置用户 ID 到独立随机凭证的 JSON 映射，
例如 `{"u1001":"replace-with-a-long-random-user-secret"}`。也可通过外部 YAML 的
`echomind.security.user-keys` 配置；环境变量 JSON 覆盖同名用户的 YAML 条目。
用户 ID 支持 1–128 个 ASCII 字母、数字、`.`、`_`、`@`、`-`，首字符必须是字母或数字；
保留名 `anonymous` 不可作为认证身份。重复凭证、无效条目会拒绝用户认证；
用户凭证与管理凭证相同则同时禁用用户和管理鉴权，修复配置后重启生效。
无效或存在重复键/尾随内容的 JSON 会禁用全部用户凭证，
保持管理接口可用；不在绑定错误中回显凭证内容。
不要在前端公开整个凭证映射，真实凭证仅保存于服务端配置中。

客户端只发送自己的 `X-User-Key`。上面的 curl 示例使用客户端环境变量
`ECHOMIND_USER_KEY` 保存这一个凭证，它不同于服务端的 `ECHOMIND_USER_KEYS` 映射。
`user_id` 可以省略；传入时必须等于认证身份，冒充其他用户返回 403。
管理凭证不授予业务用户身份，业务用户凭证也不能调用管理接口。
在 `/docs` 的 Authorize 中分别填写 UserKey 或 AdminKey，文档会按接口选择对应请求头。

`conversation_id` 可省略，由服务端生成。Redis 原子绑定会话归属，并为处理中的会话持有租约：
其他用户使用同一会话 ID 返回 403，同一会话重叠请求返回 409，客户端应等待前一轮完成后重试。
归属按工作记忆 TTL 和请求预算设置保留期；租约释放只删除当前请求持有的令牌，异常退出由租约过期恢复。
访问控制存储不可用返回 503，不继续执行对话。

每个用户的 `/chat` 和 `/search` 共享 Redis 配额，默认首次请求起 60 秒内最多 60 次，
可通过 `ECHOMIND_USER_REQUESTS_PER_MINUTE` 调整。超限返回 429 和 `Retry-After`，
Redis 故障返回 503；认证失败不进入用户配额。Nginx 原有 IP 限流继续生效。

## RAG 和知识库

文档导入链路：

```text
文档
  -> 章节边界 + 句子切分 + token 上限 / overlap
  -> metadata: document_id, document_version, source, heading_path, paragraph_start, paragraph_end
  -> previous_chunk_id / next_chunk_id
  -> 批量 Embedding + 真倒排 BM25 索引 + 独立 hash 索引
  -> JSON 持久化正文、向量空间、模型与内容指纹
  -> 复用已生成向量，批量写入 LangChain4j EmbeddingStore（可用时）
```

查询链路：

```text
用户问题
  -> 带历史、知识库版本及配置指纹的最终结果缓存
  -> 原问题直接召回：BM25 + Chroma，Chroma 不可用时使用本地同空间向量
  -> 弱召回或历史指代时按需 LLM 改写 / 消解
  -> 有数量上限的多子查询并行召回
  -> 跨查询 RRF 合并 + 对原问题 / 消解后的问题重新计算证据相关性
  -> 候选池独立于 finalK，按 token 预算 LLM rerank，异常保留融合排序
  -> 命中 chunk 前后窗口扩展、重叠内容合并、上下文 token 预算
  -> 返回 knowledge_sources / context_sources
```

文本向量由 `TextEmbeddingService` 统一管理。当前 Spring 容器中的 `EmbeddingModel` 是独立配置的
阿里云百炼 `text-embedding-v4`（默认 1024 维），同时供意图识别、本地语义向量和 ChromaDB 使用；外部调用异常时，
本地相似度计算仍可退回独立的 1024 维 hash 索引。真实向量与 hash 即使维度相同也不混用；
外部模型恢复后补建缺失的真实向量。`score` 是证据分，`retrieval_score` 是融合排序分，
`rerank_rank` 是重排位置，不能把融合分直接用于证据充分性阈值。

Embedding 模型或输出维度发生变化时，必须切换到新的 `CHROMA_COLLECTION`，不能把不同模型生成的
向量混放在同一个 collection 中。JSON 中模型与内容指纹匹配的本地向量可直接复用；
不匹配或旧格式没有向量时批量重建。改变 Chroma collection 后应重新导入知识，确保新集合有完整数据。

同来源、同标题对应稳定的 `document_id`，重新导入会替换该文档的旧 chunk，并更新知识库版本使缓存失效。
正常结果采用有界 LRU 缓存；失败和降级结果不缓存。重复并发请求合并执行。
改写、召回、评分和重排共享 `timeout-ms` 总预算，改写和重排另有阶段预算。
部分失败保留有效证据并标记 `degraded=true`，全部失败返回 `success=false`；`/search` 同时返回缓存与耗时信息。
网络请求由 `AI_CONNECT_TIMEOUT` / `AI_READ_TIMEOUT` 限制，取消 Java Future 不代表底层 HTTP 已立即停止。

相关配置：

```yaml
echomind:
  rag:
    top-k: ${RAG_TOP_K:4}
    candidate-k: ${RAG_CANDIDATE_K:20}
    rerank-k: ${RAG_RERANK_K:20}
    bm25-weight: ${RAG_BM25_WEIGHT:0.45}
    vector-weight: ${RAG_VECTOR_WEIGHT:0.55}
    fusion-strategy: ${RAG_FUSION_STRATEGY:rrf}
    rrf-k: ${RAG_RRF_K:60}
    min-relevant-score: ${RAG_MIN_RELEVANT_SCORE:0.18}
    hash-min-relevant-score: ${RAG_HASH_MIN_RELEVANT_SCORE:0.18}
    min-sources: ${RAG_MIN_SOURCES:1}
    context-window: ${RAG_CONTEXT_WINDOW:1}
    max-chunk-tokens: ${RAG_MAX_CHUNK_TOKENS:512}
    overlap-tokens: ${RAG_OVERLAP_TOKENS:64}
    context-max-tokens: ${RAG_CONTEXT_MAX_TOKENS:3000}
    rewrite-enabled: ${RAG_REWRITE_ENABLED:true}
    max-rewrite-queries: ${RAG_MAX_REWRITE_QUERIES:3}
    rewrite-trigger-score: ${RAG_REWRITE_TRIGGER_SCORE:0.35}
    timeout-ms: ${RAG_TIMEOUT_MS:30000}
    rewrite-timeout-ms: ${RAG_REWRITE_TIMEOUT_MS:3000}
    rerank-timeout-ms: ${RAG_RERANK_TIMEOUT_MS:5000}
    rerank-max-input-tokens: ${RAG_RERANK_MAX_INPUT_TOKENS:4000}
    cache-max-entries: ${RAG_CACHE_MAX_ENTRIES:2000}
    cache-ttl-seconds: ${RAG_CACHE_TTL_SECONDS:300}
```

token 预算统一使用 CL100K 编码计数，作为分块和提示词的工程预算；不同模型的实际 tokenizer 仍需核对。
旧 JSON 保持兼容加载，不自动重切已有 chunk；旧文档重新导入后才应用新的章节和 token 分块策略。
候选数量和真实/hash 阈值是可调初值，需要用业务标注集校准。`min-sources` 统计不同文档，不把同一文档的多个 chunk 当成多份来源。

检索评测同时报告底层召回和生产链路（绕过缓存）的 Recall@K、MRR、nDCG、无答案误接纳率及 P95。
`retrieval_cases` 可提供 `relevant_chunk_ids` 或 `relevant_document_ids` 集合作为 Recall 分母；旧关键词用例仅统计 Hit/MRR，Recall/nDCG 为 `null`。
支持 `no_answer` 和 `history`。评测基线首次创建后保留，显式传 `update_baseline=true` 才替换。
模型调用与阶段耗时计数是共享统计的增量，并发流量可能影响数值；报告不估算缺少数据的 token 或费用。

当知识证据不足时：

- `/chat` 返回 `knowledge_sufficient=false`
- `/chat` 返回 `knowledge_warning`
- Agent prompt 注入 Knowledge Safety 提示
- `grounded` 会被压成 `false`
- 对话会倾向转人工确认，避免编造政策细节

## 意图识别

意图识别由 `IntentRecognizer` 负责。三个通道各自产出证据，再走**决策级联**定夺（不再做数值加权投票）：

```text
用户消息
  -> LLM 通道（chatStructured，要求 JSON 输出；区分不可用、超时、JSON/字段错误和未知标签）
  -> 语义通道（按来源分开的样本向量索引 + top1/margin）
  -> 模式通道（可配置关键词表 + 泛化词过滤 + 命中区间去重 + 句段内否定过滤）

决策级联（自上而下，命中即返回）：
  1. LLM 明确返回 other 且达到 llm-direct-threshold -> 拒识，领域词不能覆盖域外结论
  2. LLM 非 other 且达到 llm-direct-threshold       -> 采信 LLM
  3. LLM 低置信/不可用且语义强证据                   -> 采信语义；强 other 证据拒识
  4. 模式通道命中 >= pattern-min-hits 个独立内容词   -> 采信模式
  5. 模式单内容词得到语义佐证、压过弱闲聊相似度，或足以解释整条消息 -> 采信模式
  6. LLM 低置信/不可用且语义弱证据足够               -> 采信语义
  7. LLM 非 other 且达到 min-confidence             -> 保留 LLM 结果并标记低置信
  8. 其余情况                                       -> 拒识并澄清
```

`/chat` 会回传 `intent_confidence`、`intent_source`（`llm`/`semantic`/`pattern`/`abstain`）、`intent_entities` 与 `intent_abstained`，便于线上排查误判。三个通道的证据同时会注入 Agent 提示词的 `[Intent]` 段，并在评测报告里逐条落库。单内容词已有语义方向佐证时，不要求相似度达到独立路由的下限；明确业务词也优先于弱 `greeting`/`feedback` 相似度，高置信 LLM 和强语义证据仍优先。

语义匹配使用 `TextEmbeddingService`。向量携带来源、模型标识和维度，意图索引仅比较同一空间的向量。真实 embedding 与 1024 维 hash 各有完整独立索引，即使维度相同也不能混用。查询降级时直接使用本地 hash 索引；样本调用中途失败时，不发布部分真实、部分 hash 的混合索引。临时 hash 结果不进入真实模型缓存，恢复后会重新请求并补全真实索引。

**相似度不是正确率，两种后端的分数分布也不同。** `semantic-threshold`、`weak-score` 和 margin 应用独立验证集按实际后端标定。默认参数主要用于本地 hash 降级路径，不代表真实模型上线后的最优阈值。

识别缓存采用线程安全 LRU，默认最多 2000 条、有效期 300 秒。缓存键包含用户、会话、原始消息、完整模型提示词和样本版本；原始消息保留实体大小写。`cache-history-turns` 同时控制提示词与缓存使用的历史消息条数，默认 4 条（user/assistant 各算一条）。LLM 故障或真实 embedding 临时降级的结果不缓存，避免恢复后继续使用旧结论。

样本文件默认位于：

```text
src/main/resources/intent-samples.json
```

文件内 `_comment` 键是标签判据文档，加载时会被跳过；未知标签不会混入 `other`。`other` 样本同时用于 LLM few-shot 与语义索引，默认兜底样本也保留域外表达。

相关配置：

```yaml
echomind:
  intent:
    samples-path: ${INTENT_SAMPLES_PATH:classpath:intent-samples.json}
    semantic-enabled: ${INTENT_SEMANTIC_ENABLED:true}
    semantic-threshold: ${INTENT_SEMANTIC_THRESHOLD:0.45}
    semantic-margin: ${INTENT_SEMANTIC_MARGIN:0.05}
    semantic-top-k: ${INTENT_SEMANTIC_TOP_K:3}
    llm-direct-threshold: ${INTENT_LLM_DIRECT_THRESHOLD:0.6}
    min-confidence: ${INTENT_MIN_CONFIDENCE:0.5}
    pattern-min-hits: ${INTENT_PATTERN_MIN_HITS:2}
    cache-history-turns: ${INTENT_CACHE_HISTORY_TURNS:4}
    cache-max-entries: ${INTENT_CACHE_MAX_ENTRIES:2000}
    cache-ttl-seconds: ${INTENT_CACHE_TTL_SECONDS:300}
```

`/eval/run` 默认包含 58 条意图用例，涵盖复述、域外、否定和跨领域表达。回归测试检查它们与样本库不存在 NFKC 归一化、去标点和空白后的重复。评测走 `recognizeUncached` 绕过缓存。

报告包含主意图 accuracy、macro-F1、每类 precision/recall、混淆矩阵、接受覆盖率、域外错误接纳率、域内拒识率与识别延迟 P95。传入 `expected_domains` 的用例还会评测完整意图集合的 precision、recall 和 exact-match，报告提供实际参与此项评测的用例数。自定义意图用例支持可选 `history`；原有只传 `message`、`expected_intent` 的请求保持兼容。

### 多意图与协作路由

`IntentResult` 除主意图外还带 `secondaryIntents`：有效规则命中的其他领域，以及达到强语义阈值且至少有两个样本支持的候选。`IntentRecognizer.domainIntents()` 始终保留主意图并合并这些次意图，拒识结果的领域集合为空。

`AgentOrchestrator` 的协作判断**只使用这一份领域证据**，不再维护自己的关键词表：`domainIntents` 里每个领域经 `routing` 映射到 AgentType，命中多个即走并行协作。两条约束：

- 只有带有效内容词的规则命中才算领域证据。“自动续费/续费”在同一处只计一次；“不用退款”“账单没问题”等被否定的领域也不会靠整句语义相似度重新进入协作。
- 上游预先指定意图时，主意图、次意图和领域证据仍会合并；已有领域证据不能覆盖主意图。上游传入拒识标记时直接返回澄清话术。

多意图通过 `also_touches` 保留原始识别证据；编排器先生成有序 `DispatchPlan`，给各专业 Agent 分配独立领域任务。CRITICAL 请求统一由升级策略参与计划，单领域和多领域路径一致。回复按领域整合，并合并完全相同的共同建议；未完成领域明确展示，返回 `SUCCESS / PARTIAL / DEGRADED / FAILED / TIMEOUT / REJECTED / CANCELLED / CLARIFICATION` 状态与分支诊断。

单 Agent、并行分支、工具和降级共用请求截止时间。Agent 与阻塞操作使用独立有界线程池，饱和时显式拒绝，超时取消实际任务并停止后续调用。只读 MCP 查询在同一请求内共享；已有工具结果供降级、汇总和校验复用。最终回答最多修订一次，未通过校验的草稿不写入可信长期记忆。

`/chat` 与对话评测共用 `ChatPipeline`。评测隔离会话和记忆写入，并可用 `compare_single_agent: true` 比较同一用例的单 Agent 与协作结果。完整配置、接口字段、评测示例和验证边界见 [多 Agent 实现说明](docs/multi-agent-implementation.md)。

## Skills

Skills 默认从 `ECHOMIND_SKILLS_DIR` 指定目录（默认 `skills/`）发现 `SKILL.md`。内置规则拆为公共底线、四类角色基础 SOP 和十类场景：

- `common_guardrails`：所有 Agent 的隐私、事实和承诺边界。
- `general_customer_service`、`technical_support`、`billing_support`、`escalation_support`：按角色常驻，不依赖本轮关键词。
- 退款申请/到账、发票、订阅、支付争议、账户、登录、接口、超时、配置部署：先按 Agent 和任务意图过滤，再匹配场景关键词。

场景 Skill 的 YAML front matter 示例：

```markdown
---
id: api-diagnostics
name: 接口错误诊断
description: API 认证授权和服务端错误排查
agents: [technical, general]
intents: [technical]
keywords: 接口,API,500,401,403
enabled: true
always_apply: false
priority: 160
---

# 接口错误诊断

## 禁止事项
不得索取完整密钥，不得仅凭状态码断定根因。

## 升级条件
生产持续故障或需要后台权限时转二线技术。

## 排查步骤
收集脱敏错误码、发生时间、request_id 和最小复现。
```

元数据使用严格 YAML 校验，支持标准列表及旧的逗号格式；未知字段、无效类型、重复键/ID、缺少结束符或空正文会拒绝整次加载，保留上一成功版本。默认不注册普通 `.md/.txt/.json` 文件，`references/`、`scripts/`、`assets/` 及隐藏目录跳过；旧文件格式须显式开启 `echomind.skills.legacy-files-enabled`。

每次请求固定同一 Skills 快照，生成、校验和修复共享实际选中的业务规则。短追问可参考近期用户历史；新场景关键词或明确切换话题会重新选择，跨领域分支仍分别过滤。规则优先按常驻、优先级、相关性和稳定 ID 排序；总预算包含前言、标题、描述、分隔符及省略提示，按完整二级章节保留，优先保护禁止事项和升级条件。`/chat` 返回版本及分支选择追踪，便于检查命中来源、跳过原因与预算省略。

运行时接口需要 `X-API-Key`：

```bash
curl -H "X-API-Key: $ECHOMIND_API_KEY" http://localhost:8080/skills
curl -X POST -H "X-API-Key: $ECHOMIND_API_KEY" http://localhost:8080/skills/reload
```

reload 响应中的 `applied` 表示本次是否生效；`version` 和 `skills` 是当前有效快照，`errors` 描述本次加载错误。已有规则时空目录默认拒绝替换，确需清空可显式开启 `allow-empty-reload`。字段、默认限制、匹配和编写要求见 [Skills 文档](skills/README.md)。

## MCP 工具调用

MCP 默认关闭，且策略为 **fail-closed**：

- `allowed-tools` 为空 = 该 server 的全部工具拒绝调用（必须显式列出白名单）
- Agent 自动调用要求工具同时出现在 `read-only-tools` 中；该列表为空时自动调用一律拒绝
- 通配符支持任意位置的 `*` 与 `?`（如 `*secret*`）

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
curl -H "X-API-Key: $ECHOMIND_API_KEY" http://localhost:8080/mcp
curl -H "X-API-Key: $ECHOMIND_API_KEY" http://localhost:8080/mcp/tools
```

手动调用 MCP 工具：

```bash
curl -X POST http://localhost:8080/mcp/tools/remote/search/call \
  -H "X-API-Key: $ECHOMIND_API_KEY" \
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

最终用户消息与已校验的助手回答作为一个 turn 原子写入 Redis，助手的质量元数据继续保留。
压缩与画像更新由独立有界后台执行器处理，同一会话任务合并，避免在请求中等待模型摘要。
压缩提交会核对快照，保留摘要生成期间新追加的消息；未通过校验的助手草稿不进入可信长期记忆。
情景向量保存模型空间标识，只比较同空间向量；临时 hash 降级使用独立 hash 比较，
真实模型恢复后按需补建该用户的向量。旧 JSON 仍可加载，无历史用户不会产生空召回的 Embedding 调用。

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
curl -H "X-API-Key: $ECHOMIND_API_KEY" http://localhost:8080/monitor
curl -H "X-API-Key: $ECHOMIND_API_KEY" http://localhost:8080/metrics
```

评测接口：

```bash
# 提交评测（异步，立即返回 job_id）
curl -X POST http://localhost:8080/eval/run \
  -H "X-API-Key: $ECHOMIND_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{}'

# 轮询结果
curl -H "X-API-Key: $ECHOMIND_API_KEY" http://localhost:8080/eval/status/<job_id>
```

当前包含：

- Intent Accuracy
- Macro-F1
- per-class Precision / Recall / F1
- Retrieval Recall@K
- Retrieval MRR
- Retrieval Hit Rate
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
- 意图样本语义匹配
- Agent 工具调用 trace
- 统一 Agent Tool Registry
- MCP allowlist / denylist / read-only / dangerous 策略
- MCP JSON Schema 参数校验
- MCP stdio 集成测试
- MCP HTTP / SSE 测试
- RAG 切块、metadata、上下文扩展
- RAG 检索评测指标

新增用户鉴权与会话授权、共享配额、记忆向量隔离与恢复、后台压缩并发保留、部署超时预算回归。
默认测试使用替代外部服务；可指定本地 `redis-server` 可执行文件，启用额外真实 Redis Lua 测试：

```powershell
.\mvnw.cmd "-Dechomind.test.redis-server=D:\path\to\redis-server.exe" test
```

这些测试自行启动随机 loopback 端口、关闭持久化的临时实例，测试结束后只关闭自己创建的进程，
不读取 `.env` 或连接现有 Redis。它们验证原子配额、窗口过期、会话归属/租约、并发摘要裁剪。

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
| `DASHSCOPE_API_KEY` | 无 | 阿里云百炼 API Key；未配置时使用本地 hash 向量 |
| `DASHSCOPE_BASE_URL` | `https://dashscope.aliyuncs.com` | 百炼北京地域 API 根地址；可换成业务空间专属域名 |
| `DASHSCOPE_EMBEDDINGS_PATH` | `/compatible-mode/v1/embeddings` | 百炼 OpenAI 兼容路径，须以 `/` 开头、`/embeddings` 结尾 |
| `DASHSCOPE_EMBEDDING_MODEL` | `text-embedding-v4` | 独立 Embedding 模型 |
| `DASHSCOPE_EMBEDDING_DIMENSIONS` | `1024` | 向量维度；改变后必须使用新 collection |
| `LLM_FALLBACK_ENABLED` | `true` | LLM 失败时是否使用本地降级回复 |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6380` | Redis port（宿主机默认映射） |
| `REDIS_PASSWORD` | `echomind123` | Redis password |
| `CHROMA_HOST` | `http://localhost` | Chroma host |
| `CHROMA_ENABLED` | `true` | 是否启用 Chroma；需要已配置的外部 EmbeddingModel |
| `CHROMA_PORT` | `8002` | Chroma port（宿主机默认映射） |
| `CHROMA_COLLECTION` | `echomind_knowledge_dashscope_te4_1024_v1` | Chroma collection；更换向量模型或维度时需改名 |
| `ECHOMIND_DATA_DIR` | `data/java` | 数据目录 |
| `RAG_FUSION_STRATEGY` | `rrf` | RAG 融合策略，支持 `rrf` / `weighted` |
| `RAG_RRF_K` | `60` | RRF 排名平滑参数 |
| `INTENT_SAMPLES_PATH` | `classpath:intent-samples.json` | 意图样本文件 |
| `INTENT_SEMANTIC_ENABLED` | `true` | 是否启用语义（样本向量）通道 |
| `INTENT_SEMANTIC_THRESHOLD` | `0.45` | 语义通道独立定夺意图的相似度阈值（需按 embedding 后端标定） |
| `INTENT_SEMANTIC_MARGIN` | `0.05` | 语义独立定夺所需的 top1-top2 间距 |
| `INTENT_SEMANTIC_TOP_K` | `3` | 语义通道按标签聚合的窗口大小 |
| `INTENT_LLM_DIRECT_THRESHOLD` | `0.6` | LLM 置信度达到该值即直接采信 |
| `INTENT_MIN_CONFIDENCE` | `0.5` | 低于该置信度视为 LLM 弃权，交由其他通道补位 |
| `INTENT_PATTERN_MIN_HITS` | `2` | 模式通道独立定夺所需的最少内容词命中数 |
| `INTENT_CACHE_HISTORY_TURNS` | `4` | 提示词与缓存共同使用的历史消息条数 |
| `INTENT_CACHE_MAX_ENTRIES` | `2000` | 识别结果缓存容量（LRU） |
| `INTENT_CACHE_TTL_SECONDS` | `300` | 识别缓存有效期，0 表示关闭 |
| `MCP_ENABLED` | `false` | 是否启用 MCP |
| `ECHOMIND_API_KEY` | 空 | 管理接口 API Key（X-API-Key 头）；为空时管理接口拒绝访问 |
| `ECHOMIND_USER_KEYS` | 空 | 用户 ID 到凭证的 JSON 映射（X-User-Key 头）；为空时业务接口拒绝访问 |
| `ECHOMIND_USER_REQUESTS_PER_MINUTE` | `60` | 每个用户 chat/search 共享的 60 秒窗口配额 |
| `AGENT_REQUEST_TIMEOUT_MS` | `50000` | 请求总等待预算；必须低于网关等待时间 |
| `AGENT_BRANCH_TIMEOUT_MS` | `30000` | 分支预算，同时受请求剩余时间限制 |
| `ECHOMIND_CORS_ORIGINS` | 空 | CORS 允许来源（逗号分隔），为空不开放跨域 |
| `MCP_AUTO_TOOL_ENABLED` | `true` | Agent 是否自动选择 MCP 工具 |

## 当前限制

- 用户认证当前采用服务端配置的独立 API 凭证，尚未接入登录页面、OAuth2/OIDC 或用户自助凭证管理。
- 默认应用预算 50 秒、分支 30 秒，模型读取 30 秒、连接 3 秒，Nginx 等待 60 秒；提高应用预算时必须同步调整网关。同步 Servlet 请求不能即时感知客户端提前断开，非中断式远端 I/O 仍依赖传输超时。
- 长期记忆与画像仍为单实例 JSON 持久化；会话归属、租约和配额通过共享 Redis 控制。扩展多副本前需要统一长期存储，现有记忆压缩 Lua 不面向 Redis Cluster 部署。

- ChromaDB 召回依赖阿里云百炼 Embedding API；短时调用失败时本地检索仍可降级到 BM25 + hash vector，但 Chroma 写入或查询会跳过。
- hash 向量主要反映词面与 n-gram 重合，弱证据路径仍可能误接纳带领域词的域外表达；局部否定规则也不能覆盖反讽、引用和所有复杂语法。泛化质量需要真实 LLM/embedding 与独立线上标注数据验证。
- 意图评测默认集为 58 条人工标注用例，规模仍偏小；本地降级评测和测试通过率都不能代替真实模型的线上准确率。
- MCP HTTP / SSE 当前按 JSON-RPC POST 兼容常见服务端；更复杂的长连接 session 管理可继续增强。
- 当前尚未提供 CLI 模式。
- 监控侧还可以继续增强更细粒度的异常检测、趋势分析和告警降噪。
