# EchoMind Skills 文档

EchoMind 启动时从 `ECHOMIND_SKILLS_DIR`（默认 `skills/`）加载业务 Skills，按 Agent 和任务场景注入 system prompt。Skills 用于维护客服 SOP、核验要求、禁止事项和升级条件；它们是业务约束，不是订单、服务状态或退款结果等事实证据，也不能授予工具权限。

## 内置规则结构

当前包含十五份独立规则：

| 层级 | 目录 | 用途 |
| --- | --- | --- |
| 公共底线 | `common_guardrails` | 所有 Agent 的隐私、事实和承诺边界 |
| 角色基础 | `general_customer_service` | 接待、澄清、分流 |
| 角色基础 | `technical_support` | 技术诊断、验证、二线升级 |
| 角色基础 | `billing_support` | 资金核验、费用说明、财务审核 |
| 角色基础 | `escalation_support` | 投诉、权限、安全事件、人工交接 |
| 账单场景 | `refund_application`、`refund_arrival` | 退款资格、申请与到账核查 |
| 账单场景 | `invoice_support`、`subscription_support` | 发票与订阅续费 |
| 账单场景 | `payment_dispute` | 重复扣款、支付未生效、优惠和费用争议 |
| 账户场景 | `account_support` | 资料、找回、会员状态、安全核验 |
| 技术场景 | `login_support`、`api_diagnostics` | 登录、接口、认证授权与回调 |
| 技术场景 | `connectivity_timeout`、`configuration_deployment` | 超时、网络、配置和部署 |

公共和角色基础使用 `always_apply: true`，不由关键词拦截。场景规则保留原有 SOP 的处理要求，通过 `intents` 和 `keywords` 限定当前任务。账单和技术场景允许 `general` 作为适用 Agent，支持专业 Agent 不可用时的降级；意图过滤仍阻止无关场景套用。

项目现有路由将 `ACCOUNT` 分配给 `BillingAgent`，因此账户及登录场景也允许 `billing`，并通过 `account/technical` 意图限制适用范围。这只是匹配既有路由，工具执行和敏感账户操作仍须遵守原有权限与核验边界。

## 文件和元数据

推荐路径：

```text
skills/<skill_name>/SKILL.md
```

文件使用 UTF-8。默认只发现大小写完全一致的 `SKILL.md`；`README.md`、隐藏文件/目录以及 `references/`、`scripts/`、`assets/` 不注册为独立 Skill。参考材料可保存在所属目录中，但不会自动注入或执行。

front matter 使用严格 YAML，开头和结尾各有一行 `---`，正文必须包含实际规则。例如：

```markdown
---
id: api-diagnostics
name: 接口错误诊断
description: API 认证授权和服务端错误排查
agents:
  - technical
  - general
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

允许的 YAML 元数据字段仅有：

| 字段 | 类型与行为 |
| --- | --- |
| `id` | 稳定且全目录唯一的标识，建议英语 slug；未填时由相对目录/路径生成 |
| `name` | 展示名称；未填时取首个标题或目录名 |
| `description` | 简短说明，未填时为空 |
| `agents` | 字符串列表：`general`、`technical`、`billing`、`escalation`；为空适用所有 Agent |
| `intents` | 字符串列表：`query`、`complaint`、`request`、`greeting`、`escalation`、`technical`、`billing`、`account`、`feedback`、`other`；为空不限制意图 |
| `keywords` | 字符串列表或英文/中文逗号分隔文本，忽略大小写；英文词按 token 边界匹配 |
| `enabled` | 使用 `true/false`，默认 `true` |
| `always_apply` | 使用 `true/false`，默认 `false`；通过 Agent/意图限制后，跳过关键词判断 |
| `priority` | 32 位整数，默认 `0`，越大越先注入 |

`agents`、`intents`、`keywords` 均支持标准 YAML 列表，也兼容已有的逗号分隔字符串。纯数字关键词放在列表中时必须加引号，例如 `keywords: ["500", "401"]`。

未知字段、无效 Agent/意图、类型错误、重复元数据键、重复 Skill ID、未关闭的 front matter、非法 UTF-8、空正文或仅含标题的正文都使本次候选加载失败。错误不会静默移除限制或发布其余文件。

未带 front matter 的旧正文仍可读取并使用默认元数据。若确需兼容普通 `.md/.txt/.json`，显式开启 `legacy-files-enabled`；JSON 可额外用 `content` 或 `instructions` 保存正文。兼容模式仍跳过 README、隐藏目录和 references/scripts/assets，并执行相同类型、ID、大小和范围校验。新规则应采用 `SKILL.md`。

## 选择和多轮行为

1. 过滤未启用、Agent 不适用及声明意图不匹配的规则。意图来自本轮结构化任务/分支上下文；检索内容不参与规则选择。
2. `always_apply` 的公共/基础 SOP 直接入选；非常驻场景有关键词时必须命中，只有意图且无关键词时按意图入选。没有意图和关键词的旧规则按 Agent 范围常驻匹配，编写新场景时应明确范围。
3. 当前消息优先。如果没有当前场景关键词且属于短追问，参考最近六条历史中的用户消息，沿用最近有匹配的场景；助手回复不会作为触发输入。最近明确话题不属于当前分支时停止回溯，避免越过新话题继承旧场景。“另外”“换个话题”等表达阻断旧主题；如果同一条历史消息建立了新主题，后续追问仍可继承该新主题。
4. 每个分支分别选择，防止账单和技术任务共享无关场景。入选顺序固定为常驻优先、`priority` 降序、匹配相关性降序、稳定 `id` 升序。

例如“显示退款成功还没到账”后问“那多久？”，可以通过历史延续到账场景；明确改问“接口 500”时按新任务领域匹配技术场景。短追问继承的是已命中的场景，不会凭空推断新的规则。

`/chat` 响应的 `skill_snapshot_version` 和 `skill_selections` 提供请求版本及分支选择记录。每条记录包括 `request_id`、`branch_id`、`snapshot_version`、`skill_id`、`agent_type`、`match_source`、`priority`、`included_chars`、`truncated`、`skipped_reason`。可检查常驻/意图/本轮关键词/历史命中，以及角色或意图不匹配、未命中和预算不足等跳过原因；这些记录不充当事实证据。

## 预算和编写要求

默认总动态 prompt 预算为 5000 字符，每份 Skill 上限为 3200 字符。预算包含前言、标题、描述、分隔符和省略提示；预算为零时不注入。

规则以完整 `##` 二级章节为取舍单位，不在句子或代码块中间硬截断。标题含“禁止”“升级”“安全”“边界”“隐私”等的章节优先保留；如果这些必要章节不能完整容纳，整份规则跳过并记录原因。剩余预算用于完整的处理章节，省略时带提示。

适用的常驻基础规则无法完整容纳必要章节时，本次动态规则整段不注入；场景规则记录 `baseline_unavailable`，避免只应用场景而失去基础约束。应根据选择诊断调整预算。

- 公共规则只写通用底线；角色 SOP 写稳定职责；场景规则写具体核验和操作步骤，减少重复大段内容。
- 公共 `priority: 1000`，角色基础 `900`，内置场景 `130-180`；新场景通常用 `100-200`。
- 必须明确禁止事项和升级条件，并使用完整二级章节；把大流程拆成有独立意义的小章节。
- 只要求本轮必要且脱敏的信息，不索取密码、验证码、完整密钥等敏感认证内容。
- 不编造系统事实、官方入口或时效；需要订单状态、人工审核或渠道核验时写清条件。
- 工具能力与权限继续由 Agent Registry、MCP 策略和官方审核流程控制，Skill 文字不能授权执行操作。

## 配置和热加载

```yaml
echomind:
  skills:
    root-dir: ${ECHOMIND_SKILLS_DIR:skills}
    max-prompt-chars: 5000
    max-skill-chars: 3200
    legacy-files-enabled: false
    max-file-bytes: 262144
    max-files: 128
    max-depth: 8
    allow-empty-reload: false
```

`max-file-bytes` 限制每个候选文件字节数，`max-files` 限制候选数量，`max-depth` 限制相对根路径的层级；超限会拒绝本次加载。文件必须位于实际 Skills 根目录内，不允许通过文件链接读取根目录外规则。

未被排除的目录超出深度上限也会拒绝候选，避免发布未扫描完整的规则集合；资源目录和隐藏目录先剪枝，不参与该检查。

修改文件后可热加载，无需重启。默认端口为 `8080`，以下管理接口须提供配置的 API Key：

```bash
curl -H "X-API-Key: $ECHOMIND_API_KEY" http://localhost:8080/skills
curl -X POST -H "X-API-Key: $ECHOMIND_API_KEY" http://localhost:8080/skills/reload
```

加载先构建并校验完整候选集合，成功后一次发布不可变版本快照；任意解析、目录、重复 ID 或限制错误都保留上一成功版本。已有规则时，空候选默认拒绝替换；确需清空须显式设置 `allow-empty-reload: true`。首轮启动没有旧规则时，空目录可加载为空快照；目录不存在仍报错。

reload 和查看接口的响应包含 `success/reload_success`、`applied`、`version`、`loaded_at`、`count`、`skills`、`errors`、`attempted_at` 和 `attempted_root_dir`。成功加载使版本递增；失败响应的 `skills/version` 仍指当前有效快照，`errors` 是本次错误。

每个请求固定一次快照，即使期间热加载，生成、路由和分支选择仍使用该版本。回答校验与修复接收本次实际选中的业务规则，并保留其核验/禁止/升级约束；事实正确性仍依据独立系统和工具证据。
