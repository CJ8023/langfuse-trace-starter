package com.langfuse.trace;

import io.micrometer.observation.Observation;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.function.Consumer;

/**
 * 流式桥接样板（trace 相关部分）：统一"Flux.create → boundedElastic"，供 Chat/Agent/RAG
 * 等流式服务收敛调用。各服务在 {@link #bridge} 的 starter 里保留领域特有血肉
 * （onRetrieved/工具回调等），并按各自事件协议发射（如 SSE 事件）。
 *
 * <p>本类<b>只负责 trace 上下文传播</b>，与具体事件协议解耦（泛型 {@code S} = 流元素类型，
 * SSE 事件、领域事件等由调用方自行构造并 {@code sink().next(...)}）。</p>
 *
 * <p>trace 上下文传播（框架标准链路）：starter 在订阅时刻执行于 boundedElastic，不在
 * Reactor 信号传递路径上、自动 ThreadLocal 恢复覆盖不到，故经 {@code deferContextual}
 * 从 Reactor Context 恢复 HTTP observation scope + 取 {@link RequestTrace}（filter 建壳）——
 * OTel span current + MDC traceId 生效，starter 内发起的 LLM 调用挂到 HTTP span 下；流式模型
 * 的回调线程（如 LangChain4j-OpenAI-N）由模型 executor 的 TaskDecorator 在提交时捕获传播。</p>
 */
public final class ObservationStreamBridges {

    private ObservationStreamBridges() {
    }

    /**
     * 桥接流式模型 → {@code Flux<S>}（元素类型由调用方定，与事件协议解耦）。
     *
     * <p>从 Reactor context 通道取 RequestTrace（filter 建壳），starter 内由各 service 调
     * {@link Bridge#complete} 补全 mode/input/conversationId；traceId 供 done 载荷回传。</p>
     *
     * @param starter 在订阅线程（boundedElastic，HTTP observation scope 已恢复）执行的初始化回调，
     *                内建 FluxSink 与 RequestTrace 载体，供领域代码发事件、收尾
     */
    public static <S> Flux<S> bridge(Consumer<Bridge<S>> starter) {
        return Flux.deferContextual(ctx -> {
            RequestTrace requestTrace = RequestTrace.fromContext(ctx);
            return Flux.<S>create(sink -> {
                try (Observation.Scope ignored = TraceSpanSupport.reactorObservationScope(ctx)) {
                    starter.accept(new Bridge<>(sink, requestTrace));
                }
            });
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 流桥接上下文：FluxSink + RequestTrace（filter 建壳、入口补全）+ traceId 解析 */
    public static final class Bridge<S> {

        private final FluxSink<S> sink;
        private RequestTrace requestTrace;
        private String traceId;

        private Bridge(FluxSink<S> sink, RequestTrace requestTrace) {
            this.sink = sink;
            this.requestTrace = requestTrace;
            this.traceId = RequestTrace.resolveTraceId(requestTrace);
        }

        /** 原始 sink：领域代码自行构造元素发射（事件协议由调用方定义） */
        public FluxSink<S> sink() {
            return sink;
        }

        /** 解析后的 traceId（载体 → 当前 span → MDC 兜底）；供 done 载荷等回传 */
        public String traceId() {
            return traceId;
        }

        /** 完整 RequestTrace（建壳 + complete 补全后）；供工具回调/节点观察显式取父上下文 */
        public RequestTrace requestTrace() {
            return requestTrace;
        }

        /**
         * 入口补全：input/conversationId 写入 RequestTrace（mode 已由 TraceHeaderFilter 按 path
         * 推导进 shell），并经 {@link RequestTrace#projectMdc} 投影 MDC（含无载体回退）——模型
         * executor 线程 trace 命名/input 经 TaskDecorator 传播读取；替代各 service 原先手工
         * {@code MDC.put(LANGKFUSE_*)}。非 HTTP 入口（RequestTrace 为 null）时回退写 MDC input
         * （mode 缺省，convention 默认 chat）。
         */
        public void complete(String input, String conversationId) {
            if (requestTrace != null) {
                requestTrace = requestTrace.with(input, conversationId);
                if (traceId == null) {
                    traceId = requestTrace.traceId();
                }
            }
            RequestTrace.projectMdc(requestTrace, null, input);
        }
    }
}
