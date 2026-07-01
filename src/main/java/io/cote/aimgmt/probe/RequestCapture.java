package io.cote.aimgmt.probe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Comprehensive capture of the inbound HTTP request. Used by Tracer.requestInfo() to give
 * an MCP client an X-ray of what the gateway forwarded to this server: who the end user is
 * (from non-credential forwarded headers), what tracing context arrived, every HTTP-layer
 * detail, every header NAME, every cookie name, every query parameter.
 *
 * Credentials are never touched. Bearer tokens, cookie values, and any header whose name
 * marks it as credential-bearing (Authorization, Proxy-Authorization, Cookie, Set-Cookie,
 * anything ending in -token / -secret / -api-key / -credential) are replaced with a fixed
 * "******" - constant length, so the original value's size (which is a side channel; JWTs
 * vs opaque bearers vs API keys have distinctive length signatures) is not leaked either.
 * The header NAME is always included; the value is what gets starred. No JWT decoding, no
 * parsing of any sensitive value.
 */
final class RequestCapture {

    private RequestCapture() {}

    static Map<String, Object> full(HttpServletRequest req) {
        var out = new LinkedHashMap<String, Object>();
        out.put("identity", identity(req));
        out.put("tracing", tracing(req));
        out.put("forwarded", forwarded(req));
        out.put("mcp", mcp(req));
        out.put("request", requestLine(req));
        out.put("headers", headers(req));
        out.put("cookies", cookies(req));
        out.put("queryParameters", queryParams(req));
        return out;
    }

    /** Best-guess end-user identity, surfaced at the top so callers don't have to grep
     *  headers. Resolved from non-credential forwarded headers ONLY (X-Forwarded-User,
     *  X-Forwarded-Email, X-Forwarded-Preferred-Username, X-User-Id, X-Auth-Request-User,
     *  X-Auth-Request-Email). Does NOT inspect Authorization, Cookie, or any token. If
     *  the upstream gateway terminates auth and forwards identity hints, you see them.
     *  If it only forwards the raw bearer, you see an empty endUser block - the bearer
     *  itself is masked in the headers section. */
    private static Map<String, Object> identity(HttpServletRequest req) {
        var out = new LinkedHashMap<String, Object>();

        String id = null;
        String source = null;
        for (String h : new String[] {
                "X-Forwarded-User", "X-Forwarded-Preferred-Username", "X-Forwarded-Email",
                "X-User-Id",
                "X-Auth-Request-User", "X-Auth-Request-Preferred-Username", "X-Auth-Request-Email" }) {
            String v = req.getHeader(h);
            if (v != null) { id = v; source = "header." + h; break; }
        }

        var enduser = new LinkedHashMap<String, Object>();
        if (id != null) {
            enduser.put("id", id);
            enduser.put("source", source);
        }
        if (!enduser.isEmpty()) out.put("endUser", enduser);

        // All forwarded-identity signals the gateway exposed, for transparency.
        var altSignals = new LinkedHashMap<String, Object>();
        for (String h : new String[] {
                "X-Forwarded-User", "X-Forwarded-Email", "X-Forwarded-Preferred-Username",
                "X-Forwarded-Groups",
                "X-User-Id",
                "X-Auth-Request-User", "X-Auth-Request-Email", "X-Auth-Request-Preferred-Username",
                "X-Auth-Request-Groups" }) {
            String v = req.getHeader(h);
            if (v != null) altSignals.put(h, v);
        }
        if (!altSignals.isEmpty()) out.put("forwardedIdentityHeaders", altSignals);

        // Note whether an Authorization header is present (scheme only, never the value).
        String authz = req.getHeader("Authorization");
        if (authz != null) {
            int sp = authz.indexOf(' ');
            String scheme = sp > 0 ? authz.substring(0, sp) : "(none)";
            out.put("authorizationScheme", scheme + " (value masked in headers section)");
        }

        return out;
    }

    /** Distributed-tracing headers. W3C trace context (traceparent / tracestate),
     *  Zipkin B3 in all its forms, generic request-id headers, CF's vcap request id. */
    private static Map<String, Object> tracing(HttpServletRequest req) {
        var out = new LinkedHashMap<String, Object>();
        putIfPresent(out, req, "traceparent");
        putIfPresent(out, req, "tracestate");

        var b3 = new LinkedHashMap<String, Object>();
        for (String h : new String[] { "X-B3-TraceId", "X-B3-SpanId", "X-B3-ParentSpanId",
                "X-B3-Sampled", "X-B3-Flags", "B3" }) {
            String v = req.getHeader(h);
            if (v != null) b3.put(h, v);
        }
        if (!b3.isEmpty()) out.put("b3", b3);

        putIfPresent(out, req, "X-Request-Id");
        putIfPresent(out, req, "X-Correlation-Id");
        putIfPresent(out, req, "X-Cloud-Trace-Context");
        putIfPresent(out, req, "X-Vcap-Request-Id");
        putIfPresent(out, req, "baggage");
        return out;
    }

    /** Proxy/gateway forwarding hints - what the gateway tells the upstream about the
     *  original client and intermediate hops. */
    private static Map<String, Object> forwarded(HttpServletRequest req) {
        var out = new LinkedHashMap<String, Object>();
        for (String h : new String[] {
                "Forwarded",
                "X-Forwarded-For", "X-Forwarded-Proto", "X-Forwarded-Host", "X-Forwarded-Port",
                "X-Forwarded-User", "X-Forwarded-Email", "X-Forwarded-Preferred-Username",
                "X-Forwarded-Groups",
                "X-Real-IP", "X-Real-Host",
                "Via" }) {
            String v = req.getHeader(h);
            if (v != null) out.put(h, v);
        }
        return out;
    }

    /** MCP-specific headers (anything starting with "mcp-", case-insensitive). The big
     *  one is Mcp-Session-Id - the Streamable HTTP transport's session correlator. */
    private static Map<String, Object> mcp(HttpServletRequest req) {
        var out = new LinkedHashMap<String, Object>();
        for (String name : Collections.list(req.getHeaderNames())) {
            if (name.toLowerCase().startsWith("mcp-")) {
                out.put(name, req.getHeader(name));
            }
        }
        return out;
    }

    /** Everything the Servlet API exposes about the request line, URL, transport, and
     *  Servlet-level auth state. */
    private static Map<String, Object> requestLine(HttpServletRequest req) {
        var out = new LinkedHashMap<String, Object>();
        out.put("method", req.getMethod());
        out.put("scheme", req.getScheme());
        out.put("serverName", req.getServerName());
        out.put("serverPort", req.getServerPort());
        out.put("uri", req.getRequestURI());
        out.put("url", req.getRequestURL().toString());
        out.put("queryString", req.getQueryString());
        out.put("protocol", req.getProtocol());
        out.put("contextPath", req.getContextPath());
        out.put("servletPath", req.getServletPath());
        out.put("pathInfo", req.getPathInfo());
        out.put("contentType", req.getContentType());
        out.put("contentLengthLong", req.getContentLengthLong());
        out.put("characterEncoding", req.getCharacterEncoding());
        out.put("locale", req.getLocale() == null ? null : req.getLocale().toString());
        out.put("secure", req.isSecure());
        out.put("remoteAddr", req.getRemoteAddr());
        out.put("remoteHost", req.getRemoteHost());
        out.put("remotePort", req.getRemotePort());
        out.put("remoteUser", req.getRemoteUser());           // populated by Servlet-level auth, usually null
        out.put("localAddr", req.getLocalAddr());
        out.put("localName", req.getLocalName());
        out.put("localPort", req.getLocalPort());
        out.put("authType", req.getAuthType());               // BASIC, DIGEST, FORM, CLIENT_CERT, or null
        out.put("requestedSessionId", req.getRequestedSessionId());
        out.put("dispatcherType", req.getDispatcherType() == null ? null : req.getDispatcherType().name());
        return out;
    }

    /** Every HTTP header NAME, verbatim. Multi-valued headers come back as a list. The
     *  value of any credential-bearing header is replaced with a fixed "******" so you
     *  see the header was present without leaking either the value or its length. */
    private static Map<String, Object> headers(HttpServletRequest req) {
        var out = new LinkedHashMap<String, Object>();
        for (String name : Collections.list(req.getHeaderNames())) {
            List<String> values = Collections.list(req.getHeaders(name));
            if (isCredentialHeader(name)) {
                List<String> masked = new ArrayList<>(values.size());
                for (int i = 0; i < values.size(); i++) masked.add(MASK);
                out.put(name, masked.size() == 1 ? masked.get(0) : masked);
            } else {
                out.put(name, values.size() == 1 ? values.get(0) : values);
            }
        }
        return out;
    }

    /** Does the header name indicate a credential value? Conservative: catches the obvious
     *  cases (Authorization, Cookie) and any name ending with -token / -secret / -api-key
     *  / -credential / -password. Identity-naming headers (X-Forwarded-User etc.) are NOT
     *  masked because they identify, not authenticate. */
    private static boolean isCredentialHeader(String name) {
        String l = name.toLowerCase();
        if (l.equals("authorization") || l.equals("proxy-authorization")) return true;
        if (l.equals("cookie") || l.equals("set-cookie")) return true;
        return l.endsWith("-token") || l.endsWith("-secret") || l.endsWith("-api-key")
                || l.endsWith("-credential") || l.endsWith("-password");
    }

    /** Fixed mask for every credential value. Length is intentionally NOT reflected -
     *  the original value's size is a side-channel we don't want to leak (e.g. JWT vs
     *  opaque bearer vs API key have distinctive length signatures). */
    private static final String MASK = "******";

    /** Cookies as a list with name + fixed-mask value + attributes. The value is replaced
     *  with a fixed "******" so you see the cookie was present without leaking value or
     *  length. */
    private static List<Map<String, Object>> cookies(HttpServletRequest req) {
        var out = new ArrayList<Map<String, Object>>();
        Cookie[] cs = req.getCookies();
        if (cs == null) return out;
        for (Cookie c : cs) {
            var ck = new LinkedHashMap<String, Object>();
            ck.put("name", c.getName());
            ck.put("value", MASK);
            if (c.getDomain() != null) ck.put("domain", c.getDomain());
            if (c.getPath() != null)   ck.put("path", c.getPath());
            if (c.getMaxAge() >= 0)    ck.put("maxAge", c.getMaxAge());
            ck.put("secure", c.getSecure());
            ck.put("httpOnly", c.isHttpOnly());
            out.add(ck);
        }
        return out;
    }

    /** Query parameters as a flat map; multi-valued params become a list. */
    private static Map<String, Object> queryParams(HttpServletRequest req) {
        var out = new LinkedHashMap<String, Object>();
        Map<String, String[]> params = req.getParameterMap();
        for (var e : params.entrySet()) {
            String[] vals = e.getValue();
            out.put(e.getKey(), vals.length == 1 ? vals[0] : Arrays.asList(vals));
        }
        return out;
    }

    private static void putIfPresent(Map<String, Object> out, HttpServletRequest req, String header) {
        String v = req.getHeader(header);
        if (v != null) out.put(header, v);
    }
}
