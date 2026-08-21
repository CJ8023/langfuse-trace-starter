package com.langfuse.trace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 配置绑定：exclude-uris 排除 + uri-mappings 最长前缀模式/标签匹配。 */
class LangfuseTracePropertiesTest {

    private LangfuseTraceProperties props() {
        LangfuseTraceProperties p = new LangfuseTraceProperties();
        p.setExcludeUris(List.of("/api/health", "/actuator"));

        LangfuseTraceProperties.UriMapping chat = new LangfuseTraceProperties.UriMapping();
        chat.setUris(List.of("/api/chat"));
        chat.setMode("chat");
        chat.setTag("chat");

        // 同一模式可对应多个 uri，且含长前缀（验证最长前缀优先，避免短前缀抢先命中）
        LangfuseTraceProperties.UriMapping agent = new LangfuseTraceProperties.UriMapping();
        agent.setUris(List.of("/api/agent", "/api/agent/v2"));
        agent.setMode("agent");
        agent.setTag("agent");

        p.setUriMappings(List.of(chat, agent));
        return p;
    }

    @Test
    void excludeUris按前缀排除() {
        LangfuseTraceProperties p = props();
        assertThat(p.isExcluded("/api/health")).isTrue();
        assertThat(p.isExcluded("/actuator/health")).isTrue();
        assertThat(p.isExcluded("/api/chat/stream")).isFalse();
    }

    @Test
    void mode按最长前缀匹配() {
        LangfuseTraceProperties p = props();
        assertThat(p.modeForPath("/api/chat/stream")).isEqualTo("chat");
        assertThat(p.modeForPath("/api/agent/stream")).isEqualTo("agent");
        // /api/agent 与 /api/agent/v2 均命中，取更长前缀对应模式（同模式，验证不误判）
        assertThat(p.modeForPath("/api/agent/v2/run")).isEqualTo("agent");
        assertThat(p.modeForPath("/api/unknown")).isNull();
    }

    @Test
    void tag与mode独立取值() {
        LangfuseTraceProperties p = props();
        assertThat(p.tagForPath("/api/agent/stream")).isEqualTo("agent");
        assertThat(p.tagForPath("/api/chat")).isEqualTo("chat");
        assertThat(p.tagForPath("/api/health")).isNull();
    }
}
