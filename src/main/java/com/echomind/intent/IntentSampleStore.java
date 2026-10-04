package com.echomind.intent;

import com.echomind.config.EchoMindProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class IntentSampleStore {

    private static final Logger log = LoggerFactory.getLogger(IntentSampleStore.class);

    private final EchoMindProperties properties;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private volatile Map<IntentCategory, List<String>> samples = fallbackSamples();
    private volatile long revision;

    public IntentSampleStore(EchoMindProperties properties, ObjectMapper objectMapper, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    public synchronized void load() {
        String path = properties.getIntent().getSamplesPath();
        try (InputStream inputStream = open(path)) {
            Map<String, List<String>> raw = objectMapper.readValue(inputStream, new TypeReference<>() {
            });
            Map<IntentCategory, List<String>> loaded = new EnumMap<>(IntentCategory.class);
            raw.forEach((key, values) -> {
                // 以下划线开头的键是文档/元数据（如 "_comment"），不是意图类别。
                // 旧实现把无法识别的键静默按 OTHER 处理，会让注释块冒充成域外样本。
                if (key == null || key.startsWith("_") || values == null) {
                    return;
                }
                IntentCategory intent = parseIntent(key);
                if (intent == null) {
                    log.warn("Ignoring unknown intent sample label: {}", key);
                    return;
                }
                List<String> cleaned = values.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
                // 保留 OTHER：域外样本参与 few-shot 与语义拒识。
                // 旧实现把 OTHER 过滤掉，模型与向量索引都学不到"什么该判为无法识别"。
                if (!cleaned.isEmpty()) {
                    loaded.put(intent, cleaned);
                }
            });
            if (loaded.isEmpty()) {
                log.warn("Intent samples at {} contained no usable entries, keeping fallback samples", path);
            } else {
                samples = Collections.unmodifiableMap(loaded);
                revision++;
                log.info("Intent samples loaded from {}: {} intents, {} samples",
                        path, loaded.size(), loaded.values().stream().mapToInt(List::size).sum());
            }
        } catch (Exception ex) {
            log.warn("Failed to load intent samples from {}, using fallback samples: {}", path, ex.getMessage());
            samples = fallbackSamples();
            revision++;
        }
    }

    public Map<IntentCategory, List<String>> samples() {
        return samples;
    }

    public long revision() {
        return revision;
    }

    private InputStream open(String path) throws Exception {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("intent samples path is blank");
        }
        if (path.startsWith("classpath:")) {
            Resource resource = resourceLoader.getResource(path);
            return resource.getInputStream();
        }
        return Files.newInputStream(Path.of(path));
    }

    private IntentCategory parseIntent(String value) {
        try {
            return IntentCategory.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<IntentCategory, List<String>> fallbackSamples() {
        Map<IntentCategory, List<String>> fallback = new EnumMap<>(IntentCategory.class);
        fallback.put(IntentCategory.QUERY, List.of("我的订单状态是什么", "如何重置密码", "快递什么时候到"));
        fallback.put(IntentCategory.COMPLAINT, List.of("服务太差了", "一直没人处理", "等了好几个小时"));
        fallback.put(IntentCategory.REQUEST, List.of("帮我取消订单", "我需要修改地址", "请协助退款"));
        fallback.put(IntentCategory.GREETING, List.of("你好", "嗨有人吗", "hello"));
        fallback.put(IntentCategory.ESCALATION, List.of("转人工客服", "我要投诉", "找你们经理"));
        fallback.put(IntentCategory.TECHNICAL, List.of("应用一直崩溃", "无法登录", "出现500错误"));
        fallback.put(IntentCategory.BILLING, List.of("为什么扣了两次款", "申请退款", "发票问题"));
        fallback.put(IntentCategory.ACCOUNT, List.of("修改邮箱", "注销账户", "更新个人信息"));
        fallback.put(IntentCategory.FEEDBACK, List.of("服务很棒", "非常满意", "给个好评"));
        fallback.put(IntentCategory.OTHER, List.of("今天天气怎么样", "帮我写一首诗", "asdfghjkl"));
        return Collections.unmodifiableMap(fallback);
    }
}
