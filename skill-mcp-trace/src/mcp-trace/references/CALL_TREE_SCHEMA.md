# Call tree schema

Each captured MCP call becomes one nested JSON tree that walks the request going in (deeper) and the response unwinding back out. Root is `Claude → gateway`, deepest node is `probe processes`, then it unwinds back to `gateway → Claude`.

All examples below use dummy placeholder data. Substitute real captured values from your run.

## Envelope

```json
{
  "call_id":   1,
  "method":    "tools/call",
  "tool_name": "toolname-or-null",
  "leg":       { ... nested tree ... }
}
```

## The nested `leg` tree

Each node has one of two shapes:

**Transfer legs** (something moves from one actor to another):

```json
{
  "from":      "claude" | "gateway" | "probe",
  "to":        "claude" | "gateway" | "probe",
  "direction": "request" | "response",
  "captured":  "real" | "partial" | "indirect" | "not_captured",
  "wire":      { "url": "...", "method": "POST", "headers": {...}, "body": {...} },
  "next":      { ... deeper node ... }
}
```

**Stop legs** (something happens at an actor):

```json
{
  "at":        "gateway" | "probe",
  "direction": "request" | "response" | "process",
  "event":     "audit_start" | "audit_stop"   (gateway only),
  "captured":  "real" | "indirect" | "not_captured",
  "audit":     { ... verbatim audit record ... }   (audit_start/stop only),
  "tool_invocation": { ... }                        (probe process only),
  "next":      { ... deeper or unwind ... }
}
```

The chain reads as 7 nodes for a normal call:

```
1. Claude → gateway         (transfer, HTTP request)
2. gateway audit_start       (stop, gateway writes .start record)
3. gateway → probe          (transfer, forwarded HTTP)
4. probe process            (stop, probe runs the tool)
5. probe → gateway          (transfer, HTTP response)
6. gateway audit_stop        (stop, gateway writes .stop record with result)
7. gateway → Claude         (transfer, HTTP response)
```

## Full example with dummy data

```json
{
  "call_id": 1,
  "method": "tools/call",
  "tool_name": "example-tool",
  "leg": {
    "from": "claude",
    "to": "gateway",
    "direction": "request",
    "captured": "partial",
    "wire": {
      "url":     "https://mcp-gateway.example.com/server-name/mcp",
      "method":  "POST",
      "headers": {
        "_status": "not_captured",
        "_reason": "The MCP client's transport adds request headers (Host, User-Agent, Authorization, etc.) but does not surface them to the tool layer. Would need a wrapping client or a network sniffer to see them."
      },
      "body": {
        "jsonrpc": "2.0",
        "id": 42,
        "method": "tools/call",
        "params": {
          "name": "example-tool",
          "arguments": { "someArg": "someValue" },
          "_meta": {
            "progressToken": 1,
            "claudecode/toolUseId": "toolu_EXAMPLEUSEID"
          }
        }
      }
    },
    "next": {
      "at": "gateway",
      "direction": "request",
      "event": "audit_start",
      "captured": "real",
      "audit": {
        "eventName": "mcp.tools.call.start",
        "severity": "INFO",
        "timestamp": "2000-01-01T00:00:00.000000Z",
        "attributes": {
          "event.name": "mcp.tools.call.start",
          "mcp.method.name": "tools/call",
          "cloudfoundry.app.id": "EXAMPLE-GATEWAY-APP-GUID",
          "mcp.session.id": "EXAMPLE-SESSION-GUID",
          "jsonrpc.request.id": "42",
          "mcp.protocol.version": "2025-11-25",
          "enduser.id": "example-user",
          "aiservices.mcp.auth.subject": "EXAMPLE-USER-GUID",
          "aiservices.mcp.auth.client_id": "EXAMPLE-CLIENT-GUID",
          "aiservices.mcp.auth.scopes": "openid",
          "client.address": "203.0.113.1",
          "user_agent.original": "example-client/1.0",
          "network.protocol.name": "http",
          "network.protocol.version": "1.1",
          "gen_ai.tool.name": "example-tool",
          "gen_ai.tool.call.arguments": { "someArg": "someValue" }
        }
      },
      "next": {
        "from": "gateway",
        "to": "probe",
        "direction": "request",
        "captured": "not_captured",
        "wire": {
          "url": "https://mcp-server.internal:8080/mcp",
          "method": "POST",
          "headers": {
            "_status": "not_captured",
            "_reason": "Gateway-internal traffic. Only the requestInfo tool's own inbound envelope is directly observed; other calls have identical shape with per-request transient values rotating."
          },
          "body": { "jsonrpc": "2.0", "id": 42, "method": "tools/call", "params": { "...same as above..." } }
        },
        "next": {
          "at": "probe",
          "direction": "process",
          "captured": "indirect",
          "tool_invocation": {
            "tool": "example-tool",
            "behavior": "Describe what the probe did with the request. Optional; document when it clarifies the trace."
          },
          "next": {
            "from": "probe",
            "to": "gateway",
            "direction": "response",
            "captured": "indirect",
            "wire": {
              "headers": {
                "_status": "not_captured",
                "_reason": "Internal C2C response headers not directly captured; the body below is what the gateway then re-emits to Claude."
              },
              "body": {
                "jsonrpc": "2.0",
                "id": 42,
                "result": { "content": [{ "type": "text", "text": "..." }] }
              }
            },
            "next": {
              "at": "gateway",
              "direction": "response",
              "event": "audit_stop",
              "captured": "real",
              "audit": {
                "eventName": "mcp.tools.call.stop",
                "severity": "INFO",
                "timestamp": "2000-01-01T00:00:00.100000Z",
                "attributes": {
                  "event.name": "mcp.tools.call.stop",
                  "mcp.method.name": "tools/call",
                  "cloudfoundry.app.id": "EXAMPLE-GATEWAY-APP-GUID",
                  "mcp.session.id": "EXAMPLE-SESSION-GUID",
                  "jsonrpc.request.id": "42",
                  "enduser.id": "example-user",
                  "gen_ai.tool.name": "example-tool",
                  "gen_ai.tool.call.arguments": { "someArg": "someValue" },
                  "gen_ai.tool.call.result": {
                    "content": [{ "type": "text", "text": "<escaped tool result string>" }],
                    "structured_content": null,
                    "meta": null
                  }
                }
              },
              "next": {
                "from": "gateway",
                "to": "claude",
                "direction": "response",
                "captured": "partial",
                "wire": {
                  "url": "https://mcp-gateway.example.com/server-name/mcp",
                  "headers": {
                    "_status": "not_captured",
                    "_reason": "The MCP client's transport reads the HTTP response and only surfaces the parsed body to the tool layer. Raw response headers (Date, Content-Type, Server, CF-RAY, X-Vcap-Request-Id, etc.) are not visible."
                  },
                  "body": {
                    "jsonrpc": "2.0",
                    "id": 42,
                    "result": { "content": [{ "type": "text", "text": "..." }] }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
```

## Error variant

When a tool call errors, the `audit_stop` record's `severity` is `"ERROR"` and it carries `error.type` instead of `gen_ai.tool.call.result`:

```json
{
  "eventName": "mcp.tools.call.stop",
  "severity": "ERROR",
  "attributes": {
    "gen_ai.tool.name": "example-tool",
    "gen_ai.tool.call.arguments": { ... },
    "error.type": "ToolError"
  }
}
```

The `Claude ← gateway` response leg's body should indicate the error:

```json
{
  "wire": {
    "body": {
      "_status": "error",
      "error_text": "<error message the MCP client surfaced>",
      "_reason": "<why it failed>"
    }
  }
}
```

## Marking things that aren't captured

Any leg or field that wasn't captured uses this shape:

```json
{
  "_status": "not_captured",
  "_reason": "<one-sentence explanation of why - e.g. 'MCP client transport did not surface response headers'>"
}
```

Don't guess plausible values. The `_reason` is what tells the reader whether the missing data is a fixable capture gap or a fundamental limitation.

## Credential masking

Any HTTP header whose value is a bearer token, cookie, or API key gets masked to fixed `"******"` (6 asterisks, no length reveal). Header names matched: `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, plus any name ending in `-token`, `-secret`, `-api-key`, `-credential`, or `-password`.

Example:

```json
"headers": {
  "Authorization": "******",
  "User-Agent": "example-client/1.0",
  "Cookie": "******"
}
```

## Emitting files

For each call tree, write to `calls/<NN>-<name>.json`:

- `<NN>` is the call_id zero-padded to 2 digits.
- `<name>` is `tool_name` if the call is a `tools/call`; otherwise the `method` with `/` replaced by `-` (e.g. `tools-list`, `initialize`).

Also emit:

- `trace.jsonl` - one call per line, compact JSON per line.
- `trace.json` - JSON array of all calls, pretty-printed. This is the input to the viewer.
