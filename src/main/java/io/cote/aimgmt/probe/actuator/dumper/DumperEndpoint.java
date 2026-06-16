package io.cote.aimgmt.probe.actuator.dumper;

import java.util.Map;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.stereotype.Component;

/**
 * HTTP dumper: a custom Actuator endpoint at /actuator/dumper. Scoped to the WEB
 * catalog, so it follows management.endpoints.web.exposure and the shared access rules.
 *
 * Deliberately named Dumper (not Dump): Spring's own thread-dump endpoint registers a
 * bean named "dumpEndpoint", so "dumperEndpoint" avoids that collision.
 */
@Component
@Endpoint(id = "dumper")
public class DumperEndpoint {

    private final WebEndpointsSupplier webEndpoints;

    DumperEndpoint(WebEndpointsSupplier webEndpoints) {
        this.webEndpoints = webEndpoints;
    }

    @ReadOperation
    public Map<String, Object> dump() {
        return DumperSupport.dump(webEndpoints.getEndpoints());
    }
}
