package com.langfuse.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.listener.ContentRetrieverErrorContext;
import dev.langchain4j.rag.content.retriever.listener.ContentRetrieverListener;
import dev.langchain4j.rag.content.retriever.listener.ContentRetrieverRequestContext;
import dev.langchain4j.rag.content.retriever.listener.ContentRetrieverResponseContext;
import dev.langchain4j.rag.query.Query;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 发射 tool / retrieval 类型的 observation，作为 Langfuse trace 树中的独立节点。
 *
 * <p>langchain4j-observation 1.18.1 仅提供 ChatModel observation，tool/retrieval 无官方
 * 实现，这里用 micrometer Observation + OTel bridge 手工发射。父上下文经
 * {@link TraceSpanSupport#currentOrSyntheticScope} 解析：优先当前 span（框架标准链路下
 * 调用线程已携带 HTTP/gen_ai span，tool 挂到真实父下形成嵌套树）；无当前 span 时从
 * traceId 合成远端父兜底（trace 归属不分裂）；两者皆无则跳过（防孤儿 trace）。</p>
 */
@Slf4j
public class GenAiObservationRecorder {

    /** JSON 序列化（span 属性载荷） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 检索命中文档写入 span 属性时的单条截断长度，避免 span 属性过大 */
    private static final int MAX_DOCUMENT_LENGTH = 500;

    private final ObservationRegistry observationRegistry;
    private final Map<String, Observation> toolObservations = new ConcurrentHashMap<>();

    public GenAiObservationRecorder(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    // ---------- 工具 ----------

    /** 同步工具路径（observeTool 内部用）：无显式载体，父上下文走当前线程 span/MDC 兜底 */
    private void startTool(ToolExecutionRequest request) {
        startTool(request, (RequestTrace) null);
    }

    /**
     * 带显式 RequestTrace 的开启（AgentService 经 {@code Bridge.requestTrace()} 把完整载体
     * 传入回调线程，作兜底——回调线程既无当前 span 又无 MDC 时仍可归属）。父上下文解析顺序：
     * 当前 span（真实嵌套）→ RequestTrace.otelContext（HTTP 父 span）→ traceId 合成远端父；
     * 皆无则跳过（防孤儿 trace）。
     */
    public void startTool(ToolExecutionRequest request, RequestTrace requestTrace) {
        if (request == null) {
            return;
        }
        if (!TraceSpanSupport.hasCurrentSpan() && (requestTrace == null || !requestTrace.usable())) {
            return;
        }
        try (Scope ignored = requestScope(requestTrace)) {
            if (ignored == null) {
                return;
            }
            Observation observation = Observation.createNotStarted("tool",
                    () -> new Observation.Context(), observationRegistry);
            observation.contextualName("tool " + request.name());
            observation.highCardinalityKeyValue("langfuse.observation.type", "tool");
            observation.highCardinalityKeyValue("gen_ai.tool.name", request.name());
            observation.highCardinalityKeyValue("gen_ai.tool.input", nullToEmpty(request.arguments()));
            observation.highCardinalityKeyValue("langfuse.observation.input", nullToEmpty(request.arguments()));
            observation.start();
            toolObservations.put(scopeKey(requestTrace) + toolKey(request), observation);
        } catch (Exception e) {
            log.warn("tool observation 开启失败: name={}", request.name(), e);
        }
    }

    /**
     * 父上下文 scope：优先当前 span（框架标准链路下调用线程已携带 HTTP/gen_ai span，真实嵌套）；
     * 无则从 RequestTrace 恢复（otelContext 优先，traceId 合成兜底）；两者皆无返回 null。
     */
    private static Scope requestScope(RequestTrace requestTrace) {
        if (TraceSpanSupport.hasCurrentSpan()) {
            return Context.current().makeCurrent();
        }
        return requestTrace == null ? null : requestTrace.makeCurrent();
    }

    /** 同步工具路径（observeTool）：无载体，键不前缀 scope */
    public void endTool(ToolExecutionRequest request, String result) {
        endTool(request, result, null);
    }

    /** AgentService 流式回调：onToolExecuted 处收尾（带请求级载体，与 startTool 同 scope 键配对） */
    public void endTool(ToolExecutionRequest request, String result, RequestTrace requestTrace) {
        if (request == null) {
            return;
        }
        Observation observation = toolObservations.remove(scopeKey(requestTrace) + toolKey(request));
        if (observation == null) {
            return;
        }
        try {
            String output = result == null ? "" : result;
            observation.highCardinalityKeyValue("gen_ai.tool.output", output);
            observation.highCardinalityKeyValue("langfuse.observation.output", output);
        } finally {
            observation.stop();
        }
    }

    /** 同步工具执行（ToolLoopEngine）：开启 → 执行 → 收尾，异常记 error */
    public String observeTool(ToolExecutionRequest request, Supplier<String> execution) {
        startTool(request);
        try {
            String result = execution.get();
            endTool(request, result);
            return result;
        } catch (RuntimeException e) {
            finishToolWithError(request, e);
            throw e;
        }
    }

    private void finishToolWithError(ToolExecutionRequest request, Throwable error) {
        if (request == null) {
            return;
        }
        Observation observation = toolObservations.remove(toolKey(request));
        if (observation == null) {
            return;
        }
        try {
            observation.error(error);
            observation.highCardinalityKeyValue("gen_ai.tool.output",
                    error.getMessage() == null ? error.toString() : error.getMessage());
        } finally {
            observation.stop();
        }
    }

    /**
     * 回收该请求所有在途工具观测（AgentService 流式失败路径）：工具执行异常走 onError 而非
     * onToolExecuted，startTool 开启的观测无人收尾——按 traceId 前缀整体 remove+stop，防止单例
     * map 随请求累积泄漏；同时按请求隔离并发请求的工具键。
     * 无 traceId（非 HTTP/单测）时不动作——键无前缀，无法安全界定归属。
     */
    public void abortTools(RequestTrace requestTrace) {
        String prefix = scopeKey(requestTrace);
        if (prefix.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Observation> entry : toolObservations.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                Observation removed = toolObservations.remove(entry.getKey());
                if (removed != null) {
                    removed.stop();
                }
            }
        }
    }

    /** 工具关联键：优先 id（同轮多次调用），无 id 退化 name+arguments */
    private static String toolKey(ToolExecutionRequest request) {
        return (request.id() != null && !request.id().isBlank())
                ? request.id()
                : request.name() + "|" + nullToEmpty(request.arguments());
    }

    /** 请求级 scope 前缀：按 traceId 隔离单例 map 中并发请求的工具观测，失败时可整体回收；无 traceId 返回空串 */
    private static String scopeKey(RequestTrace requestTrace) {
        String traceId = requestTrace == null ? null : requestTrace.traceId();
        return (traceId == null || traceId.isBlank()) ? "" : traceId + "|";
    }

    // ---------- 图节点观测（通用，经 wrap hook 统一触发） ----------

    /**
     * 开启节点 observation（Langfuse {@code span} 类型：节点执行容器），input=节点入参
     * （state 快照）。在<b>节点执行线程</b>调用（wrap 链中 nodeWrapHook 已恢复 HTTP
     * span）——与 tool/retrieval 同款 start/end 配对，支持跨线程收尾（LLM/检索 future
     * 完成线程）。
     *
     * <p>任何新流程只需注册 {@link GraphTracePropagation#nodeObservationHook(GenAiObservationRecorder)}，
     * 节点内无需任何上报代码——含 HITL 审批（无 LLM）在内的所有节点自动有有效观测。</p>
     *
     * @param nodeId 节点 id（如 {@code retrieve} / {@code approval}）
     * @param input  节点入参（state 快照，可为 null）
     * @return 待收尾句柄；当前无有效 span 且无 MDC traceId 时返回 null（跳过）
     */
    public Observation startNode(String nodeId, Map<String, Object> input) {
        if (nodeId == null || nodeId.isBlank()) {
            return null;
        }
        String traceId = TraceSpanSupport.mdcTraceId();
        if (!TraceSpanSupport.hasCurrentSpan() && traceId == null) {
            log.debug("startNode 跳过: node={} hasSpan={} mdcTraceId={}", nodeId,
                    TraceSpanSupport.hasCurrentSpan(), traceId);
            return null;
        }
        try (Scope ignored = TraceSpanSupport.currentOrSyntheticScope(traceId)) {
            Observation observation = Observation.createNotStarted("span",
                    () -> new Observation.Context(), observationRegistry);
            observation.contextualName("node." + nodeId);
            observation.highCardinalityKeyValue("langfuse.observation.type", "span");
            observation.highCardinalityKeyValue("langfuse.observation.input", mapToJson(input));
            observation.start();
            return observation;
        } catch (Exception e) {
            log.warn("node observation 开启失败: nodeId={}", nodeId, e);
            return null;
        }
    }

    /** 收尾（节点 action future 完成线程）：写 output/error 后 stop */
    public void endNode(Observation observation, Map<String, Object> output, Throwable error) {
        if (observation == null) {
            log.debug("endNode 收到 null（startNode 未开启或跳过）");
            return;
        }
        try {
            if (error != null) {
                observation.error(error);
            } else {
                observation.highCardinalityKeyValue("langfuse.observation.output", mapToJson(output));
            }
        } finally {
            observation.stop();
        }
    }

    /** state/action 返回 Map → JSON 字符串（截断防 span 属性过大）；null/空 → 空串 */
    private static String mapToJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        try {
            String json = MAPPER.writeValueAsString(map);
            return json.length() > MAX_DOCUMENT_LENGTH ? json.substring(0, MAX_DOCUMENT_LENGTH) + "…" : json;
        } catch (JsonProcessingException e) {
            return String.valueOf(map);
        }
    }

    // ---------- 检索 ----------

    /** 开启 retrieval observation（ContentRetrieverListener.onRequest 用），返回待收尾句柄 */
    public Observation startRetrieval(Query query) {
        String traceId = TraceSpanSupport.mdcTraceId();
        if (!TraceSpanSupport.hasCurrentSpan() && traceId == null) {
            return null;
        }
        try (Scope ignored = TraceSpanSupport.currentOrSyntheticScope(traceId)) {
            Observation observation = Observation.createNotStarted("retrieval",
                    () -> new Observation.Context(), observationRegistry);
            observation.contextualName("retrieval");
            observation.highCardinalityKeyValue("langfuse.observation.type", "retrieval");
            observation.highCardinalityKeyValue("retrieval.query", nullToEmpty(query.text()));
            observation.highCardinalityKeyValue("langfuse.observation.input", nullToEmpty(query.text()));
            observation.start();
            return observation;
        } catch (Exception e) {
            log.warn("retrieval observation 开启失败", e);
            return null;
        }
    }

    /** 收尾（onResponse）：写命中内容后 stop */
    public void endRetrieval(Observation observation, List<Content> contents) {
        if (observation == null) {
            return;
        }
        try {
            String documents = serializeDocuments(contents);
            if (documents != null) {
                observation.highCardinalityKeyValue("retrieval.document", documents);
                observation.highCardinalityKeyValue("langfuse.observation.output", documents);
            }
        } finally {
            observation.stop();
        }
    }

    /** 异常收尾（onError） */
    public void errorRetrieval(Observation observation, Throwable error) {
        if (observation == null) {
            return;
        }
        try {
            observation.error(error);
        } finally {
            observation.stop();
        }
    }

    /** 命中内容 → JSON 数组（单条截断，避免 span 属性过大） */
    private static String serializeDocuments(List<Content> contents) {
        List<String> texts = contents.stream()
                .map(content -> content.textSegment() == null ? "" : content.textSegment().text())
                .map(text -> text.length() > MAX_DOCUMENT_LENGTH ? text.substring(0, MAX_DOCUMENT_LENGTH) + "…" : text)
                .toList();
        try {
            return MAPPER.writeValueAsString(texts);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * RAG 检索 observation 的 {@link ContentRetrieverListener} 适配器：把 langchain4j 官方
     * 检索回调委托为 retrieval observation。经 {@code ContentRetriever.addListener(...)} 挂到
     * 检索器；ListeningContentRetriever 在 onRequest/onResponse/onError 之间共享同一
     * attributes map，observation 句柄放这里传递。
     */
    public static class RetrievalListener implements ContentRetrieverListener {

        /** 挂在 exchange attribute 上的 retrieval Observation 键（onRequest 开启、onResponse 收尾跨回调传递） */
        private static final String OBSERVATION_KEY = "langchain4j.retrieval.observation";

        private final GenAiObservationRecorder recorder;

        public RetrievalListener(GenAiObservationRecorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public void onRequest(ContentRetrieverRequestContext requestContext) {
            Observation observation = recorder.startRetrieval(requestContext.query());
            if (observation != null) {
                requestContext.attributes().put(OBSERVATION_KEY, observation);
            }
        }

        @Override
        public void onResponse(ContentRetrieverResponseContext responseContext) {
            Observation observation = (Observation) responseContext.attributes().get(OBSERVATION_KEY);
            if (observation != null) {
                recorder.endRetrieval(observation, responseContext.contents());
            }
        }

        @Override
        public void onError(ContentRetrieverErrorContext errorContext) {
            Observation observation = (Observation) errorContext.attributes().get(OBSERVATION_KEY);
            if (observation != null) {
                recorder.errorRetrieval(observation, errorContext.error());
            }
        }
    }
}
