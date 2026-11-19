package com.echomind.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "智能客服对话请求")
public record ChatRequest(
        @Schema(description = "用户消息", example = "我想申请退款，订单号是 #12345")
        @NotBlank @Size(max = 4000, message = "message must not exceed 4000 characters") String message,
        @Schema(description = "可选用户 ID，必须与 X-User-Key 对应身份一致；未传时使用已认证用户", example = "u1001")
        @Size(max = 128) String userId,
        @Schema(description = "会话 ID，未传时服务端自动生成", example = "conv-001")
        @Size(max = 128) String conversationId
) {
    public String userIdOrDefault() {
        return userId == null || userId.isBlank() ? "anonymous" : userId;
    }
}
