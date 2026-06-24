package io.cote.aimgmt.probe.actuator.dumper;

import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.micrometer.metrics.actuate.endpoint.MetricsEndpoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * HTTP dumper: a plain MVC endpoint at GET /dumper that aggregates the WEB-exposed
 * Actuator endpoints into one JSON tree.
 *
 * Intentionally NOT an Actuator @Endpoint - we don't want "dumper" to appear in the
 * catalog it dumps. Because it's a normal app route (not under /actuator), it is NOT
 * gated by actuator exposure/access and NOT covered by EndpointRequest security - so in
 * production it must be protected by Spring Security like any other app route.
 */
@RestController
public class DumperController {

    private final WebEndpointsSupplier webEndpoints;
    private final ObjectProvider<MetricsEndpoint> metricsEndpoint;
    private final Counter invocations;

    DumperController(WebEndpointsSupplier webEndpoints, ObjectProvider<MetricsEndpoint> metricsEndpoint,
            MeterRegistry registry) {
        this.webEndpoints = webEndpoints;
        this.metricsEndpoint = metricsEndpoint;
        this.invocations = Counter.builder("probe.dumper.invocations")
                .description("Times the dumper has been called")
                .tag("interface", "web")
                .register(registry);
    }

    @GetMapping("/dumper")
    public Map<String, Object> dumper(
            @RequestParam(defaultValue = "false") boolean includeMetricValues,
            @RequestParam(defaultValue = "false") boolean humanFriendly) {
        invocations.increment();
        Map<String, Object> dump = DumperSupport.dump(webEndpoints.getEndpoints());
        if (includeMetricValues && dump.containsKey("metrics")) {
            MetricsEndpoint metrics = metricsEndpoint.getIfAvailable();
            if (metrics != null) {
                dump.put("metrics", DumperSupport.metricValues(metrics, humanFriendly));
            }
        }
        return dump;
    }
}
