---
name: mcp-trace
description: >
  Capture an end-to-end MCP call trace (MCP client → gateway → MCP server → back)
  and turn it into per-call JSON trees + a self-contained HTML viewer. Use when the
  user wants to trace what happens between an MCP client and server, wants to see the
  gateway's audit records for each call, or wants to correlate wire requests with
  audit events. Works against any MCP server behind a Tanzu MCP Gateway; adaptable to
  other MCP gateways with the same audit shape.
  Trigger phrases: "trace mcp", "mcp gateway trace", "capture mcp trace",
  "build trace viewer", "regenerate mcp trace", "/mcp-trace".
compatibility: Any environment with bash + an MCP client. No language runtimes required at rendering time.
metadata:
  author: cote
  version: "2.0"
---

# MCP Trace

Turn a set of MCP calls + their gateway audit records into per-call JSON trees and a browsable HTML viewer. No scripts. This skill is instructions the LLM (you, running this) follows to assemble the artifacts directly from the captured data.

## How the skill is structured

Files an LLM running this reads:

| File | What it's for |
|---|---|
| `SKILL.md` (this file) | The workflow. Read top to bottom. |
| `references/CAPTURE_WORKFLOW.md` | Long-form runbook with per-IdP examples and gotchas. |
| `references/CALL_TREE_SCHEMA.md` | The JSON tree shape one call produces, with dummy data. |
| `references/VIEWER_TEMPLATE.html` | Self-contained HTML with embedded JS renderer. Placeholder for data. |

## The workflow

Three phases: **capture**, **assemble**, **render**.

### Phase 1 - Capture

The operator wants a trace. Run through:

1. **Record start time.** `date -u +%Y-%m-%dT%H:%M:%SZ` → save as `$START_TIME`.
2. **Make MCP tool calls** on the target server through the MCP gateway. Baseline set (skip any that don't apply):
   - `requestInfo` with `{}` - captures the gateway → server HTTP envelope (real headers) if the server has this tool.
   - `echo` with `{"sendAllInfo": true, "argsIn": {"trace_marker": "<any-string>"}}` - captures MCP `_meta` round-trip.
   - `actuatorDumper` with `{"includeMetricValues": true, "humanFriendly": true}` - large payload capture.
   - `whoami` with `{}` - validated JWT claims (if the server has this tool).
   - `trace` with `{"question": "..."}` - either a real model call or an audit error record.
3. **Record end time.** Save as `$END_TIME`.
4. **Wait ~25 seconds** for the gateway audit to flush to Hub's observability store.
5. **Pull audit records** via the Tanzu Hub MCP server (or whatever audit store the gateway ships to). Query pattern for Tanzu Hub - substitute your `<gateway-entity-id>`:
   ```graphql
   {
     observabilityQuery {
       queryLogs(
         entityId: ["<gateway-entity-id>"]
         first: 50
         input: {
           namespace: "Observability"
           startTime: "$START_TIME"
           endTime:   "$END_TIME"
           fields:    ["text"]
           sortOrder: ASC
           queryFilter: { field: "text", operator: CONTAINS, values: ["mcp.audit"] }
         }
       ) { logRecords { fields { key value } } }
     }
   }
   ```
   Run via `mcp__tanzu-hub__gql_execute_safe`. **Do NOT select `id` on `logRecords`** - Hub declares it non-null but returns null. On INTERNAL errors, wait 30s and retry once; if it still fails, proceed with whatever you have.
6. **Parse audit lines.** Each `text` field looks like `<ts> INFO mcp.audit - {<JSON>}`. Strip everything up to and including ` - ` and JSON-parse the rest. You'll get 2 records per method invocation (`.start` and `.stop`).

Full detail with gotchas: `references/CAPTURE_WORKFLOW.md`.

### Phase 2 - Assemble

For each MCP call made in phase 1, produce a nested JSON tree following the schema in `references/CALL_TREE_SCHEMA.md`. In summary:

```json
{
  "call_id": <int>,
  "method": "<jsonrpc method>",
  "tool_name": "<tool name or null>",
  "leg": {
    "from": "claude", "to": "gateway", "direction": "request",
    "captured": "partial",
    "wire": { "url": "...", "method": "POST", "headers": {...}, "body": {...} },
    "next": {
      "at": "gateway", "direction": "request", "event": "audit_start",
      "captured": "real",
      "audit": { ... the .start audit record verbatim ... },
      "next": {
        "from": "gateway", "to": "probe", "direction": "request",
        "captured": "real" | "not_captured",
        "wire": { ... },
        "next": {
          "at": "probe", "direction": "process",
          "tool_invocation": { ... },
          "next": {
            "from": "probe", "to": "gateway", "direction": "response",
            "captured": "indirect",
            "wire": { "body": { ... probe's response body ... } },
            "next": {
              "at": "gateway", "direction": "response", "event": "audit_stop",
              "captured": "real",
              "audit": { ... the .stop audit record verbatim ... },
              "next": {
                "from": "gateway", "to": "claude", "direction": "response",
                "captured": "partial",
                "wire": { "body": { ... same response body ... } }
              }
            }
          }
        }
      }
    }
  }
}
```

Rules:

- **Every leg has a `captured` field**: `"real"`, `"partial"`, `"indirect"`, or `"not_captured"`.
- **Never fabricate values.** If you don't have a piece of data, use `{"_status": "not_captured", "_reason": "<why>"}` instead of a made-up value.
- **The `audit` field is a verbatim copy** of the audit record from step 6.
- **Mask credentials.** If any wire header holds a bearer/token/cookie/api-key, replace its value with `"******"` (fixed 6 asterisks, no length reveal).

Write each call tree to a file: `calls/<NN>-<toolname-or-method>.json`. Example filenames: `calls/01-initialize.json`, `calls/05-echo.json`, `calls/09-whoami.json`.

Also emit:
- `trace.jsonl` - one call per line (compact JSON per line).
- `trace.json` - array of all calls, pretty-printed (used by the viewer).

### Phase 3 - Render

Produce `sequence.html` by:

1. Read `references/VIEWER_TEMPLATE.html`. It has all the CSS + a JS renderer.
2. Find the `<script id="trace-data" type="application/json">` block.
3. Replace its contents with the JSON array from `trace.json`.
4. Write the result as `sequence.html` in the operator's output directory.

That's it. The HTML is self-contained - open it in a browser to walk each call's breadcrumb chain.

## Critical rules

- **Never fabricate captured values.** If Hub returns nothing, mark it `not_captured`. If a header wasn't captured, don't guess it. The whole point of the trace is showing what's actually there.
- **Never leak the operator's identifiers into the skill files.** All examples in this skill use `example.com`, `<PLACEHOLDER>`, `EXAMPLE-USER-GUID`, etc. The operator's data lives only in their own output directory.
- **Mask any credential-bearing header** with fixed `"******"` (6 chars, no length).

## Common gotchas

- **Tool list caching.** After redeploying an MCP server, restart the MCP client to refresh the schema.
- **Hub `queryLogs` INTERNAL errors.** Chronic; retry once after 30s; if still failing, use wire data alone.
- **Per-request transient values rotate.** `X-B3-TraceId`, `X-Vcap-Request-Id`, `mcp-session-id`, `cf-ray` differ per call. Don't copy one call's `requestInfo` output into another call's tree.
- **HTTP `Mcp-Session-Id` header ≠ audit `mcp.session.id`.** Different identifiers; gateway assigns its own per RPC.
- **`initialize` has no `jsonrpc.request.id`** per MCP spec.

## What the operator gets

In their project directory after running through phases 2 and 3:

```
calls/
  01-<method>.json
  02-<method>.json
  ...
trace.jsonl
trace.json
sequence.html   ← open this in a browser
```

## XDG Paths

Reserved for future use (skill doesn't currently touch them):

| What | Location |
|------|----------|
| Config | `~/.config/io.cote.ai.skill.mcp_trace/` |
| Data | `~/.local/share/io.cote.ai.skill.mcp_trace/` |
| State | `~/.local/state/io.cote.ai.skill.mcp_trace/` |
| Cache | `~/.cache/io.cote.ai.skill.mcp_trace/` |
