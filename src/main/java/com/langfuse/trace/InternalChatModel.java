package com.langfuse.trace;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.MDC;

/**
 * 内部 LLM 调用装饰器：在委托 ChatModel 调用外包一个「带原因的观测标记」（MDC）。
 *
 * <p>供记忆压缩摘要等内部调用使用——观测层（{@link PromptRecordingConvention}）据此把该调用
 * 标记为内部：span 名 = 原因（如 {@code summary}）、不写 trace 列表列（langfuse.trace.*），
 * 与用户面普通调用区分。调用方不直接碰 MDC，标记逻辑收敛在本类；try/finally 保证标记不泄漏。</p>
 */
public class InternalChatModel implements ChatModel {

    private final ChatModel delegate;
    private final String reason;

    public InternalChatModel(ChatModel delegate, String reason) {
        this.delegate = delegate;
        this.reason = reason;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        MDC.put(LangfuseMdcKeys.INTERNAL_OBSERVATION_KEY, reason);
        try {
            return delegate.chat(request);
        } finally {
            MDC.remove(LangfuseMdcKeys.INTERNAL_OBSERVATION_KEY);
        }
    }
}
