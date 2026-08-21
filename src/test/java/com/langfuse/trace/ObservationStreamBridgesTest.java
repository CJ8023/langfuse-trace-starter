package com.langfuse.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 泛型流式桥接：从 Reactor context 通道取 RequestTrace，complete 补全后投影 MDC（含无载体回退）。 */
class ObservationStreamBridgesTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void bridge读取ReactorContext载体并补全投影MDC() {
        RequestTrace shell = RequestTrace.shell(null, "0123456789abcdef0123456789abcdef", "sess-1", "chat");
        AtomicReference<String> seenTraceId = new AtomicReference<>();
        AtomicReference<String> seenMode = new AtomicReference<>();
        AtomicReference<String> seenInput = new AtomicReference<>();

        ObservationStreamBridges.<String>bridge(b -> {
            b.complete("你好", "conv-1");
            seenTraceId.set(b.traceId());
            seenMode.set(MDC.get(LangfuseMdcKeys.LANGKFUSE_MODE_KEY));
            seenInput.set(MDC.get(LangfuseMdcKeys.LANGKFUSE_INPUT_KEY));
            b.sink().next("hello");
            b.sink().complete();
        }).contextWrite(ctx -> ctx.put(RequestTrace.REACTOR_KEY, shell))
          .blockLast();

        assertThat(seenTraceId.get()).isEqualTo(shell.traceId());
        assertThat(seenMode.get()).isEqualTo("chat");
        assertThat(seenInput.get()).isEqualTo("你好");
    }

    @Test
    void 无载体时complete回退写MDC() {
        AtomicReference<String> seenInput = new AtomicReference<>();
        ObservationStreamBridges.<String>bridge(b -> {
            b.complete("你好", "conv-1");
            seenInput.set(MDC.get(LangfuseMdcKeys.LANGKFUSE_INPUT_KEY));
            b.sink().next("hello");
            b.sink().complete();
        }).blockLast();
        assertThat(seenInput.get()).isEqualTo("你好");
    }
}
