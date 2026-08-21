package com.langfuse.trace;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.observation.context.ChatModelObservationContext;
import dev.langchain4j.observation.convention.ChatModelConvention;
import dev.langchain4j.observation.convention.ChatModelDocumentation;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.Map;

/**
 * 记录 LLM 请求/响应内容的 ChatModelListener（基于 langchain4j-observation 的官方
 * ObservationChatModelListener 生命周期，但使用 {@link PromptRecordingConvention}
 * 让 span 携带 {@code gen_ai.prompt} / {@code gen_ai.completion}，Langfuse 可见 input/output）。
 *
 * <p>生命周期：onRequest 创建 micrometer Observation + open scope（scope 存到 request 的
 * attributes）；onResponse/onError 从对应 attributes 恢复 scope，写入 response/error 后
 * close + stop——与官方 ObservationChatModelListener 一致。</p>
 *
 * <p>Langfuse 提示词版本号（数值）由业务侧写入 MDC（键见 {@link LangfuseMdcKeys}），
 * 本类在 openScope 后读取并写为 OTel span 数值属性。</p>
 */
@Slf4j
public class PromptRecordingChatModelListener implements ChatModelListener {

    /** scope 存储 key（与官方实现同值，存于 request/response 共享的 attributes map） */
    private static final String OBSERVATION_SCOPE_KEY = "langchain4j.observation.scope";

    private final ObservationRegistry observationRegistry;
    private final ChatModelConvention convention = new PromptRecordingConvention();

    public PromptRecordingChatModelListener(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        // 父上下文：优先当前 span（框架标准链路——HTTP observation 经 Spring 自动 context
        // propagation / 模型 executor TaskDecorator / 图 wrap hook 传播到 LLM 调用线程，
        // gen_ai span 直接挂到 HTTP span 下形成嵌套树）；无当前 span 时从 MDC traceId
        // 合成远端父兜底（trace 归属不分裂）。
        try (Scope traceScope = TraceSpanSupport.currentOrSyntheticScope(TraceSpanSupport.mdcTraceId())) {
            ChatModelObservationContext observationContext = new ChatModelObservationContext(requestContext, null, null);
            Observation observation = ChatModelDocumentation.INSTANCE.start(convention, convention,
                    () -> observationContext, observationRegistry);
            observation.start();
            Observation.Scope scope = observation.openScope();
            requestContext.attributes().put(OBSERVATION_SCOPE_KEY, scope);
            // openScope 使 generation span 成为 current（micrometer-tracing onScopeOpened 已 bytecode 验证），
            // 在此把 Langfuse 版本号写为数值属性 —— 导入器要求 promptVersion 为 number，字符串版本会被整体丢弃。
            appendPromptVersionAttribute();
        }
        log.debug("LLM observation onRequest: messages={} attributesHasScope={}",
                requestContext.chatRequest().messages().size(),
                requestContext.attributes().containsKey(OBSERVATION_SCOPE_KEY));
    }

    /**
     * Langfuse OTel 导入器要求 observation 的 promptVersion 为数值；micrometer KeyValues 只支持 String，
     * 故绕过 convention 直接写 OTel span 数值属性（openScope 后 span 为 current）。版本号非纯数字时跳过。
     */
    private void appendPromptVersionAttribute() {
        String version = MDC.get(LangfuseMdcKeys.LANGKFUSE_PROMPT_VERSION_KEY);
        if (version == null || version.isBlank()) {
            return;
        }
        try {
            long v = Long.parseLong(version);
            Span span = Span.current();
            if (span.isRecording()) {
                span.setAttribute("langfuse.prompt.version", v);
            }
        } catch (NumberFormatException ignored) {
            // 版本为 label 而非数字时 Langfuse 不要求关联版本号，忽略
        }
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        Observation.Scope scope = getScope(responseContext.attributes());
        if (scope == null) {
            // 正常路径必有 scope（onRequest 开启）；缺失多见于被跳过观测的内部调用，降级为 debug 防噪音
            log.debug("LLM observation onResponse: scope 缺失（onRequest 未开启或 attributes 未共享）");
            return;
        }
        Observation observation = scope.getCurrentObservation();
        ChatModelObservationContext ctx = (ChatModelObservationContext) observation.getContext();
        ctx.setResponseContext(responseContext);
        scope.close();
        observation.stop();
        log.debug("LLM observation onResponse: completed, responseText='{}'",
                responseContext.chatResponse().aiMessage() == null ? "null" : responseContext.chatResponse().aiMessage().text());
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        Observation.Scope scope = getScope(errorContext.attributes());
        if (scope == null) {
            log.debug("LLM observation onError: scope 缺失（onRequest 未开启或 attributes 未共享）");
            return;
        }
        Observation observation = scope.getCurrentObservation();
        ChatModelObservationContext ctx = (ChatModelObservationContext) observation.getContext();
        ctx.setErrorContext(errorContext);
        scope.close();
        observation.error(errorContext.error());
        observation.stop();
        log.warn("LLM observation onError: {}", errorContext.error().toString());
    }

    private Observation.Scope getScope(Map<Object, Object> attributes) {
        Object scope = attributes.get(OBSERVATION_SCOPE_KEY);
        return scope instanceof Observation.Scope ? (Observation.Scope) scope : null;
    }
}
