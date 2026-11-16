// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Mention;
import cc.jumpkick.guard.facts.AnnotationFacts;
import cc.jumpkick.guard.facts.CallSite;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * HTTP routes in compiled classes. A handler's route is declared by its framework's annotations
 * (Spring's {@code @RequestMapping} family, JAX-RS {@code @Path} with {@code @GET} and the rest,
 * Micronaut's {@code @Controller} with {@code @Get} and the rest), a class's paths prefixing its
 * methods'; only literal paths count, so a path built at run time gives no route. A client's use is
 * a literal path passed to a test or client call that names its method (MockMvc, RestAssured,
 * {@code @HttpExchange} interfaces), or any other string literal that is a path; the linker links
 * each only to a route a handler declares.
 */
final class JvmRoutes {

    private JvmRoutes() {}

    /**
     * One route a method's annotations name: its method ({@code *} for any) and path as written, and
     * whether a client calls it ({@code @HttpExchange}) rather than a handler serving it.
     */
    record Route(String method, String path, boolean client) {
        String name() {
            return method + " " + path;
        }
    }

    private static final String SPRING = "org.springframework.web.bind.annotation.";
    private static final String SPRING_CLIENT = "org.springframework.web.service.annotation.";
    private static final String MICRONAUT = "io.micronaut.http.annotation.";
    private static final List<String> JAX_RS = List.of("jakarta.ws.rs.", "javax.ws.rs.");

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    /** Spring's shortcut annotations by the method they fix. */
    private static final Map<String, String> SPRING_SHORTCUTS = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH");

    private static final Map<String, String> SPRING_EXCHANGES = Map.of(
            "GetExchange", "GET",
            "PostExchange", "POST",
            "PutExchange", "PUT",
            "DeleteExchange", "DELETE",
            "PatchExchange", "PATCH");

    /** Test and client builders whose static or instance method names the HTTP method, by internal owner name. */
    private static final Set<String> CLIENT_OWNERS = Set.of(
            "org/springframework/test/web/servlet/request/MockMvcRequestBuilders",
            "org/springframework/test/web/servlet/MockMvc",
            "io/restassured/specification/RequestSender",
            "io/restassured/RestAssured",
            "io/restassured/specification/RequestSpecification",
            "io/restassured/response/ValidatableResponse");

    /**
     * The path prefixes a type's annotations give its handlers: Spring {@code @RequestMapping}, JAX-RS
     * {@code @Path}, Micronaut {@code @Controller}; one empty prefix when none.
     */
    static List<String> prefixes(List<AnnotationFacts> type) {
        List<String> out = new ArrayList<>();
        for (AnnotationFacts a : type) {
            String t = a.typeName();
            if (t.equals(SPRING + "RequestMapping") || t.equals(SPRING_CLIENT + "HttpExchange")) {
                out.addAll(paths(a, "value", "path", "url"));
            } else if (jaxRs(t, "Path") || t.equals(MICRONAUT + "Controller")) {
                out.addAll(paths(a, "value"));
            }
        }
        return out.isEmpty() ? List.of("") : out;
    }

    /** True when the type's annotations make its routes a client's ({@code @HttpExchange}), not a handler's. */
    static boolean client(List<AnnotationFacts> type) {
        for (AnnotationFacts a : type) if (a.typeName().equals(SPRING_CLIENT + "HttpExchange")) return true;
        return false;
    }

    /**
     * The routes a method's annotations declare under {@code prefixes}: none for a method with no
     * mapping, or a JAX-RS {@code @Path} without a method annotation (a sub-resource locator).
     */
    static List<Route> routes(List<String> prefixes, boolean clientType, List<AnnotationFacts> method) {
        List<String> methods = new ArrayList<>();
        boolean client = clientType;
        List<String> paths = new ArrayList<>();
        boolean mapped = false;
        boolean jaxRsPath = false;
        for (AnnotationFacts a : method) {
            String t = a.typeName();
            String simple = t.substring(t.lastIndexOf('.') + 1);
            if (t.equals(SPRING + "RequestMapping") || t.equals(SPRING_CLIENT + "HttpExchange")) {
                mapped = true;
                client |= t.startsWith(SPRING_CLIENT);
                paths.addAll(paths(a, "value", "path", "url"));
                for (String m : a.values().getOrDefault("method", List.of())) methods.add(m.toUpperCase(Locale.ROOT));
            } else if (t.startsWith(SPRING) && SPRING_SHORTCUTS.containsKey(simple)) {
                mapped = true;
                methods.add(SPRING_SHORTCUTS.get(simple));
                paths.addAll(paths(a, "value", "path"));
            } else if (t.startsWith(SPRING_CLIENT) && SPRING_EXCHANGES.containsKey(simple)) {
                mapped = true;
                client = true;
                methods.add(SPRING_EXCHANGES.get(simple));
                paths.addAll(paths(a, "value", "url"));
            } else if (jaxRs(t, "Path")) {
                jaxRsPath = true;
                paths.addAll(paths(a, "value"));
            } else if (JAX_RS.stream().anyMatch(t::startsWith) && METHODS.contains(simple)) {
                mapped = true;
                methods.add(simple);
            } else if (t.startsWith(MICRONAUT) && METHODS.contains(simple.toUpperCase(Locale.ROOT))) {
                mapped = true;
                methods.add(simple.toUpperCase(Locale.ROOT));
                paths.addAll(paths(a, "value", "uri"));
            }
        }
        if (!mapped) return List.of();
        if (jaxRsPath && methods.isEmpty()) return List.of();
        if (methods.isEmpty()) methods.add("*");
        if (paths.isEmpty()) paths.add("");
        List<Route> out = new ArrayList<>();
        for (String prefix : prefixes) {
            for (String path : paths) {
                for (String m : methods) out.add(new Route(m, join(prefix, path), client));
            }
        }
        return out;
    }

    /**
     * The route uses one call site makes: a literal path passed to a client or test call whose name is
     * the HTTP method ({@link Mention.Kind#ROUTE_CALL}), else every literal that is a path ({@link
     * Mention.Kind#ROUTE}).
     */
    static List<Mention> uses(String from, CallSite site) {
        List<Mention> out = new ArrayList<>();
        String method = site.name().toUpperCase(Locale.ROOT);
        boolean call = CLIENT_OWNERS.contains(site.owner()) && METHODS.contains(method);
        for (String literal : site.literals()) {
            if (!path(literal)) continue;
            out.add(new Mention(
                    from,
                    call ? Mention.Kind.ROUTE_CALL : Mention.Kind.ROUTE,
                    (call ? method : "*") + " " + literal,
                    site.line()));
        }
        return out;
    }

    /**
     * True for a literal shaped as an absolute URL path with a literal first segment: {@code
     * /api/hotels}, {@code /orders/{id}}. A bare {@code /}, one ending in {@code /}, a file path with an
     * extension's dot in its last segment, or anything with a space is not one.
     */
    static boolean path(String s) {
        if (s.length() < 2 || s.charAt(0) != '/' || s.charAt(1) == '/' || s.charAt(1) == '{') return false;
        // A trailing slash is the head of a path built at run time: "/orders/" + id.
        if (s.endsWith("/")) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '\\' || c == '%' && i == s.length() - 1) return false;
        }
        String last = s.substring(s.lastIndexOf('/') + 1);
        return !last.contains(".");
    }

    /** {@code prefix} and {@code path} joined by one {@code /}; each may be empty. */
    private static String join(String prefix, String path) {
        String p = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        String q = path.startsWith("/") ? path.substring(1) : path;
        if (q.isEmpty()) return p.isEmpty() ? "/" : p.startsWith("/") ? p : "/" + p;
        String joined = p + "/" + q;
        return joined.startsWith("/") ? joined : "/" + joined;
    }

    private static List<String> paths(AnnotationFacts a, String... attributes) {
        List<String> out = new ArrayList<>();
        for (String attr : attributes) out.addAll(a.values().getOrDefault(attr, List.of()));
        return out;
    }

    private static boolean jaxRs(String type, String simple) {
        return JAX_RS.stream().anyMatch(p -> type.equals(p + simple));
    }
}
