package com.echomind.llm;

public interface LlmGateway {

    String chat(String systemPrompt, String userPrompt, double temperature, int maxTokens);

    /** Compatibility for simple implementations; production gateways report fallback explicitly. */
    default LlmReply chatResult(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        return new LlmReply(chat(systemPrompt, userPrompt, temperature, maxTokens), false, -1, -1);
    }

    /**
     * 要求结构化输出的调用路径：失败时<b>抛异常或返回原始文本，绝不返回自然语言兜底文案</b>。
     *
     * <p>意图识别这类"解析返回内容"的调用方不能用 {@link #chat}：后者在网关联络失败时
     * 会返回一段客服口吻的中文散文，调用方既解析不出 JSON、又收不到任何异常信号
     * （没有异常就不会置失败标记），最终静默退化为"识别不出来"而日志里查不到原因。
     *
     * <p>默认实现直接委托 {@link #chat}，供测试用 lambda 实现继续可用；
     * 真正接模型的实现必须覆盖它并跳过自然语言 fallback。
     */
    default String chatStructured(String userPrompt, double temperature, int maxTokens) {
        return chat("", userPrompt, temperature, maxTokens);
    }
}
