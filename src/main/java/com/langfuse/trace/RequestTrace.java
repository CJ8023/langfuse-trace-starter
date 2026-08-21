package com.langfuse.trace;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.MDC;
import reactor.util.context.ContextView;

/**
 * 请求级 trace 载体：请求入口创建，随请求链在三条通道间传播，任何线程可从所在通道
 * 取到整个对象——消费端显式取值，不再依赖 {@code Context.current()} / {@code MDC.get()}
 * "恰好被传对"。
 *
 * <p><b>三条通道</b>：</p>
 * <ol>
 *   <li><b>Reactor context</b>（{@link #REACTOR_KEY}）：filter 建壳写入，
 *       chat/agent/rag 各 service 入口 {@link #with} 补全后覆盖；{@code deferContextual}
 *       可读（观察桥接层）。</li>
 *   <li><b>RunnableConfig.metadata</b>（{@link #METADATA_KEY}）：图模式经
 *       {@link GraphTracePropagation#captureTo} 携带贯穿所有节点，wrap hook 恢复。</li>
 *   <li><b>方法参数/闭包</b>：工具回调、记忆压缩 lambda 显式透传整个对象。</li>
 * </ol>
 *
 * <p><b>装配两阶段</b>：filter 建壳（traceId + otelContext + sessionId，此时 mode/input
 * 未产生）；各 service 入口补全（mode/input/conversationId 由调用方传入）。</p>
 *
 * <p><b>消费</b>：{@link #makeCurrent()} 优先恢复真实 otelContext（HTTP 父 span），无则从
 * traceId 合成远端父；{@link #toMdc()} 把 mode/input 投影回 MDC——模型 executor 线程的
 * {@code PromptRecordingConvention} 仍从 MDC 读 trace 命名/input（该线程非 Reactor 线程，
 * 拿不到通道），经 TaskDecorator 的 MDC 传播生效。</p>
 */
public record RequestTrace(
        String traceId,
        Context otelContext,
        String mode,
        String input,
        String sessionId,
        String conversationId
) {

    /** Reactor context 通道键：filter 建壳写入，service 补全后覆盖 */
    public static final String REACTOR_KEY = "apiReq";

    /** RunnableConfig.metadata 通道键：图执行贯穿节点 */
    public static final String METADATA_KEY = "api.req";

    /**
     * filter 建壳：调用方需已恢复 HTTP observation scope（当前 OTel Context 即携带 HTTP
     * 父 span）并传入 traceId、请求头 sessionId 与 path 推导的 mode；input/conversationId 由后续补全。
     */
    public static RequestTrace shell(Context otelContext, String traceId, String sessionId, String mode) {
        return new RequestTrace(traceId, otelContext, mode, null, sessionId, null);
    }

    /** Reactor context 通道读取：filter 建壳写入后，消费端从所在通道取整个对象（无则 null） */
    public static RequestTrace fromContext(ContextView ctx) {
        return ctx.getOrDefault(REACTOR_KEY, null);
    }

    /**
     * 统一 traceId 解析：优先载体显式 traceId → 当前线程有效 span → MDC 32-hex 兜底。
     * 桥接层不再各自维护降级逻辑。
     */
    public static String resolveTraceId(RequestTrace requestTrace) {
        if (requestTrace != null && requestTrace.traceId() != null && !requestTrace.traceId().isBlank()) {
            return requestTrace.traceId();
        }
        SpanContext spanContext = Span.current().getSpanContext();
        if (spanContext.isValid()) {
            return spanContext.getTraceId();
        }
        return TraceSpanSupport.mdcTraceId();
    }

    /**
     * 当前线程捕获（无载体通道处，如记忆压缩）：当前 OTel Context 含有效 sampled span 才
     * 携带（否则置 null 走 traceId 合成父——Context.current() 永不为 null，直接携带空 context
     * 会让摘要 LLM 新建 trace 而非继承）；traceId 取 span 的，无则 MDC 兜底；mode/input 取
     * 当前线程 MDC 已投影值（有则带，无则 null）。
     */
    public static RequestTrace capture() {
        Context context = Context.current();
        Span span = Span.fromContext(context);
        boolean hasSpan = span.getSpanContext().isValid() && span.getSpanContext().isSampled();
        String traceId = hasSpan ? span.getSpanContext().getTraceId() : TraceSpanSupport.mdcTraceId();
        String mode = MDC.get(LangfuseMdcKeys.LANGKFUSE_MODE_KEY);
        String input = MDC.get(LangfuseMdcKeys.LANGKFUSE_INPUT_KEY);
        return new RequestTrace(traceId, hasSpan ? context : null, mode, input, null, null);
    }

    /** service 入口补全：mode/input/conversationId 就位后覆盖 Reactor context 通道（mode 为 filter 未推导时显式给，如 graph） */
    public RequestTrace with(String mode, String input, String conversationId) {
        return new RequestTrace(traceId, otelContext, mode, input, sessionId, conversationId);
    }

    /** service 入口补全（mode 已由 filter 从 path 推导进 shell）：只补 input/conversationId，保留 shell 的 mode */
    public RequestTrace with(String input, String conversationId) {
        return new RequestTrace(traceId, otelContext, mode, input, sessionId, conversationId);
    }

    /** 是否可作为父上下文（otelContext 或 traceId 任一可用） */
    public boolean usable() {
        return otelContext != null || (traceId != null && !traceId.isBlank());
    }

    /**
     * 恢复父上下文：优先 otelContext（须含有效 sampled span——真实 HTTP/gen_ai 父，树正确
     * 嵌套；空 context 不做父，否则子 span 新建 trace）；无则从 traceId 合成远端父（兜底，
     * trace 归属不分裂）；两者皆无返回 null（try-with-resources 对 null 安全，调用方跳过
     * observation 防孤儿 trace）。
     */
    public Scope makeCurrent() {
        if (otelContext != null) {
            SpanContext spanContext = Span.fromContext(otelContext).getSpanContext();
            if (spanContext.isValid() && spanContext.isSampled()) {
                return otelContext.makeCurrent();
            }
        }
        return (traceId == null || traceId.isBlank())
                ? null : TraceSpanSupport.syntheticParent(traceId).makeCurrent();
    }

    /** mode/input 投影到 MDC（模型 executor 线程读 trace 命名/input；随 TaskDecorator 的 MDC 传播生效） */
    public void toMdc() {
        if (mode != null && !mode.isBlank()) {
            MDC.put(LangfuseMdcKeys.LANGKFUSE_MODE_KEY, mode);
        }
        if (input != null && !input.isBlank()) {
            MDC.put(LangfuseMdcKeys.LANGKFUSE_INPUT_KEY, input);
        }
    }

    /**
     * 补全后的载体投影 MDC；无载体时回退写 MDC（mode/input 非空才写）。
     * 收敛观察桥接层重复的"toMdc + fallback MDC.put"：有载体走
     * {@link #toMdc()}，无载体（单测/非 HTTP 入口）按调用方给定的 mode/input 回退。
     */
    public static void projectMdc(RequestTrace requestTrace, String mode, String input) {
        if (requestTrace != null) {
            requestTrace.toMdc();
            return;
        }
        if (mode != null && !mode.isBlank()) {
            MDC.put(LangfuseMdcKeys.LANGKFUSE_MODE_KEY, mode);
        }
        if (input != null && !input.isBlank()) {
            MDC.put(LangfuseMdcKeys.LANGKFUSE_INPUT_KEY, input);
        }
    }
}
