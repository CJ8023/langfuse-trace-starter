package com.langfuse.trace;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 内部 LLM 装饰器：委托调用期间设置带原因的观测标记（MDC），调用结束后清理，不泄漏。 */
class InternalChatModelTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void 委托调用期间设置原因标记并清理() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            // 委托调用执行期间标记应可见（观测层据此识别内部调用）
            assertThat(MDC.get(LangfuseMdcKeys.INTERNAL_OBSERVATION_KEY)).isEqualTo("summary");
            return ChatResponse.builder().aiMessage(AiMessage.from("摘要")).build();
        });

        InternalChatModel model = new InternalChatModel(delegate, "summary");
        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from("hi"))).build());

        assertThat(response.aiMessage().text()).isEqualTo("摘要");
        // 调用结束后标记已清理，不污染后续调用
        assertThat(MDC.get(LangfuseMdcKeys.INTERNAL_OBSERVATION_KEY)).isNull();
        verify(delegate).chat(any(ChatRequest.class));
    }
}
