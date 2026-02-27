package io.jenkins.plugins.aiagentjob;

import java.util.Locale;

public enum AgentType {
    CLAUDE_CODE("Claude Code", "ANTHROPIC_API_KEY"),
    CODEX("Codex CLI", "OPENAI_API_KEY"),
    CURSOR_AGENT("Cursor Agent", "CURSOR_API_KEY"),
    OPENCODE("OpenCode", "OPENAI_API_KEY"),
    GEMINI_CLI("Gemini CLI", "GEMINI_API_KEY");

    private final String displayName;
    private final String defaultApiKeyEnvVar;

    AgentType(String displayName, String defaultApiKeyEnvVar) {
        this.displayName = displayName;
        this.defaultApiKeyEnvVar = defaultApiKeyEnvVar;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * The environment variable name that this agent reads for API authentication by default (e.g.
     * ANTHROPIC_API_KEY for Claude Code).
     */
    public String getDefaultApiKeyEnvVar() {
        return defaultApiKeyEnvVar;
    }

    public static AgentType fromString(String value) {
        if (value == null) {
            return CLAUDE_CODE;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (AgentType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return CLAUDE_CODE;
    }
}
