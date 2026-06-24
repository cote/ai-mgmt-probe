package io.cote.aimgmt.probe;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Diagnostic tracer for the AI call chain. trace() makes a real model call and returns
 * everything observable end to end: the answer, the per-call response metadata, and the
 * backing model's static config (including OpenAI-specific options when the backend is
 * OpenAI-compatible). aiConfig() is the same static config view without making a call.
 */
@Service
public class Tracer {

    private final ChatClient ai;
    private final ChatModel chatModel;
    private final Environment env;
    // Plain mapper - OpenAiChatOptions carries its own Jackson annotations, no Spring bean
    // needed. findAndRegisterModules() picks up jsr310 so its Duration field serializes.
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final HttpClient http = HttpClient.newHttpClient();
    // Lazily populated on first modelInfo() call (NOT at construction, so boot doesn't depend
    // on the model backend). Cached only on success, so a failed first attempt retries later.
    private volatile Map<String, Object> modelInfoCache;

    private static final String SYSTEM_PROMPT = """
            When a user asks a question, answer it in three sentences to the best of your
            ability. You are primarily used as an example and diagnostics tool to test and
            report on AI services: monitoring, management, and observability.
            """;

    // Advisor class names in the call chain, captured so trace() can report what's wired in.
    private final List<String> advisorNames;

    Tracer(ChatClient.Builder builder, ChatModel chatModel, Environment env) {
        this.chatModel = chatModel;
        this.env = env;
        var loggerAdvisor = new SimpleLoggerAdvisor();
        this.advisorNames = List.of(loggerAdvisor.getClass().getSimpleName());
        this.ai = builder.defaultAdvisors(loggerAdvisor)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    @McpTool(description = """
        Traces an end-to-end AI call: sends the question to the LLM and returns the answer
        plus EVERYTHING observable - per-call metadata (model, token usage, finish reason,
        rate limits, provider extras) and the backing model's static config (options,
        endpoint, and OpenAI-specific options when the backend is OpenAI-compatible).
        """)
    Map<String, Object> trace(@McpToolParam(required = true) String question) {
        // .chatResponse() keeps the full envelope; .content() would drop everything but the text.
        ChatResponse response = ai.prompt().user(question).call().chatResponse();

        var result = new LinkedHashMap<String, Object>();
        result.put("request", requestSent(question));
        result.put("answer", response.getResult().getOutput().getText());
        result.put("callMetadata", callMetadata(response));
        result.put("modelConfig", modelConfig());
        result.put("inboundRequest", inboundRequest());
        return result;
    }

    /** What we send to the model: the system prompt, the user message, and the advisors in
     *  the chain. This is what's CONFIGURED; the authoritative on-the-wire prompt (with any
     *  advisor-injected context and merged options) would come from a capturing advisor. */
    private Map<String, Object> requestSent(String question) {
        var req = new LinkedHashMap<String, Object>();
        req.put("systemPrompt", SYSTEM_PROMPT);
        req.put("userMessage", question);
        req.put("advisors", advisorNames);
        return req;
    }

    @McpTool(description = """
        Captures the inbound HTTP request to THIS MCP server - method, URI, remote address,
        and headers. The gateway X-ray: shows what the MCP Gateway injects (auth, X-Forwarded-*,
        routing/instance headers). Sensitive headers (Authorization, Cookie) are masked.
        """)
    Map<String, Object> requestInfo() {
        return inboundRequest();
    }

    @McpTool(description = "Reports the static configuration of the backing chat model: options, "
            + "endpoint, and OpenAI-specific options when applicable. The API key is never returned.")
    Map<String, Object> aiConfig() {
        return modelConfig();
    }

    @McpTool(description = """
        Provider-native model introspection: the model's CAPABILITIES (modalities like
        completion/vision/tools), its real DEFAULT parameters (temperature, top_p, ...), and
        details (size, quantization, context length) - none of which the OpenAI chat protocol
        exposes. Ollama-specific (uses /api/show); returns an error note for other providers.
        Fetched lazily on first call and cached, so it does not run at startup.
        """)
    Map<String, Object> modelInfo() {
        Map<String, Object> cached = modelInfoCache;
        if (cached != null) {
            return cached;
        }
        Map<String, Object> fetched = fetchModelInfo();
        if (!fetched.containsKey("error")) {   // cache only success, so a failed attempt retries
            modelInfoCache = fetched;
        }
        return fetched;
    }

    /** Ollama /api/show: capabilities, default params, details. Never throws - returns an
     *  "error" entry on any failure so trace()/callers degrade gracefully. */
    private Map<String, Object> fetchModelInfo() {
        try {
            String base = env.getProperty("spring.ai.openai.base-url", "");
            String model = env.getProperty("spring.ai.openai.chat.options.model", "");
            String root = base.replaceAll("/v1/?$", "");   // strip the OpenAI-compat /v1 suffix
            String body = json.writeValueAsString(Map.of("name", model));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(root + "/api/show"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return Map.of("error", "provider introspection unavailable (HTTP " + resp.statusCode()
                        + "). /api/show is Ollama-specific; not supported by this provider.");
            }
            Map<String, Object> show = json.readValue(resp.body(), new TypeReference<Map<String, Object>>() {});
            var out = new LinkedHashMap<String, Object>();
            out.put("capabilities", show.get("capabilities"));
            out.put("details", show.get("details"));
            out.put("defaultParameters", show.get("parameters"));
            if (show.get("model_info") instanceof Map<?, ?> mi) {
                for (var e : mi.entrySet()) {
                    if (String.valueOf(e.getKey()).endsWith(".context_length")) {
                        out.put("contextLength", e.getValue());
                        break;
                    }
                }
            }
            return out;
        } catch (Exception ex) {
            return Map.of("error", "model introspection failed: " + ex.getMessage());
        }
    }

    /** Inbound HTTP request to this server, read from the thread-bound request. Sensitive
     *  headers masked. Returns a note if no request is bound (tool ran off the request thread). */
    private Map<String, Object> inboundRequest() {
        var info = new LinkedHashMap<String, Object>();
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest req = attrs.getRequest();
            info.put("method", req.getMethod());
            info.put("uri", req.getRequestURI());
            info.put("remoteAddr", req.getRemoteAddr());
            var headers = new LinkedHashMap<String, Object>();
            for (String name : Collections.list(req.getHeaderNames())) {
                String value = String.join(", ", Collections.list(req.getHeaders(name)));
                headers.put(name, maskHeader(name, value));
            }
            info.put("headers", headers);
        } else {
            info.put("note", "no servlet request bound to this thread - the tool ran off the "
                    + "HTTP request thread (async dispatch) or outside an HTTP context");
        }
        return info;
    }

    /** Mask credential-bearing headers: keep an Authorization scheme visible (proves the gateway
     *  injected a Bearer token) but never leak the token itself. */
    private static String maskHeader(String name, String value) {
        String lower = name.toLowerCase();
        if (lower.equals("authorization") || lower.equals("proxy-authorization")) {
            int sp = value.indexOf(' ');
            String scheme = (sp > 0) ? value.substring(0, sp) + " " : "";
            return scheme + "*** redacted (" + value.length() + " chars) ***";
        }
        if (lower.equals("cookie") || lower.equals("set-cookie")) {
            return "*** redacted ***";
        }
        return value;
    }

    /** Per-call response metadata: the typed fields plus the provider's extras bag. */
    private Map<String, Object> callMetadata(ChatResponse response) {
        ChatResponseMetadata md = response.getMetadata();
        Usage usage = md.getUsage();
        var meta = new LinkedHashMap<String, Object>();
        meta.put("id", md.getId());
        meta.put("model", md.getModel());                 // the model that actually served this call
        meta.put("promptTokens", usage.getPromptTokens());
        meta.put("completionTokens", usage.getCompletionTokens());
        meta.put("totalTokens", usage.getTotalTokens());
        meta.put("finishReason", response.getResult().getMetadata().getFinishReason());
        meta.put("rateLimit", String.valueOf(md.getRateLimit()));
        // provider-specific extras the backend tacked on (OpenAI: created, systemFingerprint, ...)
        var extras = new LinkedHashMap<String, Object>();
        for (String key : md.keySet()) {
            extras.put(key, md.get(key));
        }
        if (!extras.isEmpty()) {
            meta.put("providerMetadata", extras);
        }
        return meta;
    }

    /** Static config of the backing model: generic options, endpoint, and - if the backend is
     *  OpenAI-compatible - the full OpenAI-specific option surface. API key never returned. */
    private Map<String, Object> modelConfig() {
        var cfg = new LinkedHashMap<String, Object>();
        cfg.put("chatModelClass", chatModel.getClass().getName());
        cfg.put("openAiCompatible", chatModel instanceof OpenAiChatModel);

        var opts = chatModel.getOptions();
        if (opts != null) {
            var o = new LinkedHashMap<String, Object>();
            o.put("model", opts.getModel());
            o.put("temperature", opts.getTemperature());
            o.put("topP", opts.getTopP());
            o.put("maxTokens", opts.getMaxTokens());
            o.put("frequencyPenalty", opts.getFrequencyPenalty());
            o.put("presencePenalty", opts.getPresencePenalty());
            o.put("stopSequences", opts.getStopSequences());
            cfg.put("options", o);
        }

        // OpenAI backend: cast and serialize the full OpenAI option surface (responseFormat,
        // seed, n, reasoningEffort, etc.) - more than the generic ChatOptions interface exposes.
        if (chatModel instanceof OpenAiChatModel && opts instanceof OpenAiChatOptions oa) {
            try {
                var oaMap = json.convertValue(oa, new TypeReference<Map<String, Object>>() {});
                // OpenAiChatOptions can itself carry credentials/headers - never surface those.
                for (String secret : new String[] { "apiKey", "credential", "customHeaders" }) {
                    if (oaMap.get(secret) != null) {
                        oaMap.put(secret, "*** redacted ***");
                    }
                }
                cfg.put("openAiOptions", oaMap);
            } catch (Exception ex) {
                cfg.put("openAiOptions", "unavailable: " + ex.getMessage());
            }
        }

        // Endpoint from config - base-url only. NEVER surface the api-key.
        cfg.put("baseUrl", env.getProperty("spring.ai.openai.base-url"));
        cfg.put("configuredModel", env.getProperty("spring.ai.openai.chat.options.model"));
        cfg.put("apiKey", "*** redacted ***");
        return cfg;
    }
}
