package com.echomind.agent;

import com.echomind.config.EchoMindProperties;
import com.echomind.intent.IntentCategory;
import com.echomind.intent.IntentRecognizer;
import com.echomind.intent.IntentResult;
import com.echomind.intent.RecognitionOutcome;
import com.echomind.intent.UrgencyLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/** Plans scoped work and owns each branch's single terminal transition. */
@Service
public class AgentOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final int LONG_UNRESOLVED_MESSAGE_CHARS = 60;

    private final IntentRecognizer intentRecognizer;
    private final Map<AgentType, List<BaseAgent>> pool;
    private final ExecutorService agentExecutor;
    private final ExecutorService operationExecutor;
    private final EchoMindProperties properties;
    private final Map<IntentCategory, AgentType> routing = new EnumMap<>(IntentCategory.class);
    private final Map<AgentType, Integer> selectionCursor = new EnumMap<>(AgentType.class);

    @Autowired
    public AgentOrchestrator(IntentRecognizer intentRecognizer, Map<AgentType, List<BaseAgent>> pool,
                             @Qualifier("agentExecutor") ExecutorService agentExecutor,
                             EchoMindProperties properties,
                             @Qualifier("operationExecutor") ExecutorService operationExecutor) {
        this.intentRecognizer = intentRecognizer;
        Map<AgentType, List<BaseAgent>> copy = new EnumMap<>(AgentType.class);
        pool.forEach((type, agents) -> copy.put(type, List.copyOf(agents)));
        this.pool = Map.copyOf(copy);
        this.agentExecutor = agentExecutor;
        this.operationExecutor = operationExecutor;
        this.properties = properties;
        routing.put(IntentCategory.TECHNICAL, AgentType.TECHNICAL);
        routing.put(IntentCategory.BILLING, AgentType.BILLING);
        routing.put(IntentCategory.ACCOUNT, AgentType.BILLING);
        routing.put(IntentCategory.ESCALATION, AgentType.ESCALATION);
    }

    /** Retained for standalone callers and existing tests. */
    public AgentOrchestrator(IntentRecognizer intentRecognizer, Map<AgentType, List<BaseAgent>> pool,
                             ExecutorService agentExecutor) {
        this(intentRecognizer, pool, agentExecutor, new EchoMindProperties(), null);
    }

    public OrchestratorResult run(AgentRequest request) {
        long started = System.nanoTime();
        boolean ownsContext = request.executionContext() == null;
        ExecutionContext context = request.executionContext();
        if (context == null) {
            context = operationExecutor == null
                    ? ExecutionContext.standalone(properties.getAgent().getRequestTimeoutMs())
                    : new ExecutionContext(properties.getAgent().getRequestTimeoutMs(), operationExecutor);
        }
        AgentRequest req = request.withExecutionContext(context);
        try {
            context.checkActive();
            // Freeze the catalog before classification or concurrent branch dispatch.
            ExecutionContext rootContext = context;
            pool.values().stream().flatMap(List::stream).findFirst()
                    .ifPresent(agent -> agent.prepareSkills(rootContext));
            if (req.intent() == null) {
                AgentRequest unclassified = req;
                RecognitionOutcome outcome = context.call("intent", () -> intentRecognizer.recognizeDetailed(
                        unclassified.message(), unclassified.userId(), unclassified.conversationId(), unclassified.history()));
                IntentResult intent = outcome.result();
                req = req.withIntentMetadata(intent.intent(), intent.urgency(), intent.confidence(), intent.source(),
                        intent.entities(), intent.abstained(), intent.secondaryIntents(), intentRecognizer.domainIntents(outcome));
            }
            if (req.intentAbstained()) {
                boolean escalate = req.urgency() == UrgencyLevel.CRITICAL
                        || (req.message() != null && req.message().length() > LONG_UNRESOLVED_MESSAGE_CHARS);
                return result(req, AgentType.GENERAL, clarificationResponse(req), escalate,
                        ExecutionStatus.CLARIFICATION, List.of(), started);
            }

            DispatchPlan plan = dispatchPlan(req);
            List<BranchHandle> handles = new ArrayList<>();
            for (AgentTask task : plan.tasks()) {
                ExecutionContext branchContext = context.fork(task.id(), properties.getAgent().getBranchTimeoutMs());
                BranchHandle handle = new BranchHandle(task, req.withTask(task).withExecutionContext(branchContext));
                handles.add(handle);
                submit(handle);
            }
            List<BranchResult> branches = handles.stream().map(this::await).toList();
            ExecutionStatus status = combinedStatus(branches);
            boolean escalated = plan.escalated() || status != ExecutionStatus.SUCCESS
                    || handles.stream().anyMatch(handle -> handle.escalated);
            return result(req, plan.primaryAgent(), compose(branches), escalated, status, branches, started);
        } catch (ExecutionContext.ExecutionAbortedException ex) {
            return result(req, AgentType.GENERAL, unavailableMessage(ex.status()), true, ex.status(), List.of(), started);
        } catch (RuntimeException ex) {
            log.warn("Unable to plan request {}", req.requestId(), ex);
            return result(req, AgentType.GENERAL, unavailableMessage(ExecutionStatus.FAILED), true,
                    ExecutionStatus.FAILED, List.of(), started);
        } finally {
            if (ownsContext) context.close();
        }
    }

    /** Urgency, primary intent and domain evidence all feed the same ordered plan. */
    public DispatchPlan dispatchPlan(AgentRequest request) {
        boolean escalate = request.urgency() == UrgencyLevel.CRITICAL
                || request.collaborationDomains().contains(IntentCategory.ESCALATION);
        AgentType domainPrimary = routing.get(request.intent());
        if (domainPrimary == null) {
            domainPrimary = request.collaborationDomains().stream().map(routing::get)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(AgentType.GENERAL);
        }
        AgentType primary = request.urgency() == UrgencyLevel.CRITICAL && hasAgent(AgentType.ESCALATION)
                ? AgentType.ESCALATION : domainPrimary;
        Map<AgentType, List<IntentCategory>> assignments = new LinkedHashMap<>();
        assignments.put(primary, new ArrayList<>());
        if (primary == AgentType.ESCALATION) assignments.get(primary).add(IntentCategory.ESCALATION);
        boolean collaboration = request.executionContext() == null || request.executionContext().collaborationEnabled();
        for (IntentCategory domain : request.collaborationDomains()) {
            AgentType type = routing.get(domain);
            if (type != null && (collaboration || type == primary)) {
                List<IntentCategory> domains = assignments.computeIfAbsent(type, ignored -> new ArrayList<>());
                if (!domains.contains(domain)) domains.add(domain);
            }
        }
        List<AgentTask> tasks = assignments.entrySet().stream()
                .map(entry -> new AgentTask(request.requestId() + "/" + entry.getKey().name().toLowerCase(Locale.ROOT),
                        entry.getKey(), instruction(entry.getKey()), entry.getValue()))
                .toList();
        return new DispatchPlan(primary, tasks, escalate);
    }

    private boolean hasAgent(AgentType type) { return !pool.getOrDefault(type, List.of()).isEmpty(); }

    private String instruction(AgentType type) {
        String scope = switch (type) {
            case TECHNICAL -> "仅负责技术故障、错误原因与排查步骤";
            case BILLING -> "仅负责账单、退款、支付和账户事项";
            case ESCALATION -> "仅负责人工升级、投诉受理和交接信息；不要替其他专家重做业务判断";
            case GENERAL -> "负责当前问题的综合说明；缺少专业证据时明确说明限制";
        };
        return scope + "。保留原始用户诉求，仅回答本任务负责的领域，不重复其他分支的建议。"
                + "只使用已提供或工具取得的证据，列出可执行的下一步，不宣称未执行的外部操作已经完成。";
    }

    private void submit(BranchHandle handle) {
        Thread submittingThread = Thread.currentThread();
        try {
            handle.context().checkActive();
            handle.future = agentExecutor.submit(() -> {
                // Also protect standalone/custom executors configured with CallerRunsPolicy.
                if (Thread.currentThread() == submittingThread) {
                    return handle.complete(ExecutionStatus.REJECTED, "", "agent capacity exhausted");
                }
                return execute(handle);
            });
        } catch (RejectedExecutionException ex) {
            handle.complete(ExecutionStatus.REJECTED, "", "agent capacity exhausted");
        } catch (ExecutionContext.ExecutionAbortedException ex) {
            handle.complete(ex.status(), "", ex.getMessage());
        }
    }

    private BranchResult await(BranchHandle handle) {
        if (handle.terminal != null) return handle.terminal;
        try {
            while (true) {
                if (handle.future.isDone()) return handle.future.get();
                long remaining = handle.context().remainingMillis();
                if (remaining <= 0) throw new TimeoutException("branch deadline exceeded");
                handle.context().checkActive();
                try {
                    return handle.future.get(Math.min(50, remaining), TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // Observe root cancellation promptly without extending the deadline.
                    if (handle.context().remainingMillis() <= 0) throw new TimeoutException("branch deadline exceeded");
                    handle.context().checkActive();
                }
            }
        } catch (ExecutionContext.ExecutionAbortedException ex) {
            return abort(handle, ex.status(), ex.getMessage());
        } catch (TimeoutException ex) {
            return abort(handle, ExecutionStatus.TIMEOUT, "branch deadline exceeded");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return abort(handle, ExecutionStatus.CANCELLED, "request interrupted");
        } catch (CancellationException ex) {
            return abort(handle, ExecutionStatus.CANCELLED, "branch cancelled");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            ExecutionStatus status = cause instanceof ExecutionContext.ExecutionAbortedException aborted
                    ? aborted.status() : ExecutionStatus.FAILED;
            return abort(handle, status, cause == null ? "branch failed" : cause.getMessage());
        }
    }

    private BranchResult abort(BranchHandle handle, ExecutionStatus status, String error) {
        // Set terminal and close stats before interrupting the worker. A late response
        // must not turn this timeout into a successful attempt.
        BranchResult result = handle.complete(status, "", error);
        handle.context().cancel();
        if (handle.future != null) handle.future.cancel(true);
        return result;
    }

    private BranchResult execute(BranchHandle handle) {
        try {
            handle.context().checkActive();
            BaseAgent agent = selectAndReserve(handle.task.agentType(), handle, false);
            if (agent == null) {
                agent = selectAndReserve(AgentType.GENERAL, handle, handle.task.agentType() != AgentType.GENERAL);
                handle.failure = "target agent unavailable";
            }
            if (agent == null) return handle.complete(ExecutionStatus.FAILED, "", "no available agent");
            AgentResponse response = invoke(handle, agent);
            if (response.status() == ExecutionStatus.FAILED && agent.type() != AgentType.GENERAL) {
                handle.context().checkActive();
                handle.failure = response.content();
                handle.finishAttempt(response.status());
                BaseAgent fallback = selectAndReserve(AgentType.GENERAL, handle, true);
                if (fallback != null) response = invoke(handle, fallback);
            }
            if (response.status().hasAnswer()) handle.context().checkActive();
            ExecutionStatus status = handle.fallback && response.status() == ExecutionStatus.SUCCESS
                    ? ExecutionStatus.DEGRADED : response.status();
            return handle.complete(status, response.content(), handle.failure);
        } catch (ExecutionContext.ExecutionAbortedException ex) {
            return handle.complete(ex.status(), "", ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("Agent branch {} failed", handle.task.id(), ex);
            return handle.complete(ExecutionStatus.FAILED, "", ex.getMessage());
        } finally {
            handle.releaseAttempt();
        }
    }

    /** Reserve selection atomically so concurrent requests see the new in-flight load. */
    private synchronized BaseAgent selectAndReserve(AgentType type, BranchHandle handle, boolean fallback) {
        List<BaseAgent> agents = pool.getOrDefault(type, List.of());
        if (agents.isEmpty()) return null;
        int cursor = selectionCursor.getOrDefault(type, 0);
        BaseAgent selected = null;
        double best = Double.NEGATIVE_INFINITY;
        for (int offset = 0; offset < agents.size(); offset++) {
            BaseAgent candidate = agents.get((cursor + offset) % agents.size());
            double score = candidate.stats().routingScore();
            if (score > best) { selected = candidate; best = score; }
        }
        if (!handle.context().evaluation()) {
            selectionCursor.put(type, (cursor + 1) % agents.size());
        }
        handle.startAttempt(selected, fallback);
        return selected;
    }

    private AgentResponse invoke(BranchHandle handle, BaseAgent agent) {
        AgentRequest request = handle.request;
        if (handle.fallback) {
            request = request.withTask(new AgentTask(handle.task.id(), AgentType.GENERAL,
                    "专业服务暂不可用。仅为原分支领域提供有证据的补充帮助，说明限制并给出人工交接信息。", handle.task.domains()));
        }
        AgentResponse response = agent.handle(request);
        if (response.status().hasAnswer()) handle.context().checkActive();
        for (ToolCallTrace trace : response.toolCalls()) {
            ToolCallTrace attributed = trace.branchId().isBlank()
                    ? new ToolCallTrace(trace.server(), trace.tool(), trace.arguments(), trace.success(), trace.error(),
                    trace.latencyMs(), trace.reason(), handle.context().branchId(), trace.shared()) : trace;
            if (!handle.context().branchToolCalls().contains(attributed)) handle.context().recordTool(attributed);
        }
        for (Evidence evidence : response.evidence()) {
            if (!handle.context().branchEvidence().contains(evidence)) handle.context().recordEvidence(evidence);
        }
        handle.escalated |= response.escalate();
        return response;
    }

    private ExecutionStatus combinedStatus(List<BranchResult> branches) {
        boolean answer = branches.stream().anyMatch(branch -> branch.status().hasAnswer());
        if (!answer) {
            if (branches.stream().anyMatch(branch -> branch.status() == ExecutionStatus.TIMEOUT)) return ExecutionStatus.TIMEOUT;
            if (!branches.isEmpty() && branches.stream().allMatch(branch -> branch.status() == ExecutionStatus.REJECTED)) return ExecutionStatus.REJECTED;
            if (!branches.isEmpty() && branches.stream().allMatch(branch -> branch.status() == ExecutionStatus.CANCELLED)) return ExecutionStatus.CANCELLED;
            return ExecutionStatus.FAILED;
        }
        if (branches.stream().anyMatch(branch -> !branch.status().hasAnswer() || branch.status() == ExecutionStatus.PARTIAL)) return ExecutionStatus.PARTIAL;
        if (branches.stream().anyMatch(branch -> branch.status() == ExecutionStatus.DEGRADED)) return ExecutionStatus.DEGRADED;
        return ExecutionStatus.SUCCESS;
    }

    private String compose(List<BranchResult> branches) {
        if (branches.size() == 1) {
            BranchResult branch = branches.getFirst();
            return branch.status().hasAnswer() ? branch.content() : unavailableMessage(branch.status());
        }
        Map<String, Integer> paragraphCounts = new LinkedHashMap<>();
        for (BranchResult branch : branches) {
            if (branch.status().hasAnswer()) {
                paragraphs(branch.content()).stream().distinct()
                        .forEach(paragraph -> paragraphCounts.merge(paragraph, 1, Integer::sum));
            }
        }
        List<String> sections = new ArrayList<>();
        String shared = paragraphCounts.entrySet().stream().filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey).collect(Collectors.joining("\n\n"));
        if (!shared.isBlank()) sections.add("共同建议：\n" + shared);
        for (BranchResult branch : branches) {
            String label = switch (branch.targetAgent()) {
                case TECHNICAL -> "技术问题";
                case BILLING -> "账单与账户";
                case ESCALATION -> "人工处理";
                case GENERAL -> "综合说明";
            };
            String content = branch.status().hasAnswer()
                    ? paragraphs(branch.content()).stream().distinct()
                    .filter(paragraph -> paragraphCounts.getOrDefault(paragraph, 0) <= 1)
                    .collect(Collectors.joining("\n\n"))
                    : "这部分暂未完成。" + unavailableMessage(branch.status());
            if (!content.isBlank()) sections.add(label + "：\n" + content);
        }
        return String.join("\n\n", sections);
    }

    private List<String> paragraphs(String content) {
        return java.util.Arrays.stream(content.split("(?:\\r?\\n)\\s*(?:\\r?\\n)"))
                .map(String::strip).filter(paragraph -> !paragraph.isBlank()).toList();
    }

    private String unavailableMessage(ExecutionStatus status) {
        return switch (status) {
            case REJECTED -> "服务当前繁忙，请稍后重试或联系人工客服。";
            case TIMEOUT -> "处理时间超过了本次请求的上限，请稍后重试或联系人工客服。";
            case CANCELLED -> "本次处理已取消，请重新发送您的问题。";
            default -> "服务暂时不可用，请稍后重试或联系人工客服。";
        };
    }

    private String clarificationResponse(AgentRequest req) {
        StringBuilder text = new StringBuilder("我需要先确认一下您的问题类型。请问您是想咨询使用问题、反馈故障，还是处理账单/账户相关事项？");
        if (!req.entities().isEmpty()) {
            text.append("（已记录：").append(req.entities().entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + String.join("/", entry.getValue()))
                    .collect(Collectors.joining("，"))).append("）");
        }
        return text.toString();
    }

    private OrchestratorResult result(AgentRequest req, AgentType primary, String content, boolean escalated,
                                      ExecutionStatus status, List<BranchResult> branches, long started) {
        ExecutionContext context = req.executionContext();
        return new OrchestratorResult(req.requestId(), content, primary, req.intent(), escalated, elapsed(started),
                context.toolCalls(), req.intentConfidence(), req.intentSource(), req.entities(), req.intentAbstained(),
                req.secondaryIntents(), status, branches, context.evidence(), context.metrics());
    }

    public Map<String, Object> stats() {
        Map<String, Object> result = new HashMap<>();
        pool.forEach((type, agents) -> {
            for (int i = 0; i < agents.size(); i++) {
                result.put(type.name().toLowerCase(Locale.ROOT) + "_" + i, agents.get(i).stats().snapshot());
            }
        });
        return result;
    }

    public void updateRoutingPenalties(Map<String, Double> penalties) {
        pool.forEach((type, agents) -> {
            for (int i = 0; i < agents.size(); i++) {
                agents.get(i).stats().setMonitorPenalty(penalties.getOrDefault(type.name().toLowerCase(Locale.ROOT) + "_" + i, 0.0));
            }
        });
    }

    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }

    private static final class BranchHandle {
        private final AgentTask task;
        private final AgentRequest request;
        private final long started = System.nanoTime();
        private volatile Future<BranchResult> future;
        private volatile BranchResult terminal;
        private AgentType actualAgent;
        private AgentStats.Attempt attempt;
        private long attemptStarted;
        private volatile boolean fallback;
        private volatile boolean escalated;
        private volatile String failure = "";

        private BranchHandle(AgentTask task, AgentRequest request) { this.task = task; this.request = request; }
        private ExecutionContext context() { return request.executionContext(); }

        private synchronized void startAttempt(BaseAgent agent, boolean fallback) {
            context().checkActive();
            if (terminal != null) throw new CancellationException("branch already completed");
            actualAgent = agent.type();
            this.fallback = fallback;
            attemptStarted = System.nanoTime();
            attempt = context().evaluation() ? null : agent.stats().begin();
        }

        private synchronized void finishAttempt(ExecutionStatus status) {
            if (attempt != null) { attempt.complete(status, elapsed(attemptStarted)); attempt = null; }
        }

        private synchronized void releaseAttempt() {
            if (attempt != null) {
                attempt.release();
                // Retain an unclassified exceptional attempt until await() records
                // its outcome, even though the physical worker has already exited.
                if (terminal != null) attempt = null;
            }
        }

        private synchronized BranchResult complete(ExecutionStatus status, String content, String error) {
            if (terminal != null) return terminal;
            if (status.hasAnswer()) {
                try {
                    context().checkActive();
                } catch (ExecutionContext.ExecutionAbortedException ex) {
                    status = ex.status(); content = ""; error = ex.getMessage();
                }
            }
            if (attempt != null) attempt.terminate(status, elapsed(attemptStarted));
            terminal = new BranchResult(task.id(), task.agentType(), actualAgent, status, content, elapsed(started),
                    fallback, error, context().branchToolCalls(), context().branchEvidence());
            return terminal;
        }
    }
}
