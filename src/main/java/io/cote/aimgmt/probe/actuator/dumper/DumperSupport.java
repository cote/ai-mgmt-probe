package io.cote.aimgmt.probe.actuator.dumper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.boot.actuate.endpoint.ExposableEndpoint;
import org.springframework.boot.actuate.endpoint.InvocationContext;
import org.springframework.boot.actuate.endpoint.Operation;
import org.springframework.boot.actuate.endpoint.OperationType;
import org.springframework.boot.actuate.endpoint.OperationArgumentResolver;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.web.WebOperation;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.micrometer.metrics.actuate.endpoint.MetricsEndpoint;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusOutputFormat;

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

    /** Walk endpoints, invoke each root read op, assemble by id.
     *  No self-skip needed: the dumper is a tool/controller, not an Actuator @Endpoint,
     *  so it never appears in the catalog it walks. */
    static Map<String, Object> dump(Collection<? extends ExposableEndpoint<? extends Operation>> endpoints) {
        Map<String, Object> all = new LinkedHashMap<>();
        for (ExposableEndpoint<? extends Operation> endpoint : endpoints) {
            String id = endpoint.getEndpointId().toString();
            endpoint.getOperations().stream()
                    .filter(op -> op.getType() == OperationType.READ && isRoot(op))
                    .findFirst()
                    .ifPresent(op -> put(all, id, () -> invokeRoot(op)));
        }
        return all;
    }

    /** Resolve every metric's current value in one server-side pass. The metrics endpoint's
     *  root op only lists NAMES; a value needs a per-name selector call. Doing the fan-out
     *  here (one dumper invocation) beats an MCP client firing one tool call per metric.
     *  When humanFriendly, each measurement also gets a readable "humanFriendly" string,
     *  formatted from the metric's base unit (bytes -> MB, seconds -> ms/h, else grouped). */
    static Map<String, Object> metricValues(MetricsEndpoint metrics, boolean humanFriendly) {
        var out = new LinkedHashMap<String, Object>();
        for (String name : metrics.listNames().getNames()) {
            try {
                MetricsEndpoint.MetricDescriptor m = metrics.metric(name, null);
                if (m == null) {
                    out.put(name, null);
                } else if (!humanFriendly) {
                    out.put(name, m.getMeasurements());
                } else {
                    String unit = m.getBaseUnit();
                    var samples = new ArrayList<Map<String, Object>>();
                    for (var s : m.getMeasurements()) {
                        var sm = new LinkedHashMap<String, Object>();
                        sm.put("statistic", s.getStatistic());
                        sm.put("value", s.getValue());
                        sm.put("humanFriendly", humanize(s.getValue(), unit));
                        samples.add(sm);
                    }
                    out.put(name, samples);
                }
            } catch (Exception ex) {
                out.put(name, "unavailable: " + ex.getMessage());
            }
        }
        return out;
    }

    /** Readable form of a metric value, using its base unit when known. */
    private static String humanize(double v, String unit) {
        if (unit != null) {
            String u = unit.toLowerCase();
            if (u.equals("bytes")) {
                return bytes(v);
            }
            if (u.equals("seconds")) {
                return duration(v);
            }
        }
        return number(v) + (unit != null && !unit.isBlank() ? " " + unit : "");
    }

    /** Bytes -> B/KB/MB/GB/TB (1024-based). */
    private static String bytes(double v) {
        String[] units = { "B", "KB", "MB", "GB", "TB", "PB" };
        double a = Math.abs(v);
        int i = 0;
        while (a >= 1024 && i < units.length - 1) {
            a /= 1024;
            i++;
        }
        return (i == 0) ? String.format("%,d B", (long) v)
                : String.format("%.2f %s", v / Math.pow(1024, i), units[i]);
    }

    /** Seconds -> ns/us/ms/s/min/h/d, picking the most readable unit. */
    private static String duration(double s) {
        double a = Math.abs(s);
        if (a == 0) {
            return "0 s";
        }
        if (a < 1e-6) {
            return trim(s * 1e9) + " ns";
        }
        if (a < 1e-3) {
            return trim(s * 1e6) + " us";
        }
        if (a < 1) {
            return trim(s * 1e3) + " ms";
        }
        if (a < 60) {
            return trim(s) + " s";
        }
        if (a < 3600) {
            return trim(s / 60) + " min";
        }
        if (a < 86400) {
            return trim(s / 3600) + " h";
        }
        return trim(s / 86400) + " d";
    }

    /** Plain number: thousands separators, plus a short k/M/G/T magnitude for big values. */
    private static String number(double v) {
        String grouped = (v == Math.rint(v) && !Double.isInfinite(v))
                ? String.format("%,d", (long) v)
                : trimGrouped(v);
        return (Math.abs(v) >= 1000) ? grouped + " (" + magnitude(v) + ")" : grouped;
    }

    private static String magnitude(double v) {
        double a = Math.abs(v);
        if (a >= 1e12) {
            return trim(v / 1e12) + "T";
        }
        if (a >= 1e9) {
            return trim(v / 1e9) + "G";
        }
        if (a >= 1e6) {
            return trim(v / 1e6) + "M";
        }
        if (a >= 1e3) {
            return trim(v / 1e3) + "k";
        }
        return trim(v);
    }

    /** Up to 3 decimals, trailing zeros stripped. */
    private static String trim(double v) {
        String s = String.format("%.3f", v);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    /** Like trim() but with thousands separators on the integer part. */
    private static String trimGrouped(double v) {
        String s = String.format("%,.3f", v);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
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
            // Some web ops take args the web framework normally injects per-request:
            // health needs a WebServerNamespace; prometheus needs an output format
            // (usually negotiated from the Accept header). Supply defaults so direct
            // invocation here doesn't fail.
            return web.invoke(new InvocationContext(SecurityContext.NONE, args,
                    OperationArgumentResolver.of(WebServerNamespace.class, () -> WebServerNamespace.SERVER),
                    OperationArgumentResolver.of(PrometheusOutputFormat.class,
                            () -> PrometheusOutputFormat.CONTENT_TYPE_004)));
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
