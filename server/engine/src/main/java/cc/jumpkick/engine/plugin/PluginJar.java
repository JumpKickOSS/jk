// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.PomRuntimeClasspath;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Registry of jk's child-JVM plugin jars. Locates each by Maven coordinate
 * ({@code cc.jumpkick:<artifactId>:<version>}), in order: {@code -D} jar property, then local cache
 * stores ({@code repos/jk-local}, {@code repos/jumpkick}, {@code repos/central}), then a one-shot
 * fetch from the official JumpKick Maven repository into {@code repos/jumpkick/}.
 */
public enum PluginJar {
    TEST_RUNNER("jk-test-runner", "jk.test.runner.jar", ":test-runner:installLocal"),
    KOTLIN_COMPILER("jk-kotlin-compiler", "jk.kotlin.plugin.jar", ":kotlin-compiler:installLocal"),
    GROOVY_COMPILER("jk-groovy-compiler", "jk.groovy.plugin.jar", ":groovy-compiler:installLocal"),
    JAVA_COMPILER("jk-java-compiler", "jk.java.plugin.jar", ":java-compiler:installLocal"),
    AUDITOR("jk-auditor", "jk.auditor.plugin.jar", ":auditor:installLocal"),
    PUBLISHER("jk-publisher", "jk.publisher.plugin.jar", ":publisher:installLocal"),
    IMAGE_BUILDER("jk-image-builder", "jk.image-builder.plugin.jar", ":image-builder:installLocal"),
    COMPAT_BRIDGE("jk-compat-bridge", "jk.compat-bridge.plugin.jar", ":compat-bridge:installLocal"),
    FORMATTER("jk-formatter", "jk.formatter.plugin.jar", ":formatter:installLocal"),
    SPRING_BOOT("jk-spring-boot", "jk.spring-boot.plugin.jar", ":spring-boot:installLocal"),
    GRAILS("jk-grails", "jk.grails.plugin.jar", ":grails:installLocal"),
    QUARKUS("jk-quarkus", "jk.quarkus.plugin.jar", ":quarkus:installLocal"),
    MICRONAUT("jk-micronaut", "jk.micronaut.plugin.jar", ":micronaut:installLocal"),
    ANDROID("jk-android", "jk.android.plugin.jar", ":android:installLocal"),
    PROTOBUF("jk-protobuf", "jk.protobuf.plugin.jar", ":protobuf:installLocal"),
    MINIFIED("jk-minified", "jk.minified.plugin.jar", ":minified:installLocal");

    /** Cache store + remote repo name for the official first-party Maven repo. */
    public static final String OFFICIAL_REPO = "jumpkick";

    private final String artifactId;
    private final String jarProperty;
    private final String installTask;

    PluginJar(String artifactId, String jarProperty, String installTask) {
        this.artifactId = artifactId;
        this.jarProperty = jarProperty;
        this.installTask = installTask;
    }

    /** Maven artifactId the plugin publishes under (group is always {@code cc.jumpkick}). */
    public String artifactId() {
        return artifactId;
    }

    /** System property that overrides jar location (tests / dev). */
    public String jarProperty() {
        return jarProperty;
    }

    /** The Gradle task that installs this plugin into the local repo. */
    public String installTask() {
        return installTask;
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

        for (String repoName : List.of(RepoArtifactResolver.JK_LOCAL, OFFICIAL_REPO, "central")) {
            RepoArtifactStore store = new RepoArtifactStore(cacheRoot, repoName);
            var result = store.locate(relPath);
            if (result.isPresent()) return result.get();
            checked.add(cacheRoot.resolve("repos").resolve(repoName).resolve(relPath));
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
    public Path locateStored(Cas cas) {
        String override = System.getProperty(jarProperty);
        if (override != null && !override.isBlank()) {
            Path jar = Path.of(override);
            return Files.isRegularFile(jar) ? jar : null;
        }
        for (String repoName : List.of(RepoArtifactResolver.JK_LOCAL, OFFICIAL_REPO, "central")) {
            var result = new RepoArtifactStore(cas.root(), repoName).locate(relativePath());
            if (result.isPresent()) return result.get();
        }
        return null;
    }

    /**
     * Download {@code relPath} and its sibling {@code .pom} (+ optional {@code .sha256}) from the
     * official Maven repo into {@code repos/jumpkick/}. Returns the local jar path, {@code null} if
     * the jar 404s, or throws if the jar exists without a POM.
     */
    public static Path fetchOfficial(Cas cas, String relPath) throws IOException, InterruptedException {
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
        String published = null;
        try {
            HttpResponse<byte[]> sumResp = http.get(URI.create(jarUri + ".sha256"));
            if (sumResp.statusCode() >= 200 && sumResp.statusCode() < 300) {
                published = new String(sumResp.body()).strip().split("\\s+")[0];
            }
        } catch (IOException ignored) {
            // The .sha256 sidecar is optional — an absent or unreachable sidecar keeps the hash
            // we computed. The mismatch check below must stay OUTSIDE this catch: swallowing it
            // installed jars whose published checksum disagreed.
        }
        if (published != null && published.length() == 64) {
            if (!published.equalsIgnoreCase(sha)) {
                throw new IOException(
                        "checksum mismatch for " + jarUri + " (expected " + published + ", got " + sha + ")");
            }
            sha = published.toLowerCase();
        }
        RepoArtifactStore store = RepoArtifactStore.forRepoName(cas.root(), OFFICIAL_REPO);
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
     * imports, {@code dependencyManagement}) from the official repo, then Maven Central.
     */
    private static void fetchOfficialClosure(Cas cas, Http http, URI base, Path workerJar)
            throws IOException, InterruptedException {
        Coordinate coord = PomRuntimeClasspath.coordinateOf(workerJar);
        if (coord == null) {
            throw new IOException("cannot parse Maven coordinate of official worker jar " + workerJar);
        }
        // Worker closures stay under JK_STORE_DIR (repos/jumpkick, repos/central) — not ~/.m2.
        MavenRepo official = new MavenRepo(OFFICIAL_REPO, base, http, cas, RepoCredential.ANONYMOUS, false);
        MavenRepo central = new MavenRepo(
                "central", RepositorySpec.MAVEN_CENTRAL.url(), http, cas, RepoCredential.ANONYMOUS, false);
        RepoGroup repos = RepoGroup.of(central).withReposPrepended(List.of(official));
        PomRuntimeClasspath.fetchRuntimeClosure(coord, repos);
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
        return locate(JkStores.cas(JkDirs.cache()));
    }

    /** As {@link #locate(Cas)} but {@code null} (not throwing) when the plugin can't be located. */
    public Path locateOrNull(Cas cas) {
        try {
            return locate(cas);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The plugin whose {@code artifactId} (e.g. {@code jk-git-client}) matches, if any. */
    public static Optional<PluginJar> byArtifactId(String artifactId) {
        for (PluginJar w : values()) {
            if (w.artifactId.equals(artifactId)) return Optional.of(w);
        }
        return Optional.empty();
    }
}
