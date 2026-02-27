package io.jenkins.plugins.aiagentjob;

import net.sf.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Normalized token usage and cost statistics extracted from AI agent JSONL logs. Aggregates values
 * across all lines in the log (multiple turns, partial results, etc.) so the final object reflects
 * totals for the entire session.
 */
public final class AgentUsageStats implements Serializable {
    private static final long serialVersionUID = 1L;

    private long inputTokens;
    private long outputTokens;
    private long cacheReadTokens;
    private long cacheWriteTokens;
    private long totalTokens;
    private long reasoningTokens;
    private double costUsd;
    private long durationMs;
    private long apiDurationMs;
    private int numTurns;
    private int toolCalls;
    private String detectedModel = "";

    public long getInputTokens() {
        return inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public long getCacheReadTokens() {
        return cacheReadTokens;
    }

    public long getCacheWriteTokens() {
        return cacheWriteTokens;
    }

    public long getTotalTokens() {
        if (totalTokens > 0) return totalTokens;
        return inputTokens + outputTokens + cacheReadTokens + cacheWriteTokens;
    }

    public long getReasoningTokens() {
        return reasoningTokens;
    }

    public double getCostUsd() {
        return costUsd;
    }

    /** Formatted cost string like "$0.30" or empty if no cost data. */
    public String getCostDisplay() {
        if (costUsd <= 0) return "";
        return String.format(Locale.US, "$%.2f", costUsd);
    }

    public long getDurationMs() {
        return durationMs;
    }

    /** Formatted duration like "4.5s" or "2m 15s". */
    public String getDurationDisplay() {
        if (durationMs <= 0) return "";
        if (durationMs < 1000) return durationMs + "ms";
        long secs = durationMs / 1000;
        if (secs < 60) return String.format(Locale.US, "%.1fs", durationMs / 1000.0);
        return (secs / 60) + "m " + (secs % 60) + "s";
    }

    public long getApiDurationMs() {
        return apiDurationMs;
    }

    public int getNumTurns() {
        return numTurns;
    }

    public int getToolCalls() {
        return toolCalls;
    }

    /** Model name detected from system init or result lines, empty if not found. */
    public String getDetectedModel() {
        return detectedModel;
    }

    /** Formats a token count with comma grouping (e.g., "103,854"). */
    public String getInputTokensDisplay() {
        return formatNumber(inputTokens);
    }

    public String getOutputTokensDisplay() {
        return formatNumber(outputTokens);
    }

    public String getCacheReadTokensDisplay() {
        return formatNumber(cacheReadTokens);
    }

    public String getCacheWriteTokensDisplay() {
        return formatNumber(cacheWriteTokens);
    }

    public String getTotalTokensDisplay() {
        return formatNumber(getTotalTokens());
    }

    public String getReasoningTokensDisplay() {
        return formatNumber(reasoningTokens);
    }

    private static String formatNumber(long value) {
        return NumberFormat.getIntegerInstance(Locale.US).format(value);
    }

    /** Returns true if any meaningful data was extracted. */
    public boolean hasData() {
        return inputTokens > 0
                || outputTokens > 0
                || totalTokens > 0
                || costUsd > 0
                || durationMs > 0;
    }

    /**
     * Parses the entire JSONL log file and returns aggregated stats. Each line that contains usage
     * or stats information contributes to the totals.
     */
    public static AgentUsageStats fromLogFile(File logFile) throws IOException {
        AgentUsageStats stats = new AgentUsageStats();
        if (logFile == null || !logFile.exists()) return stats;

        try (BufferedReader reader =
                Files.newBufferedReader(logFile.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith("{") || !line.endsWith("}")) continue;
                try {
                    JSONObject json = JSONObject.fromObject(line);
                    stats.extractFrom(json);
                } catch (RuntimeException ignored) {
                }
            }
        }
        return stats;
    }

    /** Extracts stats from a single JSON line. Called for every line in the log. */
    void extractFrom(JSONObject json) {
        String type = json.optString("type", "").toLowerCase(Locale.ROOT);

        if ("system".equals(type) || "init".equals(type)) {
            String model = json.optString("model", "").trim();
            if (!model.isEmpty() && detectedModel.isEmpty()) {
                detectedModel = model;
            }
        }

        if ("result".equals(type)) {
            extractClaudeResult(json);
        }

        if ("assistant".equals(type)) {
            JSONObject message = json.optJSONObject("message");
            if (message != null) {
                JSONObject usage = message.optJSONObject("usage");
                if (usage != null) {
                    accumulateUsage(usage);
                }
            }
        }

        // Codex: "turn.completed"
        if ("turn.completed".equals(type)) {
            JSONObject usage = json.optJSONObject("usage");
            if (usage != null) {
                accumulateUsage(usage);
            }
        }

        // OpenCode: "step_finish"
        if ("step_finish".equals(type)) {
            JSONObject part = json.optJSONObject("part");
            if (part != null) {
                extractOpenCodePart(part);
            }
        }
    }

    private void extractClaudeResult(JSONObject json) {
        costUsd = Math.max(costUsd, json.optDouble("total_cost_usd", 0));
        durationMs = Math.max(durationMs, json.optLong("duration_ms", 0));
        apiDurationMs = Math.max(apiDurationMs, json.optLong("duration_api_ms", 0));
        numTurns = Math.max(numTurns, json.optInt("num_turns", 0));

        JSONObject usage = json.optJSONObject("usage");
        if (usage != null) {
            accumulateUsage(usage);
        }

        // Gemini stores stats differently
        JSONObject stats = json.optJSONObject("stats");
        if (stats != null) {
            extractGeminiStats(stats);
        }
    }

    private void accumulateUsage(JSONObject usage) {
        inputTokens =
                Math.max(
                        inputTokens,
                        usage.optLong("input_tokens", usage.optLong("inputTokens", 0)));
        outputTokens =
                Math.max(
                        outputTokens,
                        usage.optLong("output_tokens", usage.optLong("outputTokens", 0)));
        cacheReadTokens =
                Math.max(
                        cacheReadTokens,
                        usage.optLong(
                                "cache_read_input_tokens",
                                usage.optLong(
                                        "cached_input_tokens",
                                        usage.optLong("cacheReadTokens", 0))));
        cacheWriteTokens =
                Math.max(
                        cacheWriteTokens,
                        usage.optLong(
                                "cache_creation_input_tokens",
                                usage.optLong(
                                        "cacheWriteTokens",
                                        usage.optLong("cacheCreationInputTokens", 0))));

        // Codex uses cached_input_tokens
        if (usage.has("cached_input_tokens") && !usage.has("cache_read_input_tokens")) {
            cacheReadTokens = Math.max(cacheReadTokens, usage.optLong("cached_input_tokens", 0));
        }
    }

    private void extractGeminiStats(JSONObject stats) {
        totalTokens = Math.max(totalTokens, stats.optLong("total_tokens", 0));
        inputTokens =
                Math.max(inputTokens, stats.optLong("input_tokens", stats.optLong("input", 0)));
        outputTokens =
                Math.max(outputTokens, stats.optLong("output_tokens", stats.optLong("output", 0)));
        cacheReadTokens = Math.max(cacheReadTokens, stats.optLong("cached", 0));
        durationMs = Math.max(durationMs, stats.optLong("duration_ms", 0));
        toolCalls = Math.max(toolCalls, stats.optInt("tool_calls", 0));
    }

    private void extractOpenCodePart(JSONObject part) {
        double partCost = part.optDouble("cost", 0);
        if (partCost > 0) costUsd += partCost;

        JSONObject tokens = part.optJSONObject("tokens");
        if (tokens == null) return;

        inputTokens += tokens.optLong("input", 0);
        outputTokens += tokens.optLong("output", 0);
        reasoningTokens += tokens.optLong("reasoning", 0);
        long partTotal = tokens.optLong("total", 0);
        if (partTotal > 0) totalTokens += partTotal;

        JSONObject cache = tokens.optJSONObject("cache");
        if (cache != null) {
            cacheReadTokens += cache.optLong("read", 0);
            cacheWriteTokens += cache.optLong("write", 0);
        }
    }
}
