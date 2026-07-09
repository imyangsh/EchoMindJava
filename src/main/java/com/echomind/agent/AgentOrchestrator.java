package com.echomind.agent;

import com.echomind.intent.IntentCategory;
import com.echomind.intent.IntentRecognizer;
import com.echomind.intent.IntentResult;
import com.echomind.intent.UrgencyLevel;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Service
public class AgentOrchestrator {

    //意图识别器
    private final IntentRecognizer intentRecognizer;
    //Agent 池（每种类型一个列表，支持同类型多实例）
    private final Map<AgentType, List<BaseAgent>> pool;
    //意图→Agent 的静态路由表
    private final Map<IntentCategory, AgentType> routing = new EnumMap<>(IntentCategory.class);

    public AgentOrchestrator(IntentRecognizer intentRecognizer, Map<AgentType, List<BaseAgent>> pool) {
        this.intentRecognizer = intentRecognizer;
        this.pool = pool;
        routing.put(IntentCategory.TECHNICAL, AgentType.TECHNICAL);
        routing.put(IntentCategory.BILLING, AgentType.BILLING);
        routing.put(IntentCategory.ACCOUNT, AgentType.BILLING);
        routing.put(IntentCategory.ESCALATION, AgentType.ESCALATION);
    }

    public OrchestratorResult run(AgentRequest request) {
        //记录起始时间戳，这个值会在最后用来计算整条链路的耗时
        Instant start = Instant.now();
        AgentRequest req = request;

        //只有当上游没有预先识别意图时，才调用 intentRecognizer 进行识别。
        if (req.intent() == null) {
            //recognize 方法接收两个参数：当前消息 req.message() 和历史对话 req.history()。
            //返回一个 IntentResult，其中包含两个维度——意图类别（如技术咨询、账单查询、投诉等）和紧急程度（urgency）。
            IntentResult intentResult = intentRecognizer.recognize(req.message(), req.history());
            //识别完成后，通过 withIntent 把这两个值"挂"到请求对象上，得到一个新的 req，后续所有环节都基于这个带意图的请求来决策。
            req = req.withIntent(intentResult.intent(), intentResult.urgency());
        }

        //这一行调用 collaborationTargets(req)，根据已经识别出的意图和紧急程度，判断当前请求是否需要多个 Agent 协同处理
        List<AgentType> collaboration = collaborationTargets(req);
        /*
        并行路径（collaboration.size() > 1）：
        当需要多个 Agent 协作时，调用 runParallel(req, collaboration)，
        并行地把请求分发给所有目标 Agent，最后再汇总各自的输出。这是系统能处理复杂、跨域问题的能力来源。
         */
        AgentResponse response = collaboration.size() > 1
                ? runParallel(req, collaboration)
                /*
                串行路径：否则走单 Agent 执行。
                这里先调用 route(req.intent(), req.urgency())
                根据"意图 + 紧急程度"路由出唯一一个最合适的 AgentType，再交给 execute(req, ...) 执行。
                 */
                : execute(req, route(req.intent(), req.urgency()));

        //用三个条件的"或"来决定是否要把这次对话升级给人工
        boolean escalated = response.escalate()     //Agent 自己判断搞不定
                || req.urgency() == UrgencyLevel.CRITICAL   //紧急程度达到最高级
                || req.intent() == IntentCategory.ESCALATION;   //意图本身就是"要求人工/投诉"，这是用户明确表达的升级诉求。

        //把所有关键信息打包成一个 OrchestratorResult 返回
        return new OrchestratorResult(
                req.requestId(),
                response.content(),
                response.agentType(),
                req.intent(),
                escalated,
                Duration.between(start, Instant.now()).toMillis(),  //链路耗时
                response.toolCalls()    //工具调用记录
        );
    }

    //多agent并行协作
    private AgentResponse runParallel(AgentRequest req, List<AgentType> targets) {
        /*这一步用流式 API 遍历所有目标 Agent 类型,
         对每一个 type 都通过 CompletableFuture.supplyAsync 提交一个异步任务
         任务体就是 execute(req, type),即把这个请求交给该类型的 Agent 去执行。
         */
        List<CompletableFuture<AgentResponse>> futures = targets.stream()
                .map(type -> CompletableFuture.supplyAsync(() -> execute(req, type)))
                .toList();
        //遍历所有 Future,逐个调用 join() 阻塞等待结果。
        List<AgentResponse> responses = futures.stream().map(CompletableFuture::join).toList();


        String content = responses.stream()
                //先过滤掉失败的 Agent,只保留成功返回的结果
                .filter(AgentResponse::success)
                /*
                把每个成功回复格式化成 "[agent类型]\n回复内容" 的形式。
                比如技术 Agent 的回复会变成 [technical]\n关于这个功能的技术说明...,账单 Agent 则是 [billing]\n您的账单信息...。
                加上类型标签是为了让用户清楚每段回答来自哪个专业 Agent,体现"分工协作"的透明度。
                 */
                .map(r -> "[" + r.agentType().name().toLowerCase(Locale.ROOT) + "]\n" + r.content())
                //用双换行把所有片段串起来,形成多段式回复。
                .reduce((a, b) -> a + "\n\n" + b)
                //如果所有 Agent 都失败了(reduce 在空流上返回空 Optional),就给一个兜底提示。
                .orElse("抱歉，所有 Agent 均处理失败。");
        //用 anyMatch 判断:只要有任何一个 Agent 标记了升级,整个并行结果就升级
        boolean escalate = responses.stream().anyMatch(AgentResponse::escalate);
        //把所有 Agent 各自的耗时取最大值,作为这次并行协作的总耗时
        long latency = responses.stream().mapToLong(AgentResponse::latencyMs).max().orElse(0);

        //用 flatMap 把每个 Agent 的工具调用列表"摊平"合并成一个总的列表
        List<ToolCallTrace> toolCalls = responses.stream()
                .flatMap(response -> response.toolCalls().stream())
                .toList();
        return new AgentResponse(targets.getFirst(), content, true, 1.0, latency, escalate, toolCalls);
    }

    private AgentType route(IntentCategory intent, UrgencyLevel urgency) {
        if (urgency == UrgencyLevel.CRITICAL) {
            return AgentType.ESCALATION;
        }
        AgentType target = routing.get(intent);
        if (target != null && pool.containsKey(target)) {
            return target;
        }
        return AgentType.GENERAL;
    }

    //根据意图类别和消息关键词，判断当前请求需要哪些类型的 Agent 参与
    private List<AgentType> collaborationTargets(AgentRequest request) {
        //空值防御：request.message() 可能为 null，直接调用 toLowerCase 会抛 NPE
        String msg = request.message() == null ? ""
                //小写归一：转成小写是为了让后面的关键词匹配不区分大小写。
                : request.message().toLowerCase(Locale.ROOT);
        LinkedHashSet<AgentType> targets = new LinkedHashSet<>();

        //判定是否需要技术 Agent 介入，判断条件是​"意图 OR 关键词"​的二选一触发
        if (request.intent() == IntentCategory.TECHNICAL
                || containsAny(msg, "崩溃", "报错", "error", "crash", "无法登录", "500", "401")) {
            targets.add(AgentType.TECHNICAL);
        }
        //判定账单 Agent 是否需要介入
        if (request.intent() == IntentCategory.BILLING
                || request.intent() == IntentCategory.ACCOUNT
                || containsAny(msg, "退款", "扣款", "发票", "账单", "支付", "订阅", "refund", "invoice")) {
            targets.add(AgentType.BILLING);
        }
        return new ArrayList<>(targets.stream().filter(pool::containsKey).toList());
    }

    //判断关键词命中
    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    //它接收一个请求和目标 Agent 类型,完成三件事:挑选最合适的 Agent 实例、执行它、失败时降级到通用 Agent 重试
    private AgentResponse execute(AgentRequest req, AgentType agentType) {

        //第一级:bestAgent(agentType) 先尝试获取指定类型的最佳 Agent。
        BaseAgent agent = bestAgent(agentType)
                //第二级:如果指定类型没有可用实例,通过 orElseGet 惰性降级到 bestAgent(AgentType.GENERAL),即通用兜底 Agent。
                .orElseGet(() -> bestAgent(AgentType.GENERAL)
                        //最终兜底:如果连通用 Agent 也没有,最内层 .orElse(null) 返回 null,交给下一步处理。
                        .orElse(null));

        //如果两级降级后 agent 仍然为 null,说明连通用 Agent 都没有可用实例
        if (agent == null) {
            return new AgentResponse(AgentType.GENERAL, "服务暂时不可用，请稍后重试。", false, 0.0, 0, false);
        }
        //拿到了可用的 Agent 实例后,调用它的 handle(req) 方法执行实际业务
        AgentResponse response = agent.handle(req);
        //指定 Agent 执行失败了并且当前执行的不是通用 Agent
        if (!response.success() && agentType != AgentType.GENERAL) {
            //如果通用 Agent 可用,map 会执行 a.handle(req),返回通用 Agent 的处理结果
            return bestAgent(AgentType.GENERAL).map(a -> a.handle(req))
                    //如果通用 Agent 也不可用,map 不执行,orElse(response) 返回原始失败响应
                    .orElse(response);
        }
        return response;
    }

    private Optional<BaseAgent> bestAgent(AgentType agentType) {
        //这一行从 pool（一个 Map<AgentType, List<BaseAgent>>）中取出对应类型的 Agent 列表
        return pool.getOrDefault(agentType, List.of())  //如果没有（比如该类型从未注册,或运行中被动态移除）,返回 List.of()——一个不可变的空列表。
                .stream()
                //用流的 max 操作配合一个自定义 Comparator 找出得分最高的 Agent
                .max(Comparator.comparingDouble(a -> a.stats().routingScore()));
    }


    public Map<String, Object> stats() {
        Map<String, Object> result = new HashMap<>();
        pool.forEach((type, agents) -> {
            for (int i = 0; i < agents.size(); i++) {
                BaseAgent agent = agents.get(i);
                Map<String, Object> data = new HashMap<>();
                data.put("total", agent.stats().total());
                data.put("success_rate", round(agent.stats().successRate()));
                data.put("avg_ms", round(agent.stats().avgLatencyMs()));
                data.put("monitor_penalty", round(agent.stats().monitorPenalty()));
                data.put("routing_score", round(agent.stats().routingScore()));
                result.put(type.name().toLowerCase(Locale.ROOT) + "_" + i, data);
            }
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    public void updateRoutingPenalties(Map<String, Double> penalties) {
        pool.forEach((type, agents) -> {
            for (int i = 0; i < agents.size(); i++) {
                agents.get(i).stats().setMonitorPenalty(penalties.getOrDefault(type.name().toLowerCase(Locale.ROOT) + "_" + i, 0.0));
            }
        });
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
