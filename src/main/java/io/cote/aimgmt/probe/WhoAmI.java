package io.cote.aimgmt.probe;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

/**
 * Returns the decoded, validated claims from the inbound JWT.
 *
 * Spring Security's OAuth2 Resource Server parses the Authorization bearer, validates
 * the signature against the configured identity provider's JWKS (see SecurityConfig for
 * the property list; any OIDC-compliant IdP works - Tanzu UAA, Keycloak, Auth0, Okta,
 * Azure AD, Cognito, ...), validates exp/iat, and stores the resulting
 * JwtAuthenticationToken on the SecurityContext for the current request. This tool
 * pulls it from there and returns only the payload claims (sub, user_name, email,
 * scope, ...). The raw bearer token and signature are never exposed.
 *
 * Why SecurityContextHolder instead of @AuthenticationPrincipal: Spring AI MCP's
 * schema generator treats @AuthenticationPrincipal parameters as MCP tool inputs,
 * exposing Jwt fields like tokenValue as required call arguments. Pulling from the
 * holder takes no method parameter, so the tool schema stays empty.
 */
@Service
public class WhoAmI {

    @McpTool(description = """
        Returns the validated JWT claims for the calling user: who you are (sub,
        user_name, preferred_username, email), which OAuth client made the request
        (client_id, azp), what scopes you have (scope), and the token's lifecycle
        (iss, aud, exp, iat). Never returns the raw bearer token or its signature.

        Cloud: Spring Security validates the JWT against the configured identity
        provider's JWKS before this tool runs; the validated claims are returned.

        Local: no identity provider is configured, so there's no Jwt principal.
        Returns a note explaining what's present on the SecurityContext instead.
        """)
    Map<String, Object> whoami() {
        var out = new LinkedHashMap<String, Object>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            out.put("note", "no Authentication on the SecurityContext - request slipped through outside Spring Security");
            return out;
        }
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            out.put("note", "Authentication is present but not a JwtAuthenticationToken - probably running locally without OAuth (anonymous or other auth type)");
            out.put("authenticationType", auth.getClass().getName());
            out.put("principal", String.valueOf(auth.getName()));
            return out;
        }
        Jwt jwt = jwtAuth.getToken();
        out.put("claims", jwt.getClaims());
        return out;
    }
}
