// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.runtime.base.TestStoreSeed;
import cc.jumpkick.util.JkDirs;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end: a Maven shop behind Nexus. {@code settings.xml} mirrors {@code central} to a
 * loopback repository standing in for Nexus — a caching proxy that hosts the shop's own
 * artifacts and relays everything else to Central, which is what Nexus is — and the {@code
 * pom.xml}, unmodified and with no {@code <repository>} of its own, depends on an artifact only
 * that repository has. The coexistence build succeeds, every request reaches the mirror, and the
 * lock's {@code source} still reads {@code central} at Central's own URL: the mirror is this
 * machine's transport, not a fact about the project.
 *
 * <p>Network: the test runner's JUnit artifacts come from Central through the proxy into the
 * cache under {@code build/}, which persists across runs so repeats are warm.
 */
@Tag("integration")
class CoexistenceMirrorE2eTest {

    private static final String GROUP_PATH = "com/example/mirrored";
    private static final String ARTIFACT = "only-on-nexus";

    /** Fresh per run, so the cache under {@code build/} cannot answer for the mirror. */
    private static final String VERSION = "1." + Long.toString(System.nanoTime(), 36);

    private static final String POM_PATH =
            "/" + GROUP_PATH + "/" + ARTIFACT + "/" + VERSION + "/" + ARTIFACT + "-" + VERSION + ".pom";
    private static final String JAR_PATH =
            "/" + GROUP_PATH + "/" + ARTIFACT + "/" + VERSION + "/" + ARTIFACT + "-" + VERSION + ".jar";
    private static final String METADATA_PATH = "/" + GROUP_PATH + "/" + ARTIFACT + "/maven-metadata.xml";

    /** The build's store: warm with the JUnit Platform from this engine's, like a suite's sandbox. */
    private static final Path CACHE = Path.of("build/test-cache/coexistence-mirror");

    /** The corporate repository. It answers for Central; the build never addresses Central itself. */
    private final Nexus nexus = new Nexus();

    private @Nullable String priorSettings;

    @BeforeAll
    static void engineShadowSource() {
        ShadowManifests.install();
    }

    @BeforeEach
    void start() throws IOException {
        priorSettings = System.getProperty(MavenSettings.SETTINGS_PROPERTY);
        // The process memos are keyed by the repository, which a mirror does not change: a launcher
        // another test in this JVM resolved would answer here without a request reaching the mirror.
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        Files.createDirectories(CACHE);
        TestStoreSeed.seed(JkDirs.store(), CACHE);
        nexus.start();
    }

    @AfterEach
    void stop() {
        nexus.stop();
        if (priorSettings == null) System.clearProperty(MavenSettings.SETTINGS_PROPERTY);
        else System.setProperty(MavenSettings.SETTINGS_PROPERTY, priorSettings);
        SessionContext.reset();
    }

    @Test
    void an_unmodified_pom_builds_through_the_settings_xml_mirror_and_the_lock_records_central(@TempDir Path tmp)
            throws Exception {
        serveOnlyOnNexus();
        Path settings = tmp.resolve("settings.xml");
        Files.writeString(settings, """
                <settings>
                  <mirrors>
                    <mirror>
                      <id>nexus</id>
                      <mirrorOf>central</mirrorOf>
                      <url>%s/</url>
                    </mirror>
                  </mirrors>
                </settings>
                """.formatted(nexus.base()));
        System.setProperty(MavenSettings.SETTINGS_PROPERTY, settings.toString());
        Path project = writeProject(tmp.resolve("shop"));

        BuildPlan plan = plan(project, CACHE);
        BuildPlanResult built = plan.run();

        assertThat(built.errors()).isEmpty();
        assertThat(built.success()).isTrue();
        assertThat(project.resolve("target/classes/main/com/example/Shop.class"))
                .exists();
        assertThat(nexus.requested).contains(POM_PATH, JAR_PATH);
        assertThat(nexus.requested)
                .as("the test runner's own dependencies came through the mirror too")
                .anyMatch(path -> path.contains("/org/junit/platform/junit-platform-launcher/"));

        Path lockFile = LockPaths.lockFile(project);
        assertThat(lockFile).isEqualTo(ManifestPaths.shadowManifestPath(project).resolveSibling("jk-lock.toml"));
        Lockfile lock = LockfileReader.read(lockFile);
        assertThat(lock.artifacts())
                .filteredOn(a -> a.name().startsWith("com.example.mirrored:" + ARTIFACT + ":"))
                .singleElement()
                .satisfies(a -> assertThat(a.source())
                        .as("the lock names the logical repository, not the mirror")
                        .isEqualTo(RepositorySpec.CENTRAL + "+" + RepositorySpec.MAVEN_CENTRAL.url()));
        assertThat(Files.readString(lockFile))
                .doesNotContain(nexus.base().getHost() + ":" + nexus.base().getPort());
        assertThat(project.resolve("jk.toml")).doesNotExist();
    }

    /** A POM, a jar with a manifest, and the version list, for a coordinate Central has never heard of. */
    private void serveOnlyOnNexus() throws Exception {
        nexus.serve(POM_PATH, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example.mirrored</groupId>
                  <artifactId>only-on-nexus</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(VERSION));
        nexus.serve(METADATA_PATH, """
                <metadata>
                  <groupId>com.example.mirrored</groupId>
                  <artifactId>only-on-nexus</artifactId>
                  <versioning>
                    <latest>%s</latest>
                    <release>%s</release>
                    <versions><version>%s</version></versions>
                  </versioning>
                </metadata>
                """.formatted(VERSION, VERSION, VERSION));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            jar.putNextEntry(new JarEntry("only-on-nexus.txt"));
            jar.write("served by the mirror".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        nexus.hosted.put(JAR_PATH, bytes.toByteArray());
    }

    /**
     * A loopback stand-in for Nexus: serves what it hosts, then what the seeded store holds of
     * Central's layout (the JUnit Platform the test runner injects, version lists included), relays
     * every other path to Central and answers 404 for what Central has not got. Checksum sidecars of
     * hosted artifacts are computed on request, the way a repository manager publishes them.
     */
    private static final class Nexus {
        final Map<String, byte[]> hosted = new ConcurrentHashMap<>();
        final List<String> requested = new CopyOnWriteArrayList<>();
        private final Http upstream = new Http();
        private @Nullable HttpServer server;

        void serve(String path, String body) {
            hosted.put(path, body.getBytes(StandardCharsets.UTF_8));
        }

        URI base() {
            HttpServer running = Objects.requireNonNull(server, "start first");
            return URI.create("http://127.0.0.1:" + running.getAddress().getPort());
        }

        void start() throws IOException {
            HttpServer bound = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            bound.setExecutor(Executors.newCachedThreadPool());
            bound.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                requested.add(path);
                byte[] body = served(path);
                if (body == null && path.endsWith(".sha1")) {
                    byte[] artifact = served(path.substring(0, path.length() - ".sha1".length()));
                    if (artifact != null) {
                        body = Hashing.hashHex("SHA-1", artifact).getBytes(StandardCharsets.UTF_8);
                    }
                }
                if (body == null && !path.startsWith("/" + GROUP_PATH + "/")) body = relay(path);
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            bound.start();
            server = bound;
        }

        /** What this repository holds itself: the hosted coordinate, then the seeded Central layout. */
        private byte @Nullable [] served(String path) {
            byte[] hostedBody = hosted.get(path);
            if (hostedBody != null || path.startsWith("/" + GROUP_PATH + "/")) return hostedBody;
            try {
                return TestStoreSeed.seeded(CACHE, path.substring(1)).orElse(null);
            } catch (IOException e) {
                return null; // an unreadable seed is what the relay is for
            }
        }

        private byte @Nullable [] relay(String path) {
            try {
                HttpResponse<byte[]> answer = upstream.get(URI.create(RepositorySpec.MAVEN_CENTRAL
                        .url()
                        .resolve(path.substring(1))
                        .toString()));
                return answer.statusCode() == 200 ? answer.body() : null;
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return null;
            }
        }

        void stop() {
            HttpServer running = server;
            if (running != null) running.stop(0);
            server = null;
        }
    }

    private static Path writeProject(Path project) throws Exception {
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>shop</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>25</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>com.example.mirrored</groupId>
                      <artifactId>only-on-nexus</artifactId>
                      <version>%s</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(VERSION));
        Path main = Files.createDirectories(project.resolve("src/main/java/com/example"));
        Files.writeString(main.resolve("Shop.java"), """
                package com.example;
                public final class Shop {
                    public static String open() {
                        return "open";
                    }
                }
                """);
        return project;
    }

    private static BuildPlan plan(Path project, Path cache) {
        Path buildFile = ManifestPaths.manifestIn(project);
        Path lockFile = LockPaths.lockFile(project);
        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                project,
                cache,
                buildFile,
                lockFile,
                LockPaths.lockOwnerDir(project),
                1,
                0,
                null,
                null,
                true,
                false,
                false,
                false,
                Set.of(),
                SessionContext.current());
        return BuildPlanner.fullPlan(in);
    }
}
