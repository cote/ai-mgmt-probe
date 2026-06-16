package io.cote.aimgmt.probe.actuator;

import java.util.List;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.actuate.endpoint.OperationType;
import org.springframework.stereotype.Component;

import io.cote.aimgmt.probe.actuator.McpEndpointDiscoverer.ExposableMcpEndpoint;

/**
 * MCP tools for the Actuator surface. Both run off the McpEndpointDiscoverer, so
 * they only see endpoints turned on via management.endpoints.mcp.exposure.* - and
 * they work whether or not the HTTP actuator is exposed.
 */
@Component
public class ActuatorTools {

    private final McpEndpointDiscoverer endpoints;

    ActuatorTools(McpEndpointDiscoverer endpoints) {
        this.endpoints = endpoints;
    }

    @McpTool(description = "List the Actuator endpoints exposed over MCP (e.g. health, info, metrics).")
    List<String> actuatorEndpoints() {
        return endpoints.getEndpoints().stream()
                .map(e -> e.getEndpointId().toString())
                .sorted()
                .toList();
    }

    @McpTool(description = "Run an MCP-exposed Actuator endpoint by id (e.g. health, info, metrics) and return its JSON.")
    Object actuator(@McpToolParam(description = "endpoint id, e.g. health") String endpoint) {
        return endpoints.getEndpoints().stream()
                .filter(e -> e.getEndpointId().toString().equals(endpoint))
                .findFirst()
                .map(this::invokeRoot)
                .orElse("No MCP-exposed actuator endpoint named '" + endpoint
                        + "'. Call actuatorEndpoints to see what's available.");
    }

    /** Invoke an endpoint's root (no-selector) read operation. */
    private Object invokeRoot(ExposableMcpEndpoint ep) {
        return ep.getOperations().stream()
                .filter(op -> op.getType() == OperationType.READ && op.rootInvokable())
                .findFirst()
                .map(McpEndpointDiscoverer.McpOperation::invokeRoot)
                .orElse("'" + ep.getEndpointId() + "' has no root-level read operation.");
    }
}
