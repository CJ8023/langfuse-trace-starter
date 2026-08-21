package com.langfuse.trace;

import io.micrometer.observation.Observation;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.MDC;
import reactor.util.context.ContextView;

import java.util.Map;
import java.util.function.Supplier;

/**
 * OTel 父上下文工具：手工创建的 observation span（tool/retrieval，以及 LLM gen_ai span）
 * 的父上下文解析。
 *
 * <p><b>优先当前 span</b>：框架标准链路下（HTTP observation + Spring 自动 context
 * propagation + 模型 executor TaskDecorator + 图 wrap hook），业务调用线程的
 * Context.current() 已携带 HTTP span——直接用真实父，span 树正确嵌套。
 * 仅当前 span 无效时，从 MDC traceId 合成远端父（兜底，保持 trace 归属不分裂）。</p>
 */
public final class TraceSpanSupport {

    private TraceSpanSupport() {
    }

    /** 当前 MDC traceId（32-hex），无则返回 null */
    public static String mdcTraceId() {
        String traceId = MDC.get(LangfuseMdcKeys.TRACE_ID_KEY);
        return (traceId != null && traceId.matches("[0-9a-f]{32}")) ? traceId : null;
    }

    /** 当前线程是否存在有效且被采样的 span */
    public static boolean hasCurrentSpan() {
        SpanContext spanContext = Span.current().getSpanContext();
        return spanContext.isValid() && spanContext.isSampled();
    }

    /**
     * 父上下文 scope：优先当前 OTel Context（真实 HTTP/gen_ai 父，span 树嵌套）；
     * 无效则从 traceId 合成远端父 context（span-id 非全零，远端父不导出，子 span 即 trace 根）；
     * traceId 也为空则返回 null（调用方跳过 observation，防孤儿 trace）。
     */
    public static Scope currentOrSyntheticScope(String traceId) {
        if (hasCurrentSpan()) {
            return Context.current().makeCurrent();
        }
        return traceId == null ? null : syntheticParent(traceId).makeCurrent();
    }

    /** 合成以指定 traceId 为根的远端父 context（兜底路径：trace 归属不分裂但树为扁平兄弟） */
    static Context syntheticParent(String traceId) {
        SpanContext remote = SpanContext.createFromRemoteParent(
                traceId, "0000000000000001", TraceFlags.getSampled(), TraceState.getDefault());
        return Context.root().with(Span.wrap(remote));
    }

    /**
     * 从 Reactor Context 恢复 HTTP observation scope（框架标准传播的桥接补全）。
     *
     * <p>Spring WebFlux 已把 HTTP Observation 写入 Reactor Context
     * （{@link ObservationThreadLocalAccessor#KEY} 键）。但 {@code Flux.create} 的 starter
     * 在<b>订阅时刻</b>执行于 subscribeOn 线程，不在信号传递路径上——Reactor 自动
     * ThreadLocal 恢复（仅覆盖 onNext/onComplete 等信号跨线程）覆盖不到。本方法在
     * 执行线程补上：{@code openScope} 使 OTel span current + micrometer-tracing 写
     * MDC traceId/spanId，桥接内发起的 LLM 调用、TaskDecorator 捕获、图 trace
     * 捕获全部挂到 HTTP span 下。</p>
     *
     * <p>无 observation（非 HTTP 入口、单测）返回 null——try-with-resources 对 null 安全，
     * 调用方直通原逻辑。</p>
     */
    public static Observation.Scope reactorObservationScope(ContextView reactorCtx) {
        Observation observation = reactorCtx.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
        return observation == null ? null : observation.openScope();
    }

    /**
     * 跨线程父上下文执行句柄：<b>捕获在调用线程</b>（此刻 OTel Context 携带 HTTP/gen_ai
     * span），之后在任意执行线程（非 Reactor 信号路径、无 TaskDecorator 覆盖的场景，如
     * boundedElastic 上的记忆压缩）经 {@link TraceExecution#execute} 恢复。业务方不感知
     * RequestTrace 类型，观测细节全部留在观测层。
     */
    public static TraceExecution captureTrace() {
        return new TraceExecution(RequestTrace.capture());
    }

    /** 已捕获的父上下文执行器：捕获后可在任意线程 {@link #execute}，临时恢复当前线程 OTel Context */
    public static final class TraceExecution {

        private final RequestTrace captured;

        private TraceExecution(RequestTrace captured) {
            this.captured = captured;
        }

        /** 在恢复父上下文的作用域内执行；无父上下文（非追踪环境）直通 */
        public <T> T execute(Supplier<T> supplier) {
            // 恢复父上下文同时投影 MDC（mode/input）——PromptRecordingConvention 从 MDC 读
            // trace 命名/input，不投影会缺省为 chat 前缀，导致记忆摘要等跨线程调用覆盖 trace 名。
            // finally 还原原 MDC，避免污染 boundedElastic 等线程池复用线程。
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try (Scope ignored = captured.makeCurrent()) {
                captured.toMdc();
                return supplier.get();
            } finally {
                if (previous == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previous);
                }
            }
        }
    }
}
