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

    @McpTool(description = "Returns everything passed into the echo() tool that the tool can find.")
    Map<String, Object> echo(
            @McpToolParam(description = "Anything to return back.", required = false) Object argsIn,
            @McpToolParam(description = "Toggle to send back arguments AND all meta-info known. Defaults to false.", required = false) Boolean sendAllInfo,
            CallToolRequest request) {

        Map<String, Object> reponseToSend = new LinkedHashMap<>();
        Map<String, Object> requestToEcho = new LinkedHashMap<>();

        requestToEcho.put("name", request.name());
        requestToEcho.put("argsIn", request.arguments());
        // the framework can't handle the lack of an argument pased in and use
        // the default...makes sense, I guess...since you want method signature
        // matching for overloading...?
        if (Boolean.TRUE.equals(sendAllInfo)) {
            requestToEcho.put("meta",
                    Map.of("size", request.meta().size(),
                            "meta-fields", request.meta()));

        }

        reponseToSend.put("originalRequest", requestToEcho);
        return Collections.unmodifiableMap(reponseToSend);
    }

}
