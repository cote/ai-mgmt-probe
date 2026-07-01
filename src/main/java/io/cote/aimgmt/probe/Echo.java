package io.cote.aimgmt.probe;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

@Component
public class Echo {

    /**
     *  Returns everything passed into the tool call, optionally adding in mea data from {@CallToolRequest#meta()}
     * 
     * @param argsIn whatever was passed in from the MCP client.
     * @param sendAllInfo true to also send meta information 
     * @param request the {@CallToolRequest}
     * @return what was sent in
     */
    @McpTool(description = "Returns everything passed into the echo() tool that the tool can find.")
    Map<String, Object> echo(
            @McpToolParam(description = "Anything to return back.", required = false) Object argsIn,
            @McpToolParam(description = "Toggle to send back arguments AND all meta-info known. Defaults to false.", required = false) Boolean sendAllInfo,
            CallToolRequest request) {

        Map<String, Object> responseToSend = new LinkedHashMap<>();
        Map<String, Object> requestToEcho = new LinkedHashMap<>();

        requestToEcho.put("name", request.name());
        requestToEcho.put("argsIn", request.arguments());
        // Boxed Boolean so a missing sendAllInfo arg is null, not an NPE: an optional
        // @McpToolParam isn't supplied when absent, and a primitive would fail to bind.
        if (Boolean.TRUE.equals(sendAllInfo)) {
            requestToEcho.put("meta",
                    Map.of("size", request.meta().size(),
                            "meta-fields", request.meta()));
        }

        responseToSend.put("originalRequest", requestToEcho);
        return Collections.unmodifiableMap(responseToSend);
    }

}
