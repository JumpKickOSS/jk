// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.PomRuntimeClasspath;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.wire.PluginJarNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Registry of jk's child-JVM plugin jars. Locates each by Maven coordinate
 * ({@code cc.jumpkick:<artifactId>:<version>}), in order: {@code -D} jar property, then the
 * first-party stores ({@code repos/jk-local}, the official repository's, Central's), then a
 * one-shot fetch from the official JumpKick Maven repository into that repository's store.
 */
public enum PluginJar {
    TEST_RUNNER("jk-test-runner", "jk.test.runner.jar"),
    KOTLIN_COMPILER("jk-kotlin-compiler", "jk.kotlin.plugin.jar"),
    GROOVY_COMPILER("jk-groovy-compiler", "jk.groovy.plugin.jar"),
    JAVA_COMPILER("jk-java-compiler", "jk.java.plugin.jar"),
    AUDITOR("jk-auditor", "jk.auditor.plugin.jar"),
    PUBLISHER("jk-publisher", "jk.publisher.plugin.jar"),
    IMAGE_BUILDER("jk-image-builder", "jk.image-builder.plugin.jar"),
    FORMATTER("jk-formatter", "jk.formatter.plugin.jar"),
    SPRING_BOOT("jk-spring-boot", "jk.spring-boot.plugin.jar"),
    GRAILS("jk-grails", "jk.grails.plugin.jar"),
    QUARKUS("jk-quarkus", "jk.quarkus.plugin.jar"),
    MICRONAUT("jk-micronaut", "jk.micronaut.plugin.jar"),
    ANDROID("jk-android", "jk.android.plugin.jar"),
    PROTOBUF("jk-protobuf", "jk.protobuf.plugin.jar"),
    GENERATOR("jk-generator", "jk.generator.plugin.jar"),
    OPENAPI("jk-openapi", "jk.openapi.plugin.jar"),
    MINIFIED("jk-minified", "jk.minified.plugin.jar");

    private final String artifactId;
    private final String jarProperty;

    PluginJar(String artifactId, String jarProperty) {
        this.artifactId = artifactId;
        this.jarProperty = jarProperty;
    }

    /** The group every first-party worker publishes under. */
    public static final String GROUP = "cc.jumpkick";

    /** Maven artifactId the plugin publishes under (group is always {@link #GROUP}). */
    public String artifactId() {
        return artifactId;
    }

    /** System property that overrides jar location (tests / dev). */
    public String jarProperty() {
        return jarProperty;
    }

    /**
     * The m2-layout relative path for this plugin at its current version.
     * E.g. {@code cc/jumpkick/jk-formatter/0.10.1/jk-formatter-0.10.1.jar}.
     */
    public String relativePath() {
        String version = JkVersion.VERSION;
        return "cc/jumpkick/" + artifactId + "/" + version + "/" + artifactId + "-" + version + ".jar";
    }

    /**
     * Locate the plugin jar: {@code -D<jarProperty>} override first, then cache stores, then fetch
     * from the official JumpKick repo. Throws {@link PluginJarNotFoundException} with side-load
     * instructions if none resolves.
     */
    public Path locate(Cas cas) {
        String override = System.getProperty(jarProperty);
        if (override != null && !override.isBlank()) {
            Path jar = Path.of(override);
            if (Files.isRegularFile(jar)) return jar;
            throw new IllegalStateException(
                    "-D" + jarProperty + " is set to '" + override + "' but no file exists there.");
        }

        Path cacheRoot = cas.root();
        String relPath = relativePath();
        String coordinate = "cc.jumpkick:" + artifactId + ":" + JkVersion.VERSION;
        List<Path> checked = new ArrayList<>();

        for (RepoArtifactStore store : RepoArtifactStore.firstParty(cacheRoot)) {
            var result = store.locate(relPath);
            if (result.isPresent()) return result.get();
            checked.add(Objects.requireNonNull(store.root(), "store root").resolve(relPath));
        }

        // First-run / clean cache: pull from the official Maven layout on GCS (or JK_OFFICIAL_REPO_URL).
        try {
            Path fetched = fetchOfficial(cas, relPath);
            if (fetched != null) return fetched;
        } catch (Exception e) {
            throw new PluginJarNotFoundException(
                    artifactId, coordinate, checked, jarProperty, "official fetch failed: " + e.getMessage());
        }

        throw new PluginJarNotFoundException(artifactId, coordinate, checked, jarProperty);
    }

    /**
     * As {@link #locate(Cas)} but store-only: honors the {@code -D<jarProperty>} override and the
     * repo stores, never the network. {@code null} on a miss. Engine startup and lock/publish
     * enumeration must use this — an eager {@link #locate(Cas)} loop over all values serially
     * mass-downloads every plugin on a cold store and turns an offline start into sixteen failed
     * fetches.
     */
    public @Nullable Path locateStored(Cas cas) {
        String override = System.getProperty(jarProperty);
        if (override != null && !override.isBlank()) {
            Path jar = Path.of(override);
            return Files.isRegularFile(jar) ? jar : null;
        }
        for (RepoArtifactStore store : RepoArtifactStore.firstParty(cas.root())) {
            var result = store.locate(relativePath());
            if (result.isPresent()) return result.get();
        }
        return null;
    }

    /**
     * Download {@code relPath} and its sibling {@code .pom} (+ optional {@code .sha256}) from the
     * official Maven repo into that repository's store. Returns the local jar path, {@code null} if
     * the jar 404s, or throws if the jar exists without a POM.
     */
    public static @Nullable Path fetchOfficial(Cas cas, String relPath) throws IOException, InterruptedException {
        if (!relPath.endsWith(".jar")) {
            throw new IOException("official fetch expected a jar path, got " + relPath);
        }
        URI base = officialRepoBase();
        String pomRel = relPath.substring(0, relPath.length() - 4) + ".pom";
        URI jarUri = base.resolve(relPath);
        URI pomUri = base.resolve(pomRel);
        Http http = new Http();
        HttpResponse<byte[]> jarResp;
        try {
            jarResp = http.get(jarUri);
        } catch (IOException e) {
            // A network failure is not "not found" — surfacing it as null made an offline host
            // report a plain missing plugin with no cause.
            throw new IOException("official repo unreachable: GET " + jarUri + ": " + e.getMessage(), e);
        }
        if (jarResp.statusCode() == 404) return null;
        if (jarResp.statusCode() < 200 || jarResp.statusCode() >= 300) {
            throw new IOException("GET " + jarUri + " → HTTP " + jarResp.statusCode());
        }
        HttpResponse<byte[]> pomResp = http.get(pomUri);
        if (pomResp.statusCode() < 200 || pomResp.statusCode() >= 300) {
            throw new IOException("official repo has no POM for " + relPath + " (HTTP " + pomResp.statusCode() + ")");
        }
        byte[] pomBody = pomResp.body();
        if (pomBody == null || pomBody.length == 0) {
            throw new IOException("official repo POM for " + relPath + " is empty");
        }
        byte[] bytes = jarResp.body();
        String sha = Hashing.sha256Hex(bytes);
        Optional<String> published = Optional.empty();
        try {
            HttpResponse<byte[]> sumResp = http.get(URI.create(jarUri + ".sha256"));
            if (sumResp.statusCode() >= 200 && sumResp.statusCode() < 300) {
                published = Hashing.checksumFromSidecar(new String(sumResp.body(), StandardCharsets.UTF_8), 64);
            }
        } catch (IOException ignored) {
            // The .sha256 sidecar is optional — an absent or unreachable sidecar keeps the hash
            // we computed. The mismatch check below must stay OUTSIDE this catch: swallowing it
            // installed jars whose published checksum disagreed.
        }
        if (published.isPresent()) {
            if (!published.get().equals(sha)) {
                throw new IOException(
                        "checksum mismatch for " + jarUri + " (expected " + published.get() + ", got " + sha + ")");
            }
            sha = published.get();
        }
        RepoArtifactStore store = RepoArtifactStore.forRepository(cas.root(), RepositorySpec.JUMPKICK_NAME, base);
        Files.createDirectories(cas.root());
        Path tmpJar = Files.createTempFile(cas.root(), ".worker-", ".jar");
        Path tmpPom = Files.createTempFile(cas.root(), ".worker-", ".pom");
        try {
            Files.write(tmpJar, bytes);
            store.materialize(relPath, tmpJar, sha);
            String pomSha = Hashing.sha256Hex(pomBody);
            Files.write(tmpPom, pomBody);
            store.materialize(pomRel, tmpPom, pomSha);
        } finally {
            Files.deleteIfExists(tmpJar);
            Files.deleteIfExists(tmpPom);
        }
        Path localJar = store.locate(relPath)
                .orElseThrow(() -> new IOException("could not materialize official worker jar " + relPath + " under "
                        + store.root() + " (store write failed — check disk space and permissions)"));
        fetchOfficialClosure(cas, http, base, localJar);
        return localJar;
    }

    /**
     * Fetch the worker POM's Maven runtime closure (effective POM: parent properties, BOM
     * imports, {@code dependencyManagement}) from the {@linkplain PomRuntimeClasspath#workerRemotes
     * built-in remotes}: the official repo, Maven Central, Google's Android Maven.
     */
    private static void fetchOfficialClosure(Cas cas, Http http, URI base, Path workerJar)
            throws IOException, InterruptedException {
        Coordinate coord = PomRuntimeClasspath.coordinateOf(workerJar);
        if (coord == null) {
            throw new IOException("cannot parse Maven coordinate of official worker jar " + workerJar);
        }
        // Worker closures stay under JK_STORE_DIR (repos/jumpkick, repos/central, repos/google) — not ~/.m2.
        PomRuntimeClasspath.fetchRuntimeClosure(coord, PomRuntimeClasspath.workerRemotes(base, http, cas));
    }

    /**
     * System property override for {@link #officialRepoBase()} — used by hermetic tests so
     * {@link #locate(Cas)} cannot soft-succeed via network when the local cache is empty.
     */
    public static final String OFFICIAL_REPO_URL_PROPERTY = RepositorySpec.OFFICIAL_REPO_URL_PROPERTY;

    /** Base URL ending in {@code /} for the official first-party Maven repo. */
    public static URI officialRepoBase() {
        return RepositorySpec.officialUrl();
    }

    /** Locate using the default jk CAS ({@code $JK_CACHE_DIR}). */
    public Path locate() {
        return locate(JkStores.storeCas());
    }

    /** As {@link #locate(Cas)} but {@code null} (not throwing) when the plugin can't be located. */
    public @Nullable Path locateOrNull(Cas cas) {
        try {
            return locate(cas);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The plugin whose {@code artifactId} (e.g. {@code jk-git-client}) matches, if any. */
    public static Optional<PluginJar> byArtifactId(@Nullable String artifactId) {
        for (PluginJar w : values()) {
            if (w.artifactId.equals(artifactId)) return Optional.of(w);
        }
        return Optional.empty();
    }
}
