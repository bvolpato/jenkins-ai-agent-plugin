package io.jenkins.plugins.aiagentjob;

import hudson.model.FreeStyleBuild;

import java.io.File;
import java.io.IOException;

/** Build type for {@link AiAgentProject}, used to bind the correct project class at load time. */
public class AiAgentBuild extends FreeStyleBuild {
    public AiAgentBuild(AiAgentProject project) throws IOException {
        super(project);
    }

    public AiAgentBuild(AiAgentProject project, File buildDir) throws IOException {
        super(project, buildDir);
    }
}
