// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gradle {@code .module} slice for KMP root redirects: pick the {@code java-runtime} variant for
 * this build's {@code org.gradle.jvm.environment}. Unparseable → no redirect.
 */
public final class GradleModuleMetadata {

    /** The POM comment marker Gradle writes on every module published with a {@code .module} file. */
    public static final String POM_MARKER = "do_not_remove: published-with-gradle-metadata";

    /** A variant redirect: this module's classes actually live at {@code module}:{@code version}. */
    public record Redirect(String group, String module, String version) {}

    /**
     * Process-wide parse memo keyed by absolute path + size + mtime. First-in-process Android locks
     * re-read the same {@code .module} files across scopes; MiniJson dominates without this.
     */
    private static final ConcurrentHashMap<String, GradleModuleMetadata> PARSE_CACHE = new ConcurrentHashMap<>();

    private static final int PARSE_CACHE_MAX = 4_096;

    private final List<Map<String, Object>> variants;

    private GradleModuleMetadata(List<Map<String, Object>> variants) {
        this.variants = variants;
    }

    /** Test seam. */
    public static void clearParseCache() {
        PARSE_CACHE.clear();
    }

    @SuppressWarnings("unchecked")
    public static GradleModuleMetadata parse(Path moduleFile) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(moduleFile, BasicFileAttributes.class);
        String cacheKey = moduleFile.toAbsolutePath()
                + "\0"
                + attrs.size()
                + "\0"
                + attrs.lastModifiedTime().toMillis();
        GradleModuleMetadata hit = PARSE_CACHE.get(cacheKey);
        if (hit != null) return hit;

        String text = Files.readString(moduleFile, StandardCharsets.UTF_8);
        // KMP roots publish platform redirects via available-at. Most Gradle .module files are
        // variant catalogs without redirects — skip MiniJson entirely (dominates first-in-process
        // Android locks: hundreds of 20–70 KB parses that always yield empty).
        if (!text.contains("\"available-at\"")) {
            GradleModuleMetadata empty = new GradleModuleMetadata(List.of());
            remember(cacheKey, empty);
            return empty;
        }

        Object root = MiniJson.parse(text);
        if (!(root instanceof Map<?, ?> map)) throw new IOException("not a GMM document: " + moduleFile);
        Object variants = map.get("variants");
        GradleModuleMetadata parsed = !(variants instanceof List<?> list)
                ? new GradleModuleMetadata(List.of())
                : new GradleModuleMetadata((List<Map<String, Object>>) (List<?>) list);
        remember(cacheKey, parsed);
        return parsed;
    }

    private static void remember(String cacheKey, GradleModuleMetadata parsed) {
        if (PARSE_CACHE.size() < PARSE_CACHE_MAX) {
            PARSE_CACHE.putIfAbsent(cacheKey, parsed);
        }
    }

    /**
     * The runtime redirect for {@code jvmEnvironment} ({@code "android"} / {@code "standard-jvm"}),
     * falling back to the other environment, or empty when this module publishes its runtime
     * in-place (no {@code available-at} on the matching variant).
     */
    public Optional<Redirect> runtimeRedirect(String jvmEnvironment) {
        String fallback = "android".equals(jvmEnvironment) ? "standard-jvm" : "android";
        return runtimeRedirectFor(jvmEnvironment).or(() -> runtimeRedirectFor(fallback));
    }

    private Optional<Redirect> runtimeRedirectFor(String jvmEnvironment) {
        for (Map<String, Object> variant : variants) {
            if (!(variant.get("attributes") instanceof Map<?, ?> attrs)) continue;
            if (!"java-runtime".equals(attrs.get("org.gradle.usage"))) continue;
            // Sources/javadoc variants also declare java-runtime usage — category tells them apart.
            Object category = attrs.get("org.gradle.category");
            if (category != null && !"library".equals(category)) continue;
            // Gradle's compatibility rule: an ABSENT org.gradle.jvm.environment is
            // standard-jvm-compatible. androidx declares the attribute explicitly; kotlinx
            // (datetime, coroutines, serialization) omits it on the jvm variants.
            Object env = attrs.get("org.gradle.jvm.environment");
            boolean matches = env == null ? "standard-jvm".equals(jvmEnvironment) : env.equals(jvmEnvironment);
            if (!matches) continue;
            // The FIRST matching runtime variant decides. In-place files (no available-at) mean
            // this module ships its own jar — scanning on would misread later FEATURE variants
            // (grails-core's cliRuntimeElements → grails-core-cli) as a KMP-root redirect.
            return Optional.ofNullable(availableAt(variant));
        }
        return Optional.empty();
    }

    /**
     * Every module this root's variants redirect to — the set of platform-artifact siblings whose
     * POM-fallback dependency edges must be dropped when a redirect is taken (keeping the -jvm
     * fallback alongside the -android redirect would recreate the double-define).
     */
    public Set<String> redirectTargetModules() {
        Set<String> out = new LinkedHashSet<>();
        for (Map<String, Object> variant : variants) {
            Redirect r = availableAt(variant);
            if (r != null) out.add(r.group() + ":" + r.module());
        }
        return out;
    }

    private static Redirect availableAt(Map<String, Object> variant) {
        if (!(variant.get("available-at") instanceof Map<?, ?> at)) return null;
        Object group = at.get("group");
        Object module = at.get("module");
        Object version = at.get("version");
        if (group instanceof String g && module instanceof String m && version instanceof String v) {
            return new Redirect(g, m, v);
        }
        return null;
    }
}
