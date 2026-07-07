package com.echomind.intent;

import com.echomind.config.EchoMindProperties;
import com.echomind.knowledge.TextEmbeddingService;
import com.echomind.llm.LlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class IntentRecognizerTest {

    @Test
    void semanticSampleMatchCanWinWhenLlmIsUncertain() {
        ObjectMapper objectMapper = new ObjectMapper();
        EchoMindProperties properties = new EchoMindProperties();
        IntentSampleStore sampleStore = new IntentSampleStore(properties, objectMapper, new DefaultResourceLoader());
        sampleStore.load();
        LlmGateway llm = (systemPrompt, userPrompt, temperature, maxTokens) ->
                "{\"intent\":\"other\",\"confidence\":0.0,\"reasoning\":\"uncertain\"}";
        IntentRecognizer recognizer = new IntentRecognizer(
                llm,
                objectMapper,
                sampleStore,
                TextEmbeddingService.hashOnly(),
                properties
        );

        IntentResult result = recognizer.recognize("钱什么时候退回原支付账户", null);

        assertThat(result.intent()).isEqualTo(IntentCategory.BILLING);
    }
}
