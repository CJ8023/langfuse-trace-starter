package com.langfuse.trace;

import io.micrometer.observation.Observation;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.context.Context;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 链路追踪 Filter：唯一职责——把框架 HTTP observation 的 traceId/spanId 经响应头回传给
 * 客户端（前端桥接、Langfuse 排查、HITL 恢复续写同 trace）。
 *
 * <p>回传两个响应头：</p>
 * <ul>
 *   <li>{@code X-Trace-Id}：traceId（前端桥接与排查用）</li>
 *   <li>{@code traceparent}：{@code 00-{traceId}-{httpSpanId}-01}——HITL 场景（graph 编排）
 *       主请求中断后，前端 resume 时把该值原样作为入站 {@code traceparent} 带回，框架
 *       {@code HttpWebHandlerAdapter} 自动复用同一 trace_id，主请求 + resume 合并为一条
 *       trace（与 Dify 一次 workflow_run 一条 trace 的标准对齐）</li>
 * </ul>
 *
 * <p>HTTP span 由框架标准链路创建：{@code HttpWebHandlerAdapter} 自动创建 HTTP observation
 * （入站 traceparent 提取，无则 OTel 生成），经 {@code ObservationPredicate}
 * （见 {@link LangfuseTraceAutoConfiguration}）排除非业务端点；traceId 跨线程传播、MDC 写入、
 * gen_ai span 归属均由 Spring Boot 自动 context propagation 完成——本类不再创建 span、不写 MDC、
 * 不做 Reactor context 注入。</p>
 *
 * <p>beforeCommit 时读取 traceId：经 {@code Mono.deferContextual} 取到 Reactor Context 里的
 * HTTP observation，回调内 {@code openScope()} 恢复 OTel span current 后读取——兼容 SSE
 * 场景（graph 等事件在 boundedElastic 线程产生、回调线程无当前 span）；scope 为空则降级读 MDC。</p>
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class TraceHeaderFilter implements WebFilter {

    /** 响应头名：把 traceId 回传给客户端（前端桥接与排查用） */
    private static final String X_TRACE_ID = "X-Trace-Id";

    /** 响应头名：W3C traceparent，resume 续写同 trace 用 */
    private static final String TRACEPARENT = "traceparent";

    private final ObjectProvider<Tracer> tracerProvider;
    private final LangfuseTraceProperties traceProperties;

    public TraceHeaderFilter(ObjectProvider<Tracer> tracerProvider, LangfuseTraceProperties traceProperties) {
        this.tracerProvider = tracerProvider;
        this.traceProperties = traceProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // 订阅时从 Reactor Context 取 HTTP observation（frame 内 HTTP observation 已写入 context），
        // 供 beforeCommit 恢复 scope 后读取 traceId/spanId（见 TraceSpanSupport.reactorObservationScope）。
        return Mono.deferContextual(ctxView -> {
            Observation observation = ctxView.getOrDefault(
                    io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor.KEY, null);
            // 建壳：HTTP observation scope 内捕获当前 OTel Context（父 span）+ traceId + sessionId，
            // 经 contextWrite 写入 Reactor context 通道（RequestTrace.REACTOR_KEY），供下游消费。
            RequestTrace requestTrace = buildShell(exchange, observation);
            exchange.getResponse().beforeCommit(() -> {
                SpanContext sc;
                if (observation != null) {
                    try (Observation.Scope ignored = observation.openScope()) {
                        sc = currentSpanContext();
                    }
                } else {
                    sc = currentSpanContext();
                }
                if (sc != null) {
                    exchange.getResponse().getHeaders().set(X_TRACE_ID, sc.traceId());
                    // W3C traceparent：version-00 + traceId + spanId + flags-01（sampled）
                    exchange.getResponse().getHeaders().set(TRACEPARENT,
                            "00-" + sc.traceId() + "-" + sc.spanId() + "-01");
                }
                return Mono.empty();
            });
            return chain.filter(exchange)
                    .contextWrite(ctx -> ctx.put(RequestTrace.REACTOR_KEY, requestTrace));
        });
    }

    /** 建壳：observation scope 内捕获当前 OTel Context（不可变值，可跨线程 makeCurrent 恢复）与 traceId */
    private RequestTrace buildShell(ServerWebExchange exchange, Observation observation) {
        Context otelContext = null;
        String traceId = null;
        if (observation != null) {
            try (Observation.Scope ignored = observation.openScope()) {
                var sc = io.opentelemetry.api.trace.Span.current().getSpanContext();
                if (sc.isValid() && sc.isSampled()) {
                    otelContext = Context.current();
                    traceId = sc.getTraceId();
                }
            }
        }
        if (traceId == null) {
            traceId = MDC.get(LangfuseMdcKeys.TRACE_ID_KEY);
        }
        String sessionId = exchange.getRequest().getHeaders().getFirst("X-Session-Id");
        return RequestTrace.shell(otelContext, traceId, sessionId,
                traceProperties.modeForPath(exchange.getRequest().getPath().value()));
    }

    /** 当前线程的 HTTP span context：优先当前 span，降级 MDC 取 traceId（spanId 不可得时不发 traceparent） */
    private SpanContext currentSpanContext() {
        Tracer tracer = tracerProvider.getIfAvailable();
        Span span = tracer == null ? null : tracer.currentSpan();
        if (span != null) {
            return new SpanContext(span.context().traceId(), span.context().spanId());
        }
        String traceId = MDC.get(LangfuseMdcKeys.TRACE_ID_KEY);
        if (traceId != null && traceId.matches("[0-9a-f]{32}")) {
            return new SpanContext(traceId, null);
        }
        return null;
    }

    /** 可空 spanId 的 span context（降级 MDC 时无 spanId） */
    private record SpanContext(String traceId, String spanId) {
    }
}
