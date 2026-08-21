package com.langfuse.trace;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨线程执行器回归：{@link TraceSpanSupport.TraceExecution} 在恢复 OTel 父上下文的同时
 * 投影 mode/input 到 MDC，且不污染调用线程 MDC（修复记忆压缩等跨线程调用的 trace 断链/命名丢失）。
 */
class TraceSpanSupportTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void execute恢复父上下文并投影modeInput且还原MDC() {
        Tracer tracer = SdkTracerProvider.builder().build().get("test");
        Span root = tracer.spanBuilder("HTTP /api/agent/stream").startSpan();
        String traceId = root.getSpanContext().getTraceId();
        MDC.put(LangfuseMdcKeys.LANGKFUSE_MODE_KEY, "agent");
        MDC.put(LangfuseMdcKeys.LANGKFUSE_INPUT_KEY, "你好");

        AtomicReference<SpanContext> seenSpan = new AtomicReference<>();
        AtomicReference<String> seenMode = new AtomicReference<>();
        AtomicReference<String> seenInput = new AtomicReference<>();

        try (Scope ignored = root.makeCurrent()) {
            TraceSpanSupport.TraceExecution exec = TraceSpanSupport.captureTrace();
            exec.execute(() -> {
                seenSpan.set(Span.current().getSpanContext());
                seenMode.set(MDC.get(LangfuseMdcKeys.LANGKFUSE_MODE_KEY));
                seenInput.set(MDC.get(LangfuseMdcKeys.LANGKFUSE_INPUT_KEY));
                return null;
            });
        } finally {
            root.end();
        }

        // 执行器内：当前 span 为调用线程捕获的父 span（同一 traceId）
        assertThat(seenSpan.get().getTraceId()).isEqualTo(traceId);
        // 执行器内：mode/input 已投影到 MDC（convention 据此读 trace 命名/input）
        assertThat(seenMode.get()).isEqualTo("agent");
        assertThat(seenInput.get()).isEqualTo("你好");
        // 执行器外：调用线程 MDC 原样还原，未被内部投影污染
        assertThat(MDC.get(LangfuseMdcKeys.LANGKFUSE_MODE_KEY)).isEqualTo("agent");
        assertThat(MDC.get(LangfuseMdcKeys.LANGKFUSE_INPUT_KEY)).isEqualTo("你好");
    }

    @Test
    void 无父上下文时直通不报错() {
        TraceSpanSupport.TraceExecution exec = TraceSpanSupport.captureTrace();
        String result = exec.execute(() -> "ok");
        assertThat(result).isEqualTo("ok");
    }
}
