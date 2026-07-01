# Capture runbook

How to produce a fresh end-to-end MCP trace: probe deployed, MCP client connected, tool calls made, gateway audit pulled, JSON trees regenerated, HTML viewer rebuilt.

Not a code runbook. Assumes the probe code and `build-call-trees.py` already exist.

## How to use this

**If you're a human:** read top to bottom, run the commands in the per-capture workflow section. The "What you get" and "Toolchain" tables tell you what the outputs are.

**If you're an LLM running this for someone (e.g. Claude Code):** the per-capture workflow has 9 numbered steps. Each one has an explicit command or MCP-tool call. Run them in order. Don't ad-lib - if step 5's GraphQL query returns an INTERNAL error, retry once after 30s; if it still fails, build the viewer with whatever audit you have (step 6 says how). Always finish with `./regen.sh` (step 8). Show the user `sequence.html` at the end (step 9).

**Critical LLM rule:** never fabricate captured values. The point of the trace is to show what's actually there. If a field wasn't captured, mark it `"_status": "not_captured"` with a `"_reason"` - don't invent a plausible value. If the user catches you making things up they'll lose trust in the whole capture.

## What you get at the end

| File | Format | Use |
|---|---|---|
| `captured-logs/calls/NN-toolname.json` | one nested call tree per file | open in any JSON viewer, walk the tree top-down |
| `captured-logs/trace.jsonl` | one call per line | jq, line-oriented processing |
| `captured-logs/trace.json` | pretty array of all calls | one-file browse, input to the HTML generator |
| `captured-logs/sequence.html` | HTML viewer with breadcrumb headers | browser, generated from `trace.json` |

## The toolchain

Everything is in `captured-logs/`:

| Tool | Role |
|---|---|
| `build-call-trees.py` | Reads inline capture data, writes `calls/*.json` + `trace.jsonl` + `trace.json` |
| `build-html-viewer.py` | Reads `trace.json`, writes `sequence.html` |
| `regen.sh` | Runs both in order. The one-liner you'll use most. |

After updating `build-call-trees.py` with a fresh capture's data (step 7 below), one `./regen.sh` rebuilds everything.

## One-time prerequisites

| Thing | How to verify |
|---|---|
| CF foundation with mcp-gateway service offering | `cf marketplace -e mcp-gateway` shows a `gateway` plan |
| `cf` CLI logged in to the right org/space | `cf target` |
| Java 25 + Maven (for the probe) | `./mvnw -v` |
| Probe app deployed at `<SERVER>`, internal route only | `cf app <SERVER>` shows `<SERVER>.apps.internal` |
| Gateway service instance bound to the probe | `cf service <SERVER>-gateway` shows `bound apps: <SERVER>` |
| UAA / SSO service bound (or referenced via OIDC bind param) | `cf services` lists `<SERVER>-sso` |
| Claude Code (or any MCP client doing OAuth dynamic-client-registration) | `claude --version` |
| `<SERVER>-gateway` configured as an MCP server in Claude Code | `~/.claude.json` `mcpServers` block has it; URL ends in `/<SERVER>/mcp` |
| `tanzu-hub` configured as an MCP server too | same file; URL is `https://<hub-host>/hub/mcp` |
| Python 3 + jq | for the generator and queries |

## When something in the probe changes

Build + push, then restart your MCP client so the cached tool list refreshes:

```bash
cd ai-mgmt-probe
./mvnw clean package -DskipTests
cf push
```

Wait for `cf app <SERVER>` to show `requested state: started`. Then **restart Claude Code** (it caches the MCP tool inventory at connect time; new tools or schema changes won't appear until a fresh connection).

## Per-capture workflow

### 1. Mark wall-clock start time

```bash
date -u +%Y-%m-%dT%H:%M:%SZ
```

Save the output as `$START_TIME`. You'll use it in step 5.

### 2. Call the tools you want to capture, from your MCP client

Baseline set (exercises every audit pattern):

| Tool | Args | What this captures |
|---|---|---|
| `requestInfo` | `{}` | Real gateway→probe forwarded HTTP envelope |
| `echo` | `{"sendAllInfo": true, "argsIn": {"trace_marker": "<your marker>"}}` | MCP `_meta` channel, `claudecode/toolUseId` round-trip |
| `actuatorDumper` | `{"includeMetricValues": true, "humanFriendly": true}` | Large response body in `gen_ai.tool.call.result` |
| `whoami` | `{}` | Spring Security validated JWT claims |
| `trace` | `{"question": "Say hi in three words."}` | Either a real model call OR an audit error record |

If running as an LLM via Claude Code's MCP integration: invoke each tool in order via `mcp__<SERVER>-gateway__<toolname>`.

### 3. Mark wall-clock end time

```bash
date -u +%Y-%m-%dT%H:%M:%SZ
```

Save as `$END_TIME`.

### 4. Wait 20-30 seconds for log ingest

```bash
until [ "$(date -u +%s)" -ge "$(( $(date -u +%s) + 25 ))" ]; do sleep 5; done
```

(Or just `sleep 25`. The gateway writes to stdout, Loggregator scoops, Hub's observability store indexes. Faster than ~20s and queries may come back empty.)

### 5. Pull the gateway audit via the Hub MCP server

You need the **gateway app's Hub entity ID**. It's stable across pushes; find it once and remember it. Use `mcp__tanzu-hub__get_applications` with this filter, where `<gateway-app-guid>` is the CF GUID of the gateway app (visible in any audit line as `cloudfoundry.app.id`, or in any `requestInfo` capture as `x-cf-applicationid`):

```json
{
  "filter": {"field": "entityId", "operator": "CONTAINS", "values": ["<gateway-app-guid>"]},
  "first": 1
}
```

The response's `entityId` is what you pass to `queryLogs`. Save it as `$GATEWAY_ENTITY_ID`.

Then pull audit lines via `mcp__tanzu-hub__gql_execute_safe`:

```graphql
{
  observabilityQuery {
    queryLogs(
      entityId: ["$GATEWAY_ENTITY_ID"]
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

**Gotchas:**
- Do NOT select `id` on `logRecords` - Hub's schema declares it non-null but returns null and the whole query errors out. Only select `fields { key value }`.
- If the response is `{"ok": false, "error": "...INTERNAL..."}`, wait 30s and retry once. If it still fails, proceed to step 6 with whatever you have - the wire data from step 2 plus prior captures' audit shapes is still useful.

### 6. Convert audit lines to records

Each `text` field looks like `<ts> INFO mcp.audit - {<json>}`. Strip everything up to and including ` - ` and parse the JSON. You'll get 2 records per MCP method (a `.start` and a `.stop` with the same `mcp.session.id`). Tool calls carry `gen_ai.tool.name`, `gen_ai.tool.call.arguments`, and `gen_ai.tool.call.result`.

For each call you made in step 2, extract from the audit:
- `mcp.session.id` (different per call, gateway-assigned)
- `jsonrpc.request.id`
- start timestamp, stop timestamp
- `gen_ai.tool.call.arguments` (tool calls only)
- `gen_ai.tool.call.result` (tool calls only, may be missing on errors)
- `severity` (`INFO` or `ERROR`)
- `error.type` (errors only)

### 7. Update `build-call-trees.py` with the fresh capture data

Open `captured-logs/build-call-trees.py`. The `calls = [...]` list at the bottom has one `make_call_tree(...)` block per call. For each call you captured in step 2, update its block with the values you extracted in step 6:

| Field | Source |
|---|---|
| `audit_session_id` | audit record's `mcp.session.id` (rotates per call) |
| `audit_timestamps` | `(start_record.timestamp, stop_record.timestamp)` |
| `jsonrpc_id` | audit's `jsonrpc.request.id` |
| `request_body` | the wire JSON-RPC request you sent in step 2 |
| `response_body` | unescape `gen_ai.tool.call.result.content[0].text` from the stop record |
| `audit_start_extra` | tool-call-only fields from the start record (`gen_ai.tool.name`, `gen_ai.tool.call.arguments`) |
| `audit_stop_extra` | tool-call-only fields from the stop record (same as start, plus `gen_ai.tool.call.result`, or `error.type` on errors) |
| `REQUESTINFO_FORWARDED_HEADERS` (top of file) | from this run's `requestInfo` response, only the `headers` block. Update only if you re-ran requestInfo. |

For fields you didn't capture (Hub query failed, didn't run that tool, etc.), set the corresponding field to the structured `{"_status": "not_captured", "_reason": "..."}` shape - **do not fabricate values**.

### 8. Regenerate the JSON trees and HTML viewer

One command does it all:

```bash
cd captured-logs
./regen.sh
```

That runs `build-call-trees.py` (produces 9 per-call JSONs, `trace.jsonl`, `trace.json`) followed by `build-html-viewer.py` (produces `sequence.html` from `trace.json`). Idempotent - re-run safely.

If you only want one stage:

```bash
python3 build-call-trees.py    # JSON only
python3 build-html-viewer.py   # HTML only (reads trace.json)
```

### 9. Open the HTML viewer for the user

```bash
open sequence.html
```

(LLMs running this: this is the last step. Tell the user "opened sequence.html" and stop. Don't proactively summarize what's in the trace - they'll explore it themselves.)

Alternative views the user might ask for:

```bash
# tree walker - one call at a time
jless calls/09-whoami.json

# selective query
jq '.leg.next.audit' calls/09-whoami.json

# all errored calls
jq 'select(.leg.next.next.next.next.next.audit.severity == "ERROR")' trace.json

# IDE
subl calls/
```

## What the audit captures, by event

| Event | Envelope | Args | Result | Notes |
|---|---|---|---|---|
| `mcp.initialize.{start,stop}` | yes | n/a | n/a | No `jsonrpc.request.id` per spec |
| `mcp.tools.list.{start,stop}` | yes | n/a | n/a | The returned tool list is not in the audit |
| `mcp.prompts.list.{start,stop}` | yes | n/a | n/a | Same |
| `mcp.resources.list.{start,stop}` | yes | n/a | n/a | Same |
| `mcp.tools.call.{start,stop}` success | yes | yes (`gen_ai.tool.call.arguments`) | yes (`gen_ai.tool.call.result`) | The full result body |
| `mcp.tools.call.{start,stop}` error | yes; stop has `severity: "ERROR"` | yes | no (just `error.type`) | Error message text only in probe stdout |

## Gateway-side identity (in the audit)

| Field | Meaning |
|---|---|
| `enduser.id` | readable username, from JWT `user_name` claim |
| `aiservices.mcp.auth.subject` | UAA user GUID, from JWT `sub` claim |
| `aiservices.mcp.auth.client_id` | OAuth client (dynamically-registered MCP client) |
| `aiservices.mcp.auth.scopes` | granted OAuth scopes |
| `client.address` | original client IP (Cloudflare's, if behind Cloudflare) |
| `user_agent.original` | the MCP client's UA string |
| `mcp.session.id` | **gateway-assigned per RPC**. NOT the same as the HTTP `Mcp-Session-Id` the client sent |
| `cloudfoundry.app.id` | the gateway app's CF GUID |

## Probe-side identity (in `requestInfo`'s response)

Pulled from the JWT by Spring Security, then surfaced by the probe:

| Field | Source | Notes |
|---|---|---|
| `request.remoteUser` | JWT `sub` | Set by Spring Security after JWKS validation |
| `headers.Authorization` | inbound bearer | Always masked (length only) |
| `headers.mcp-session-id` | client-sent | **Different from audit's `mcp.session.id`** |
| `headers.cf-connecting-ip` | Cloudflare-added | Original client IP (no `X-Forwarded-For`) |
| `headers.cf-ipcountry` | Cloudflare-added | GeoIP, e.g. `NL` |
| `headers.cf-ray` | Cloudflare-added | Per-request ray id |
| `headers.x-cf-applicationid` | CF gorouter | The gateway app's CF GUID |
| `headers.x-vcap-request-id` | CF gorouter | Per-request UUID |
| `headers.x-b3-traceid` | CF gorouter | Same bytes as `x-vcap-request-id`, no dashes |

The gateway does **not** synthesize convenience identity headers (no `X-Forwarded-User`, no `X-Forwarded-Email`). Identity travels via the JWT only.

## Common gotchas

- **Tool list caching.** Restart your MCP client after a probe redeploy or new tool. Otherwise calls fail with stale schema.
- **Hub `queryLogs` INTERNAL errors.** Wait 30s and retry. If it stays broken, build the viewer using the prior capture's audit; the new wire data still gives you most of the story.
- **Per-request transient values rotate.** `X-B3-TraceId`, `X-Vcap-Request-Id`, `mcp-session-id`, `cf-ray`, `cf-warp-tag-id` are different every call. Don't copy values from one call's `requestInfo` into another call's documentation.
- **`cf push` on a shared foundation** affects only your `<SERVER>` app, but takes 30-90s to restart. Bind survives.
- **`trace` errors in cloud** unless you bind a Tanzu GenAI service. Cloud profile is wired to `localhost:11434` (Ollama) which isn't in the container.
- **`Mcp-Session-Id` header ≠ audit `mcp.session.id`.** Two distinct identifiers. The audit's is gateway-assigned per RPC.
- **Initialize has no `jsonrpc.request.id`** per the MCP spec; only the start/stop pair share an `mcp.session.id` to join on.

## When to re-capture

| Reason | What to re-capture |
|---|---|
| Probe code changed | Everything - tool list shape may differ |
| New tool added | At minimum: `tools/list` + the new tool's call |
| JWT expired (after 12 hours) | Reconnect MCP client, redo OAuth, re-capture |
| Demonstrating a different gateway config | The whole 9 calls baseline |
| Just want fresh transient ids | Just step 2-8 (no redeploy needed) |
