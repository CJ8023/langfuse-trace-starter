package com.langfuse.trace;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** 请求级载体：capture 捕获当前 span + MDC，makeCurrent 跨线程恢复；with 补全保留 shell 的 mode。 */
class RequestTraceTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void capture捕获当前span与MDC并跨线程恢复() throws Exception {
        Tracer tracer = SdkTracerProvider.builder().build().get("test");
        Span root = tracer.spanBuilder("HTTP /api/agent/stream").startSpan();
        String traceId = root.getSpanContext().getTraceId();
        MDC.put(LangfuseMdcKeys.LANGKFUSE_MODE_KEY, "agent");

        RequestTrace captured;
        try (Scope ignored = root.makeCurrent()) {
            captured = RequestTrace.capture();
        }
        assertThat(captured.traceId()).isEqualTo(traceId);
        assertThat(captured.mode()).isEqualTo("agent");
        assertThat(captured.usable()).isTrue();

        // 另一线程 makeCurrent 恢复父上下文（模拟 boundedElastic 上的摘要/图节点线程）
        CompletableFuture<SpanContext> future = CompletableFuture.supplyAsync(() -> {
            try (Scope ignored = captured.makeCurrent()) {
                return Span.current().getSpanContext();
            }
        });
        assertThat(future.get().getTraceId()).isEqualTo(traceId);
        root.end();
    }

    @Test
    void with补全保留shell的mode并携带input会话() {
        RequestTrace shell = RequestTrace.shell(null, "0123456789abcdef0123456789abcdef", "sess-1", "chat");
        RequestTrace completed = shell.with("你好", "conv-1");
        assertThat(completed.mode()).isEqualTo("chat");
        assertThat(completed.input()).isEqualTo("你好");
        assertThat(completed.conversationId()).isEqualTo("conv-1");
        assertThat(completed.sessionId()).isEqualTo("sess-1");
        assertThat(completed.traceId()).isEqualTo(shell.traceId());
    }
}
