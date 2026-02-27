# Jenkins AI Agent Job Plugin

[![CI](https://github.com/brunocvcunha/jenkins-ai-agent-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/brunocvcunha/jenkins-ai-agent-plugin/actions/workflows/ci.yml)

A Jenkins plugin that adds a native **AI Agent Job** type for running autonomous coding agents as first-class Jenkins builds.

## Features

- **Native job type** — appears in the Jenkins "New Item" dialog alongside Freestyle and Pipeline jobs.
- **Multiple agent support** — Claude Code, Codex CLI, Cursor Agent, OpenCode, and Gemini CLI.
- **Inline conversation view** — live-streaming conversation on the build page with structured display of messages, tool calls, and outputs.
- **Approval gates** — optionally pause builds for human review before tool execution.
- **Usage statistics** — token counts, cost, and duration extracted from agent logs and displayed per build.
- **Standard Jenkins integrations** — SCM checkout, build triggers, credentials injection, post-build steps, and publishers.

## Supported Agents

| Agent | Output Format | Cost Tracking |
|-------|--------------|---------------|
| Claude Code | stream-json | Yes |
| Codex CLI | JSON | Tokens only |
| Cursor Agent | stream-json | Tokens only |
| OpenCode | JSON | Yes |
| Gemini CLI | stream-json | Tokens only |

## Installation

1. Build the plugin (see [Building](#building)) or download a release `.hpi`.
2. Go to **Manage Jenkins > Plugins > Advanced settings**.
3. Upload the `.hpi` file under **Deploy Plugin**.
4. Restart Jenkins.

## Usage

1. Click **New Item**, enter a name, and select **AI Agent Job**.
2. Configure:
   - **Agent Type** — select the coding agent to run.
   - **Prompt** — the task to send to the agent.
   - **Model** — optional model override (e.g., `claude-sonnet-4`).
   - **YOLO mode** — skip confirmation prompts in the agent.
   - **Approvals** — require human approval for tool calls.
   - **Environment variables** — inject additional env vars (`KEY=VALUE`, one per line).
   - **Command override** — replace the default command template entirely.
   - **Extra CLI args** — append flags to the generated command.
3. Optionally add SCM, build triggers, post-build steps, and publishers as with any Jenkins job.
4. Build the job. The conversation streams live on the build page.

### Environment Variables

The plugin injects these variables into every build:

| Variable | Description |
|----------|-------------|
| `AI_AGENT_PROMPT` | The configured prompt text |
| `AI_AGENT_MODEL` | The configured model name |
| `AI_AGENT_JOB` | The Jenkins job name |
| `AI_AGENT_BUILD_NUMBER` | The build number |

### Credential Injection

If the selected agent type has an associated credential ID (e.g., API key), the plugin resolves it from Jenkins credentials and injects it as an environment variable. The credential is masked in the build log.

### Approval Gates

When approvals are enabled and YOLO mode is off, tool calls detected in the agent's output trigger a blocking approval request. The build pauses until a user approves or denies from the build page. Denied or timed-out requests fail the build.

### Usage Statistics

After a build completes, a statistics bar shows token usage, cost (when available), and duration. Data is extracted from the agent's own reporting in the JSONL log. The level of detail depends on the agent — Claude Code reports full cost, while others report only token counts.

## Building

Requires Java 17+ and Maven 3.9+.

```bash
# Run tests
mvn clean verify

# Package without tests
mvn clean package -DskipTests
```

The plugin artifact is generated at `target/jenkins-ai-agent-plugin.hpi`.

## Development

```bash
# Format code (Google Java Format, AOSP style)
mvn com.spotify.fmt:fmt-maven-plugin:format

# Run with a local Jenkins instance
mvn hpi:run
```

The project uses:
- [Google Java Format](https://github.com/google/google-java-format) (AOSP variant) via `fmt-maven-plugin`
- [JaCoCo](https://www.jacoco.org/) for test coverage
- [SpotBugs](https://spotbugs.github.io/) for static analysis
- [Jenkins Test Harness](https://github.com/jenkinsci/jenkins-test-harness) for integration tests

See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution guidelines.

## Architecture

```
src/main/java/io/jenkins/plugins/aiagentjob/
├── AiAgentProject.java        # Job type (extends Project)
├── AiAgentBuild.java          # Build execution logic
├── AiAgentRunAction.java       # Per-build action: conversation UI, streaming, approvals
├── AiAgentLogParser.java       # JSONL log parser for all agent formats
├── AgentUsageStats.java        # Token/cost/duration stats normalization
├── AgentType.java              # Enum of supported agents with command templates
├── ExecutionRegistry.java      # In-memory registry for live execution state
└── AiAgentCredentialInjection.java  # Credential resolution and env injection
```

## License

MIT License. See [pom.xml](pom.xml) for details.
