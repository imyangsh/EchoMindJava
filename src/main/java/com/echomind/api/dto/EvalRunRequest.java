package com.echomind.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

@Schema(description = "评测运行请求；为空对象时使用内置默认用例")
public record EvalRunRequest(
        @Schema(description = "意图识别评测用例")
        @Size(max = 200) List<IntentCase> intentCases,
        @Schema(description = "知识库检索评测用例")
        @Size(max = 50) List<RetrievalCase> retrievalCases,
        @Schema(description = "对话质量评测用例")
        @Size(max = 20) List<DialogCase> dialogCases,
        @Schema(description = "显式将本次报告设为基线；默认只在基线不存在时建立，后续运行保留原基线")
        Boolean updateBaseline,
        @Schema(description = "为 true 时在相同对话集比较单 Agent 与协作，默认不重复运行")
        Boolean compareSingleAgent
) {
    public EvalRunRequest(List<IntentCase> intentCases, List<RetrievalCase> retrievalCases, List<DialogCase> dialogCases,
                          Boolean updateBaseline) {
        this(intentCases, retrievalCases, dialogCases, updateBaseline, false);
    }
    public EvalRunRequest(List<IntentCase> intentCases, List<RetrievalCase> retrievalCases, List<DialogCase> dialogCases) {
        this(intentCases, retrievalCases, dialogCases, false, false);
    }

    @Schema(description = "意图识别评测用例")
    public record IntentCase(
            @Schema(description = "用户消息", example = "应用一直报500错误")
            String message,
            @Schema(description = "期望意图", example = "technical")
            String expectedIntent,
            @Schema(description = "可选历史消息，用于评测多轮省略表达")
            @Size(max = 20) List<Map<String, String>> history,
            @Schema(description = "可选完整意图集合（主意图与次意图），传空列表表示期望拒识")
            @Size(max = 10) List<String> expectedDomains
    ) {
        public IntentCase(String message, String expectedIntent) {
            this(message, expectedIntent, null, null);
        }
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
            Integer topK,
            @Schema(description = "完整相关 chunk ID 集合，用于真正 Recall@K；不可与相关文档集合同时提供")
            @Size(max = 200) List<String> relevantChunkIds,
            @Schema(description = "完整相关文档 ID 集合，对应结果 metadata.document_id；同文档的多块只计一次")
            @Size(max = 200) List<String> relevantDocumentIds,
            @Schema(description = "为 true 时预期知识库没有答案，评测证据充分性误接纳率", example = "false")
            Boolean noAnswer,
            @Schema(description = "完整检索链路使用的历史消息，可评测多轮指代")
            @Size(max = 20) List<Map<String, String>> history
    ) {
        public RetrievalCase(String query, String expectedTitle, String expectedText, String expectedSource, Integer topK) {
            this(query, expectedTitle, expectedText, expectedSource, topK, null, null, false, null);
        }
    }

    @Schema(description = "对话质量评测用例")
    public record DialogCase(
            @Schema(description = "单轮问题", example = "我的订单 #12345 还没到")
            String question,
            @Schema(description = "多轮对话输入")
            @Size(max = 20) List<String> turns,
            @Schema(description = "用户 ID", example = "eval_user")
            String userId,
            @Schema(description = "会话 ID", example = "eval_conv_001")
            String conversationId,
            @Schema(description = "独立标注的期望参与角色，如 technical / billing")
            @Size(max = 4) List<String> expectedParticipants,
            @Schema(description = "独立标注的回复关键术语；覆盖率为精确术语匹配，不能替代人工质量审核")
            @Size(max = 30) List<String> requiredAnswerTerms,
            @Schema(description = "可选标注是否应升级人工")
            Boolean expectedEscalation
    ) {
        public DialogCase(String question, List<String> turns, String userId, String conversationId) {
            this(question, turns, userId, conversationId, null, null, null);
        }
    }
}
