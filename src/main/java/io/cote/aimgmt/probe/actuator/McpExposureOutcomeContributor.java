package io.cote.aimgmt.probe.actuator;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.springframework.boot.actuate.autoconfigure.endpoint.condition.EndpointExposureOutcomeContributor;
import org.springframework.boot.actuate.autoconfigure.endpoint.expose.EndpointExposure;
import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.core.env.Environment;

/**
 * Makes MCP a first-class Actuator exposure technology, beside Web and JMX.
 *
 * Spring's @ConditionalOnAvailableEndpoint decides whether an @Endpoint bean is
 * even created, by asking every registered EndpointExposureOutcomeContributor
 * "is this endpoint exposed to your technology?". The built-in contributors answer
 * for web and jmx. This one answers for MCP, reading management.endpoints.mcp.exposure.*.
 *
 * Effect: an endpoint listed in management.endpoints.mcp.exposure.include becomes
 * available (its bean is created) EVEN IF web/jmx don't expose it. That's how the
 * MCP surface stops caring about web exposure.
 *
 * Registered via META-INF/spring.factories (NOT a @Component) - it's instantiated
 * by SpringFactoriesLoader with the Environment, during condition evaluation,
 * before beans exist.
 */
public class McpExposureOutcomeContributor implements EndpointExposureOutcomeContributor {

    private final List<String> include;
    private final List<String> exclude;

    public McpExposureOutcomeContributor(Environment environment) {
        this.include = list(environment, "management.endpoints.mcp.exposure.include");
        this.exclude = list(environment, "management.endpoints.mcp.exposure.exclude");
    }

    private static List<String> list(Environment environment, String key) {
        String value = environment.getProperty(key, "");
        return value.isBlank() ? List.of()
                : Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public ConditionOutcome getExposureOutcome(EndpointId endpointId, Set<EndpointExposure> exposures,
            ConditionMessage.Builder message) {
        String id = endpointId.toString();
        boolean excluded = exclude.contains("*") || exclude.contains(id);
        boolean included = !excluded && (include.contains("*") || include.contains(id));
        if (included) {
            return ConditionOutcome.match(message.because("exposed over MCP (management.endpoints.mcp.exposure)"));
        }
        // noMatch does NOT veto - the endpoint is available if ANY technology (web/jmx/mcp) exposes it.
        return ConditionOutcome.noMatch(message.because("not exposed over MCP"));
    }
}
