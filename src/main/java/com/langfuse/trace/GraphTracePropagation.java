package com.langfuse.trace;

import io.micrometer.observation.Observation;
import io.opentelemetry.context.Scope;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.hook.NodeHook;
import org.bsc.langgraph4j.state.AgentState;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

/**
 * langgraph4j 图执行的 trace 上下文传播（框架标准链路的非 Reactor 兜底）。
 *
 * <p>图节点在 langgraph4j 内部 future 链线程上执行，Reactor 自动 context propagation
 * 覆盖不到，故用显式三段式（统一经 {@link RequestTrace} 载体）：</p>
 * <ol>
 *   <li><b>捕获</b>：图入口在 boundedElastic 装配线程（HTTP observation scope 已恢复）
 *       取 Reactor context 通道的 RequestTrace（filter 建壳 + 入口补全），
 *       经 {@link #captureTo} 写入 RunnableConfig.metadata（键 {@link RequestTrace#METADATA_KEY}）；</li>
 *   <li><b>携带</b>：RunnableConfig 随图执行贯穿所有节点（langgraph4j 原生机制）；</li>
 *   <li><b>恢复</b>：工作流构建图时注册 {@link #nodeWrapHook()}，wrap hook 在节点执行
 *       线程 {@code RequestTrace.makeCurrent()} 恢复捕获的 OTel Context（HTTP 父 span）
 *       并投影 mode/input 到 MDC——节点内 LLM/检索调用的 gen_ai span 自动挂到 HTTP span
 *       下、trace 命名/输入沿用入口标记，图模式与 chat/agent/rag 三模式同构。</li>
 * </ol>
 *
 * <p>注：wrap hook 用 try-with-resources 包裹节点 action——节点为同步实现
 * （{@code node_async} 同步包装）时作用域覆盖整个节点执行，语义正确。</p>
 *
 * <p>本类依赖 langgraph4j（starter 中为 optional 依赖）；目标项目未使用图编排时不会
 * 被自动装配引用，类可安全存在于 classpath。</p>
 */
public final class GraphTracePropagation {

    private GraphTracePropagation() {
    }

    /**
     * 捕获装配线程的 RequestTrace 到 config metadata（单键携带整个对象）。
     * RequestTrace 为 null（单测/无追踪环境）时不写，节点按无父执行直通。
     */
    public static RunnableConfig.Builder captureTo(RunnableConfig.Builder builder, RequestTrace requestTrace) {
        if (requestTrace != null) {
            builder.putMetadata(RequestTrace.METADATA_KEY, requestTrace);
        }
        return builder;
    }

    /** wrap hook：节点执行线程恢复 RequestTrace（makeCurrent OTel Context + mode/input 投影 MDC）；
     *  无捕获（如单测）则直通 */
    @SuppressWarnings("unchecked")
    public static <S extends AgentState> NodeHook.WrapCall<S> nodeWrapHook() {
        return (nodeId, state, config, action) -> {
            Object captured = config.metadata(RequestTrace.METADATA_KEY).orElse(null);
            if (captured instanceof RequestTrace requestTrace && requestTrace.usable()) {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                try (Scope ignored = requestTrace.makeCurrent()) {
                    requestTrace.toMdc();
                    return action.apply((S) state, config);
                } finally {
                    if (previous != null) {
                        MDC.setContextMap(previous);
                    } else {
                        MDC.clear();
                    }
                }
            }
            return action.apply((S) state, config);
        };
    }

    /**
     * 节点观测 hook：wrap 包住每个节点执行，input=节点入参（state 快照），
     * output=节点写回（action 返回值），发射 {@code span} 类型 observation。
     *
     * <p>这是"通用更上层"的节点观测方案：任何新流程只需注册本 hook，节点内无需
     * 任何上报代码——含 HITL 审批（无 LLM）在内的所有节点自动有有效观测，Langfuse
     * trace 中可见每个节点的输入/输出与耗时。</p>
     *
     * <p>入参语义：普通节点记录全量 state 快照；HITL 审批节点（state 含 {@code audit}
     * 描述符）只记录待审内容 + 人工决策——{@code audit} 即中断时点的审核上下文
     * （与 interrupt 事件推送一致），{@code decision} 为 resume 注入的用户决策，二者
     * 明确区分"审什么、怎么批"，避免全量快照混入无关字段并被截断吞掉关键信息。</p>
     *
     * <p>上下文处理：{@code startNode} 在<b>节点执行线程</b>调用——wrap 链中
     * nodeWrapHook 已 makeCurrent HTTP span（节点线程置于 HTTP trace 下），观测自动
     * 挂父。{@code endNode} 经 action future 完成回调触发（可能跨线程，同 tool/retrieval
     * 的 start/end 配对模式）。</p>
     */
    @SuppressWarnings("unchecked")
    public static <S extends AgentState> NodeHook.WrapCall<S> nodeObservationHook(
            GenAiObservationRecorder recorder) {
        return (nodeId, state, config, action) -> {
            Map<String, Object> input = observationInput(state);
            Observation observation = recorder.startNode(nodeId, input);
            return action.apply((S) state, config).whenComplete((output, error) ->
                    recorder.endNode(observation, output, error));
        };
    }

    /**
     * 节点入参提取：state 含 {@code audit} 描述符（HITL 审批节点）时只取待审内容 + 人工
     * 决策；否则（普通节点）记录全量状态快照。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> observationInput(AgentState state) {
        Map<String, Object> data = state.data();
        Object audit = data.get("audit");
        if (!(audit instanceof Map<?, ?>)) {
            return data;
        }
        Map<String, Object> input = new HashMap<>(2);
        input.put("audit", audit);
        Object decision = data.get("decision");
        if (decision != null) {
            input.put("decision", decision);
        }
        return input;
    }
}
