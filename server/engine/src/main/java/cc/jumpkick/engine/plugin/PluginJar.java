// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.util.Hashing;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
    ANDROID("jk-android", "jk.android.plugin.jar", ":android:installLocal"),
    PROTOBUF("jk-protobuf", "jk.protobuf.plugin.jar", ":protobuf:installLocal"),
    SHRINK("jk-shrink", "jk.shrink.plugin.jar", ":shrink:installLocal");

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

        Path cacheRoot = cas.root(); // cas root is the jk cache directory (e.g. ~/.jk/cache)
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
     * Download {@code relPath} (+ optional {@code .sha256}) from the official repo into
     * {@code repos/jumpkick/}. Returns the local path or {@code null} if the remote 404s.
     */
    static Path fetchOfficial(Cas cas, String relPath) throws IOException, InterruptedException {
        URI base = officialRepoBase();
        URI jarUri = base.resolve(relPath);
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
        byte[] bytes = jarResp.body();
        String sha = Hashing.sha256Hex(bytes);
        // Prefer published sidecar when present
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
            // sidecar optional for fetch; we still pin what we hashed
        }
        Path casBlob = cas.put(bytes, sha);
        RepoArtifactStore store = RepoArtifactStore.forRepoName(cas.root(), OFFICIAL_REPO);
        store.materialize(relPath, casBlob, sha);
        Path localJar = store.locate(relPath).orElseThrow();
        fetchDepsSidecar(cas, http, base, jarUri, localJar);
        return localJar;
    }

    /**
     * Thin workers publish a flat coordinate closure as {@code <jar>.deps} (one {@code
     * group:artifact:version} per line — see {@code jk.plugin-conventions} installLocal and
     * {@code scripts/publish-maven-repo.sh}). Resolve every coordinate — first-party from the
     * official repo, the rest from Maven Central — and write the absolute-path launch sidecar next
     * to the fetched jar so a thin worker starts on a cold store (JK-1351). A missing {@code .deps}
     * means a legacy fat jar: nothing to do. A listed-but-unfetchable dep fails the whole fetch —
     * a thin worker without its classpath would only die later with a bare CNFE.
     */
    private static void fetchDepsSidecar(Cas cas, Http http, URI base, URI jarUri, Path localJar)
            throws IOException, InterruptedException {
        HttpResponse<byte[]> depsResp;
        try {
            depsResp = http.get(URI.create(jarUri + ".deps"));
        } catch (IOException e) {
            return; // no sidecar reachable — legacy repo
        }
        if (depsResp.statusCode() < 200 || depsResp.statusCode() >= 300) return;
        List<String> lines = new String(depsResp.body(), java.nio.charset.StandardCharsets.UTF_8)
                .lines()
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .toList();
        if (lines.isEmpty()) return;
        cc.jumpkick.repo.MavenRepo official = new cc.jumpkick.repo.MavenRepo(OFFICIAL_REPO, base, http, cas);
        cc.jumpkick.repo.MavenRepo central =
                new cc.jumpkick.repo.MavenRepo("central", RepositorySpec.MAVEN_CENTRAL.url(), http, cas);
        cc.jumpkick.repo.RepoGroup repos =
                cc.jumpkick.repo.RepoGroup.of(central).withReposPrepended(List.of(official));
        List<Path> resolved = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.split(":");
            if (parts.length < 3) {
                throw new IOException("malformed line in " + jarUri + ".deps: `" + line + "`");
            }
            var coord = cc.jumpkick.model.Coordinate.of(parts[0], parts[1], parts[2]);
            var fetched = repos.tryFetchArtifact(coord)
                    .orElseThrow(() -> new IOException(
                            "worker dependency " + line + " (from " + jarUri + ".deps) not found in any repo"));
            resolved.add(fetched.fetched().cachePath());
        }
        cc.jumpkick.compile.WorkerClasspath.writeSidecar(localJar, resolved);
    }

    /**
     * System property override for {@link #officialRepoBase()} — used by hermetic tests so
     * {@link #locate(Cas)} cannot soft-succeed via network when the local cache is empty.
     */
    public static final String OFFICIAL_REPO_URL_PROPERTY = "jk.official.repo.url";

    /** Base URL ending in {@code /} for the official first-party Maven repo. */
    public static URI officialRepoBase() {
        String prop = System.getProperty(OFFICIAL_REPO_URL_PROPERTY);
        if (prop != null && !prop.isBlank()) {
            return URI.create(prop.endsWith("/") ? prop : prop + "/");
        }
        String env = System.getenv("JK_OFFICIAL_REPO_URL");
        if (env != null && !env.isBlank()) {
            return URI.create(env.endsWith("/") ? env : env + "/");
        }
        // Prefer jumpkick.build once DNS works (Firebase redirects to GCS); GCS origin always works.
        return RepositorySpec.JUMPKICK.url();
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
    public static java.util.Optional<PluginJar> byArtifactId(String artifactId) {
        for (PluginJar w : values()) {
            if (w.artifactId.equals(artifactId)) return java.util.Optional.of(w);
        }
        return java.util.Optional.empty();
    }
}
