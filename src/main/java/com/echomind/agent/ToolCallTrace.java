package com.echomind.agent;

import java.util.Map;

public record ToolCallTrace(
        String server,
        String tool,
        Map<String, Object> arguments,
        boolean success,
        String error,
        long latencyMs,
        String reason
) {
    public ToolCallTrace {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        error = error == null ? "" : error;
        reason = reason == null ? "" : reason;
    }
}
