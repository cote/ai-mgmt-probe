package io.cote.aimgmt.probe;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Boots the full app on a random port and drives it through the real MCP client SDK
 * over Streamable HTTP - the automated version of the manual handshake-and-call pokes.
 *
 * Assumes the default application.properties exposure (health, info, env, metrics, dumper).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "management.endpoints.web.exposure.include=health,info,env,metrics",
        "management.endpoints.mcp.exposure.include=health,info,env,metrics",
        "management.endpoint.health.show-details=always"
})
class McpToolsIntegrationTests {

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
    void listsTheExpectedTools() {
        List<String> names = client.listTools().tools().stream().map(t -> t.name()).toList();
        assertTrue(names.containsAll(List.of("echo", "actuator", "actuatorEndpoints", "actuatorDumper")),
                () -> "missing tools, got: " + names);
    }

    @Test
    void echoRoundTripsArguments() {
        CallToolResult result = client.callTool(CallToolRequest.builder("echo")
                .arguments(Map.of("argsIn", "hello there")).build());
        assertFalse(result.isError());
        assertTrue(firstText(result).contains("hello there"), () -> firstText(result));
    }

    @Test
    void actuatorEndpointsListsExposedIds() {
        CallToolResult result = client.callTool(CallToolRequest.builder("actuatorEndpoints").build());
        assertFalse(result.isError());
        String text = firstText(result);
        assertTrue(text.contains("health") && text.contains("metrics"), () -> text);
    }

    @Test
    void actuatorHealthReportsStatus() {
        CallToolResult result = client.callTool(CallToolRequest.builder("actuator")
                .arguments(Map.of("endpoint", "health")).build());
        assertFalse(result.isError(), () -> "health call errored: " + firstText(result));
        assertTrue(firstText(result).contains("UP"), () -> firstText(result));
    }

    @Test
    void actuatorDumperAggregatesEndpoints() {
        CallToolResult result = client.callTool(CallToolRequest.builder("actuatorDumper").build());
        assertFalse(result.isError());
        String text = firstText(result);
        assertTrue(text.contains("health") && text.contains("metrics"), () -> text);
    }

    private static String firstText(CallToolResult result) {
        Content content = result.content().get(0);
        return (content instanceof TextContent text) ? text.text() : content.toString();
    }
}
