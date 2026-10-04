package com.echomind.tool;

public record ToolResult<T>(
        boolean success,
        T data,
        String toolName,
        String error,
        boolean cached,
        long latencyMs,
        boolean reranked,
        boolean degraded
) {
    public ToolResult(boolean success, T data, String toolName, String error,
                      boolean cached, long latencyMs, boolean reranked) {
        this(success, data, toolName, error, cached, latencyMs, reranked, false);
    }
}
