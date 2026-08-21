package com.langfuse.trace;

/**
 * MDC 键常量（观察层与业务层共享的唯一事实来源）。
 *
 * <p>从原 {@code TraceContextConfig} 拆出：starter 内的 convention/listener 读取，
 * 业务侧（TraceHeaderFilter 建壳、LangfusePromptService 提示词关联）写入。两端引用同一
 * 常量，避免字符串漂移。</p>
 */
public final class LangfuseMdcKeys {

    /** MDC 键：与日志 pattern {@code %X{traceId:-}} 对齐（micrometer-tracing 在 observation scope 打开时写入） */
    public static final String TRACE_ID_KEY = "traceId";

    /** MDC 键：请求模式标记（chat/rag/agent/workflow），由各模式入口写入，经 TaskDecorator 传播到 LLM 线程；LLM trace 命名前缀用 */
    public static final String LANGKFUSE_MODE_KEY = "langfuse.trace.mode";

    /** MDC 键：用户原始 query（trace 级 input 展示用；LLM prompt 为拼接内容时覆盖为其原始 query） */
    public static final String LANGKFUSE_INPUT_KEY = "langfuse.trace.input";

    /** MDC 键：本次请求使用的 Langfuse 提示词名称（PromptRecordingConvention 读取，关联 generation 到模板） */
    public static final String LANGKFUSE_PROMPT_NAME_KEY = "langfuse.prompt.name";

    /** MDC 键：本次请求使用的 Langfuse 提示词版本（PromptRecordingChatModelListener 读，写 OTel 数值 span 属性） */
    public static final String LANGKFUSE_PROMPT_VERSION_KEY = "langfuse.prompt.version";

    private LangfuseMdcKeys() {
    }
}
