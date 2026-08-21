// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.PomRuntimeClasspath;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.util.Hashing;
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
 * stores ({@code repos/local}, {@code repos/jumpkick}, {@code repos/central}), then a one-shot
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

        for (String repoName : List.of("local", OFFICIAL_REPO, "central")) {
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
     * Download {@code relPath} and its sibling {@code .pom} (+ optional {@code .sha256}) from the
     * official Maven repo into {@code repos/jumpkick/}. Returns the local jar path, {@code null} if
     * the jar 404s, or throws if the jar exists without a POM.
     */
    static Path fetchOfficial(Cas cas, String relPath) throws IOException, InterruptedException {
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
            return null;
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
        try {
            HttpResponse<byte[]> sumResp = http.get(URI.create(jarUri + ".sha256"));
            if (sumResp.statusCode() >= 200 && sumResp.statusCode() < 300) {
                String published = new String(sumResp.body()).strip().split("\\s+")[0];
                if (published.length() == 64 && !published.equalsIgnoreCase(sha)) {
                    throw new IOException(
                            "checksum mismatch for " + jarUri + " (expected " + published + ", got " + sha + ")");
                }
                if (published.length() == 64) sha = published.toLowerCase();
            }
        } catch (IOException ignored) {
            // checksum file optional; we still pin what we hashed
        }
        Path casBlob = cas.put(bytes, sha);
        RepoArtifactStore store = RepoArtifactStore.forRepoName(cas.root(), OFFICIAL_REPO);
        store.materialize(relPath, casBlob, sha);
        String pomSha = Hashing.sha256Hex(pomBody);
        store.materialize(pomRel, cas.put(pomBody, pomSha), pomSha);
        Path localJar = store.locate(relPath).orElseThrow();
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
        MavenRepo official = new MavenRepo(OFFICIAL_REPO, base, http, cas);
        MavenRepo central = new MavenRepo("central", RepositorySpec.MAVEN_CENTRAL.url(), http, cas);
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
