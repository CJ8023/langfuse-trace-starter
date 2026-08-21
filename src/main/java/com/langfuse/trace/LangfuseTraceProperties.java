package com.langfuse.trace;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Langfuse 请求追踪过滤与命名配置（反向排除：命中 exclude-uris 前缀的请求不创建 HTTP observation）。
 *
 * <p>默认追踪全部请求——新增业务接口零改动即接入 Langfuse；仅需排除健康检查、
 * 会话 CRUD 等纯管理端点（高频且无 AI 语义）。</p>
 *
 * <p>路径→模式/标签映射<b>完全由调用方在 yml 配置</b>（不同项目端点前缀不同），
 * 以「URI 前缀列表 → mode+tag」的 list 结构承载（{@link #uriMappings}）；未配置映射的路径
 * {@link #modeForPath}/{@link #tagForPath} 返回 null，对应 trace 名前缀/tags 属性不设置。
 * 示例见 README「使用方式」。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "langfuse.trace")
public class LangfuseTraceProperties {

    /** 不追踪的请求 URI 前缀（startsWith 匹配）；空列表则所有请求都追踪 */
    private List<String> excludeUris = new ArrayList<>();

    /** 请求路径前缀列表 → 模式+标签组合（LLM trace 名前缀 + Langfuse tags）；yml 配置，默认空 */
    private List<UriMapping> uriMappings = new ArrayList<>();

    /** 是否命中排除前缀（ObservationPredicate 用：命中则跳过观测） */
    public boolean isExcluded(String path) {
        for (String excludeUri : excludeUris) {
            if (path.startsWith(excludeUri)) {
                return true;
            }
        }
        return false;
    }

    /** 路径 → 模式（最长前缀优先，/api/chat vs /api/chat-history 之类场景避免短前缀误命中）；未命中返回 null */
    public String modeForPath(String path) {
        UriMapping m = longestPrefix(path);
        return m == null ? null : m.getMode();
    }

    /** 路径 → tags 标签；未命中返回 null（不设属性） */
    public String tagForPath(String path) {
        UriMapping m = longestPrefix(path);
        return m == null ? null : m.getTag();
    }

    /** 最长匹配前缀查找：遍历所有条目的 uri 前缀，避免 {"/api/chat", "/api/chat/v2"} 短前缀抢先命中长前缀 */
    private UriMapping longestPrefix(String path) {
        UriMapping matched = null;
        int matchedLength = -1;
        for (UriMapping mapping : uriMappings) {
            if (mapping.getUris() == null) {
                continue;
            }
            for (String uri : mapping.getUris()) {
                if (path.startsWith(uri) && uri.length() > matchedLength) {
                    matched = mapping;
                    matchedLength = uri.length();
                }
            }
        }
        return matched;
    }

    /** 请求路径前缀列表 → 模式+标签组合（mode 为 LLM trace 名前缀，tag 为 Langfuse tags；任一为空则不设置对应属性） */
    @Getter
    @Setter
    public static class UriMapping {

        /** 命中的请求路径前缀列表（startsWith 匹配，最长前缀优先）；可多个 uri 共用一个模式/标签 */
        private List<String> uris = new ArrayList<>();

        /** LLM trace 名前缀（如 chat / rag / agent）；空则不设前缀 */
        private String mode;

        /** Langfuse tags 标签（如 chat / graph）；空则不设标签 */
        private String tag;
    }
}
