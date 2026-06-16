package io.cote.aimgmt.probe.actuator;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.endpoint.expose.IncludeExcludeEndpointFilter;
import org.springframework.boot.actuate.autoconfigure.endpoint.PropertiesEndpointAccessResolver;
import org.springframework.boot.actuate.endpoint.Access;
import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.actuate.endpoint.ExposableEndpoint;
import org.springframework.boot.actuate.endpoint.InvocationContext;
import org.springframework.boot.actuate.endpoint.Operation;
import org.springframework.boot.actuate.endpoint.OperationFilter;
import org.springframework.boot.actuate.endpoint.OperationType;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.annotation.DiscoveredOperationMethod;
import org.springframework.boot.actuate.endpoint.annotation.EndpointDiscoverer;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.invoke.OperationInvoker;
import org.springframework.boot.actuate.endpoint.invoke.OperationInvokerAdvisor;
import org.springframework.boot.actuate.endpoint.invoke.convert.ConversionServiceParameterValueMapper;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * MCP-flavoured Actuator endpoint discoverer - a peer to Spring's Web and JMX
 * discoverers. It reuses the shared @Endpoint scan in the base class and applies
 * its OWN exposure namespace: management.endpoints.mcp.exposure.include / .exclude.
 *
 * Turn it on with, e.g., management.endpoints.mcp.exposure.include=*  (default: nothing).
 * This is independent of web/jmx exposure - you can lock down HTTP and still drive
 * actuator over MCP.
 *
 * It's a plain Spring bean: inject it, call getEndpoints() (computed once, memoized).
 */
@Component
public class McpEndpointDiscoverer
        extends EndpointDiscoverer<McpEndpointDiscoverer.ExposableMcpEndpoint, McpEndpointDiscoverer.McpOperation> {

    public McpEndpointDiscoverer(ApplicationContext applicationContext, Environment environment,
            ObjectProvider<OperationInvokerAdvisor> invokerAdvisors) {
        super(applicationContext,
                new ConversionServiceParameterValueMapper(),
                invokerAdvisors.orderedStream().toList(),
                // our own exposure namespace; reads .include/.exclude from the Environment
                List.of(new IncludeExcludeEndpointFilter<>(ExposableMcpEndpoint.class, environment,
                        "management.endpoints.mcp.exposure")),
                // honor the standard, technology-agnostic access properties:
                // management.endpoint.<id>.access, management.endpoints.access.default / .max-permitted
                List.of(OperationFilter.byAccess(new PropertiesEndpointAccessResolver(environment))));
    }

    @Override
    protected ExposableMcpEndpoint createEndpoint(Object endpointBean, EndpointId id, Access defaultAccess,
            Collection<McpOperation> operations) {
        return new ExposableMcpEndpoint(id, defaultAccess, operations);
    }

    @Override
    protected McpOperation createOperation(EndpointId endpointId, DiscoveredOperationMethod operationMethod,
            OperationInvoker invoker) {
        // Root-callable = no MANDATORY selector. A scalar @Selector (e.g. metrics/{name})
        // requires an argument; a match-all @Selector (varargs/array, e.g. health/{*path})
        // is fine to call - but it must be PASSED as an empty value, not omitted.
        var params = operationMethod.getMethod().getParameters();
        boolean rootInvokable = Arrays.stream(params)
                .filter(p -> p.isAnnotationPresent(Selector.class))
                .allMatch(p -> p.isVarArgs() || p.getType().isArray());
        String matchAllSelector = Arrays.stream(params)
                .filter(p -> p.isAnnotationPresent(Selector.class) && (p.isVarArgs() || p.getType().isArray()))
                .map(java.lang.reflect.Parameter::getName)
                .findFirst()
                .orElse(null);
        return new McpOperation(operationMethod.getOperationType(), operationMethod.getMethod().getName(),
                rootInvokable, matchAllSelector, invoker);
    }

    @Override
    protected OperationKey createOperationKey(McpOperation operation) {
        // Must be unique per operation, or e.g. metrics' list + by-name ops collapse into one.
        return new OperationKey(operation.name(), () -> "MCP operation " + operation.name());
    }

    /** Our exposable endpoint: id (e.g. "health"), default access, and its operations. */
    public record ExposableMcpEndpoint(EndpointId id, Access access, Collection<McpOperation> operations)
            implements ExposableEndpoint<McpOperation> {

        @Override
        public EndpointId getEndpointId() {
            return id;
        }

        @Override
        public Access getDefaultAccess() {
            return access;
        }

        @Override
        public Collection<McpOperation> getOperations() {
            return operations;
        }
    }

    /** Our operation: wraps the framework invoker. rootInvokable = callable with no mandatory
     *  selector; matchAllSelector = name of an optional match-all selector param (or null). */
    public record McpOperation(OperationType type, String name, boolean rootInvokable, String matchAllSelector,
            OperationInvoker invoker) implements Operation {

        @Override
        public OperationType getType() {
            return type;
        }

        @Override
        public Object invoke(InvocationContext context) {
            return invoker.invoke(context);
        }

        /** Invoke at the root level, supplying an empty match-all selector if the op has one. */
        public Object invokeRoot() {
            Map<String, Object> args = (matchAllSelector == null)
                    ? Map.of()
                    : Map.of(matchAllSelector, new String[0]);
            return invoker.invoke(new InvocationContext(SecurityContext.NONE, args));
        }
    }
}
