package io.jenkins.plugins.aiagentjob;

import hudson.Util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the CLI command line for each supported {@link AgentType}. Also handles environment
 * variable parsing and command-to-string serialisation.
 */
final class AiAgentCommandFactory {
    private AiAgentCommandFactory() {}

    static List<String> buildDefaultCommand(AiAgentProject project, String prompt) {
        List<String> command = new ArrayList<>();
        String model = trimToNull(project.getModel());

        switch (project.getAgentType()) {
            case CLAUDE_CODE:
                command.add("npx");
                command.add("-y");
                command.add("@anthropic-ai/claude-code");
                command.add("-p");
                command.add(prompt);
                command.add("--output-format=stream-json");
                command.add("--verbose");
                if (project.isYoloMode()) {
                    command.add("--dangerously-skip-permissions");
                } else if (project.isRequireApprovals()) {
                    command.add("--permission-mode=default");
                }
                if (model != null) {
                    command.add("--model");
                    command.add(model);
                }
                break;

            case CODEX:
                command.add("codex");
                command.add("exec");
                command.add("--json");
                if (project.isYoloMode()) {
                    command.add("--dangerously-bypass-approvals-and-sandbox");
                } else {
                    command.add("--sandbox");
                    command.add("workspace-write");
                    if (project.isRequireApprovals()) {
                        command.add("--ask-for-approval");
                        command.add("on-request");
                    } else {
                        command.add("--ask-for-approval");
                        command.add("never");
                    }
                }
                if (model != null) {
                    command.add("--model");
                    command.add(model);
                }
                command.add(prompt);
                break;

            case CURSOR_AGENT:
                command.add("cursor-agent");
                command.add("-p");
                command.add("--output-format=stream-json");
                command.add("--trust");
                if (project.isYoloMode()) {
                    command.add("--yolo");
                }
                if (model != null) {
                    command.add("--model");
                    command.add(model);
                }
                command.add(prompt);
                break;

            case OPENCODE:
                command.add("opencode");
                command.add("run");
                command.add("--format");
                command.add("json");
                if (model != null) {
                    command.add("--model");
                    command.add(model);
                }
                command.add(prompt);
                break;

            case GEMINI_CLI:
                command.add("gemini");
                command.add("-p");
                command.add(prompt);
                command.add("--output-format");
                command.add("stream-json");
                if (project.isYoloMode()) {
                    command.add("--yolo");
                } else if (project.isRequireApprovals()) {
                    command.add("--approval-mode");
                    command.add("default");
                }
                if (model != null) {
                    command.add("-m");
                    command.add(model);
                }
                break;

            default:
                throw new IllegalStateException(
                        "Unsupported agent type: " + project.getAgentType());
        }

        String extraArgs = trimToNull(project.getExtraArgs());
        if (extraArgs != null) {
            Collections.addAll(command, Util.tokenize(extraArgs));
        }
        return command;
    }

    static Map<String, String> parseEnvironmentVariables(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null || raw.trim().isEmpty()) {
            return values;
        }

        String[] lines = raw.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int idx = trimmed.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = trimmed.substring(0, idx).trim();
            String value = trimmed.substring(idx + 1);
            if (!key.isEmpty()) {
                values.put(key, value);
            }
        }
        return values;
    }

    static String commandAsString(List<String> command) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < command.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            String token = command.get(i);
            if (token.contains(" ") || token.contains("\"")) {
                sb.append('"').append(token.replace("\"", "\\\"")).append('"');
            } else {
                sb.append(token);
            }
        }
        return sb.toString();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
