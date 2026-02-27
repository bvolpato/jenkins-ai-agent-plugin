package io.jenkins.plugins.aiagentjob;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class AiAgentLogParser {
    private AiAgentLogParser() {}

    static List<EventView> parse(File rawLogFile) throws IOException {
        if (rawLogFile == null || !rawLogFile.exists()) {
            return Collections.emptyList();
        }
        List<EventView> events = new ArrayList<>();
        try (BufferedReader reader =
                Files.newBufferedReader(rawLogFile.toPath(), StandardCharsets.UTF_8)) {
            String line;
            long idx = 0;
            while ((line = reader.readLine()) != null) {
                idx++;
                EventView ev = parseLine(idx, line).toEventView();
                if (!ev.isEmpty()) {
                    events.add(ev);
                }
            }
        }
        return events;
    }

    static ParsedLine parseLine(long lineNumber, String line) {
        if (line == null) {
            return ParsedLine.raw(lineNumber, "");
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return ParsedLine.raw(lineNumber, "");
        }

        JSONObject json = tryParseJson(trimmed);
        if (json == null) {
            return ParsedLine.raw(lineNumber, trimmed);
        }
        return classifyJson(lineNumber, json);
    }

    private static ParsedLine classifyJson(long lineNumber, JSONObject json) {
        String type = firstNonEmpty(json, "type", "event", "kind", "subtype");
        String role = firstNonEmpty(json, "role");
        String typeLower = normalize(type);
        String roleLower = normalize(role);
        String text = extractText(json);
        String details = json.toString(2);

        // --- Claude Code stream-json: system, assistant, user, tool_use, tool_result,
        // stream_event, result ---
        if (typeLower.equals("system")) {
            String subtype = normalize(firstNonEmpty(json, "subtype"));
            String modelField = firstNonEmpty(json, "model");
            String systemText =
                    !modelField.isEmpty() ? "System init (model: " + modelField + ")" : text;
            return ParsedLine.of(
                    lineNumber,
                    "system",
                    summary("System" + (!subtype.isEmpty() ? " " + subtype : ""), systemText),
                    details,
                    null,
                    null);
        }
        if (typeLower.equals("result")) {
            String resultText = firstNonEmpty(json, "result", "error");
            return ParsedLine.of(
                    lineNumber, "system", summary("Result", resultText), details, null, null);
        }

        // Claude: assistant message with content array containing
        // text/thinking/tool_use
        if (typeLower.equals("assistant") || typeLower.equals("user")) {
            JSONObject message = json.optJSONObject("message");
            if (message != null) {
                JSONArray contentArr = message.optJSONArray("content");
                if (contentArr != null && contentArr.size() > 0) {
                    return classifyClaudeContentArray(lineNumber, typeLower, contentArr, details);
                }
                String msgText = extractText(message);
                String cat = typeLower.equals("assistant") ? "assistant" : "user";
                return ParsedLine.of(
                        lineNumber, cat, summary(capitalize(cat), msgText), details, null, null);
            }
        }

        // Claude: stream_event (streaming deltas)
        if (typeLower.equals("stream_event")) {
            JSONObject event = json.optJSONObject("event");
            if (event != null) {
                return classifyClaudeStreamEvent(lineNumber, event, details);
            }
            return ParsedLine.of(
                    lineNumber, "system", summary("Stream event", text), details, null, null);
        }

        // Claude: tool_use (standalone)
        if (typeLower.equals("tool_use")) {
            String toolName = firstNonEmpty(json, "tool_name", "name");
            String toolCallId = firstNonEmpty(json, "id", "tool_call_id");
            return ParsedLine.of(
                    lineNumber,
                    "tool_call",
                    summary("Tool call: " + toolName, text),
                    details,
                    toolCallId,
                    toolName);
        }

        // Claude: tool_result (standalone)
        if (typeLower.equals("tool_result")) {
            String toolCallId = firstNonEmpty(json, "tool_call_id", "id");
            String toolName = firstNonEmpty(json, "tool_name", "name");
            return ParsedLine.of(
                    lineNumber,
                    "tool_result",
                    summary("Tool result", text),
                    details,
                    toolCallId,
                    toolName);
        }

        // --- Codex JSONL: item.started / item.completed with nested item ---
        JSONObject item = json.optJSONObject("item");
        if (item != null) {
            String itemType = normalize(item.optString("type"));
            String status = normalize(item.optString("status"));
            String itemText = extractText(item);
            String mergedText = !itemText.isEmpty() ? itemText : text;

            if (itemType.contains("reason")) {
                return ParsedLine.of(
                        lineNumber,
                        "thinking",
                        summary("Thinking", mergedText),
                        details,
                        null,
                        null);
            }
            if (itemType.contains("agent_message") || itemType.contains("message")) {
                return ParsedLine.of(
                        lineNumber,
                        "assistant",
                        summary("Assistant", mergedText),
                        details,
                        null,
                        null);
            }
            if (itemType.contains("command_execution")
                    || itemType.contains("mcp_tool_call")
                    || itemType.contains("tool_call")
                    || itemType.contains("tool")) {
                String toolCallId = firstNonEmpty(item, "id", "call_id", "tool_call_id");
                String toolName = firstNonEmpty(item, "tool_name", "toolName", "name");
                if (toolName.isEmpty() && itemType.contains("command_execution")) {
                    toolName = "bash";
                }
                String summaryText =
                        summary("Tool " + (status.isEmpty() ? "event" : status), mergedText);
                if (typeLower.contains("started") || status.contains("in_progress")) {
                    return ParsedLine.of(
                            lineNumber, "tool_call", summaryText, details, toolCallId, toolName);
                }
                return ParsedLine.of(
                        lineNumber, "tool_result", summaryText, details, toolCallId, toolName);
            }
        }

        // --- Cursor Agent: type=thinking with text field ---
        if (typeLower.equals("thinking")) {
            String thinkText = firstNonEmpty(json, "text");
            return ParsedLine.of(
                    lineNumber, "thinking", summary("Thinking", thinkText), details, null, null);
        }

        // --- Cursor Agent: type=tool_call with tool_call object ---
        if (typeLower.equals("tool_call")) {
            String subtype = normalize(firstNonEmpty(json, "subtype"));
            String callId = firstNonEmpty(json, "call_id");
            String toolName = extractCursorToolName(json);
            if (subtype.equals("completed")) {
                return ParsedLine.of(
                        lineNumber,
                        "tool_result",
                        summary("Tool completed: " + toolName, text),
                        details,
                        callId,
                        toolName);
            }
            return ParsedLine.of(
                    lineNumber,
                    "tool_call",
                    summary("Tool call: " + toolName, text),
                    details,
                    callId,
                    toolName);
        }

        // --- Generic fallback classification ---
        if (typeLower.contains("thinking") || typeLower.contains("reasoning")) {
            return ParsedLine.of(
                    lineNumber, "thinking", summary("Thinking", text), details, null, null);
        }
        if (isToolCall(typeLower, json)) {
            String toolCallId = firstNonEmpty(json, "tool_call_id", "call_id", "id");
            String toolName = firstNonEmpty(json, "tool_name", "toolName", "name");
            return ParsedLine.of(
                    lineNumber,
                    "tool_call",
                    summary("Tool call", text),
                    details,
                    toolCallId,
                    toolName);
        }
        if (isToolResult(typeLower, json)) {
            String toolCallId = firstNonEmpty(json, "tool_call_id", "call_id", "id");
            String toolName = firstNonEmpty(json, "tool_name", "toolName", "name");
            return ParsedLine.of(
                    lineNumber,
                    "tool_result",
                    summary("Tool result", text),
                    details,
                    toolCallId,
                    toolName);
        }
        if (roleLower.equals("assistant")
                || typeLower.contains("assistant")
                || typeLower.contains("agent_message")) {
            return ParsedLine.of(
                    lineNumber, "assistant", summary("Assistant", text), details, null, null);
        }
        if (roleLower.equals("user") || typeLower.contains("user")) {
            return ParsedLine.of(lineNumber, "user", summary("User", text), details, null, null);
        }
        if (typeLower.contains("error") || json.has("error")) {
            return ParsedLine.of(lineNumber, "error", summary("Error", text), details, null, null);
        }
        return ParsedLine.of(lineNumber, "system", summary("System", text), details, null, null);
    }

    private static ParsedLine classifyClaudeContentArray(
            long lineNumber, String parentType, JSONArray contentArr, String details) {
        // Prioritize tool_use - scan entire array first since it's most actionable
        for (int i = 0; i < contentArr.size(); i++) {
            Object obj = contentArr.get(i);
            if (!(obj instanceof JSONObject)) continue;
            JSONObject ci = (JSONObject) obj;
            String ciType = normalize(ci.optString("type"));

            if (ciType.equals("tool_use")) {
                String toolName = firstNonEmpty(ci, "name");
                String toolCallId = firstNonEmpty(ci, "id");
                String inputText = "";
                JSONObject input = ci.optJSONObject("input");
                if (input != null) {
                    inputText =
                            firstNonEmpty(
                                    input,
                                    "command",
                                    "file_path",
                                    "path",
                                    "pattern",
                                    "text",
                                    "url",
                                    "query");
                }
                return ParsedLine.of(
                        lineNumber,
                        "tool_call",
                        summary("Tool call: " + toolName, inputText),
                        details,
                        toolCallId,
                        toolName);
            }
        }
        // Then check for thinking blocks
        for (int i = 0; i < contentArr.size(); i++) {
            Object obj = contentArr.get(i);
            if (!(obj instanceof JSONObject)) continue;
            JSONObject ci = (JSONObject) obj;
            String ciType = normalize(ci.optString("type"));

            if (ciType.equals("thinking")) {
                String thinking = firstNonEmpty(ci, "thinking");
                return ParsedLine.of(
                        lineNumber, "thinking", summary("Thinking", thinking), details, null, null);
            }
        }
        // Default: extract text from all text content items
        StringBuilder textBuilder = new StringBuilder();
        for (int i = 0; i < contentArr.size(); i++) {
            Object obj = contentArr.get(i);
            if (!(obj instanceof JSONObject)) continue;
            JSONObject ci = (JSONObject) obj;
            if ("text".equals(ci.optString("type"))) {
                String t = ci.optString("text", "");
                if (!t.isEmpty()) {
                    if (textBuilder.length() > 0) textBuilder.append(' ');
                    textBuilder.append(t);
                }
            }
        }
        String cat = parentType.equals("assistant") ? "assistant" : "user";
        return ParsedLine.of(
                lineNumber,
                cat,
                summary(capitalize(cat), textBuilder.toString()),
                details,
                null,
                null);
    }

    private static ParsedLine classifyClaudeStreamEvent(
            long lineNumber, JSONObject event, String details) {
        String eventType = normalize(event.optString("type"));

        if (eventType.equals("content_block_start") || eventType.equals("content_block_delta")) {
            JSONObject contentBlock = event.optJSONObject("content_block");
            JSONObject delta = event.optJSONObject("delta");
            JSONObject source = contentBlock != null ? contentBlock : delta;
            if (source != null) {
                String blockType = normalize(source.optString("type"));
                if (blockType.contains("thinking")) {
                    String thinking = firstNonEmpty(source, "thinking", "text");
                    return ParsedLine.of(
                            lineNumber,
                            "thinking",
                            summary("Thinking", thinking),
                            details,
                            null,
                            null);
                }
                if (blockType.contains("text")) {
                    String text = firstNonEmpty(source, "text");
                    return ParsedLine.of(
                            lineNumber,
                            "assistant",
                            summary("Assistant", text),
                            details,
                            null,
                            null);
                }
            }
        }
        if (eventType.equals("message_start")) {
            JSONObject message = event.optJSONObject("message");
            if (message != null) {
                String model = firstNonEmpty(message, "model");
                if (!model.isEmpty()) {
                    return ParsedLine.of(
                            lineNumber,
                            "system",
                            summary("System", "Model: " + model),
                            details,
                            null,
                            null);
                }
            }
        }
        return ParsedLine.of(
                lineNumber, "system", summary("Stream event", eventType), details, null, null);
    }

    private static String extractCursorToolName(JSONObject json) {
        JSONObject tc = json.optJSONObject("tool_call");
        if (tc == null) return "";
        for (String key :
                new String[] {
                    "shellToolCall",
                    "readToolCall",
                    "writeToolCall",
                    "editToolCall",
                    "globToolCall",
                    "grepToolCall",
                    "lsToolCall",
                    "deleteToolCall",
                    "mcpToolCall",
                    "semSearchToolCall"
                }) {
            if (tc.has(key)) {
                return key.replace("ToolCall", "").replace("Tool", "");
            }
        }
        return firstNonEmpty(tc, "name", "tool_name");
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static boolean isToolCall(String typeLower, JSONObject json) {
        if (typeLower.contains("tool_call")) {
            return true;
        }
        if (typeLower.contains("tool") && typeLower.contains("start")) {
            return true;
        }
        if (json.has("tool_call") || json.has("tool_name") || json.has("toolName")) {
            return !isToolResult(typeLower, json);
        }
        return false;
    }

    private static boolean isToolResult(String typeLower, JSONObject json) {
        if (typeLower.contains("tool_result")) {
            return true;
        }
        if (typeLower.contains("tool")
                && (typeLower.contains("complete")
                        || typeLower.contains("result")
                        || typeLower.contains("response"))) {
            return true;
        }
        return json.has("tool_result") || json.has("tool_output");
    }

    private static String extractText(JSONObject json) {
        String text = firstNonEmpty(json, "text", "message", "content", "delta");
        if (!text.isEmpty()) {
            return text;
        }

        Object messageObj = json.opt("message");
        if (messageObj instanceof JSONObject) {
            JSONObject msg = (JSONObject) messageObj;
            text = firstNonEmpty(msg, "text", "content", "value");
            if (!text.isEmpty()) {
                return text;
            }
        }
        if (messageObj instanceof JSONArray) {
            return joinTextArray((JSONArray) messageObj);
        }

        Object contentObj = json.opt("content");
        if (contentObj instanceof JSONArray) {
            return joinTextArray((JSONArray) contentObj);
        }
        if (contentObj instanceof JSONObject) {
            text = firstNonEmpty((JSONObject) contentObj, "text", "content", "value");
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    private static String joinTextArray(JSONArray array) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < array.size(); i++) {
            Object item = array.get(i);
            String text = null;
            if (item instanceof String) {
                text = (String) item;
            } else if (item instanceof JSONObject) {
                text = firstNonEmpty((JSONObject) item, "text", "content", "value");
            }
            if (text != null && !text.isEmpty()) {
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append(text);
            }
        }
        return builder.toString().trim();
    }

    private static JSONObject tryParseJson(String line) {
        if (!(line.startsWith("{") && line.endsWith("}"))) {
            return null;
        }
        try {
            return JSONObject.fromObject(line);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String firstNonEmpty(JSONObject json, String... keys) {
        for (String key : keys) {
            if (json == null || !json.containsKey(key)) {
                continue;
            }
            Object value = json.get(key);
            if (value == null) {
                continue;
            }
            if (value instanceof String) {
                String s = ((String) value).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            } else if (!(value instanceof JSONObject) && !(value instanceof JSONArray)) {
                String s = String.valueOf(value).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return "";
    }

    private static String summary(String prefix, String text) {
        if (text == null || text.isEmpty()) {
            return prefix;
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        if (compact.length() > 180) {
            compact = compact.substring(0, 177) + "...";
        }
        return prefix + ": " + compact;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    static final class ParsedLine {
        private final long id;
        private final String category;
        private final String summary;
        private final String details;
        private final String toolCallId;
        private final String toolName;
        private final Instant timestamp;

        private ParsedLine(
                long id,
                String category,
                String summary,
                String details,
                String toolCallId,
                String toolName) {
            this.id = id;
            this.category = category;
            this.summary = summary;
            this.details = details;
            this.toolCallId = toolCallId;
            this.toolName = toolName;
            this.timestamp = Instant.now();
        }

        static ParsedLine raw(long id, String line) {
            String compact = line;
            if (compact.length() > 180) {
                compact = compact.substring(0, 177) + "...";
            }
            return new ParsedLine(id, "raw", compact, line, null, null);
        }

        static ParsedLine of(
                long id,
                String category,
                String summary,
                String details,
                String toolCallId,
                String toolName) {
            return new ParsedLine(id, category, summary, details, toolCallId, toolName);
        }

        boolean isToolCall() {
            return "tool_call".equals(category);
        }

        String getToolCallIdOrGenerated() {
            if (toolCallId != null && !toolCallId.trim().isEmpty()) {
                return toolCallId.trim();
            }
            return "tool-call-" + id;
        }

        String getToolName() {
            return toolName == null ? "" : toolName;
        }

        String getSummary() {
            return summary;
        }

        EventView toEventView() {
            return new EventView(id, category, summary, details, timestamp);
        }
    }

    public static final class EventView {
        private final long id;
        private final String category;
        private final String summary;
        private final String details;
        private final Instant timestamp;

        EventView(long id, String category, String summary, String details, Instant timestamp) {
            this.id = id;
            this.category = category;
            this.summary = summary;
            this.details = details;
            this.timestamp = timestamp;
        }

        public long getId() {
            return id;
        }

        public String getCategory() {
            return category;
        }

        public String getSummary() {
            return summary;
        }

        public String getDetails() {
            return details;
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        public String getCategoryLabel() {
            return category.replace('_', ' ').toUpperCase(Locale.ROOT);
        }

        public boolean isExpandedByDefault() {
            return "error".equals(category) || "tool_call".equals(category);
        }

        public boolean isEmpty() {
            if ("raw".equals(category)) {
                return summary == null || summary.trim().isEmpty();
            }
            return false;
        }
    }
}
