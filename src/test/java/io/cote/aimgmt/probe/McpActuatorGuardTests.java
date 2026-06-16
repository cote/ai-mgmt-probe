package io.cote.aimgmt.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * Proves the MCP actuator surface is gated, not just "unlisted":
 *  - web exposes health + metrics, but MCP exposes only health
 *  - so over MCP you cannot call metrics (even though it IS reachable over HTTP)
 *  - and you cannot call a made-up endpoint name
 *
 * The catalog filter is re-checked on every call, so naming an endpoint directly
 * does not get you around exposure.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "management.endpoints.web.exposure.include=health,metrics",
        "management.endpoints.mcp.exposure.include=health"
})
class McpActuatorGuardTests {

    @LocalServerPort
    int port;

    private McpSyncClient client;

    @BeforeEach
    void connect() {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint("/mcp")
                .build();
        client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
        client.initialize();
    }

    @AfterEach
    void disconnect() {
        if (client != null) {
            client.closeGracefully();
        }
    }

    @Test
    void listExcludesEndpointsNotExposedToMcp() {
        String text = firstText(client.callTool(CallToolRequest.builder("actuatorEndpoints").build()));
        assertTrue(text.contains("health"), () -> text);
        assertFalse(text.contains("metrics"), () -> "metrics should not be MCP-exposed: " + text);
    }

    @Test
    void cannotCallEndpointExposedOnlyToWeb() throws Exception {
        // It IS reachable over HTTP...
        HttpResponse<String> http = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/metrics")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, http.statusCode(), "metrics should be reachable over HTTP");

        // ...but NOT over MCP, even though we name it directly.
        String text = firstText(client.callTool(CallToolRequest.builder("actuator")
                .arguments(Map.of("endpoint", "metrics")).build()));
        assertTrue(text.contains("No MCP-exposed actuator endpoint named 'metrics'"), () -> text);
    }

    @Test
    void cannotCallMadeUpEndpoint() {
        String text = firstText(client.callTool(CallToolRequest.builder("actuator")
                .arguments(Map.of("endpoint", "totally-not-real")).build()));
        assertTrue(text.contains("No MCP-exposed actuator endpoint named 'totally-not-real'"), () -> text);
    }

    @Test
    void canStillCallTheMcpExposedEndpoint() {
        String text = firstText(client.callTool(CallToolRequest.builder("actuator")
                .arguments(Map.of("endpoint", "health")).build()));
        assertTrue(text.contains("UP"), () -> text);
    }

    private static String firstText(CallToolResult result) {
        List<Content> content = result.content();
        Content first = content.get(0);
        return (first instanceof TextContent text) ? text.text() : first.toString();
    }
}
