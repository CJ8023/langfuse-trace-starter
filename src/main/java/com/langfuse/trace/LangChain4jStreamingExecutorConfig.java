package com.langfuse.trace;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;

/**
 * 覆盖 langchain4j 各 provider starter 的流式 executor，注入 MDC + OTel context 传播装饰器。
 *
 * <p><b>背景</b>：各 streaming ChatModel 的 LLM 调用（含 {@code ChatModelListener.onRequest}）
 * 在其专用 executor 线程（openai → {@code LangChain4j-OpenAI-N}）上执行。starter 默认的
 * {@code *TaskExecutorWithContextPropagation} 虽挂
 * {@code ContextPropagatingTaskDecorator}，但实测 MDC 未传播到该线程（ContextRegistry
 * 捕获时机不可靠），导致 LLM gen_ai span 无法归属应用 traceId。</p>
 *
 * <p><b>修复</b>：注册 openai starter 的同名 executor bean（其声明
 * {@code @ConditionalOnMissingBean(name="openAiStreamingChatModelTaskExecutor")}，
 * 我们早于 auto-config 注册即触发其退避），注入 {@link #tracePropagationDecorator()}，
 * 在提交时显式捕获 OTel Context 与 MDC、任务内恢复——LLM listener 从 MDC 读到 traceId
 * 合成父上下文，与 HTTP span 同 trace。</p>
 *
 * <p><b>条件装配</b>：仅当目标项目 classpath 存在 openai 模型（使用
 * {@code langchain4j-open-ai-spring-boot-starter}）时生效；未使用 openai 的项目自动跳过。</p>
 */
@Slf4j
@Configuration
@ConditionalOnClass(name = "dev.langchain4j.model.openai.OpenAiChatModel")
public class LangChain4jStreamingExecutorConfig {

    /** bean 名必须与 openai starter httpClientBuilder 的 @Qualifier("openAiStreamingChatModelTaskExecutor") 一致 */
    @Bean(name = "openAiStreamingChatModelTaskExecutor")
    public AsyncTaskExecutor openAiStreamingChatModelTaskExecutor() {
        return streamingExecutor("LangChain4j-OpenAI-");
    }

    /** 构建带线程前缀的流式 executor：提交时捕获、任务内恢复 MDC + OTel Context */
    private AsyncTaskExecutor streamingExecutor(String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setQueueCapacity(0);
        executor.setTaskDecorator(tracePropagationDecorator());
        log.info("langchain4j streaming executor overridden with MDC+OTel context propagation: {}", threadNamePrefix);
        return executor;
    }

    /**
     * 任务装饰器：在提交线程（boundedElastic，MDC/OTel context 已由 Spring 自动 context
     * propagation 恢复）捕获 OTel Context 与 MDC 副本，任务在模型 executor 线程执行前恢复——
     * 模型 executor 非 Reactor 线程，自动传播覆盖不到，需显式装饰。
     */
    private static TaskDecorator tracePropagationDecorator() {
        return task -> {
            Context otelCtx = Context.current();
            Map<String, String> mdc = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                try (Scope scope = otelCtx.makeCurrent()) {
                    if (mdc != null) {
                        MDC.setContextMap(mdc);
                    }
                    task.run();
                } finally {
                    if (previous != null) {
                        MDC.setContextMap(previous);
                    } else {
                        MDC.clear();
                    }
                }
            };
        };
    }
}
