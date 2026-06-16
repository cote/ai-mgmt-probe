package io.cote.aimgmt.probe.actuator.dumper;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.boot.actuate.endpoint.ExposableEndpoint;
import org.springframework.boot.actuate.endpoint.InvocationContext;
import org.springframework.boot.actuate.endpoint.Operation;
import org.springframework.boot.actuate.endpoint.OperationType;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.web.WebOperation;

import io.cote.aimgmt.probe.actuator.McpEndpointDiscoverer.McpOperation;

/**
 * Shared dumper logic, technology-agnostic: walk any catalog of endpoints, invoke each
 * one's root (no-selector) read operation, and assemble the results by endpoint id.
 *
 * The web and MCP dumpers differ only in WHICH catalog they pass in - so the walk lives
 * here once, and the two thin consumer beans (DumperEndpoint, DumperTool) just supply
 * their own endpoint collection.
 */
final class DumperSupport {

    private DumperSupport() {
    }

    /** Walk endpoints, invoke each root read op, skip "dumper" (self), assemble by id. */
    static Map<String, Object> dump(Collection<? extends ExposableEndpoint<? extends Operation>> endpoints) {
        Map<String, Object> all = new LinkedHashMap<>();
        for (ExposableEndpoint<? extends Operation> endpoint : endpoints) {
            String id = endpoint.getEndpointId().toString();
            if (id.equals("dumper")) {
                continue; // don't dump ourselves -> infinite recursion
            }
            endpoint.getOperations().stream()
                    .filter(op -> op.getType() == OperationType.READ && isRoot(op))
                    .findFirst()
                    .ifPresent(op -> put(all, id, () -> invokeRoot(op)));
        }
        return all;
    }

    /** Is this the endpoint's root op - callable with no mandatory selector? */
    private static boolean isRoot(Operation op) {
        if (op instanceof WebOperation web) {
            var predicate = web.getRequestPredicate();
            return predicate.getMatchAllRemainingPathSegmentsVariable() != null
                    || !predicate.getPath().contains("{");
        }
        if (op instanceof McpOperation mcp) {
            return mcp.rootInvokable();
        }
        return false;
    }

    /** Invoke an op at root, supplying an empty match-all selector if it needs one. */
    private static Object invokeRoot(Operation op) {
        if (op instanceof McpOperation mcp) {
            return mcp.invokeRoot();
        }
        if (op instanceof WebOperation web) {
            String matchAll = web.getRequestPredicate().getMatchAllRemainingPathSegmentsVariable();
            Map<String, Object> args = (matchAll == null) ? Map.of() : Map.of(matchAll, new String[0]);
            return web.invoke(new InvocationContext(SecurityContext.NONE, args));
        }
        return "unsupported operation type: " + op.getClass().getSimpleName();
    }

    private static void put(Map<String, Object> all, String id, Supplier<Object> call) {
        try {
            all.put(id, call.get());
        } catch (Exception ex) {
            all.put(id, "unavailable: " + ex.getMessage());
        }
    }
}
