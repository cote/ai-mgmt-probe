# ai-mgmt-probe

Diagnostics and tracing MCP Servers. These tools help trace the chain of calls to an MCP Server, collect and report on the parameters passed down and up the line. Also, this includes an MCP Server to access Spring Boot Actuator, including dumping all non-sensitive info available.

## What's in here

MCP tools exposed by the running app:

- **`echo`** - returns whatever you send. Optional `sendAllInfo` flag echoes the full `CallToolRequest` (name, args, meta). Sanity check that the MCP transport is wired up.
- **`trace`** - calls an OpenAI-compatible chat model and returns the answer plus everything observable: model name, token usage, finish reason, advisor chain, request/response metadata. Credentials are masked.
- **`aiConfig`** / **`modelInfo`** / **`requestInfo`** - introspect what Spring AI is actually configured with: base URL (no api-key), default chat options, ChatClient defaults, advisors in the chain. `requestInfo` also dumps the full inbound HTTP request (headers, tracing context, forwarded headers) so you can see what an upstream MCP gateway is injecting - credential-bearing header values are replaced with a fixed `******`.
- **`whoami`** - returns the validated JWT claims for the calling user (sub, user_name, email, scope, exp, iat, ...). Spring Security validates the JWT against the configured identity provider's JWKS before this tool runs; the raw bearer token is never exposed. Cloud profile only - locally there's no OIDC provider configured so it reports what's on the SecurityContext instead.
- **`actuator*`** - one MCP tool per exposed Boot Actuator endpoint (`health`, `info`, `metrics`, optionally `configprops`/`env`/...). Discovered dynamically via `McpEndpointDiscoverer`.
- **`actuatorDumper`** - aggregates every MCP-exposed Actuator endpoint into one JSON tree. Optional `includeMetricValues` resolves every metric in one server-side pass (instead of one MCP call per metric); optional `humanFriendly` adds readable forms (bytes -> MB, seconds -> ms/h, big counts with separators).

Other pieces:

- `application.properties` - secure by default. `health`, `info`, `metrics` over web and MCP. The opt-in `configprops`/`env` block is commented with a warning - they enumerate the full environment in plaintext when `show-values=always`.
- `application-cloud.properties` - what's different when `SPRING_PROFILES_ACTIVE=cloud`: graceful shutdown, Prometheus exposure for the platform's metric scrape, the `spring-boot-starter-cloudfoundry` actuator integration, and (blank by default!) the OAuth2 Resource Server JWKS URL.
- `SecurityConfig.java` - Spring Security. Local profile permits all; cloud profile requires a valid JWT on `/mcp/**`. Standard `spring-boot-starter-oauth2-resource-server` - works behind any OIDC provider; the specific JWKS URL is in `application-cloud.properties`.
- `manifest.yml` - locked-down Cloud Foundry manifest: **no public route**, internal-only, reachable via the MCP Gateway over container-to-container networking.

## Before you deploy: set the JWKS URL

The cloud profile requires a valid JWT on every `/mcp/**` call. To validate JWTs, Spring Security needs the JWKS URL for your identity provider. `application-cloud.properties` leaves this **blank on purpose** - fill it in for your environment, don't commit a foundation URL back to the repo.

Set `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` to one of the following (or the equivalent for whatever IdP fronts your MCP calls):

| Identity provider | JWKS URL template |
|---|---|
| **Tanzu Platform / Cloud Foundry UAA** | `https://login.sys.<FOUNDATION-DOMAIN>/token_keys` |
| **Keycloak** | `https://<host>/realms/<REALM>/protocol/openid-connect/certs` |
| **Auth0** | `https://<TENANT>.auth0.com/.well-known/jwks.json` |
| **Okta** | `https://<TENANT>.okta.com/oauth2/<AUTH-SERVER-ID>/v1/keys` |
| **Azure AD / Entra ID** | `https://login.microsoftonline.com/<TENANT>/discovery/v2.0/keys` |
| **AWS Cognito** | `https://cognito-idp.<REGION>.amazonaws.com/<USER-POOL-ID>/.well-known/jwks.json` |
| **Google** | `https://www.googleapis.com/oauth2/v3/certs` |

You can set it three ways:

1. **Env var on the app** (simplest, keeps it out of git):
   ```bash
   cf set-env mcp-probe SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI 'https://login.sys.<FOUNDATION>/token_keys'
   cf restart mcp-probe
   ```
   Spring Boot's env-var-to-property mapping handles the case conversion.

2. **Edit `application-cloud.properties` locally, deploy, then revert** (don't commit the value back).

3. **Alternative: OIDC discovery.** Instead of `jwk-set-uri`, set `spring.security.oauth2.resourceserver.jwt.issuer-uri` to the IdP's base URL. Spring will fetch `/.well-known/openid-configuration` to find the JWKS. This also validates the JWT's `iss` claim, which is stricter (and better).

If the property is empty when the cloud profile activates, Spring Security's Resource Server bean fails to construct and the app won't start. That's intentional - fail-loud beats a silently-open MCP endpoint.

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
cf set-env mcp-probe SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI \
    'https://login.sys.<YOUR-FOUNDATION>/token_keys'    # required, see above
cf restart mcp-probe
cf register-metrics-endpoint mcp-probe /actuator/prometheus    # one-time
```

You'll also need:

- The JWKS URL set (see **Before you deploy** above; the app won't start without it under the cloud profile).
- An OpenAI-compatible model bound or configured via env / VCAP. The cloud profile reads `spring.ai.openai.base-url` / `api-key` / `chat.options.model` from the environment.
- A network policy from the MCP Gateway app to `mcp-probe` on the app port (the gateway's bind step sets this up).

A walk-through of the local / open-CF / gateway-locked progression lives in the parent repo's `DEPLOY.md`.

## Layout

```
src/main/java/io/cote/aimgmt/probe/
├── ProbeApplication.java
├── SecurityConfig.java                # Spring Security: local=permitAll, cloud=JWT required
├── Echo.java                          # echo tool
├── Tracer.java                        # trace / aiConfig / modelInfo / requestInfo tools
├── RequestCapture.java                # helper: full inbound HTTP X-ray for requestInfo
├── WhoAmI.java                        # whoami tool - validated JWT claims via SecurityContextHolder
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
└── application-cloud.properties       # cloud-only overrides (SPRING_PROFILES_ACTIVE=cloud), including JWKS URL
```
