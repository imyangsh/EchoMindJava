package com.echomind.intent;

import java.util.List;
import java.util.Map;

public record IntentResult(
        IntentCategory intent,
        double confidence,
        UrgencyLevel urgency,
        Map<String, List<String>> entities,
        String reasoning,
        long latencyMs,
        /** 定夺该意图的通道：llm / semantic / pattern / abstain。旧实现不留痕，线上误判无法归因。 */
        String source,
        /** 明确域外或证据不足时为 true，此时 intent 为 OTHER，下游返回澄清。 */
        boolean abstained,
        /**
         * 次意图：一句话同时命中多个领域时（"我要退款，但是 app 一直崩溃"）排在主意图之后的候选。
         * 单标签模型必然丢掉这类信息，旧实现只回传一个 intent，协作路由只能靠另一套硬编码关键词表补。
         */
        List<IntentCategory> secondaryIntents
) {
    public IntentResult {
        entities = entities == null ? Map.of() : entities.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        secondaryIntents = secondaryIntents == null ? List.of() : List.copyOf(secondaryIntents);
    }
}
