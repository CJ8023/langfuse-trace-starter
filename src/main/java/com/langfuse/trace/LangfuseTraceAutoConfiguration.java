package com.langfuse.trace;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

/**
 * Langfuse OTel 追踪自动装配（对应原业务项目 {@code ObservabilityConfig} + {@code TraceHeaderFilter}）。
 *
 * <p>装配内容：</p>
 * <ol>
 *   <li>{@link PromptRecordingChatModelListener}：LLM 观测发射（gen_ai.* + Langfuse 兼容属性）</li>
 *   <li>{@link GenAiObservationRecorder}：tool / retrieval / 图节点观测发射器</li>
 *   <li>{@link ObservationPredicate}：排除 {@code langfuse.trace.exclude-uris} 与 OPTIONS 预检</li>
 *   <li>{@link ObservationFilter}：session.id / langfuse.user.id / langfuse.tags 属性写入
 *       （tags 来自 {@code langfuse.trace.tag-uri-mappings}，可配置）</li>
 *   <li>{@link TraceHeaderFilter}：X-Trace-Id / traceparent 响应头回传（仅 reactive WebFlux 生效）</li>
 * </ol>
 *
 * <p>HTTP 链路依赖 WebFlux 框架标准 observation（HttpWebHandlerAdapter 自动创建），
 * 本装配只做监听器/过滤器的声明，不创建 span、不写 MDC——纯声明式接入。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(LangfuseTraceProperties.class)
public class LangfuseTraceAutoConfiguration {

    @Bean
    @ConditionalOnClass(ChatModelListener.class)
    @ConditionalOnMissingBean
    public PromptRecordingChatModelListener observationChatModelListener(ObservationRegistry observationRegistry) {
        return new PromptRecordingChatModelListener(observationRegistry);
    }

    /** tool / retrieval / 图节点 observation 发射器（AgentService / ToolLoopEngine / 图编排注入） */
    @Bean
    @ConditionalOnClass(ChatModelListener.class)
    @ConditionalOnMissingBean
    public GenAiObservationRecorder genAiObservationRecorder(ObservationRegistry observationRegistry) {
        return new GenAiObservationRecorder(observationRegistry);
    }

    /**
     * HTTP observation 排除过滤（仅作用于 HTTP 服务器观测；LLM/tool/retrieval observation 不受影响）：
     * <ol>
     *   <li>命中 langfuse.trace.exclude-uris 前缀的请求不创建 trace</li>
     *   <li>{@code OPTIONS} 请求（CORS 预检）不观测——无业务语义，避免污染 tracing 列表</li>
     * </ol>
     */
    @Bean
    @ConditionalOnMissingBean
    public ObservationPredicate langfuseHttpExcludePredicate(LangfuseTraceProperties traceProperties) {
        // Micrometer 1.15 起 ObservationPredicate 收敛为 BiPredicate<String, Context>（providerId 已移除）
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext requestContext) {
                if ("OPTIONS".equalsIgnoreCase(String.valueOf(requestContext.getCarrier().getMethod()))) {
                    return false;
                }
                return !traceProperties.isExcluded(requestContext.getCarrier().getPath().value());
            }
            return true;
        };
    }

    /**
     * 请求级属性写入 HTTP observation（Langfuse trace 聚合与筛选）：
     * <ul>
     *   <li>{@code session.id} ← X-Session-Id 头（会话聚合；缺省不设，避免假聚合）</li>
     *   <li>{@code langfuse.user.id} ← X-User-Id 头（Langfuse Users 分析；缺省不设）</li>
     *   <li>{@code langfuse.tags} ← 按 endpoint 映射模式标签（langfuse.trace.tag-uri-mappings，
     *       默认 chat/rag/agent/graph），供 Langfuse 按功能分类筛选</li>
     * </ul>
     */
    @Bean
    @ConditionalOnMissingBean
    public ObservationFilter langfuseSessionFilter(LangfuseTraceProperties traceProperties) {
        return context -> {
            if (context instanceof ServerRequestObservationContext requestContext) {
                var headers = requestContext.getCarrier().getHeaders();
                String sessionId = headers.getFirst("X-Session-Id");
                if (sessionId != null && !sessionId.isBlank()) {
                    requestContext.addHighCardinalityKeyValue(KeyValue.of("session.id", sessionId));
                }
                String userId = headers.getFirst("X-User-Id");
                if (userId != null && !userId.isBlank()) {
                    requestContext.addHighCardinalityKeyValue(KeyValue.of("langfuse.user.id", userId));
                }
                String tag = traceProperties.tagForPath(requestContext.getCarrier().getPath().value());
                if (tag != null) {
                    requestContext.addHighCardinalityKeyValue(KeyValue.of("langfuse.tags", tag));
                }
            }
            return context;
        };
    }

    /** 响应头回传 Filter（X-Trace-Id / traceparent），仅 reactive WebFlux 环境生效 */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    public TraceHeaderFilter traceHeaderFilter(ObjectProvider<Tracer> tracerProvider,
                                               LangfuseTraceProperties traceProperties) {
        return new TraceHeaderFilter(tracerProvider, traceProperties);
    }
}
