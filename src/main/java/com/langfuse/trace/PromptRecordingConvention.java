package com.langfuse.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.observation.context.ChatModelObservationContext;
import dev.langchain4j.observation.convention.DefaultChatModelConvention;
import io.micrometer.common.KeyValues;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记录 LLM 请求/响应内容供 Langfuse 展示（继承默认，增强 high cardinality）。
 *
 * <p>langchain4j 默认 convention 只记录元数据（provider/model/耗时/token），不记录
 * prompt/completion 内容——Langfuse 因此显示 input/output 为空。本类补充结构化消息到
 * {@code gen_ai.*}，并用 Langfuse 专属命名空间控制 trace 列表列：</p>
 * <ul>
 *   <li>{@code gen_ai.prompt/completion}：generation 详情的结构化 Input/Output（OpenAI 风格消息数组，
 *       含 tool 消息/tool_calls，Langfuse 渲染为消息列表）。</li>
 *   <li>{@code langfuse.trace.name/input/output}：trace 列表的 Name/Input/Output 列（纯文本）。</li>
 * </ul>
 * <p>注意：Langfuse 的 OTel 映射用 waterfall 取 input/output，{@code langfuse.observation.input/output}
 * 优先级<b>高于</b> {@code gen_ai.prompt}——若写了会盖掉结构化详情，故本类<b>不写</b>
 * langfuse.observation.* 的 input/output，只保留 trace 列表列所需的 langfuse.trace.*。</p>
 * <p>token：默认 convention 仅发新版分项（{@code gen_ai.usage.input_tokens/output_tokens}），
 * 本类补<b>旧版名</b> {@code gen_ai.usage.prompt_tokens/completion_tokens}（Langfuse v3.222 的
 * Input/Output usage 列读旧名）+ {@code gen_ai.usage.total_tokens}。</p>
 * <p>Langfuse 提示词关联：本次请求使用的模板名从 MDC 读（业务侧 LangfusePromptService 在
 * 装配系统提示词时写入，经 executor 装饰器随 MDC 传播到模型线程）；MDC 键定义在本组件
 * {@link LangfuseMdcKeys}，业务侧引用同一常量写入，无编译期依赖。</p>
 */
public class PromptRecordingConvention extends DefaultChatModelConvention {

    /** JSON 序列化（提示词/会话内容写入 span 属性） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** span 属性单值截断长度（防 stop_sequences 等配置项撑大属性） */
    private static final int MAX_ATTRIBUTE_LENGTH = 500;

    @Override
    public KeyValues getHighCardinalityKeyValues(ChatModelObservationContext context) {
        KeyValues kvs = super.getHighCardinalityKeyValues(context);
        kvs = appendLangfuse(kvs, context);
        kvs = appendUsage(kvs, context);
        kvs = appendModelParameters(kvs, context);
        kvs = appendPromptLink(kvs);
        return kvs;
    }

    @Override
    public String getContextualName(ChatModelObservationContext context) {
        // 内部调用（记忆压缩摘要等）：span 名 = 调用原因（如 summary），非默认 chat {model}，
        // Langfuse 中一眼可辨内部调用与普通生成
        String reason = internalReason();
        if (reason != null) {
            return reason;
        }
        return super.getContextualName(context);
    }

    /**
     * 模型请求参数 → {@code gen_ai.request.*}（Langfuse 详情 modelParameters）。
     * 模型会把默认参数（如 openai profile 的 temperature=0.1）merge 进请求再触发 listener，
     * 此处读到的即实际请求参数。
     */
    private KeyValues appendModelParameters(KeyValues kvs, ChatModelObservationContext context) {
        ChatModelRequestContext req = context.getRequestContext();
        if (req == null || req.chatRequest() == null) {
            return kvs;
        }
        ChatRequestParameters parameters = req.chatRequest().parameters();
        if (parameters == null) {
            return kvs;
        }
        if (parameters.temperature() != null) {
            kvs = kvs.and("gen_ai.request.temperature", String.valueOf(parameters.temperature()));
        }
        if (parameters.topP() != null) {
            kvs = kvs.and("gen_ai.request.top_p", String.valueOf(parameters.topP()));
        }
        if (parameters.maxOutputTokens() != null) {
            kvs = kvs.and("gen_ai.request.max_tokens", String.valueOf(parameters.maxOutputTokens()));
        }
        if (parameters.frequencyPenalty() != null) {
            kvs = kvs.and("gen_ai.request.frequency_penalty", String.valueOf(parameters.frequencyPenalty()));
        }
        if (parameters.presencePenalty() != null) {
            kvs = kvs.and("gen_ai.request.presence_penalty", String.valueOf(parameters.presencePenalty()));
        }
        if (parameters.stopSequences() != null && !parameters.stopSequences().isEmpty()) {
            kvs = kvs.and("gen_ai.request.stop_sequences", truncate(String.join(",", parameters.stopSequences())));
        }
        return kvs;
    }

    /** 超长属性截断，避免 span 属性过大 */
    private static String truncate(String value) {
        return value.length() > MAX_ATTRIBUTE_LENGTH ? value.substring(0, MAX_ATTRIBUTE_LENGTH) + "…" : value;
    }

    /**
     * 本次请求使用的 Langfuse 提示词 → {@code langfuse.prompt.*}，与 Langfuse 模板版本关联。
     * 业务侧 {@code LangfusePromptService} 在系统提示词装配时写入 MDC（经 executor 装饰器随
     * MDC 传播到模型线程），此处读取；未用 Langfuse 提示词的调用（如直接 chatModel.chat）
     * 读到空。
     */
    private KeyValues appendPromptLink(KeyValues kvs) {
        String name = MDC.get(LangfuseMdcKeys.LANGKFUSE_PROMPT_NAME_KEY);
        if (name == null || name.isBlank()) {
            return kvs;
        }
        kvs = kvs.and("langfuse.prompt.name", name);
        // 注意：不在这里发 langfuse.prompt.version —— Langfuse OTel 导入器要求 promptVersion 为数值，
        // micrometer KeyValues 只支持 String，字符串版本会导致整个 generation observation 校验失败被丢弃。
        // 数值版本由 PromptRecordingChatModelListener 直接写 OTel span 属性。
        return kvs;
    }

    /** 详情走结构化 gen_ai.*，trace 列表列走纯文本 langfuse.trace.*（不写 langfuse.observation.input/output） */
    private KeyValues appendLangfuse(KeyValues kvs, ChatModelObservationContext context) {
        kvs = kvs.and("langfuse.observation.type", "generation");
        // 内部 LLM 调用（记忆压缩摘要等）：仍写 gen_ai.prompt/completion（trace 详情可见），
        // 但不写 langfuse.trace.*——避免内部调用作为最后一条 generation 覆盖请求 trace 的
        // 列表列（name/input/output）
        boolean internal = internalReason() != null;

        List<ChatMessage> messages = requestMessages(context);
        if (messages != null) {
            String prompt = serializeMessages(messages);
            if (prompt != null) {
                kvs = kvs.and("gen_ai.prompt", prompt);
            }
            if (!internal) {
                // 原始 query 优先取 MDC（graph 等场景 LLM prompt 为拼接内容，需用入口 query 覆盖）；
                // 无则回退从消息里提取用户输入。
                String mdcInput = MDC.get(LangfuseMdcKeys.LANGKFUSE_INPUT_KEY);
                String input = (mdcInput != null && !mdcInput.isBlank())
                        ? mdcInput : extractUserInput(messages);
                if (input != null) {
                    kvs = kvs.and("langfuse.trace.input", input);
                }
                ChatModelRequestContext req = context.getRequestContext();
                String model = req == null || req.chatRequest() == null ? null : req.chatRequest().modelName();
                kvs = kvs.and("langfuse.trace.name", traceName(model));
            }
        }

        ChatModelResponseContext resp = context.getResponseContext();
        if (resp != null && resp.chatResponse() != null && resp.chatResponse().aiMessage() != null) {
            AiMessage ai = resp.chatResponse().aiMessage();
            String completion = serializeMessage(ai);
            if (completion != null) {
                kvs = kvs.and("gen_ai.completion", completion);
            }
            if (!internal) {
                String text = ai.text();
                if (text != null && !text.isBlank()) {
                    kvs = kvs.and("langfuse.trace.output", text);
                }
            }
        }
        return kvs;
    }

    /** MDC 内部观测标记值：内部 LLM 调用原因（记忆压缩摘要="summary"）；null/空 = 非内部调用 */
    private static String internalReason() {
        String reason = MDC.get(LangfuseMdcKeys.INTERNAL_OBSERVATION_KEY);
        return (reason == null || reason.isBlank()) ? null : reason;
    }

    /**
     * trace 名 = 「模式前缀 + 模型名」：模式前缀来自 MDC（各模式入口写入，
     * {@link LangfuseMdcKeys#LANGKFUSE_MODE_KEY}），chat→chat、rag→rag、agent→agent、
     * graph→workflow；缺省 chat。模型名为空时回退纯前缀。
     */
    private static String traceName(String model) {
        String mode = MDC.get(LangfuseMdcKeys.LANGKFUSE_MODE_KEY);
        String prefix = (mode == null || mode.isBlank()) ? "chat" : mode;
        return (model == null || model.isBlank()) ? prefix : prefix + " " + model;
    }

    /**
     * token 属性：默认 convention 仅发新版分项（gen_ai.usage.input_tokens/output_tokens），
     * Langfuse v3.222 的 Input/Output usage 列读<b>旧版名</b> prompt_tokens/completion_tokens，
     * 故补旧名分项 + total_tokens。
     */
    private KeyValues appendUsage(KeyValues kvs, ChatModelObservationContext context) {
        ChatModelResponseContext resp = context.getResponseContext();
        if (resp == null || resp.chatResponse() == null || resp.chatResponse().tokenUsage() == null) {
            return kvs;
        }
        TokenUsage usage = resp.chatResponse().tokenUsage();
        Integer input = usage.inputTokenCount();
        Integer output = usage.outputTokenCount();
        if (input != null) {
            kvs = kvs.and("gen_ai.usage.prompt_tokens", String.valueOf(input));
        }
        if (output != null) {
            kvs = kvs.and("gen_ai.usage.completion_tokens", String.valueOf(output));
        }
        if (input != null && output != null) {
            kvs = kvs.and("gen_ai.usage.total_tokens", String.valueOf(input + output));
        }
        return kvs;
    }

    /** 请求消息列表（无则返回 null） */
    private List<ChatMessage> requestMessages(ChatModelObservationContext context) {
        ChatModelRequestContext req = context.getRequestContext();
        if (req == null || req.chatRequest() == null || req.chatRequest().messages() == null) {
            return null;
        }
        return req.chatRequest().messages();
    }

    /** 用户消息文本：优先取最后一条 UserMessage，无则拼接 system/user/tool 文本 */
    private String extractUserInput(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message instanceof UserMessage user) {
                String text = user.singleText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        StringBuilder joined = new StringBuilder();
        for (ChatMessage message : messages) {
            String text = messageText(message);
            if (text == null) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append('\n');
            }
            joined.append(text);
        }
        return joined.length() > 0 ? joined.toString() : null;
    }

    /** 按消息类型提取文本（ChatMessage 无统一 text()；input 不含 assistant 回复） */
    private String messageText(ChatMessage message) {
        if (message instanceof UserMessage user) {
            return user.singleText();
        }
        if (message instanceof SystemMessage sys) {
            return sys.text();
        }
        if (message instanceof ToolExecutionResultMessage tool) {
            return tool.text();
        }
        return null;
    }

    /** 消息列表 → OpenAI 风格消息数组 JSON */
    private static String serializeMessages(List<ChatMessage> messages) {
        List<Object> list = new ArrayList<>();
        for (ChatMessage message : messages) {
            Map<String, Object> map = toMessageMap(message);
            if (map != null) {
                list.add(map);
            }
        }
        return toJson(list);
    }

    /** assistant 消息 → OpenAI 风格消息 JSON（含 tool_calls） */
    private static String serializeMessage(AiMessage ai) {
        return toJson(toMessageMap(ai));
    }

    /** langchain4j 消息 → OpenAI 风格 map（role/content/tool_calls/tool_call_id） */
    private static Map<String, Object> toMessageMap(ChatMessage message) {
        if (message instanceof SystemMessage sys) {
            return Map.of("role", "system", "content", sys.text() == null ? "" : sys.text());
        }
        if (message instanceof UserMessage user) {
            return Map.of("role", "user", "content", user.singleText() == null ? "" : user.singleText());
        }
        if (message instanceof AiMessage ai) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("role", "assistant");
            map.put("content", ai.text() == null ? "" : ai.text());
            if (ai.toolExecutionRequests() != null && !ai.toolExecutionRequests().isEmpty()) {
                List<Object> calls = new ArrayList<>();
                for (ToolExecutionRequest request : ai.toolExecutionRequests()) {
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("name", request.name());
                    fn.put("arguments", request.arguments() == null ? "" : request.arguments());
                    Map<String, Object> call = new LinkedHashMap<>();
                    call.put("id", request.id() == null ? "" : request.id());
                    call.put("type", "function");
                    call.put("function", fn);
                    calls.add(call);
                }
                map.put("tool_calls", calls);
            }
            return map;
        }
        if (message instanceof ToolExecutionResultMessage tool) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("role", "tool");
            map.put("tool_call_id", tool.id());
            map.put("content", tool.text() == null ? "" : tool.text());
            return map;
        }
        return null;
    }

    /** 序列化失败返回 null（不中断上报） */
    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
