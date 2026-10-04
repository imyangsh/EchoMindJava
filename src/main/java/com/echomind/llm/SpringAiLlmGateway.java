package com.echomind.llm;

import com.echomind.config.EchoMindProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SpringAiLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmGateway.class);
    private static final String STARTUP_PLACEHOLDER_KEY = "echomind-local-placeholder";

    private final ChatModel chatModel;
    private final EchoMindProperties properties;
    private final String activeProfile;
    private final boolean modelCredentialsConfigured;

    public SpringAiLlmGateway(ObjectProvider<ChatModel> chatModelProvider,
                              EchoMindProperties properties,
                              @Value("${spring.profiles.active:anthropic}") String activeProfile,
                              @Value("${spring.ai.anthropic.api-key:}") String anthropicApiKey,
                              @Value("${spring.ai.deepseek.api-key:}") String deepSeekApiKey) {
        this.chatModel = chatModelProvider.getIfAvailable();
        this.properties = properties;
        this.activeProfile = activeProfile;
        this.modelCredentialsConfigured = credentialsConfigured(activeProfile, anthropicApiKey, deepSeekApiKey);
    }

    @Override
    public String chat(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        return chatResult(systemPrompt, userPrompt, temperature, maxTokens).content();
    }

    @Override
    public LlmReply chatResult(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        try {
            return callModel(systemPrompt, userPrompt, temperature, maxTokens);
        } catch (Exception ex) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("LLM call interrupted", ex);
            }
            if (!properties.getLlm().isFallbackEnabled()) {
                throw ex;
            }
            log.warn("Spring AI chat failed, using deterministic fallback: {}", ex.getMessage());
            return new LlmReply(fallback(userPrompt), true, 0, 0);
        }
    }

    /**
     * 意图识别等需要解析返回内容的调用方走这里：不做自然语言 fallback，
     * 失败就是失败，由调用方按 llm_unavailable / llm_timeout 归因处理。
     */
    @Override
    public String chatStructured(String userPrompt, double temperature, int maxTokens) {
        return callModel("", userPrompt, temperature, maxTokens).content();
    }

    private LlmReply callModel(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        if (chatModel == null) {
            throw new IllegalStateException("Spring AI ChatModel is not configured");
        }
        if (!modelCredentialsConfigured) {
            throw new IllegalStateException("No real API key configured for active LLM profile: " + activeProfile);
        }
        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new SystemMessage(clean(systemPrompt)));
        }
        messages.add(new UserMessage(clean(userPrompt)));
        ChatOptions options = ChatOptions.builder()
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();
        ChatResponse response = chatModel.call(new Prompt(messages, options));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("LLM returned an empty response");
        }
        String text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("LLM returned an empty answer");
        }
        var usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        return new LlmReply(text, false,
                usage == null || usage.getPromptTokens() == null ? -1 : usage.getPromptTokens(),
                usage == null || usage.getCompletionTokens() == null ? -1 : usage.getCompletionTokens());
    }

    private String fallback(String userPrompt) {
        String prompt = clean(userPrompt);
        if (prompt.contains("退款") || prompt.toLowerCase().contains("refund")) {
            return "当前模型服务不可用，未能核实退款政策。请提供订单号，或转人工客服核查退款申请。";
        }
        if (prompt.contains("报错") || prompt.contains("登录") || prompt.toLowerCase().contains("error")) {
            return "我建议先确认账号状态、网络环境和错误码。如果问题持续，请提供错误码和发生时间，技术支持会进一步排查。";
        }
        if (prompt.contains("扣款") || prompt.contains("账单") || prompt.contains("发票")) {
            return "账单问题需要核对支付记录、订单号和扣款时间。请提供相关信息，涉及退款或发票开具时会进入人工审核。";
        }
        return "我已收到你的问题。当前模型服务不可用，系统返回了本地降级回复；请稍后重试或转人工处理。";
    }

    private static String clean(String value) {
        return value == null ? "" : value;
    }

    private static boolean credentialsConfigured(String activeProfile, String anthropicApiKey, String deepSeekApiKey) {
        String profile = clean(activeProfile).toLowerCase();
        if (profile.contains("deepseek")) {
            return hasRealKey(deepSeekApiKey);
        }
        return hasRealKey(anthropicApiKey);
    }

    private static boolean hasRealKey(String value) {
        String key = clean(value).trim();
        return !key.isBlank() && !STARTUP_PLACEHOLDER_KEY.equals(key);
    }
}
