package io.cote.aimgmt.probe;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Gets ahold of the JWT stuff.
 *
 * Two filter chains, one per runtime profile:
 *
 * - Local (no "cloud" profile): permitAll. No identity provider available
 * locally;
 * the probe is meant to be poked at freely from an MCP Inspector or unit test
 * on
 * your machine.
 *
 * - Cloud (SPRING_PROFILES_ACTIVE=cloud): require a valid JWT on /mcp/**. Any
 * upstream
 * that terminates OIDC and forwards the bearer will work; we re-validate here
 * so the
 * probe is safe even if someone reaches it without going through that upstream.
 * Actuator endpoints stay open so the platform's health/metric scrapes still
 * work.
 *
 * CSRF disabled in both: this is a stateless JSON-RPC server, not a browser
 * app.
 *
 * Configuration properties (set in application-cloud.properties for the cloud
 * profile):
 *
 * spring.security.oauth2.resourceserver.jwt.jwk-set-uri
 * Direct URL to the identity provider's JWKS endpoint. Fastest path; skips OIDC
 * discovery. Example values for common IdPs:
 * Tanzu UAA: https://login.sys.<foundation>/token_keys
 * Keycloak: https://<host>/realms/<realm>/protocol/openid-connect/certs
 * Auth0: https://<tenant>.auth0.com/.well-known/jwks.json
 * Cognito:
 * https://cognito-idp.<region>.amazonaws.com/<pool-id>/.well-known/jwks.json
 * Azure AD: https://login.microsoftonline.com/<tenant>/discovery/v2.0/keys
 *
 * spring.security.oauth2.resourceserver.jwt.issuer-uri
 * Alternative: the OIDC issuer base URL. Spring auto-fetches
 * /.well-known/openid-configuration to find the JWKS. Use this OR jwk-set-uri,
 * not
 * both. Also validates the JWT's iss claim against this value.
 *
 * spring.security.oauth2.resourceserver.jwt.audiences
 * Optional list of accepted "aud" claim values. Defense in depth if you want to
 * reject tokens issued for a different client.
 */
@Configuration
class SecurityConfig {

    @Bean
    @Profile("!cloud")
    SecurityFilterChain localChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Profile("cloud")
    SecurityFilterChain cloudChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(a -> a
                        // Cloud Foundry requires the actutor path to be open
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
                .build();
    }
}
