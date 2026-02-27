package io.jenkins.plugins.aiagentjob;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.Result;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.File;

public class AiAgentBuildExecutionTest {
    @Rule public JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void runsAgentCommandAndCapturesConversation() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        AiAgentProject project = jenkins.createProject(AiAgentProject.class, "ai-build-success");
        project.setAgentType(AgentType.CLAUDE_CODE);
        project.setPrompt("hello");
        project.setCommandOverride(
                "echo '{\"type\":\"assistant\",\"message\":\"hello from test\"}'");
        project.setFailOnAgentError(true);
        project.save();

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        assertFalse(action.getEvents().isEmpty());
        assertTrue(action.getRawLogFile().exists());
    }

    @Test
    public void failsWhenApprovalTimesOut() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        AiAgentProject project =
                jenkins.createProject(AiAgentProject.class, "ai-build-approval-timeout");
        project.setAgentType(AgentType.CLAUDE_CODE);
        project.setPrompt("needs approval");
        project.setRequireApprovals(true);
        project.setApprovalTimeoutSeconds(1);
        project.setFailOnAgentError(true);
        project.setCommandOverride(
                "echo '{\"type\":\"tool_call\",\"tool_name\":\"bash\",\"tool_call_id\":\"call-1\",\"text\":\"ls\"}'; "
                        + "sleep 2; "
                        + "echo '{\"type\":\"assistant\",\"message\":\"done\"}'");
        project.save();

        FreeStyleBuild build = project.scheduleBuild2(0).get();
        jenkins.assertBuildStatus(Result.FAILURE, build);

        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        assertTrue(action.getEvents().stream().anyMatch(e -> "tool_call".equals(e.getCategory())));
    }
}
