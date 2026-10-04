package com.echomind.agent;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

public record ToolCallTrace(
        String server,
        String tool,
        Map<String, Object> arguments,
        boolean success,
        String error,
        long latencyMs,
        String reason,
        String branchId,
        boolean shared
) {
    public ToolCallTrace {
        Map<String, Object> copied = new LinkedHashMap<>();
        if (arguments != null) arguments.forEach((key, value) -> copied.put(key, freeze(value)));
        arguments = Collections.unmodifiableMap(copied);
        error = error == null ? "" : error;
        reason = reason == null ? "" : reason;
        branchId = branchId == null ? "" : branchId;
    }

    public ToolCallTrace(String server, String tool, Map<String, Object> arguments, boolean success,
                         String error, long latencyMs, String reason) {
        this(server, tool, arguments, success, error, latencyMs, reason, "", false);
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), freeze(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) return list.stream().map(ToolCallTrace::freeze).toList();
        return value;
    }
}
