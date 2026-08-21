package com.langfuse.trace;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.observation.context.ChatModelObservationContext;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 内部观测标记回归：convention 对内部 LLM 调用（记忆压缩摘要等）不写 trace 列表列
 * （langfuse.trace.*），避免覆盖请求 trace 的 name/input/output；gen_ai 详情与
 * langfuse.observation.type 保留（trace 详情仍可见该内部 generation）。
 */
class PromptRecordingConventionTest {

    private final PromptRecordingConvention convention = new PromptRecordingConvention();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private ChatModelObservationContext context(String responseText) {
        ChatModelRequestContext req = mock(ChatModelRequestContext.class);
        ChatRequest chatRequest = mock(ChatRequest.class);
        when(req.chatRequest()).thenReturn(chatRequest);
        when(chatRequest.messages()).thenReturn(List.of(UserMessage.from("你好")));
        when(chatRequest.modelName()).thenReturn("agnes-2.0-flash");
        when(chatRequest.parameters()).thenReturn(null);

        ChatModelResponseContext resp = mock(ChatModelResponseContext.class);
        ChatResponse chatResponse = mock(ChatResponse.class);
        when(resp.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.aiMessage()).thenReturn(AiMessage.from(responseText));
        when(chatResponse.metadata()).thenReturn(ChatResponseMetadata.builder().build());

        return new ChatModelObservationContext(req, resp, null);
    }

    @Test
    void 外部调用写trace列表列() {
        KeyValues kvs = convention.getHighCardinalityKeyValues(context("回答"));
        assertThat(hasKey(kvs, "langfuse.trace.input")).isTrue();
        assertThat(hasKey(kvs, "langfuse.trace.output")).isTrue();
        assertThat(hasKey(kvs, "langfuse.trace.name")).isTrue();
        assertThat(hasKey(kvs, "gen_ai.prompt")).isTrue();
    }

    @Test
    void 内部调用不写trace列表列但保留gen_ai详情() {
        MDC.put(LangfuseMdcKeys.INTERNAL_OBSERVATION_KEY, "summary");
        KeyValues kvs = convention.getHighCardinalityKeyValues(context("摘要"));

        assertThat(hasKey(kvs, "langfuse.trace.input")).isFalse();
        assertThat(hasKey(kvs, "langfuse.trace.output")).isFalse();
        assertThat(hasKey(kvs, "langfuse.trace.name")).isFalse();
        // gen_ai 详情与 observation.type 保留：trace 详情仍可见该内部 generation
        assertThat(hasKey(kvs, "gen_ai.prompt")).isTrue();
        assertThat(hasKey(kvs, "gen_ai.completion")).isTrue();
        assertThat(hasKey(kvs, "langfuse.observation.type")).isTrue();
    }

    @Test
    void 外部调用span名为chat前缀() {
        // 外部调用为默认名（chat 前缀 + 模型名）；模型名解析依赖 mock 链，不断言完整串
        String name = convention.getContextualName(context("回答"));
        assertThat(name).startsWith("chat ");
        assertThat(name).isNotEqualTo("summary");
    }

    @Test
    void 内部调用span名为summary() {
        MDC.put(LangfuseMdcKeys.INTERNAL_OBSERVATION_KEY, "summary");
        assertThat(convention.getContextualName(context("摘要"))).isEqualTo("summary");
    }

    private static boolean hasKey(KeyValues kvs, String key) {
        for (KeyValue kv : kvs) {
            if (key.equals(kv.getKey())) {
                return true;
            }
        }
        return false;
    }
}
