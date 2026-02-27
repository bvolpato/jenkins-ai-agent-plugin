package io.jenkins.plugins.aiagentjob;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;

import hudson.model.FreeStyleBuild;
import hudson.util.Secret;

import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class AiAgentCredentialInjectionTest {
    @Rule public JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void injectsApiKeyFromCredential() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        // Store a secret text credential
        StringCredentialsImpl cred =
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "test-api-key",
                        "Test API key",
                        Secret.fromString("sk-test-secret-12345"));
        CredentialsProvider.lookupStores(jenkins.getInstance())
                .iterator()
                .next()
                .addCredentials(Domain.global(), cred);

        // Create project that echoes back the env var to prove injection
        AiAgentProject project =
                jenkins.createProject(AiAgentProject.class, "credential-inject-test");
        project.setAgentType(AgentType.CLAUDE_CODE);
        project.setPrompt("test");
        project.setApiCredentialsId("test-api-key");
        // Command override that outputs the env var value as JSON so it appears in the
        // raw log
        project.setCommandOverride(
                "echo \"{\\\"type\\\":\\\"system\\\",\\\"key_set\\\":\\\"$(test -n \\\"$ANTHROPIC_API_KEY\\\" && echo yes || echo no)\\\"}\"");
        project.setFailOnAgentError(true);
        project.save();

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);

        // The raw log should show key_set=yes because we injected the credential
        String rawLog = Files.readString(action.getRawLogFile().toPath(), StandardCharsets.UTF_8);
        assertTrue("Env var should have been set", rawLog.contains("\"key_set\":\"yes\""));

        // Build log should mention the injection
        String buildLog = build.getLog();
        assertTrue(
                "Build log should mention API key injection",
                buildLog.contains("API key injected as ANTHROPIC_API_KEY"));
    }

    @Test
    public void usesCustomEnvVarOverride() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        StringCredentialsImpl cred =
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "custom-key",
                        "Custom key",
                        Secret.fromString("my-custom-secret"));
        CredentialsProvider.lookupStores(jenkins.getInstance())
                .iterator()
                .next()
                .addCredentials(Domain.global(), cred);

        AiAgentProject project = jenkins.createProject(AiAgentProject.class, "custom-envvar-test");
        project.setAgentType(AgentType.OPENCODE);
        project.setPrompt("test");
        project.setApiCredentialsId("custom-key");
        project.setApiKeyEnvVar("ANTHROPIC_API_KEY");
        project.setCommandOverride(
                "echo \"{\\\"type\\\":\\\"system\\\",\\\"key_set\\\":\\\"$(test -n \\\"$ANTHROPIC_API_KEY\\\" && echo yes || echo no)\\\"}\"");
        project.setFailOnAgentError(true);
        project.save();

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        String buildLog = build.getLog();
        assertTrue(
                "Should inject as ANTHROPIC_API_KEY not OPENAI_API_KEY",
                buildLog.contains("API key injected as ANTHROPIC_API_KEY"));
    }

    @Test
    public void warnsWhenCredentialNotFound() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        AiAgentProject project = jenkins.createProject(AiAgentProject.class, "missing-cred-test");
        project.setAgentType(AgentType.GEMINI_CLI);
        project.setPrompt("test");
        project.setApiCredentialsId("nonexistent-credential-id");
        project.setCommandOverride("echo '{\"type\":\"system\"}'");
        project.setFailOnAgentError(true);
        project.save();

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        String buildLog = build.getLog();
        assertTrue(
                "Build log should warn about missing credential",
                buildLog.contains("WARNING") && buildLog.contains("not found"));
    }

    @Test
    public void noCredentialNoInjection() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        AiAgentProject project = jenkins.createProject(AiAgentProject.class, "no-cred-test");
        project.setAgentType(AgentType.CLAUDE_CODE);
        project.setPrompt("test");
        // No credential configured
        project.setCommandOverride("echo '{\"type\":\"system\"}'");
        project.setFailOnAgentError(true);
        project.save();

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        String buildLog = build.getLog();
        assertFalse("Should not mention API key injection", buildLog.contains("API key injected"));
    }

    @Test
    public void effectiveApiKeyEnvVar_defaultsToAgentType() {
        AiAgentProject project =
                new AiAgentProject(null, "test") {
                    @Override
                    public void save() {}
                };
        project.setAgentType(AgentType.CLAUDE_CODE);
        project.setApiKeyEnvVar("");
        assertEquals("ANTHROPIC_API_KEY", project.getEffectiveApiKeyEnvVar());

        project.setAgentType(AgentType.GEMINI_CLI);
        assertEquals("GEMINI_API_KEY", project.getEffectiveApiKeyEnvVar());
    }

    @Test
    public void effectiveApiKeyEnvVar_respectsCustomOverride() {
        AiAgentProject project =
                new AiAgentProject(null, "test") {
                    @Override
                    public void save() {}
                };
        project.setAgentType(AgentType.OPENCODE);
        project.setApiKeyEnvVar("ANTHROPIC_API_KEY");
        assertEquals("ANTHROPIC_API_KEY", project.getEffectiveApiKeyEnvVar());
    }
}
