package io.cote.aimgmt.probe.actuator.dumper;

import java.util.Map;

import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final Counter invocations;

    DumperController(WebEndpointsSupplier webEndpoints, MeterRegistry registry) {
        this.webEndpoints = webEndpoints;
        this.invocations = Counter.builder("probe.dumper.invocations")
                .description("Times the dumper has been called")
                .tag("interface", "web")
                .register(registry);
    }

    @GetMapping("/dumper")
    public Map<String, Object> dumper() {
        invocations.increment();
        return DumperSupport.dump(webEndpoints.getEndpoints());
    }
}
