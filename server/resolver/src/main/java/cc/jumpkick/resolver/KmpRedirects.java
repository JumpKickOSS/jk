// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.repo.GradleModuleMetadata;
import cc.jumpkick.repo.RepoGroup;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KMP root-module redirects via Gradle {@code .module} metadata for this build's {@code
 * org.gradle.jvm.environment}. Root becomes a POM-only alias; platform target is the classpath
 * artifact. Missing/unparseable metadata → no redirect (plain Maven).
 */
public final class KmpRedirects {

    /** No-op instance for callers with no repo context (git/path-only locks). */
    public static final KmpRedirects NONE = new KmpRedirects(null, "standard-jvm");

    /** A resolved root: the runtime target plus every platform sibling the POM must not follow. */
    public record Selection(GradleModuleMetadata.Redirect target, Set<String> allTargets) {}

    private final RepoGroup repos;
    private final String jvmEnvironment;
    private final Map<String, Optional<Selection>> cache = new ConcurrentHashMap<>();

    /**
     * Process-wide selection memo: Gradle module metadata is immutable per GAV on disk, and the
     * redirect for a given {@code jvmEnvironment} does not change mid-process. Warm re-locks
     * (and the three scope solves) used to re-parse hundreds of {@code .module} files every time.
     * Keyed by {@code env + '\0' + module@version}.
     */
    private static final Map<String, Optional<Selection>> PROCESS_CACHE = new ConcurrentHashMap<>();

    /** Test seam: drop process-wide selection memo. */
    public static void clearProcessCache() {
        PROCESS_CACHE.clear();
    }

    /**
     * Every non-selected platform sibling of every redirected root seen this resolve (A5f
     * finding 20). A platform artifact's own POM can name a SIBLING concretely
     * (datastore-core-okio-jvm → datastore-core-jvm) — an edge that is variant-aware in GMM
     * space but bypasses the root's selection in POM space and double-defines every class at
     * dex. The lock excludes these globally; the selected sibling supplies the classes.
     */
    private final Map<String, String> droppedSiblings = new ConcurrentHashMap<>();

    /**
     * Non-selected platform sibling → the selected sibling that replaces it (grows as
     * selections happen). Exclusion applies only when the selected side is actually in the
     * resolution — a graph reaching a platform artifact with no redirected root keeps it.
     */
    public Map<String, String> droppedSiblings() {
        return java.util.Collections.unmodifiableMap(droppedSiblings);
    }

    public KmpRedirects(RepoGroup repos, String jvmEnvironment) {
        this.repos = repos;
        this.jvmEnvironment = jvmEnvironment == null || jvmEnvironment.isBlank() ? "standard-jvm" : jvmEnvironment;
    }

    /** The redirect selection for {@code module}:{@code version}, or empty when none applies. */
    public Optional<Selection> selectionFor(String module, String version) {
        if (repos == null) return Optional.empty();
        // Fast reject: groups that never publish Gradle module metadata / KMP roots. Avoids a
        // tryFetchPom + head scan on every Guava/OkHttp/etc expand (hundreds per Android lock).
        if (!mayPublishGradleMetadata(module)) return Optional.empty();
        // Key by GA@ver — type/classifier (jar vs aar) share one POM/.module. BOM warm uses
        // default jar: keys; AndroidX solver packages are often aar: — separate keys forced a
        // full cold re-parse of every KMP root on first-in-process locks.
        String gaKey = gaAt(module, version);
        Optional<Selection> local = cache.get(gaKey);
        if (local != null) {
            local.ifPresent(this::rememberDropped);
            return local;
        }
        String processKey = jvmEnvironment + "\0" + gaKey;
        // Single-flight: concurrent PubGrub prefetches must not re-parse the same .module.
        long t0 = cc.jumpkick.resolve.ResolveProfile.on() ? System.nanoTime() : 0L;
        Optional<Selection> found = PROCESS_CACHE.computeIfAbsent(processKey, k -> lookup(module, version));
        cache.put(gaKey, found);
        found.ifPresent(this::rememberDropped);
        if (cc.jumpkick.resolve.ResolveProfile.on() && t0 != 0L) {
            // Count wall only when we may have done work (process miss is still inside computeIfAbsent).
            cc.jumpkick.resolve.ResolveProfile.kmp(System.nanoTime() - t0);
        }
        return found;
    }

    private static String gaAt(String module, String version) {
        try {
            if (PackageId.isMavenPackageKey(module)) {
                return PackageId.parse(module).ga() + "@" + version;
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return module + "@" + version;
    }

    private void rememberDropped(Selection selection) {
        String selected =
                PackageId.ofGa(selection.target().group() + ":" + selection.target().module()).key();
        for (String sibling : selection.allTargets()) {
            String siblingKey = PackageId.ofGa(sibling).key();
            if (!siblingKey.equals(selected)) droppedSiblings.put(siblingKey, selected);
        }
    }

    private Optional<Selection> lookup(String module, String version) {
        try {
            PackageId id = PackageId.parse(module);
            Coordinate coord = id.withVersion(version);
            // The POM is already disk-cached by the dependency walk; the marker comment is the
            // cheap gate that keeps non-KMP modules to zero extra fetches. Only the head of the
            // file is scanned — Gradle writes the marker near the top; reading multi-MB POMs as
            // full strings dominated warm Android locks (hundreds of KMP roots).
            var pomHit = repos.tryFetchPom(coord);
            if (pomHit.isEmpty()) return Optional.empty();
            if (!pomHasGradleMetadataMarker(pomHit.get().fetched().cachePath())) return Optional.empty();

            Coordinate moduleCoord = new Coordinate(coord.group(), coord.artifact(), coord.version(), null, "module");
            var moduleHit = repos.tryFetchArtifact(moduleCoord);
            if (moduleHit.isEmpty()) return Optional.empty();

            GradleModuleMetadata gmm =
                    GradleModuleMetadata.parse(moduleHit.get().fetched().cachePath());
            return gmm.runtimeRedirect(jvmEnvironment)
                    .map(target -> new Selection(target, gmm.redirectTargetModules()));
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Optional.empty(); // fail-soft: plain-Maven view
        }
    }

    /** True when the POM head contains Gradle's published-with-gradle-metadata marker. */
    static boolean pomHasGradleMetadataMarker(java.nio.file.Path pomPath) throws IOException {
        // Marker sits in the first few KB of every Gradle-published POM.
        final int headBytes = 8192;
        byte[] buf = new byte[headBytes];
        int n;
        try (var in = Files.newInputStream(pomPath)) {
            n = in.read(buf);
        }
        if (n <= 0) return false;
        String head = new String(buf, 0, n, StandardCharsets.UTF_8);
        return head.contains(GradleModuleMetadata.POM_MARKER);
    }

    /**
     * Groups that commonly publish {@code .module} files (Gradle metadata / KMP). Everything else
     * is plain Maven — skip the marker scan. Broad enough for AndroidX, Kotlin, Compose, and the
     * usual multiplatform ecosystem; uncommon KMP roots still work if they land under these
     * prefixes or are added later.
     */
    static boolean mayPublishGradleMetadata(String moduleKey) {
        String ga = moduleKey;
        try {
            if (PackageId.isMavenPackageKey(moduleKey)) {
                ga = PackageId.parse(moduleKey).ga();
            }
        } catch (RuntimeException ignored) {
            // fall through with raw key
        }
        int colon = ga.indexOf(':');
        String group = colon > 0 ? ga.substring(0, colon) : ga;
        return group.startsWith("androidx.")
                || group.startsWith("org.jetbrains.")
                || group.startsWith("com.google.android.")
                || group.startsWith("com.android.")
                || group.startsWith("app.cash.")
                || group.startsWith("co.touchlab.")
                || group.startsWith("com.russhwolf.")
                || group.startsWith("io.ktor.")
                || group.startsWith("io.github.oshai.")
                || group.startsWith("org.koin.")
                || group.startsWith("com.arkivanov.")
                || group.startsWith("cafe.adriel.voyager")
                || group.equals("com.squareup.okio")
                || group.startsWith("com.squareup.okio.")
                || group.startsWith("org.mongodb.")
                || group.startsWith("io.insert-koin.")
                || group.startsWith("media.kamel.")
                || group.startsWith("com.moriatsushi.")
                || group.startsWith("com.github.skydoves.")
                || group.startsWith("io.coil-kt")
                || group.startsWith("com.google.accompanist");
    }
}
