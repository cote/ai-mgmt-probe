package io.cote.aimgmt.probe.actuator.dumper;

import java.util.Map;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

import io.cote.aimgmt.probe.actuator.McpEndpointDiscoverer;

/**
 * MCP dumper: an MCP tool. Scoped to the MCP catalog, so it follows
 * management.endpoints.mcp.exposure and the shared access rules.
 */
@Component
public class DumperTool {

    private final McpEndpointDiscoverer mcpEndpoints;

    DumperTool(McpEndpointDiscoverer mcpEndpoints) {
        this.mcpEndpoints = mcpEndpoints;
    }

    @McpTool(description = "Dump every MCP-exposed Actuator endpoint as one JSON tree.")
    Map<String, Object> actuatorDumper() {
        return DumperSupport.dump(mcpEndpoints.getEndpoints());
    }
}
