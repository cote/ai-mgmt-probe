# MCP Trace

Instructional skill for turning MCP call captures into a browsable trace viewer. No scripts to run - the skill is a set of instructions that an AI assistant (Claude Code, or similar) follows to assemble the artifacts directly from captured data.

Shipped alongside the [ai-mgmt-probe](https://github.com/cote/ai-mgmt-probe) MCP server as an example, but works against any MCP server behind a Tanzu MCP Gateway.

## How it works

Three phases:

1. **Capture.** The AI (or operator) makes MCP tool calls against the target server, notes the timing, and pulls matching audit records from the gateway's audit store (Tanzu Hub for the Tanzu MCP Gateway).
2. **Assemble.** For each call, the AI builds a nested JSON tree following the shape in `references/CALL_TREE_SCHEMA.md`. Writes per-call files under `calls/`, plus `trace.jsonl` and `trace.json`.
3. **Render.** The AI takes `references/VIEWER_TEMPLATE.html`, injects the trace data into its `#trace-data` script block, and saves as `sequence.html`. Open in a browser to walk the calls.

All data lives in the operator's output directory. The skill files contain only dummy placeholder data (`example.com`, `EXAMPLE-USER-GUID`, etc.) so nothing personal or foundation-specific leaks into the shared skill.

## Install

```bash
./build.sh --install
```

Copies to `$SKILL_INSTALL_DIR` (default `~/.claude/skills/mcp-trace/`). Claude Code discovers it on next start.

## Use

Ask an AI assistant with the skill installed:

> Trace an MCP call to `<your MCP server>` via the gateway and build a viewer.

The AI reads `SKILL.md`, runs through the three phases, and produces `sequence.html` in the current working directory. Open it in a browser.

## Files

```
skill-mcp-trace/
├── build.sh                              # install + package (opt-in flags)
├── README.md                             # this file
├── CHANGELOG.md
├── .gitignore
└── src/mcp-trace/
    ├── SKILL.md                          # the workflow the AI follows
    └── references/
        ├── CAPTURE_WORKFLOW.md           # long-form runbook, per-IdP examples
        ├── CALL_TREE_SCHEMA.md           # JSON tree shape with dummy data
        └── VIEWER_TEMPLATE.html          # self-contained HTML + JS renderer
```
