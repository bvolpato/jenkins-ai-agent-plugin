# Jenkins AI Agent Job Plugin

Native Jenkins plugin that adds a new job type: **AI Agent Job**.

The job type is designed for autonomous coding-agent runs with:

- SCM checkout + standard Jenkins scheduling/triggers
- AI agent selection and runtime controls in job config
- stream-json/raw event capture for full run traceability
- interactive run page showing conversation events, thinking, tool calls, and tool outputs
- optional approval gates that block the build until a reviewer approves/denies tool calls

## What This Plugin Adds

### New job type

- **AI Agent Job** appears in the Jenkins "New Item" UI.
- It is a native `Project`-style job, so it supports:
  - SCM configuration
  - build triggers/schedules (cron, SCM polling, etc.)
  - node/workspace controls from Jenkins core

### Agent execution model

Each build executes one selected agent command with configurable prompt/model/options.

Supported agent families:

- Claude Code
- Codex CLI
- Cursor Agent
- OpenCode
- Gemini CLI

### Conversation and event visibility

For each run, the plugin stores raw event logs in JSONL (`stream-json`/`json` style) and renders an interactive run page:

- `AI Agent Conversation` action on build page
- Event timeline grouped by category:
  - user
  - assistant
  - thinking
  - tool_call
  - tool_result
  - system/error/raw
- Expandable details for each event (pretty JSON block)
- Raw logs endpoint:
  - `<build-url>/ai-agent/raw`

### Approval gate mode

When approvals are enabled (and YOLO is disabled), tool-call events trigger a blocking approval request:

- Build pauses waiting for reviewer input.
- Reviewer can approve/deny from build page (`AI Agent Conversation`).
- Timeout is configurable per job (seconds).
- Deny or timeout fails the build (unless you disable fail-on-error).

## Job Configuration

In `AI Agent Job` configuration:

- **Agent Type**: pick one of the supported agents
- **Prompt**: task prompt sent to the agent
- **Model**: optional model override
- **Working directory**: relative path within workspace
- **Enable YOLO mode**: broad autonomous execution mode
- **Require manual approvals**: enforce tool-call gating
- **Approval timeout (seconds)**: block duration before timeout fail
- **Command override**:
  - optional full command to replace built-in command template
  - receives env vars:
    - `AI_AGENT_PROMPT`
    - `AI_AGENT_MODEL`
    - `AI_AGENT_JOB`
    - `AI_AGENT_BUILD_NUMBER`
- **Extra CLI args**: appended to generated command
- **Environment variables**: multiline `KEY=VALUE`
- **Fail build on non-zero exit**: strict/lenient mode

## Built-in Agent Command Strategy

Default command templates are wired for non-interactive automation:

- Claude Code: stream-json flags
- Codex: `codex exec --json`
- Cursor Agent: stream-json output
- OpenCode: `opencode run --format json`
- Gemini CLI: `--output-format stream-json`

If your environment uses different binary paths/flags, set **Command override**.

## Build and Test

From repository root:

```bash
mvn -B test
mvn -B package -DskipTests
```

Generated plugin file:

- `target/jenkins-ai-agent-plugin.hpi`

## Install in Jenkins

1. Open `Manage Jenkins` -> `Plugins` -> `Advanced`.
2. In **Deploy Plugin**, upload:
   - `target/jenkins-ai-agent-plugin.hpi`
3. Restart Jenkins.
4. Create a new item of type **AI Agent Job**.

## Notes and Scope

- The plugin captures and renders structured run events for traceability and review.
- Approval gating is implemented at the Jenkins-runner layer (blocking on detected tool-call events).
- Agent CLI specifics evolve quickly; use **Command override** for provider-specific tuning.
