package io.jenkins.plugins.aiagentjob;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;

import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;

/**
 * Build step that launches the configured AI agent process and captures its output. Automatically
 * added to every {@link AiAgentProject}.
 */
public class AiAgentBuilder extends Builder {
    @DataBoundConstructor
    public AiAgentBuilder() {}

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException {
        if (!(build.getProject() instanceof AiAgentProject project)) {
            listener.getLogger().println("[ai-agent] Project is not an AI Agent Job; skipping.");
            return true;
        }

        AiAgentRunAction action = AiAgentRunAction.getOrCreate(build);
        int exitCode = AiAgentExecutor.execute(build, launcher, listener, project, action);

        if (exitCode != 0 && project.isFailOnAgentError()) {
            listener.getLogger()
                    .println("[ai-agent] Agent finished with non-zero exit code: " + exitCode);
            return false;
        }
        if (exitCode != 0) {
            listener.getLogger()
                    .println(
                            "[ai-agent] Agent exited with code "
                                    + exitCode
                                    + ", but failure is disabled.");
        }
        return true;
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return AiAgentProject.class.isAssignableFrom(jobType);
        }

        @Override
        public String getDisplayName() {
            return "Run AI Agent";
        }
    }
}
