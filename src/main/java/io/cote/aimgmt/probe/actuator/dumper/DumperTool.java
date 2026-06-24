package io.cote.aimgmt.probe.actuator.dumper;

import java.util.Map;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.micrometer.metrics.actuate.endpoint.MetricsEndpoint;
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
    private final ObjectProvider<MetricsEndpoint> metricsEndpoint;
    private final Counter invocations;

    DumperTool(McpEndpointDiscoverer mcpEndpoints, ObjectProvider<MetricsEndpoint> metricsEndpoint,
            MeterRegistry registry) {
        this.mcpEndpoints = mcpEndpoints;
        this.metricsEndpoint = metricsEndpoint;
        this.invocations = Counter.builder("probe.dumper.invocations")
                .description("Times the dumper has been called")
                .tag("interface", "mcp")
                .register(registry);
    }

    @McpTool(description = "Dump every MCP-exposed Actuator endpoint as one JSON tree. Set "
            + "includeMetricValues=true to also resolve every metric's current value in one "
            + "server-side pass (instead of one MCP call per metric). Set humanFriendly=true to "
            + "add a readable form of each value (e.g. 1.381 -> '1.381 s', bytes -> MB, big "
            + "counts with separators).")
    Map<String, Object> actuatorDumper(
            @McpToolParam(required = false, description = "Resolve all metric values too. Default false.") Boolean includeMetricValues,
            @McpToolParam(required = false, description = "Add a humanFriendly form to each metric value. Default false.") Boolean humanFriendly) {
        invocations.increment();
        Map<String, Object> dump = DumperSupport.dump(mcpEndpoints.getEndpoints());
        if (Boolean.TRUE.equals(includeMetricValues) && dump.containsKey("metrics")) {
            MetricsEndpoint metrics = metricsEndpoint.getIfAvailable();
            if (metrics != null) {
                dump.put("metrics", DumperSupport.metricValues(metrics, Boolean.TRUE.equals(humanFriendly)));
            }
        }
        return dump;
    }
}
