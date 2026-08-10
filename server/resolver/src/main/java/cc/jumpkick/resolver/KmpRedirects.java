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
     * Keyed by the repositories asked <em>and</em> {@code env + module@version} — which {@code
     * .module} is fetched depends on the repo set. No TTL: release GAV content is immutable; force
     * / {@link #clearProcessCache} drop the memo.
     *
     * <p>Values are futures, not results (JK-1785): the winner parks a future and runs the
     * network lookup <em>outside</em> the map, so unrelated keys sharing a CHM bin never
     * serialize behind a slow {@code .module} fetch the way {@code computeIfAbsent} made them.
     * Completed futures stay as the memo. Bounded like the sibling process memos; past the cap
     * lookups run uncached.
     */
    private static final Map<String, java.util.concurrent.CompletableFuture<Optional<Selection>>> PROCESS_CACHE =
            new ConcurrentHashMap<>();

    private static final int PROCESS_CACHE_MAX = 8_192;

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
        // Authoritative gate is the POM Gradle-metadata marker (see lookup) — not a group
        // allowlist. Missing a KMP redirect is a classpath bug; process memo makes plain-Maven
        // GAs cheap after the first head-scan miss.
        // Key by GA@ver — type/classifier (jar vs aar) share one POM/.module. BOM warm uses
        // default jar: keys; AndroidX solver packages are often aar: — separate keys forced a
        // full cold re-parse of every KMP root on first-in-process locks.
        String gaKey = gaAt(module, version);
        Optional<Selection> local = cache.get(gaKey);
        if (local != null) {
            local.ifPresent(this::rememberDropped);
            return local;
        }
        String processKey = repos.processIdentity() + "\0" + jvmEnvironment + "\0" + gaKey;
        // Single-flight: concurrent PubGrub prefetches must not re-parse the same .module.
        long t0 = cc.jumpkick.resolve.ResolveProfile.on() ? System.nanoTime() : 0L;
        Optional<Selection> found = processMemoized(processKey, module, version);
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
        String selected = PackageId.ofGa(
                        selection.target().group() + ":" + selection.target().module())
                .key();
        for (String sibling : selection.allTargets()) {
            String siblingKey = PackageId.ofGa(sibling).key();
            if (!siblingKey.equals(selected)) droppedSiblings.put(siblingKey, selected);
        }
    }

    /**
     * Future-based single-flight around {@link #lookup}: joiners wait on the winner's future
     * while the network lookup runs outside any map lock. No cycle risk here (unlike the
     * EffectivePomBuilder single-flight, JK-1764): lookup never re-enters {@code selectionFor}.
     * {@code lookup} is fail-soft, so the future always completes normally; the finally guard
     * only fires on an {@link Error}, unparking joiners without memoizing a guess.
     */
    private Optional<Selection> processMemoized(String processKey, String module, String version) {
        java.util.concurrent.CompletableFuture<Optional<Selection>> flight = PROCESS_CACHE.get(processKey);
        if (flight == null && PROCESS_CACHE.size() < PROCESS_CACHE_MAX) {
            java.util.concurrent.CompletableFuture<Optional<Selection>> mine =
                    new java.util.concurrent.CompletableFuture<>();
            java.util.concurrent.CompletableFuture<Optional<Selection>> raced =
                    PROCESS_CACHE.putIfAbsent(processKey, mine);
            if (raced != null) {
                flight = raced;
            } else {
                try {
                    mine.complete(lookup(module, version));
                } finally {
                    if (!mine.isDone()) {
                        mine.complete(Optional.empty());
                        PROCESS_CACHE.remove(processKey, mine);
                    }
                }
                flight = mine;
            }
        }
        return flight != null ? flight.join() : lookup(module, version);
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

    /** Chunk size for the marker scan; the marker sits in the first few KB of most POMs. */
    private static final int MARKER_SCAN_CHUNK = 8192;

    /** Hard cap on how far the preamble scan will go on a pathological file. */
    private static final int MARKER_SCAN_MAX = 256 * 1024;

    /**
     * True when the POM head contains Gradle's published-with-gradle-metadata marker. Reads full
     * chunks via {@code readNBytes} (a bare {@code read} may return fewer bytes than available and
     * silently drop a redirect) and carries an overlap across chunk boundaries so a straddling
     * marker is still seen. The marker comment always precedes the POM's content, so scanning
     * stops one chunk after the root element appears — a long license header pushes the marker
     * past the first chunk, but a plain-Maven POM still costs at most one extra chunk.
     */
    static boolean pomHasGradleMetadataMarker(java.nio.file.Path pomPath) throws IOException {
        // Overlap enough to reassemble a marker split across a chunk boundary (ASCII marker:
        // byte-aligned regardless of surrounding multi-byte sequences).
        final int overlap = GradleModuleMetadata.POM_MARKER.length() - 1;
        try (var in = Files.newInputStream(pomPath)) {
            String carry = "";
            boolean sawRoot = false;
            int scanned = 0;
            while (scanned < MARKER_SCAN_MAX) {
                byte[] buf = in.readNBytes(MARKER_SCAN_CHUNK);
                if (buf.length == 0) return false;
                scanned += buf.length;
                String text = carry + new String(buf, StandardCharsets.UTF_8);
                if (text.contains(GradleModuleMetadata.POM_MARKER)) return true;
                if (sawRoot) return false; // marker precedes content; one chunk past <project is enough
                sawRoot = text.contains("<project");
                carry = text.length() <= overlap ? text : text.substring(text.length() - overlap);
            }
            return false;
        }
    }
}
