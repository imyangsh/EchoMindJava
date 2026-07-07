package com.echomind.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "评测运行请求；为空对象时使用内置默认用例")
public record EvalRunRequest(
        @Schema(description = "意图识别评测用例")
        List<IntentCase> intentCases,
        @Schema(description = "知识库检索评测用例")
        List<RetrievalCase> retrievalCases,
        @Schema(description = "对话质量评测用例")
        List<DialogCase> dialogCases
) {
    @Schema(description = "意图识别评测用例")
    public record IntentCase(
            @Schema(description = "用户消息", example = "应用一直报500错误")
            String message,
            @Schema(description = "期望意图", example = "technical")
            String expectedIntent
    ) {
    }

    @Schema(description = "知识库检索评测用例")
    public record RetrievalCase(
            @Schema(description = "检索查询", example = "退款多久到账")
            String query,
            @Schema(description = "期望命中的标题关键词", example = "退款")
            String expectedTitle,
            @Schema(description = "期望命中的内容关键词", example = "5-7 个工作日")
            String expectedText,
            @Schema(description = "期望命中的来源关键词", example = "policy.md")
            String expectedSource,
            @Schema(description = "检索 topK", example = "4")
            Integer topK
    ) {
    }

    @Schema(description = "对话质量评测用例")
    public record DialogCase(
            @Schema(description = "单轮问题", example = "我的订单 #12345 还没到")
            String question,
            @Schema(description = "多轮对话输入")
            List<String> turns,
            @Schema(description = "用户 ID", example = "eval_user")
            String userId,
            @Schema(description = "会话 ID", example = "eval_conv_001")
            String conversationId
    ) {
    }
}
