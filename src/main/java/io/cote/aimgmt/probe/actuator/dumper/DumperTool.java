package io.cote.aimgmt.probe.actuator.dumper;

import java.util.Map;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

import io.cote.aimgmt.probe.actuator.McpEndpointDiscoverer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * MCP dumper: an MCP tool. Scoped to the MCP catalog, so it follows
 * management.endpoints.mcp.exposure and the shared access rules.
 */
@Component
public class DumperTool {

    private final McpEndpointDiscoverer mcpEndpoints;
    private final Counter invocations;

    DumperTool(McpEndpointDiscoverer mcpEndpoints, MeterRegistry registry) {
        this.mcpEndpoints = mcpEndpoints;
        this.invocations = Counter.builder("probe.dumper.invocations")
                .description("Times the dumper has been called")
                .tag("interface", "mcp")
                .register(registry);
    }

    @McpTool(description = "Dump every MCP-exposed Actuator endpoint as one JSON tree.")
    Map<String, Object> actuatorDumper() {
        invocations.increment();
        return DumperSupport.dump(mcpEndpoints.getEndpoints());
    }
}
