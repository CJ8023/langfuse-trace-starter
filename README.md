# langfuse-trace-spring-boot-starter

Langchain4j 应用接入 [Langfuse](https://langfuse.com)（LLM 可观测平台）的 **Spring Boot Starter**。
从 `langchain4j-ddd-service` 的 `shared.observation` 包抽取、解耦后封装为独立组件，
**开箱即用、可整体迁移到相同版本基线（Spring Boot 3.5.x / langchain4j 1.18.1 / langgraph4j 1.8.24）的其他项目**。

- 坐标：`com.cj:langfuse-trace-spring-boot-starter:1.0.0`
- 代码包：`com.langfuse.trace.*`

---

## 目录

- [背景](#背景)
- [适配版本](#适配版本)
- [技术实现](#技术实现)
  - [整体架构](#整体架构)
  - [核心类职责](#核心类职责)
  - [trace 上下文三通道](#trace-上下文三通道)
  - [跨线程传播三层](#跨线程传播三层)
  - [流式桥接：观测与事件协议解耦](#流式桥接观测与事件协议解耦)
  - [自动装配机制](#自动装配机制)
- [使用方式](#使用方式)
  - [1. 引入依赖](#1-引入依赖)
  - [2. 配置 yml](#2-配置-yml)
  - [3. 流式响应接入（SSE 薄封装）](#3-流式响应接入sse-薄封装)
  - [4. 图编排（langgraph4j，可选）](#4-图编排langgraph4j可选)
  - [5. 提示词模板关联（可选 SPI）](#5-提示词模板关联可选-spi)
  - [6. 验证](#6-验证)
- [常见问题](#常见问题)

---

## 背景

Langfuse 官方通过 **OTLP + OpenTelemetry 语义约定**接入 trace。langchain4j 应用直接接入需要大量样板代码：

1. HTTP 层：为每个请求创建 HTTP observation、回传 `X-Trace-Id`，并按业务端点打 `langfuse.tags`；
2. 模型层：注册 `ChatModelListener` 产出 `gen_ai.*` 语义 span，并在 trace 上写 `langfuse.trace.name/input/output`、关联提示词模板版本；
3. 线程传播：langchain4j 流式模型在独立 executor 线程、RAG/图节点在 `boundedElastic` 线程执行——**MDC 与 OTel Context 必须手动跨线程投影**，否则 trace 断链、命名模式丢失；
4. 图编排：langgraph4j 每个节点需开启 span observation（HITL 审批等异步 resume 场景尤其关键）；
5. 流式响应：SSE 事件流中要持续携带 trace 上下文。

本 Starter 将这些能力**组件化、参数化（配置外置）**，宿主项目只需：
- 引入依赖 + 配置 `langfuse.trace.*`；
- 流式接口套一层 SSE 薄封装；
- （可选）写入 3 个 MDC 键关联 Langfuse Prompt 模板。

> 背景细节：早期版本中 Langfuse 出现「trace 名被后到节点覆盖」「trace 消失」等问题，
> 根因即**摘要压缩线程 MDC 未投影**导致命名模式缺省为 `chat`。本组件在
> `TraceSpanSupport.TraceExecution` 中实现了 OTel Context + MDC 双投影修复（见「跨线程传播」）。

---

## 适配版本

| 依赖 | 版本 | 说明 |
|---|---|---|
| Java | **17** | 编译目标 |
| Spring Boot | **3.5.16**（3.5.x） | 父 POM + Actuator |
| langchain4j | **1.18.1** | BOM 统一管理，`langchain4j-observation` / `langchain4j-core` |
| langgraph4j | **1.8.24** | 可选，与 langchain4j 1.18.1 精确匹配 |
| OpenTelemetry | **1.49.0** | `opentelemetry-exporter-otlp`，与 Spring Boot 3.5.16 BOM 对齐 |
| micrometer | — | `micrometer-tracing-bridge-otel`（版本由 Boot BOM 管理） |

> 依赖版本均在 `pom.xml` `<properties>` 显式声明，迁移时如需升级请整体对齐上述基线。

> **流式 executor 覆盖仅限 OpenAI**：`LangChain4jStreamingExecutorConfig` 仅覆盖 OpenAI 流式模型的
> `openAiStreamingChatModelTaskExecutor`（修复 langchain4j 该 executor 的 MDC 未传播、gen_ai span
> 无法归属应用 traceId 的问题）。**其他 provider（xinference / ollama 等）需按同款
> `TaskDecorator` 模式自行覆盖对应流式 executor**，否则流式 LLM 调用仍会 trace 断链。

---

## 技术实现

### 整体架构

```
浏览器/客户端
   │  HTTP + X-Trace-Id ←————————————（响应头回传）
   ▼
WebFlux 过滤器链
   ├─ TraceHeaderFilter         生成/传递 RequestTrace（Reactor context），X-Trace-Id 回传
   ├─ langfuseSessionFilter     X-Session-Id/X-User-Id → session.id/langfuse.user.id，路径→tags
   └─ langfuseHttpExcludePredicate   exclude-uris 前缀排除 + OPTIONS 跳过
   ▼
业务 Service（Reactor 管道，traceId 自动传播）
   ├─ SseObservationStreamBridges.bridge(...)  流式调用（观测桥接，SSE 协议在业务侧）
   ├─ GenAiObservationRecorder                 工具/节点/检索手动观测
   └─ langgraph4j（GraphTracePropagation）      图节点 wrap-hook（span 树 + MDC）
   ▼
ChatModelListener（自动装配）
   └─ PromptRecordingChatModelListener + PromptRecordingConvention
        gen_ai.* span / langfuse.trace.name/input/output / prompt 模板版本
   ▼
micrometer-tracing-bridge-otel → opentelemetry-exporter-otlp → Langfuse OTLP 端点
```

### 核心类职责

| 类 | 职责 |
|---|---|
| `RequestTrace` | trace 上下文三通道载体：Reactor context（`apiReq`）→ RunnableConfig.metadata（`api.req`）→ 方法参数/闭包；记录 traceId / OTel Context / 模式 / 输入 / 会话 ID；`capture()` 捕获调用线程 OTel Context + MDC |
| `TraceSpanSupport` | 观测执行器：`currentOrSyntheticScope`（无父 span 时合成远端父）、`reactorObservationScope`（Reactor 信号路径建 Observation.Scope）、`captureTrace`；内部 `TraceExecution` 提供 **OTel Context + MDC 双投影** |
| `TraceHeaderFilter` | WebFlux `WebFilter`：拦截请求 → `buildShell` 建 `RequestTrace` → `contextWrite(apiReq)` → 响应 `beforeCommit` 回传 `X-Trace-Id`/`traceparent`；降级链：当前 span → MDC `traceId` |
| `PromptRecordingConvention` | `DefaultChatModelConvention` 子类：写 `gen_ai.prompt/completion`、`langfuse.trace.name/input/output`、`gen_ai.usage.*`、`langfuse.prompt.name`；trace 名前缀读 MDC `langfuse.trace.mode`（缺省 `chat`） |
| `PromptRecordingChatModelListener` | `ChatModelListener`：`onRequest` 开启 scope + 记录 prompt 版本属性，`onResponse/onError` 关闭 |
| `GenAiObservationRecorder` | 手动观测：工具调用（`startTool/endTool/observeTool/abortTools`）、图节点（`startNode/endNode`）、检索（`ContentRetrieverListener`） |
| `ObservationStreamBridges` | **泛型流式桥接** `bridge(Consumer<Bridge<S>>)` 返回 `Flux<S>`：`boundedElastic` 订阅 + 信号路径建 Observation.Scope；与具体事件协议解耦 |
| `GraphTracePropagation` | langgraph4j 工具类：`captureTo`（写 `RunnableConfig.metadata`）、`nodeWrapHook`（节点线程恢复 span 树 + MDC）、`nodeObservationHook`（节点 span）、`observationInput`（HITL 审批审计） |
| `LangChain4jStreamingExecutorConfig` | 覆盖 OpenAI 流式模型的 `openAiStreamingChatModelTaskExecutor` bean：`TaskDecorator` 提交时捕获 OTel Context + MDC，任务内恢复 |
| `LangfuseTraceProperties` | `@ConfigurationProperties(prefix = "langfuse.trace")`：排除 URI、路径前缀列表→模式+标签组合（`uriMappings`，内嵌 `uris`/`mode`/`tag`） |
| `LangfuseMdcKeys` | MDC 键常量唯一事实来源（traceId / mode / input / prompt.name / prompt.version） |
| `LangfuseTraceAutoConfiguration` | 自动装配入口：5 个 bean（见「自动装配机制」） |

### trace 上下文三通道

`RequestTrace` 通过三条通道跨异步边界传递（按场景择优）：

1. **Reactor context**（键 `apiReq`）：主请求 → 业务 Service → 流式 `Flux` 信号路径，由 Spring Boot 自动 context propagation 贯穿；
2. **RunnableConfig.metadata**（键 `api.req`）：图编排模式，`GraphTracePropagation.captureTo` 写入，节点线程内读取；
3. **方法参数/闭包**：工具回调、记忆压缩 lambda、HITL 审批 resume 等无法走前两通道的场景。

父上下文解析（`resolveTraceId`）：
- 有真实 HTTP 父 span → 复用其 traceId；
- 无（如后台任务、摘要线程）→ 由 traceId 合成远端父（`0000000000000001`），避免孤儿 span；
- 仍无 traceId → 跳过观测（防污染）。

### 跨线程传播三层

| 层 | 机制 | 场景 |
|---|---|---|
| 1. Reactor 信号路径 | Spring Boot 自动 context propagation | 主流程、流式 `Flux` 内 |
| 2. 模型 executor 线程 | `LangChain4jStreamingExecutorConfig` 的 `TaskDecorator`（bean 名 `openAiStreamingChatModelTaskExecutor`） | 流式 LLM 调用线程 |
| 3. 业务自建线程 | `TraceExecution` / `GraphTracePropagation.nodeWrapHook` 的 **OTel Context + MDC 双投影** | `boundedElastic`、图节点线程 |

`TraceExecution.execute()` 关键实现（阶段一修复的核心）：

```java
Map<String, String> previous = MDC.getCopyOfContextMap();
try (Scope ignored = captured.makeCurrent()) {
    captured.toMdc();          // 投影 mode/input/traceId 到 MDC
    return supplier.get();
} finally {
    if (previous == null) MDC.clear();
    else MDC.setContextMap(previous);   // 还原调用线程 MDC，避免污染
}
```

### 流式桥接：观测与事件协议解耦

`ObservationStreamBridges` 只负责 **trace 上下文传播**，不感知业务事件协议：

```java
public static <S> Flux<S> bridge(Consumer<Bridge<S>> starter)
public static final class Bridge<S> {
    public FluxSink<S> sink();
    public String traceId();
    public RequestTrace requestTrace();
    public void complete(String input, String conversationId);  // 入口补全 + MDC 投影
}
```

SSE 事件协议（`delta/done/error`、`SseEvent`/`SseEventFlux`）留在业务侧薄封装
（如 `SseObservationStreamBridges`），调用方式：

```java
Flux<StreamEvent> flux = SseObservationStreamBridges.bridge(bridge -> {
    bridge.complete(input, conversationId);
    chatModel.chat(chatRequest, handler -> handler
            .onPartialResponse(text -> bridge.delta(text))
            .onCompleteResponse(resp -> bridge.done(resp, conversationId))
            .onError(err -> bridge.error(err)));
});
```

### 自动装配机制

通过 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册：

```text
com.langfuse.trace.LangfuseTraceAutoConfiguration
com.langfuse.trace.LangChain4jStreamingExecutorConfig
```

`LangfuseTraceAutoConfiguration` 装配 5 个 bean：

| Bean | 条件 | 作用 |
|---|---|---|
| `observationChatModelListener` | `@ConditionalOnClass(ChatModelListener)` + `@ConditionalOnMissingBean` | 模型级 gen_ai 观测 |
| `genAiObservationRecorder` | 同上 | 工具/节点/检索手动观测 |
| `langfuseHttpExcludePredicate` | — | HTTP observation 排除（exclude-uris + OPTIONS） |
| `langfuseSessionFilter` | — | 会话/用户/标签属性 WebFilter |
| `traceHeaderFilter` | `@ConditionalOnWebApplication(type = REACTIVE)` + `@ConditionalOnMissingBean` | X-Trace-Id 回传 |

`LangChain4jStreamingExecutorConfig` 以 `@ConditionalOnClass(name = "dev.langchain4j.model.openai.OpenAiChatModel")`
仅在存在 OpenAI 流式模型时覆盖 executor bean；langgraph4j 为 optional 依赖，未引入时相关类不加载。

---

## 使用方式

### 1. 引入依赖

```xml
<dependency>
    <groupId>com.cj</groupId>
    <artifactId>langfuse-trace-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

> Starter 已传递：`spring-boot-starter-webflux`、`spring-boot-starter-actuator`、`micrometer-tracing-bridge-otel`、
> `opentelemetry-exporter-otlp`、`langchain4j-observation`、`langchain4j-core`（langgraph4j 为 optional）。
> 宿主无需重复声明上述依赖，但需要自己引入 `spring-boot-starter-webflux` 之前的业务栈（Web 应用本身）。

### 2. 配置 yml

```yaml
management:
  tracing:
    sampling:
      probability: 1.0          # 生产可按需调低

langfuse:
  trace:
    # 不追踪的 URI 前缀（高频管理端点，无 AI 语义）
    exclude-uris:
      - /api/health
      - /actuator
    # 请求路径前缀列表 → 模式+标签组合（LLM trace 名前缀 + Langfuse tags；同一模式可对应多个 uri，最长前缀匹配，未命中不设前缀/标签）
    uri-mappings:
      - uris:
          - /api/chat
        mode: chat
        tag: chat
      - uris:
          - /api/rag
        mode: rag
        tag: rag
      - uris:
          - /api/agent
        mode: agent
        tag: agent
      - uris:
          - /api/graph
        mode: workflow
        tag: graph
      - uris:
          - /api/structured
        mode: structured     # 仅设模式，不设标签
```

OTLP 导出端点按环境配置（在宿主的 application-dev/prod.yml）：

```yaml
management:
  otlp:
    tracing:
      export:
        enabled: ${LANGFUSE_OTLP_ENABLED:true}
        endpoint: ${LANGFUSE_OTLP_ENDPOINT:http://localhost:3000/api/public/otel/v1/traces}
        headers:
          Authorization: Basic ${LANGFUSE_AUTH:}
```

> `uri-mappings` **无内置默认值，必须由调用方配置**；
> 未配置的路径 trace 名前缀/tags 不设置（`modeForPath`/`tagForPath` 返回 null）。

### 3. 流式响应接入（SSE 薄封装）

Starter 的桥接是泛型的，与 SSE 协议解耦。宿主在自己的 `shared.stream` 包实现薄封装：

```java
public final class SseObservationStreamBridges {
    public static Flux<StreamEvent> bridge(Consumer<Bridge> starter) {
        return ObservationStreamBridges.<StreamEvent>bridge(b -> starter.accept(new Bridge(b)));
    }
    public static final class Bridge {
        private final ObservationStreamBridges.Bridge<StreamEvent> delegate;
        public FluxSink<StreamEvent> sink() { return delegate.sink(); }
        public String traceId() { return delegate.traceId(); }
        public RequestTrace requestTrace() { return delegate.requestTrace(); }
        public void complete(String input, String conversationId) { delegate.complete(input, conversationId); }
        public void delta(String text) { /* sink().next(EVENT_DELTA, ...) */ }
        public void done(ChatResponse r, String conversationId) { /* sink().next(EVENT_DONE, ...); complete() */ }
        public void error(Throwable t) { /* sink().next(EVENT_ERROR, ...); sink().error(t) */ }
    }
}
```

Service 中调用（完整示例见上方「流式桥接」小节）。

### 4. 图编排（langgraph4j，可选）

引入 `langgraph4j-core` 后，图节点自动获得 span + MDC 传播：

```java
// 运行时（如 GraphRunBridge）：把 RequestTrace 写入 RunnableConfig.metadata
RunnableConfig config = GraphTracePropagation.captureTo(
        RunnableConfig.builder().threadId(threadId), requestTrace).build();

// 组装图（如 WorkflowSupport）：注册两个 wrap-hook，注意顺序——先节点观测再上下文恢复
graph.addWrapCallNodeHook(GraphTracePropagation.nodeObservationHook(recorder));
graph.addWrapCallNodeHook(GraphTracePropagation.nodeWrapHook());
```

### 5. 提示词模板关联（可选 SPI）

Starter 通过 **MDC 键 SPI** 与宿主的 Langfuse Prompt 服务解耦（无编译期依赖）。宿主在请求内写入：

```java
MDC.put(LangfuseMdcKeys.LANGKFUSE_PROMPT_NAME_KEY, name);      // "langfuse.prompt.name"
MDC.put(LangfuseMdcKeys.LANGKFUSE_PROMPT_VERSION_KEY, version); // "langfuse.prompt.version"
```

`PromptRecordingConvention` / `PromptRecordingChatModelListener` 会自动将其写入 OTel 属性，
Langfuse 侧即可看到本次 LLM 调用使用的提示词模板名与版本。

### 6. 验证

1. 启动后访问业务端点，响应头应携带 `X-Trace-Id`；
2. 控制台日志按 `%X{traceId:-}` 渲染，同一次请求所有线程日志 traceId 一致（含流式 executor、图节点、摘要压缩线程）；
3. Langfuse 控制台按 `langfuse.trace.name` 前缀与 `tags` 筛选，能聚合到 LLM span、工具 span、节点 span；
4. 流式接口的 SSE 事件序列完整（delta/done/error），且单条 trace 内包含完整调用链。

---

## 常见问题

| 现象 | 原因与处理 |
|---|---|
| trace 名被后到节点覆盖为 `chat xxx` | 跨线程 MDC 未投影，`langfuse.trace.mode` 缺省 `chat`。确认使用本组件的 `TraceExecution`/`nodeWrapHook`（已内置双投影）承载异步调用 |
| 某些节点无 TOOL/节点 span | OTLP 导出侧独立问题（span 关联丢失），先确认同一 traceId 下 span 是否已创建（`GenAiObservationRecorder` 有日志） |
| 图节点线程 trace 断链 | 确认已注册 `nodeWrapHook` 且顺序在 `nodeObservationHook` 之后 |
| `X-Trace-Id` 未回传 | 确认 `traceHeaderFilter` bean 存在（`@ConditionalOnWebApplication(REACTIVE)`）且非 `@ConditionalOnMissingBean` 冲突 |
| 升级版本后编译失败 | 严格对齐「适配版本」基线；langchain4j/langgraph4j 为强版本耦合 |
| 非 OpenAI provider 流式 trace 断链 | 当前 starter 仅覆盖 OpenAI 流式 executor 的 MDC/OTel 传播；其他 provider 参照 `LangChain4jStreamingExecutorConfig` 的同款 `TaskDecorator` 模式自行覆盖对应 executor bean |
