package io.jenkins.plugins.aiagentjob;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class AiAgentProjectConfigRoundTripTest {
    @Rule public JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void preservesConfiguredFields() throws Exception {
        AiAgentProject project = jenkins.createProject(AiAgentProject.class, "ai-config-roundtrip");
        project.setAgentType(AgentType.GEMINI_CLI);
        project.setPrompt("Summarize this repository.");
        project.setModel("gemini-2.5-pro");
        project.setWorkingDirectory("src");
        project.setYoloMode(false);
        project.setRequireApprovals(true);
        project.setApprovalTimeoutSeconds(42);
        project.setCommandOverride("echo '{\"type\":\"assistant\",\"message\":\"hi\"}'");
        project.setExtraArgs("--foo bar");
        project.setEnvironmentVariables("FOO=bar\nHELLO=world");
        project.setSetupScript("export PATH=$HOME/.local/bin:$PATH\nnpm install");
        project.setFailOnAgentError(false);
        project.save();

        jenkins.configRoundtrip(project);

        assertEquals(AgentType.GEMINI_CLI, project.getAgentType());
        assertEquals("Summarize this repository.", project.getPrompt());
        assertEquals("gemini-2.5-pro", project.getModel());
        assertEquals("src", project.getWorkingDirectory());
        assertFalse(project.isYoloMode());
        assertTrue(project.isRequireApprovals());
        assertEquals(42, project.getApprovalTimeoutSeconds());
        assertEquals(
                "echo '{\"type\":\"assistant\",\"message\":\"hi\"}'", project.getCommandOverride());
        assertEquals("--foo bar", project.getExtraArgs());
        assertEquals("FOO=bar\nHELLO=world", project.getEnvironmentVariables());
        assertEquals("export PATH=$HOME/.local/bin:$PATH\nnpm install", project.getSetupScript());
        assertFalse(project.isFailOnAgentError());
    }
}
