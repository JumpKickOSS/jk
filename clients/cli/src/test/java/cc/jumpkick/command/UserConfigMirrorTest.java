// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.IsolatedHome;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.testing.RecordingProxy;
import cc.jumpkick.util.JkDirs;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code mirror-of} table in the user's {@code ~/.jk/config.toml}, driven through {@code jk lock}:
 * the mirror answers for every repository it matches, the lock it writes is the lock written
 * without it, a mirror that does not answer is the failure, and a mirror of Central alone leaves
 * Google and JumpKick to answer for themselves. The resolves that run with no project in hand — a
 * script's dependencies, a tool by its coordinate, {@code jk format}'s formatter, build logic's
 * Groovy, a giter8 template's {@code maven()} property — ask the mirror too. The user's {@code
 * [network] proxy} is a {@link RecordingProxy}, so anything dialed past the loopback stubs is in
 * its log and fails.
 */
@Tag("integration")
@IsolatedHome
class UserConfigMirrorTest {

    static final String CENTRAL_LIB = "com.example.e2e";
    static final String GOOGLE_LIB = "androidx.e2ejk";
    static final String JUMPKICK_LIB = "cc.jumpkick.e2e";

    /** The three origins, each under its own prefix: Central, Google and JumpKick as the project knows them. */
    @RegisterExtension
    final LoopbackHttp origins = new LoopbackHttp();

    /** The corporate repository the mirror table names. */
    @RegisterExtension
    final LoopbackHttp mirror = new LoopbackHttp();

    @RegisterExtension
    final RecordingProxy proxy = new RecordingProxy();

    @Test
    void a_mirror_of_everything_answers_every_request_and_writes_the_unmirrored_lock(@TempDir Path dir)
            throws Exception {
        publishAll(origins, "/central", "/google", "/jumpkick");
        publishAll(mirror, "", "", "");
        manifest(dir);
        userConfig(origins());

        assertThat(run("lock", "-C", dir.toString()))
                .as("the lock without a mirror")
                .isZero();
        String direct = Files.readString(dir.resolve("jk-lock.toml"));
        origins.clearRequests();

        userConfig(origins() + mirrorOf("*"));
        assertThat(run("lock", "-F", "-C", dir.toString()))
                .as("the lock through the mirror")
                .isZero();

        assertThat(Files.readString(dir.resolve("jk-lock.toml"))).isEqualTo(direct);
        assertThat(origins.requested()).as("no origin is asked at its own URL").isEmpty();
        assertThat(mirror.requested())
                .contains(
                        MavenStub.path(CENTRAL_LIB, "central-lib", "1.0", ".jar"),
                        MavenStub.path(GOOGLE_LIB, "google-lib", "1.0", ".jar"),
                        MavenStub.path(JUMPKICK_LIB, "jk-lib", "1.0", ".jar"));
        assertNoRepositoryDialed();
    }

    @Test
    void a_mirror_nothing_answers_at_fails_the_lock_naming_it(@TempDir Path dir) throws Exception {
        publishAll(origins, "/central", "/google", "/jumpkick");
        manifest(dir);
        userConfig(origins() + mirrorOf("*"));
        mirror.stop();

        int[] exit = {-1};
        String out = output(() -> exit[0] = run("lock", "-C", dir.toString()));

        assertThat(exit[0]).isNotZero();
        assertThat(out).contains("corp");
        assertThat(origins.requested()).as("nothing falls back to an origin").isEmpty();
        assertNoRepositoryDialed();
    }

    @Test
    void a_mirror_of_central_carries_centrals_traffic_and_no_other(@TempDir Path dir) throws Exception {
        publishAll(origins, "/central", "/google", "/jumpkick");
        publishCentral(mirror, "");
        manifest(dir);
        userConfig(origins() + mirrorOf("central"));

        assertThat(run("lock", "-C", dir.toString())).isZero();

        assertThat(mirror.requested())
                .as("Central's catalog, POM, jar and sidecars go to the mirror")
                .contains(
                        MavenStub.metadataPath(CENTRAL_LIB, "central-lib"),
                        MavenStub.path(CENTRAL_LIB, "central-lib", "1.0", ".pom"),
                        MavenStub.path(CENTRAL_LIB, "central-lib", "1.0", ".jar"),
                        MavenStub.path(CENTRAL_LIB, "central-lib", "1.0", ".jar.sha1"));
        assertThat(origins.requested())
                .as("Central is never asked at its own URL")
                .noneMatch(path -> path.startsWith("/central/"));
        assertThat(origins.requested())
                .as("Google and JumpKick are asked directly")
                .contains(
                        "/google" + MavenStub.path(GOOGLE_LIB, "google-lib", "1.0", ".jar"),
                        "/jumpkick" + MavenStub.path(JUMPKICK_LIB, "jk-lib", "1.0", ".jar"));
        assertThat(mirror.requested())
                .as("the mirror carries nothing of Google's or JumpKick's")
                .noneMatch(path -> path.contains("/google-lib/1.0/") || path.contains("/jk-lib/"));
        assertNoRepositoryDialed();
    }

    @Test
    void a_script_resolves_its_dependency_through_the_mirror(@TempDir Path dir) throws Exception {
        publishAll(mirror, "", "", "");
        userConfig(origins() + mirrorOf("*"));
        Path script = dir.resolve("Hello.java");
        Files.writeString(script, """
                //jk dep %s:central-lib:1.0
                public class Hello {
                    public static void main(String[] args) {}
                }
                """.formatted(CENTRAL_LIB));

        assertThat(run("tool", "run", script.toString())).isZero();

        assertThat(mirror.requested()).contains(MavenStub.path(CENTRAL_LIB, "central-lib", "1.0", ".jar"));
        assertThat(origins.requested()).isEmpty();
        assertNoRepositoryDialed();
    }

    @Test
    void a_tool_by_its_coordinate_resolves_through_the_mirror(@TempDir Path dir) throws Exception {
        new MavenStub(mirror)
                .metadata(CENTRAL_LIB, "hello-tool", "1.0")
                .text(
                        MavenStub.path(CENTRAL_LIB, "hello-tool", "1.0", ".pom"),
                        MavenStub.emptyPom(CENTRAL_LIB, "hello-tool", "1.0"))
                .bytes(MavenStub.path(CENTRAL_LIB, "hello-tool", "1.0", ".jar"), helloJar(dir));
        userConfig(origins() + mirrorOf("*"));

        assertThat(run("tool", "run", CENTRAL_LIB + ":hello-tool:1.0")).isZero();

        assertThat(mirror.requested()).contains(MavenStub.path(CENTRAL_LIB, "hello-tool", "1.0", ".jar"));
        assertThat(origins.requested()).isEmpty();
        assertNoRepositoryDialed();
    }

    @Test
    void the_formatters_jars_are_asked_of_the_mirror_and_nowhere_else(@TempDir Path dir) throws Exception {
        publishAll(mirror, "", "", "");
        userConfig(origins() + mirrorOf("*"));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "formatted"
                version = "0.1.0"
                java = 25
                """);
        Path source = Files.createDirectories(dir.resolve("src/main/java/com/example"));
        Files.writeString(source.resolve("A.java"), "package com.example;\nclass A {}\n");

        // The stub publishes no formatter, so the format fails; where it asked is the point.
        run("format", "-C", dir.toString());

        assertThat(mirror.requested()).anyMatch(path -> path.contains("java-format"));
        assertThat(origins.requested()).isEmpty();
        assertNoRepositoryDialed();
    }

    @Test
    void build_logics_groovy_is_asked_of_the_mirror_and_nowhere_else(@TempDir Path dir) throws Exception {
        publishAll(mirror, "", "", "");
        userConfig(origins() + mirrorOf("*"));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "scripted"
                version = "0.1.0"
                java = 25
                """);
        Path scripts = Files.createDirectories(dir.resolve(".jk/scripts"));
        Files.writeString(scripts.resolve("before-compile.groovy"), "println 'before compile'\n");
        Path source = Files.createDirectories(dir.resolve("src/main/java/com/example"));
        Files.writeString(source.resolve("A.java"), "package com.example;\nclass A {}\n");

        // The stub publishes no Groovy, so the script cannot run; where it asked is the point.
        run("build", "-C", dir.toString());

        assertThat(mirror.requested()).anyMatch(path -> path.startsWith("/org/apache/groovy/"));
        assertThat(origins.requested()).isEmpty();
        assertNoRepositoryDialed();
    }

    @Test
    void a_giter8_maven_property_reads_the_versions_the_mirror_lists(@TempDir Path dir) throws Exception {
        publishAll(mirror, "", "", "");
        userConfig(origins() + mirrorOf("*"));
        Path g8 = Files.createDirectories(dir.resolve("template/src/main/g8"));
        Files.writeString(g8.resolve("default.properties"), """
                name=fromtemplate
                lib_version=maven(%s, central-lib)
                """.formatted(CENTRAL_LIB));
        Files.writeString(g8.resolve("jk.toml"), """
                group = "com.example"
                name = "$name$"
                version = "0.1.0"
                java = 25
                # central-lib $lib_version$
                """);
        Path target = dir.resolve("made");

        assertThat(run("new", target.toString(), "-t", dir.resolve("template").toString(), "-N"))
                .isZero();

        assertThat(target.resolve("jk.toml")).content().contains("# central-lib 1.0");
        assertThat(mirror.requested()).contains(MavenStub.metadataPath(CENTRAL_LIB, "central-lib"));
        assertThat(origins.requested()).isEmpty();
        assertNoRepositoryDialed();
    }

    /** A jar whose {@code Main-Class} prints nothing and exits 0. */
    private static byte[] helloJar(Path dir) throws IOException {
        Path src = Files.createDirectories(dir.resolve("hello-src/e2e"));
        Files.writeString(
                src.resolve("Hello.java"),
                "package e2e;\npublic class Hello { public static void main(String[] a) {} }\n");
        Path classes = Files.createDirectories(dir.resolve("hello-classes"));
        int javac = ToolProvider.getSystemJavaCompiler()
                .run(
                        null,
                        null,
                        null,
                        "-d",
                        classes.toString(),
                        src.resolve("Hello.java").toString());
        assertThat(javac).isZero();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "e2e.Hello");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            jar.putNextEntry(new JarEntry("e2e/Hello.class"));
            jar.write(Files.readAllBytes(classes.resolve("e2e/Hello.class")));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    /** Central's library and the injected JUnit defaults under {@code central}; one library each under the other two. */
    static void publishAll(LoopbackHttp http, String central, String google, String jumpkick) {
        publishCentral(http, central);
        MavenStub stub = new MavenStub(http);
        stub.leaf(GOOGLE_LIB, "google-lib", "1.0");
        stub.leaf(JUMPKICK_LIB, "jk-lib", "1.0");
        if (!google.isEmpty()) move(http, "/" + GOOGLE_LIB.replace('.', '/') + "/", google);
        if (!jumpkick.isEmpty()) move(http, "/" + JUMPKICK_LIB.replace('.', '/') + "/", jumpkick);
    }

    static void publishCentral(LoopbackHttp http, String prefix) {
        new MavenStub(http)
                .leaf(CENTRAL_LIB, "central-lib", "1.0")
                .leaf("org.junit.jupiter", "junit-jupiter", DefaultTestDepsFixture.JUNIT_VERSION)
                .leaf("org.junit.platform", "junit-platform-launcher", DefaultTestDepsFixture.JUNIT_VERSION);
        if (prefix.isEmpty()) return;
        for (String root : List.of("/com/example/", "/org/junit/")) move(http, root, prefix);
    }

    /** Re-file every served path under {@code root} beneath {@code prefix}. */
    private static void move(LoopbackHttp http, String root, String prefix) {
        for (String path : List.copyOf(http.served().keySet())) {
            if (path.startsWith(root))
                http.served().put(prefix + path, http.served().remove(path));
        }
    }

    /** A project with one dependency from each of Central, Google and JumpKick; Central's floats, so its catalog is read. */
    static void manifest(Path dir) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "mirrored"
                version = "0.1.0"
                java = 25

                [dependencies]
                central-lib = { group = "%s", name = "central-lib", version = "^1.0" }
                google-lib = { group = "%s", name = "google-lib", version = "1.0" }
                jk-lib = { group = "%s", name = "jk-lib", version = "1.0" }
                """.formatted(CENTRAL_LIB, GOOGLE_LIB, JUMPKICK_LIB));
    }

    /** {@code [repositories]} placing Central, Google and JumpKick at the origin stub's three prefixes. */
    String origins() {
        return """
                [repositories.central]
                url = "%scentral/"

                [repositories.google]
                url = "%sgoogle/"

                [repositories.jumpkick]
                url = "%sjumpkick/"
                groups = ["cc.jumpkick", "cc.jumpkick.*"]
                """.formatted(origins.baseUrl(), origins.baseUrl(), origins.baseUrl());
    }

    String mirrorOf(String pattern) {
        return """

                [repositories.corp]
                url = "%s"
                mirror-of = ["%s"]
                """.formatted(mirror.baseUrl(), pattern);
    }

    /** The isolated home's starting {@code config.toml}, read before the first rewrite. */
    private @Nullable String homeConfig;

    /** The isolated home's {@code config.toml}: its starting text, the recording proxy, then {@code tables}. */
    void userConfig(String tables) throws IOException {
        Path config = JkDirs.userConfigFile();
        if (homeConfig == null) homeConfig = Files.readString(config);
        Files.writeString(config, homeConfig + "\n[network]\nproxy = \"" + proxy.url() + "\"\n\n" + tables);
    }

    /**
     * The public repositories' hosts, and Central's own mirror: none of them is dialed while the
     * user's repositories answer for them. Other hosts a fresh engine asks (the JDK catalog, the
     * library registry) are not repositories and are not this class's subject.
     */
    static final List<String> REPOSITORY_HOSTS = List.of(
            "repo.maven.apache.org",
            "repo1.maven.org",
            "maven-central.storage-download.googleapis.com",
            "dl.google.com",
            "maven.google.com",
            "jumpkick.build");

    void assertNoRepositoryDialed() {
        assertThat(proxy.hosts()).as("hosts dialed past the stubs").doesNotContainAnyElementsOf(REPOSITORY_HOSTS);
    }

    /** What {@code body} printed to stdout and stderr together. */
    static String output(Runnable body) {
        String[] out = {""};
        String err = Capture.stderr(() -> out[0] = Capture.stdout(body));
        return out[0] + err;
    }
}
