package io.jenkins.plugins.aiagentjob;

import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Run;
import hudson.util.HttpResponses;

import jenkins.model.RunAction2;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Per-build action that stores agent execution metadata and provides the inline conversation view
 * on the build page. Also exposes Stapler endpoints for progressive event streaming, approval
 * handling, and raw log access.
 */
public class AiAgentRunAction implements Action, RunAction2 {
    private static final String RAW_LOG_FILE = "ai-agent-stream.jsonl";

    private transient Run<?, ?> run;
    private String agentType = "";
    private String model = "";
    private String commandLine = "";
    private boolean yoloMode;
    private boolean approvalsEnabled;
    private long startedAtMillis;
    private long completedAtMillis;
    private Integer exitCode;

    public static AiAgentRunAction getOrCreate(Run<?, ?> run) {
        AiAgentRunAction existing = run.getAction(AiAgentRunAction.class);
        if (existing != null) {
            return existing;
        }
        AiAgentRunAction created = new AiAgentRunAction();
        run.addAction(created);
        created.onAttached(run);
        return created;
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return "AI Agent Conversation";
    }

    @Override
    public String getUrlName() {
        return "ai-agent";
    }

    @Override
    public void onAttached(Run<?, ?> run) {
        this.run = run;
    }

    @Override
    public void onLoad(Run<?, ?> run) {
        this.run = run;
    }

    public synchronized void markStarted(
            AgentType agentType,
            String model,
            String commandLine,
            boolean yoloMode,
            boolean approvalsEnabled)
            throws IOException {
        this.agentType = agentType.getDisplayName();
        this.model = model == null ? "" : model;
        this.commandLine = commandLine == null ? "" : commandLine;
        this.yoloMode = yoloMode;
        this.approvalsEnabled = approvalsEnabled;
        this.startedAtMillis = System.currentTimeMillis();
        this.completedAtMillis = 0L;
        this.exitCode = null;
        run.save();
    }

    public synchronized void markCompleted(int exitCode) throws IOException {
        this.exitCode = exitCode;
        this.completedAtMillis = System.currentTimeMillis();
        run.save();
    }

    public Run<?, ?> getRun() {
        return run;
    }

    public String getAgentType() {
        return agentType;
    }

    public String getModel() {
        return model;
    }

    public String getCommandLine() {
        return commandLine;
    }

    public boolean isYoloMode() {
        return yoloMode;
    }

    public boolean isApprovalsEnabled() {
        return approvalsEnabled;
    }

    public String getStartedAt() {
        if (startedAtMillis <= 0L) {
            return "";
        }
        return new Date(startedAtMillis).toString();
    }

    public synchronized String getCompletedAt() {
        if (completedAtMillis <= 0L) {
            return null;
        }
        return new Date(completedAtMillis).toString();
    }

    public synchronized Integer getExitCode() {
        return exitCode;
    }

    public boolean isLive() {
        return run != null && run.isBuilding();
    }

    public List<ExecutionRegistry.PendingApproval> getPendingApprovals() {
        if (run == null) {
            return Collections.emptyList();
        }
        ExecutionRegistry.LiveExecution liveExecution = ExecutionRegistry.get(run);
        if (liveExecution == null) {
            return Collections.emptyList();
        }
        return liveExecution.getPendingApprovals();
    }

    public List<AiAgentLogParser.EventView> getEvents() {
        try {
            return AiAgentLogParser.parse(getRawLogFile());
        } catch (IOException e) {
            return Collections.singletonList(
                    new AiAgentLogParser.EventView(
                            -1,
                            "error",
                            "Error",
                            "Failed to read raw agent logs: " + e.toString(),
                            "",
                            "",
                            "",
                            java.time.Instant.now()));
        }
    }

    /** Returns aggregated token usage and cost stats from the JSONL log. */
    public AgentUsageStats getUsageStats() {
        try {
            return AgentUsageStats.fromLogFile(getRawLogFile());
        } catch (IOException e) {
            return new AgentUsageStats();
        }
    }

    public File getRawLogFile() {
        return new File(run.getRootDir(), RAW_LOG_FILE);
    }

    @RequirePOST
    public Object doApprove(@QueryParameter String id) {
        checkBuildPermission();
        if (id == null || id.trim().isEmpty()) {
            return HttpResponses.errorWithoutStack(400, "Missing approval id");
        }
        ExecutionRegistry.LiveExecution liveExecution = ExecutionRegistry.get(run);
        if (liveExecution == null || !liveExecution.approve(id)) {
            return HttpResponses.errorWithoutStack(404, "Approval request not found");
        }
        return HttpResponses.redirectToDot();
    }

    @RequirePOST
    public Object doDeny(@QueryParameter String id, @QueryParameter String reason) {
        checkBuildPermission();
        if (id == null || id.trim().isEmpty()) {
            return HttpResponses.errorWithoutStack(400, "Missing approval id");
        }
        ExecutionRegistry.LiveExecution liveExecution = ExecutionRegistry.get(run);
        if (liveExecution == null || !liveExecution.deny(id, reason)) {
            return HttpResponses.errorWithoutStack(404, "Approval request not found");
        }
        return HttpResponses.redirectToDot();
    }

    public void doProgressiveEvents(StaplerRequest2 request, StaplerResponse2 response)
            throws IOException {
        checkReadPermission();
        long startLine = 0;
        String startParam = request.getParameter("start");
        if (startParam != null) {
            try {
                startLine = Long.parseLong(startParam);
            } catch (NumberFormatException ignored) {
            }
        }

        File raw = getRawLogFile();
        List<AiAgentLogParser.EventView> newEvents = new ArrayList<>();
        long lineCount = 0;

        if (raw.exists()) {
            try (BufferedReader reader =
                    Files.newBufferedReader(raw.toPath(), StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lineCount++;
                    if (lineCount <= startLine) {
                        continue;
                    }
                    AiAgentLogParser.EventView ev =
                            AiAgentLogParser.parseLine(lineCount, line).toEventView();
                    if (!ev.isEmpty()) {
                        newEvents.add(ev);
                    }
                }
            }
        }

        JSONArray eventsJson = new JSONArray();
        for (AiAgentLogParser.EventView ev : newEvents) {
            JSONObject obj = new JSONObject();
            obj.put("id", ev.getId());
            obj.put("category", ev.getCategory());
            obj.put("categoryLabel", ev.getCategoryLabel());
            obj.put("label", ev.getLabel());
            obj.put("content", ev.getContent());
            obj.put("toolInput", ev.getToolInput());
            obj.put("toolOutput", ev.getToolOutput());
            obj.put("summary", ev.getSummary());
            eventsJson.add(obj);
        }

        JSONObject result = new JSONObject();
        result.put("events", eventsJson);
        result.put("nextStart", lineCount);
        result.put("live", isLive());
        result.put("exitCode", getExitCode());

        JSONArray approvalsJson = new JSONArray();
        for (ExecutionRegistry.PendingApproval pa : getPendingApprovals()) {
            JSONObject paObj = new JSONObject();
            paObj.put("id", pa.getId());
            paObj.put("toolName", pa.getToolName());
            paObj.put("toolCallId", pa.getToolCallId());
            paObj.put("inputSummary", pa.getInputSummary());
            approvalsJson.add(paObj);
        }
        result.put("pendingApprovals", approvalsJson);

        if (!isLive()) {
            AgentUsageStats stats = getUsageStats();
            if (stats.hasData()) {
                JSONObject statsJson = new JSONObject();
                statsJson.put("inputTokens", stats.getInputTokens());
                statsJson.put("outputTokens", stats.getOutputTokens());
                statsJson.put("cacheReadTokens", stats.getCacheReadTokens());
                statsJson.put("cacheWriteTokens", stats.getCacheWriteTokens());
                statsJson.put("totalTokens", stats.getTotalTokens());
                statsJson.put("reasoningTokens", stats.getReasoningTokens());
                statsJson.put("costDisplay", stats.getCostDisplay());
                statsJson.put("durationDisplay", stats.getDurationDisplay());
                statsJson.put("numTurns", stats.getNumTurns());
                statsJson.put("toolCalls", stats.getToolCalls());
                result.put("usageStats", statsJson);
            }
        }

        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(result.toString());
    }

    public void doRaw(StaplerRequest2 request, StaplerResponse2 response) throws IOException {
        checkReadPermission();
        File raw = getRawLogFile();
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/plain;charset=UTF-8");
        if (!raw.exists()) {
            response.getWriter().println("No raw log file has been captured yet.");
            return;
        }
        Files.copy(raw.toPath(), response.getOutputStream());
    }

    private void checkReadPermission() {
        run.getParent().checkPermission(Item.READ);
    }

    private void checkBuildPermission() {
        run.getParent().checkPermission(Item.BUILD);
    }
}
