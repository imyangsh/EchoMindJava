package com.echomind.agent;

import com.echomind.intent.IntentCategory;
import com.echomind.intent.UrgencyLevel;

import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record AgentRequest(
        String message,
        String userId,
        String conversationId,
        String context,
        List<Map<String, String>> history,
        IntentCategory intent,
        UrgencyLevel urgency,
        String requestId,
        /**
         * 意图识别置信度。旧实现只把 intent 与 urgency 挂到请求上，
         * 置信度、推理理由、实体全部丢弃，导致 Agent 完全不知道自己为何被调用，
         * 也无法对"低置信度"做任何区分处理。
         */
        double intentConfidence,
        /** 定夺意图的通道：llm / semantic / pattern / abstain。 */
        String intentSource,
        /** 类型化实体（order_id / error_code / amount / phone / date）。 */
        Map<String, List<String>> entities,
        /** 三通道均无有效证据时为 true，上层应走澄清而不是继续硬答。 */
        boolean intentAbstained,
        /** 次意图：一句话同时命中多个领域时排在主意图之后的候选。 */
        List<IntentCategory> secondaryIntents,
        /** 本条消息涉及的全部领域意图（主意图 + 次意图 + 模式层命中的其他领域），供协作路由使用。 */
        Set<IntentCategory> detectedIntents,
        ExecutionContext executionContext,
        AgentTask task
) {
    public AgentRequest {
        history = history == null ? List.of() : history.stream().map(Map::copyOf).toList();
        Map<String, List<String>> entityCopy = new LinkedHashMap<>();
        if (entities != null) {
            entities.forEach((key, values) -> entityCopy.put(key, List.copyOf(values)));
        }
        entities = Collections.unmodifiableMap(entityCopy);
        intentSource = intentSource == null ? "" : intentSource;
        secondaryIntents = secondaryIntents == null ? List.of() : List.copyOf(secondaryIntents);
        detectedIntents = detectedIntents == null ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(detectedIntents));
    }

    public AgentRequest(String message, String userId, String conversationId, String context,
                        List<Map<String, String>> history, IntentCategory intent, UrgencyLevel urgency,
                        String requestId, double intentConfidence, String intentSource,
                        Map<String, List<String>> entities, boolean intentAbstained,
                        List<IntentCategory> secondaryIntents, Set<IntentCategory> detectedIntents) {
        this(message, userId, conversationId, context, history, intent, urgency, requestId,
                intentConfidence, intentSource, entities, intentAbstained, secondaryIntents, detectedIntents,
                null, null);
    }

    public AgentRequest withIntent(IntentCategory intent, UrgencyLevel urgency) {
        return new AgentRequest(message, userId, conversationId, context, history, intent, urgency, requestId,
                intentConfidence, intentSource, entities, intentAbstained, secondaryIntents, detectedIntents,
                executionContext, task);
    }

    /** 挂载完整的识别结果，保留置信度、实体、次意图与领域集合用于提示词与协作路由。 */
    public AgentRequest withIntentMetadata(IntentCategory intent, UrgencyLevel urgency, double confidence,
                                           String source, Map<String, List<String>> entities, boolean abstained,
                                           List<IntentCategory> secondaryIntents, Set<IntentCategory> detectedIntents) {
        return new AgentRequest(message, userId, conversationId, context, history, intent, urgency, requestId,
                confidence, source, entities, abstained, secondaryIntents, detectedIntents, executionContext, task);
    }

    public AgentRequest withExecutionContext(ExecutionContext executionContext) {
        return new AgentRequest(message, userId, conversationId, context, history, intent, urgency, requestId,
                intentConfidence, intentSource, entities, intentAbstained, secondaryIntents, detectedIntents,
                executionContext, task);
    }

    public AgentRequest withTask(AgentTask task) {
        return new AgentRequest(message, userId, conversationId, context, history, intent, urgency, requestId,
                intentConfidence, intentSource, entities, intentAbstained, secondaryIntents, detectedIntents,
                executionContext, task);
    }

    /**
     * 协作领域始终合并主意图、次意图与识别证据；拒识时领域为空。
     */
    public Set<IntentCategory> collaborationDomains() {
        if (intentAbstained) {
            return Set.of();
        }
        Set<IntentCategory> fallback = new LinkedHashSet<>();
        if (intent != null) {
            fallback.add(intent);
        }
        fallback.addAll(secondaryIntents);
        // Evidence sets may originate from an unordered Set.of/Set.copyOf. Keep the
        // primary and secondary order, then append remaining domains deterministically.
        detectedIntents.stream().sorted().forEach(fallback::add);
        fallback.remove(IntentCategory.OTHER);
        return Collections.unmodifiableSet(fallback);
    }

    public static AgentRequest of(String message, String userId, String conversationId, String context, List<Map<String, String>> history) {
        return new AgentRequest(message, userId, conversationId, context, history, null, null,
                UUID.randomUUID().toString(), 0.0, "", Map.of(), false, List.of(), Set.of());
    }
}
