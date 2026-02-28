package io.jenkins.plugins.aiagentjob;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;

import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.security.ACL;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import jakarta.servlet.ServletException;

import jenkins.model.Jenkins;

import net.sf.json.JSONObject;

import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

import java.io.IOException;
import java.util.Collections;

/**
 * Top-level Jenkins job type for AI agent runs. Extends {@link FreeStyleProject} with
 * agent-specific configuration (agent type, prompt, model, approval settings, API credentials).
 */
public class AiAgentProject extends FreeStyleProject {
    private AgentType agentType = AgentType.CLAUDE_CODE;
    private String model = "";
    private String prompt = "";
    private String workingDirectory = "";
    private boolean yoloMode;
    private boolean requireApprovals;
    private int approvalTimeoutSeconds = 600;
    private String commandOverride = "";
    private String extraArgs = "";
    private String environmentVariables = "";
    private boolean failOnAgentError = true;
    private String setupScript = "";
    private String apiCredentialsId = "";
    private String apiKeyEnvVar = "";

    public AiAgentProject(ItemGroup parent, String name) {
        super(parent, name);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    protected Class<FreeStyleBuild> getBuildClass() {
        return (Class) AiAgentBuild.class;
    }

    @Override
    public void onCreatedFromScratch() {
        super.onCreatedFromScratch();
        ensureRunnerBuilder();
    }

    @Override
    public void onLoad(ItemGroup<? extends hudson.model.Item> parent, String name)
            throws IOException {
        super.onLoad(parent, name);
        ensureRunnerBuilder();
    }

    @Override
    protected void submit(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException, ServletException, Descriptor.FormException {
        super.submit(req, rsp);
        JSONObject json = req.getSubmittedForm();

        this.agentType = AgentType.fromString(json.optString("agentType", agentType.name()));
        this.model = Util.fixNull(json.optString("model", ""));
        this.prompt = Util.fixNull(json.optString("prompt", ""));
        this.workingDirectory = Util.fixNull(json.optString("workingDirectory", ""));
        this.yoloMode = json.optBoolean("yoloMode", false);
        this.requireApprovals = json.optBoolean("requireApprovals", false);
        if (this.yoloMode) {
            this.requireApprovals = false;
        }
        this.approvalTimeoutSeconds = Math.max(1, json.optInt("approvalTimeoutSeconds", 600));
        this.commandOverride = Util.fixNull(json.optString("commandOverride", ""));
        this.extraArgs = Util.fixNull(json.optString("extraArgs", ""));
        this.environmentVariables = Util.fixNull(json.optString("environmentVariables", ""));
        this.setupScript = Util.fixNull(json.optString("setupScript", ""));
        this.failOnAgentError = json.optBoolean("failOnAgentError", true);
        this.apiCredentialsId = Util.fixNull(json.optString("apiCredentialsId", ""));
        this.apiKeyEnvVar = Util.fixNull(json.optString("apiKeyEnvVar", ""));

        ensureRunnerBuilder();
        save();
    }

    public AgentType getAgentType() {
        return agentType;
    }

    public void setAgentType(AgentType agentType) {
        this.agentType = agentType == null ? AgentType.CLAUDE_CODE : agentType;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = Util.fixNull(model);
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = Util.fixNull(prompt);
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = Util.fixNull(workingDirectory);
    }

    public boolean isYoloMode() {
        return yoloMode;
    }

    public void setYoloMode(boolean yoloMode) {
        this.yoloMode = yoloMode;
    }

    public boolean isRequireApprovals() {
        return requireApprovals;
    }

    public void setRequireApprovals(boolean requireApprovals) {
        this.requireApprovals = requireApprovals;
    }

    public int getApprovalTimeoutSeconds() {
        return approvalTimeoutSeconds;
    }

    public void setApprovalTimeoutSeconds(int approvalTimeoutSeconds) {
        this.approvalTimeoutSeconds = Math.max(1, approvalTimeoutSeconds);
    }

    public String getCommandOverride() {
        return commandOverride;
    }

    public void setCommandOverride(String commandOverride) {
        this.commandOverride = Util.fixNull(commandOverride);
    }

    public String getExtraArgs() {
        return extraArgs;
    }

    public void setExtraArgs(String extraArgs) {
        this.extraArgs = Util.fixNull(extraArgs);
    }

    public String getEnvironmentVariables() {
        return environmentVariables;
    }

    public void setEnvironmentVariables(String environmentVariables) {
        this.environmentVariables = Util.fixNull(environmentVariables);
    }

    /**
     * Shell script executed before the agent process to prepare the build environment (install
     * dependencies, source dotfiles, configure PATH, etc.).
     */
    public String getSetupScript() {
        return setupScript;
    }

    public void setSetupScript(String setupScript) {
        this.setupScript = Util.fixNull(setupScript);
    }

    public boolean isFailOnAgentError() {
        return failOnAgentError;
    }

    public void setFailOnAgentError(boolean failOnAgentError) {
        this.failOnAgentError = failOnAgentError;
    }

    public String getApiCredentialsId() {
        return apiCredentialsId;
    }

    public void setApiCredentialsId(String apiCredentialsId) {
        this.apiCredentialsId = Util.fixNull(apiCredentialsId);
    }

    /**
     * The environment variable name to inject the API key secret into. If empty, defaults to the
     * agent type's standard env var (e.g. ANTHROPIC_API_KEY for Claude Code).
     */
    public String getApiKeyEnvVar() {
        return apiKeyEnvVar;
    }

    public void setApiKeyEnvVar(String apiKeyEnvVar) {
        this.apiKeyEnvVar = Util.fixNull(apiKeyEnvVar);
    }

    /** Returns the effective env var name to use when injecting the API key. */
    public String getEffectiveApiKeyEnvVar() {
        String custom = Util.fixEmptyAndTrim(apiKeyEnvVar);
        if (custom != null) {
            return custom;
        }
        return agentType.getDefaultApiKeyEnvVar();
    }

    private Object readResolve() {
        if (agentType == null) {
            agentType = AgentType.CLAUDE_CODE;
        }
        if (approvalTimeoutSeconds <= 0) {
            approvalTimeoutSeconds = 600;
        }
        if (model == null) {
            model = "";
        }
        if (prompt == null) {
            prompt = "";
        }
        if (workingDirectory == null) {
            workingDirectory = "";
        }
        if (commandOverride == null) {
            commandOverride = "";
        }
        if (extraArgs == null) {
            extraArgs = "";
        }
        if (environmentVariables == null) {
            environmentVariables = "";
        }
        if (setupScript == null) {
            setupScript = "";
        }
        if (apiCredentialsId == null) {
            apiCredentialsId = "";
        }
        if (apiKeyEnvVar == null) {
            apiKeyEnvVar = "";
        }
        return this;
    }

    private void ensureRunnerBuilder() {
        for (Builder builder : getBuildersList()) {
            if (builder instanceof AiAgentBuilder) {
                return;
            }
        }
        getBuildersList().add(new AiAgentBuilder());
    }

    @Extension
    @Symbol("aiAgentJob")
    public static final class DescriptorImpl extends FreeStyleProject.DescriptorImpl {
        @Override
        public String getDisplayName() {
            return "AI Agent Job";
        }

        @Override
        public AiAgentProject newInstance(ItemGroup parent, String name) {
            return new AiAgentProject(parent, name);
        }

        @Override
        public String getCategoryId() {
            return "build";
        }

        public ListBoxModel doFillAgentTypeItems() {
            ListBoxModel model = new ListBoxModel();
            for (AgentType type : AgentType.values()) {
                model.add(type.getDisplayName(), type.name());
            }
            return model;
        }

        public ListBoxModel doFillApiCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String apiCredentialsId) {
            StandardListBoxModel result = new StandardListBoxModel();
            if (item == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return result.includeCurrentValue(apiCredentialsId);
                }
            } else {
                if (!item.hasPermission(Item.EXTENDED_READ)
                        && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                    return result.includeCurrentValue(apiCredentialsId);
                }
            }
            return result.includeEmptyValue()
                    .includeMatchingAs(
                            item instanceof hudson.model.Queue.Task
                                    ? ((hudson.model.Queue.Task) item).getDefaultAuthentication2()
                                    : ACL.SYSTEM2,
                            item,
                            StringCredentials.class,
                            Collections.<DomainRequirement>emptyList(),
                            CredentialsMatchers.always())
                    .includeCurrentValue(apiCredentialsId);
        }

        public FormValidation doCheckApprovalTimeoutSeconds(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Timeout is required.");
            }
            try {
                int parsed = Integer.parseInt(value.trim());
                if (parsed < 1) {
                    return FormValidation.error("Timeout must be at least 1 second.");
                }
                if (parsed > 86400) {
                    return FormValidation.warning(
                            "Large timeout values will block executors for a long time.");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Timeout must be a number.");
            }
        }
    }
}
