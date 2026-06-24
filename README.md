# ai-mgmt-probe

A small Spring Boot / Spring AI app that exposes itself over **MCP (Model Context Protocol)** so an AI client can poke at it. Built as a test fixture for AI-management / observability work: Boot Actuator, Spring AI tracing, Cloud Foundry deploy patterns, MCP Gateway integration.

## What's in here

MCP tools exposed by the running app:

- **`echo`** - returns whatever you send. Optional `sendAllInfo` flag echoes the full `CallToolRequest` (name, args, meta). Sanity check that the MCP transport is wired up.
- **`trace`** - calls a real OpenAI-compatible chat model and returns the answer plus everything observable: model name, token usage, finish reason, advisor chain, request/response metadata. Credentials are masked.
- **`aiConfig`** / **`modelInfo`** / **`requestInfo`** - introspect what Spring AI is actually configured with: base URL (no api-key), default chat options, ChatClient defaults, advisors in the chain.
- **`actuator*`** - one MCP tool per exposed Boot Actuator endpoint (`health`, `info`, `metrics`, optionally `configprops`/`env`/...). Discovered dynamically via `McpEndpointDiscoverer`.
- **`actuatorDumper`** - aggregates every MCP-exposed Actuator endpoint into one JSON tree. Optional `includeMetricValues` resolves every metric in one server-side pass (instead of one MCP call per metric); optional `humanFriendly` adds readable forms (bytes -> MB, seconds -> ms/h, big counts with separators).

Other pieces:

- `application.properties` - secure by default. `health`, `info`, `metrics` over web and MCP. The opt-in `configprops`/`env` block is commented with a warning - they enumerate the full environment in plaintext when `show-values=always`.
- `application-cloud.properties` - what's different on Cloud Foundry: graceful shutdown, Prometheus exposure for the platform's metric scrape, the `spring-boot-starter-cloudfoundry` actuator integration.
- `manifest.yml` - locked-down Cloud Foundry manifest: **no public route**, internal-only, reachable via the MCP Gateway over container-to-container networking.

## Requirements

- Java 25 (or whatever your `pom.xml` line says)
- Maven (the `./mvnw` wrapper is included)
- For the AI tools (`trace` etc.): an OpenAI-compatible chat endpoint. Locally this is [Ollama](https://ollama.com); in the cloud it's a Tanzu GenAI binding or OpenAI proper.

## Run locally

1. Start Ollama and pull a model:

   ```bash
   ollama pull gemma3:4b
   ```

2. Confirm `application.properties` points at it. Defaults:

   ```properties
   spring.ai.openai.base-url=http://localhost:11434/v1
   spring.ai.openai.api-key=ollama
   spring.ai.openai.chat.options.model=gemma3:4b
   ```

   The `/v1` in the base-url is required - the Spring AI 2.0 OpenAI starter uses the official `com.openai` SDK and appends `/chat/completions` itself.

3. Build and run:

   ```bash
   ./mvnw spring-boot:run
   ```

4. App is on `http://localhost:8080`. The MCP endpoint is `/mcp` (Streamable HTTP transport). Standard Actuator under `/actuator/*`.

## Run the tests

```bash
./mvnw test
```

Integration tests in `src/test/java/...` stand up the app on a random port and call MCP tools via the real `HttpClientStreamableHttpTransport`.

## Deploy to Cloud Foundry

The included `manifest.yml` deploys the app **without a public route**, reachable only over the platform's internal C2C network - intended for use behind the Tanzu MCP Gateway.

```bash
./mvnw clean package -DskipTests
cf push
cf register-metrics-endpoint mcp-probe /actuator/prometheus    # one-time
```

You'll also need:

- An OpenAI-compatible model bound or configured via env / VCAP. The cloud profile reads `spring.ai.openai.base-url` / `api-key` / `chat.options.model` from the environment.
- A network policy from the MCP Gateway app to `mcp-probe` on the app port (the gateway's bind step sets this up).

A walk-through of the local / open-CF / gateway-locked progression lives in the parent repo's `DEPLOY.md`.

## Layout

```
src/main/java/io/cote/aimgmt/probe/
├── ProbeApplication.java
├── Echo.java                          # echo tool
├── Tracer.java                        # trace / aiConfig / modelInfo / requestInfo tools
└── actuator/
    ├── ActuatorTools.java             # discovery glue
    ├── McpEndpointDiscoverer.java     # which Actuator endpoints are exposed via MCP
    ├── McpExposureOutcomeContributor.java
    └── dumper/
        ├── DumperController.java      # GET /dumper - web view
        ├── DumperTool.java            # actuatorDumper MCP tool
        └── DumperSupport.java         # shared aggregation + humanize logic

src/main/resources/
├── application.properties             # secure-by-default base config
└── application-cloud.properties       # CF-only overrides (active with SPRING_PROFILES_ACTIVE=cloud)
```
