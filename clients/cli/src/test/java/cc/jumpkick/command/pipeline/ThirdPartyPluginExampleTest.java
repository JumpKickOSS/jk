// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.testing.RepoRoot;
import cc.jumpkick.testing.SysProps;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The manual's {@code examples/third-party-plugin}, end to end, the way a plugin author outside
 * this tree meets it: the SDK and its one dependency are published to a Maven repository with
 * {@code jk publish} — the release step, aimed at a {@code file://} repository here — the sample
 * locks and builds against that repository alone, and a consumer project pins the resulting jar,
 * has its {@code [hello]} table validated by the plugin's schema, and builds with the plugin's
 * javac contribution and its trusted code layer.
 *
 * <p>The SDK bytes are the ones this checkout's own build packaged, so the test needs a prior
 * {@code jk build} and skips without one rather than inventing an SDK.
 */
@Tag("integration")
@SysProps.TempRoots("jk.m2.local")
class ThirdPartyPluginExampleTest {

    private static final Path ROOT = RepoRoot.find(ThirdPartyPluginExampleTest.class);
    private static final Path EXAMPLE = ROOT.resolve("docs/user/examples/third-party-plugin");
    /** The release the sample targets: the running version without a {@code -SNAPSHOT}, which a snapshot run adds. */
    private static final String VERSION = JkVersion.VERSION.replaceFirst("-SNAPSHOT$", "");

    @Test
    void a_plugin_compiled_against_the_published_sdk_loads_in_a_consumer_build(@TempDir Path dir) throws Exception {
        // 1. The release step: the SDK's dependency, then the SDK, into one Maven repository.
        Path repo = Files.createDirectories(dir.resolve("sdk-repo"));
        URI repoUrl = repo.toUri();
        publish(
                dir.resolve("host"),
                "jk-host",
                "The JDK-only floor every plugin worker shares",
                "[dependencies]\njspecify = \"1.0.1\"\n",
                ROOT.resolve("shared/host"),
                repoUrl);
        publish(
                dir.resolve("sdk"),
                "jk-plugin-sdk",
                "Plugin SPI",
                "[dependencies]\njk-host = \"cc.jumpkick:jk-host:" + VERSION + "\"\n",
                ROOT.resolve("shared/plugin-sdk"),
                repoUrl);
        Path sdkPom = repo.resolve("cc/jumpkick/jk-plugin-sdk/" + VERSION + "/jk-plugin-sdk-" + VERSION + ".pom");
        assertThat(sdkPom).exists();
        assertThat(Files.readString(sdkPom)).contains("<artifactId>jk-host</artifactId>");
        assertThat(repo.resolve("cc/jumpkick/jk-plugin-sdk/" + VERSION + "/jk-plugin-sdk-" + VERSION + "-sources.jar"))
                .exists();

        // 2. The sample, as committed, locked against that repository alone and built.
        Path plugin = dir.resolve("hello-plugin");
        copyTree(EXAMPLE, plugin);
        // The sample pins the last released SDK; the test compiles it against the SDK it just built.
        Path manifest = plugin.resolve("jk.toml");
        Path pluginToml = plugin.resolve("src/main/resources/jk-plugin.toml");
        Matcher pinned = Pattern.compile("jk-plugin-sdk:(\\d+\\.\\d+\\.\\d+)\"").matcher(Files.readString(manifest));
        assertThat(pinned.find()).as("the sample pins a jk-plugin-sdk release").isTrue();
        String pin = pinned.group(1);
        assertThat(Files.readString(pluginToml))
                .as("the manifest names the same SDK release the code compiles against")
                .contains("sdk       = \"" + pin + "\"");
        assertThat(Versions.compare(pin, VERSION))
                .as("the sample pins a release the running jk has reached, never a later one")
                .isLessThanOrEqualTo(0);
        Files.writeString(
                manifest, Files.readString(manifest).replace("jk-plugin-sdk:" + pin, "jk-plugin-sdk:" + VERSION));
        Files.writeString(pluginToml, Files.readString(pluginToml).replace("\"" + pin + "\"", "\"" + VERSION + "\""));
        // The SDK comes from the repository the release step wrote. cc.jumpkick resolves from the
        // jumpkick repository alone, so that repository stands in for it; the test launcher jk adds
        // to every test graph still comes from Central.
        Files.writeString(
                plugin.resolve("jk.toml"),
                Files.readString(plugin.resolve("jk.toml"))
                        + "\n[repositories.jumpkick]\nurl = \"" + repoUrl + "\"\n"
                        + "groups = [\"cc.jumpkick\", \"cc.jumpkick.*\"]\n");
        int[] lockExit = new int[1];
        String lockOut = Capture.stdout(() -> lockExit[0] = run("lock", "--no-ansi", "-C", plugin.toString()));
        assertThat(lockExit[0]).as(lockOut).isEqualTo(0);
        assertThat(Files.readString(plugin.resolve("jk-lock.toml"))).contains("jk-plugin-sdk");
        assertThat(run("build", "-C", plugin.toString(), "--skip-tests")).isEqualTo(0);
        Path jar = plugin.resolve("target/hello-plugin-0.1.0.jar");
        assertThat(jar).exists();
        try (JarFile jf = new JarFile(jar.toFile())) {
            assertThat(jf.getEntry("jk-plugin.toml"))
                    .as("the manifest rides at the jar root")
                    .isNotNull();
            assertThat(jf.getEntry("com/example/hello/HelloPlugin.class")).isNotNull();
            assertThat(jf.getEntry("META-INF/services/cc.jumpkick.plugin.Plugin"))
                    .isNotNull();
            assertThat(requireNonNull(jf.getManifest()).getMainAttributes().getValue("Main-Class"))
                    .as("a plugin jar's entry is the SDK's worker host")
                    .isEqualTo("cc.jumpkick.plugin.process.PluginMain");
        }

        // 3. A consumer pins the jar by content, trusts its code, and builds under its table; its
        // lock carries the plugin's SDK floor as ordinary rows.
        Path app = Files.createDirectories(dir.resolve("app"));
        Files.createDirectories(app.resolve("src/main/java/app"));
        Files.writeString(app.resolve("src/main/java/app/App.java"), """
                package app;
                public class App { public static String greet(String who) { return "hi " + who; } }
                """);
        Files.writeString(app.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "0.1.0"
                java    = 25

                # The plugin forks with the SDK floor it compiled against, resolved from this
                # module's repositories at lock time — the same file repository the plugin used,
                # standing in for jumpkick.
                [repositories.jumpkick]
                url    = "%s"
                groups = ["cc.jumpkick", "cc.jumpkick.*"]

                [plugins]
                hello = { path = "%s", sha256 = "%s" }

                [hello]
                greeting = "hi"
                """.formatted(
                        repoUrl, jar.toString().replace('\\', '/'), Hashing.sha256Hex(jar)));
        int[] appLockExit = new int[1];
        Capture.Streams appLock =
                Capture.both((Runnable) () -> appLockExit[0] = run("lock", "--no-ansi", "-C", app.toString()));
        assertThat(appLockExit[0]).as(appLock.out() + appLock.err()).isEqualTo(0);
        assertThat(appLock.out() + appLock.err())
                .as("the manifest's sdk pins the floor, so the running-version fallback note is absent")
                .doesNotContain("declares no SDK version");
        assertThat(Files.readString(app.resolve("jk-lock.toml")))
                .as("the plugin's SDK floor rides in the consumer's lock at the manifest's SDK version")
                .contains("cc.jumpkick:jk-plugin-sdk:jar:")
                .contains("cc.jumpkick:jk-host:jar:")
                .contains("scopes   = [\"plugin\"]")
                .contains("pinned-by = \"plugin:path:hello\"");
        try (Stream<Path> manifests = Files.list(app.resolve("target/plugin-manifests"))) {
            assertThat(manifests.map(p -> p.getFileName().toString()))
                    .as("the plugin's manifest is materialized for the consumer")
                    .anyMatch(n -> n.endsWith(".jk-plugin.toml"));
        }
        // A path pin is trusted by the bytes its row carries, never by the alias that names it.
        assertThat(run("trust", "plugin", "path:hello")).isEqualTo(64);
        assertThat(run("trust", "plugin", "sha256:" + Hashing.sha256Hex(jar))).isEqualTo(0);
        buildGreen(app, "first build");
        assertThat(app.resolve("target/app-0.1.0.jar")).exists();
        // The plugin's [[contribute.compiler-args]] adds -parameters: the consumer's compile step
        // honours a path-pinned plugin's javac contribution, on the first build and on the cached
        // second one alike.
        Path appClass = app.resolve("target/classes/app/App.class");
        assertThat(carriesMethodParameters(appClass)).as("first build").isTrue();
        buildGreen(app, "second build");
        assertThat(carriesMethodParameters(appClass)).as("second build").isTrue();
    }

    /** {@code jk build -S} of {@code app}, its output the message when the exit is not 0. */
    private static void buildGreen(Path app, String which) throws Exception {
        int[] exit = new int[1];
        Capture.Streams streams = Capture.both(
                (Runnable) () -> exit[0] = run("build", "--no-ansi", "-C", app.toString(), "--skip-tests"));
        assertThat(exit[0]).as(which + ":\n" + streams.out() + streams.err()).isEqualTo(0);
    }

    /** javac writes the {@code MethodParameters} attribute only under {@code -parameters}. */
    private static boolean carriesMethodParameters(Path classFile) throws IOException {
        return new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1).contains("MethodParameters");
    }

    /**
     * A standalone project shaped like the workspace module — same coordinate, description,
     * dependencies and sources — built and published with {@code jk publish}, which builds first.
     * {@code cc.jumpkick} resolves from {@code repoUrl}, where the SDK's own dependency lands first.
     */
    private static void publish(Path project, String name, String description, String deps, Path module, URI repoUrl)
            throws IOException {
        copyTree(module.resolve("src/main"), project.resolve("src/main"));
        Files.writeString(project.resolve("jk.toml"), """
                group       = "cc.jumpkick"
                name        = "%s"
                version     = "%s"
                description = "%s"
                java        = 17
                %s
                [repositories.jumpkick]
                url    = "%s"
                groups = ["cc.jumpkick", "cc.jumpkick.*"]
                """.formatted(name, VERSION, description, deps, repoUrl));
        assertThat(run(
                        "publish",
                        "-C",
                        project.toString(),
                        "--repo-url",
                        repoUrl.toString(),
                        "--skip-tests",
                        "--allow-snapshot"))
                .isEqualTo(0);
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target);
            }
        }
    }
}
