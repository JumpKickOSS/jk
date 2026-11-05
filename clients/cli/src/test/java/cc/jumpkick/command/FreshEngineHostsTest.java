// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.IsolatedHome;
import cc.jumpkick.cli.testing.RecordingProxy;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every host a fresh engine and {@code jk lock} / {@code jk new} ask has a mirror spelling: with
 * a {@code mirror-of} table for the repositories, {@code [mirrors] libraries} and {@code jdk}
 * for the two feeds and {@code [templates] official} for the template catalog, nothing is dialed
 * past the loopback stub and the local repository. Without the feed mirrors the feeds
 * are optional: their hosts are the only ones dialed, and the commands neither fail nor say so.
 * The user's {@code [network] proxy} is a {@link RecordingProxy}, the request log of everything
 * that went past the stub.
 */
@Tag("integration")
@IsolatedHome
class FreshEngineHostsTest {

    static final String REGISTRY = "/libs/libraries.toml";
    static final String JDK_FEED = "/jdk/download.jetbrains.com/jdk/feed/v1/jdks.json";

    /** The hosts of the two optional feeds: the library registry and the JDK catalog. */
    static final List<String> FEED_HOSTS = List.of("raw.githubusercontent.com", "download.jetbrains.com");

    @RegisterExtension
    final LoopbackHttp mirror = new LoopbackHttp();

    @RegisterExtension
    final RecordingProxy proxy = new RecordingProxy();

    @Test
    void with_every_origin_mirrored_nothing_is_dialed_past_the_mirror(@TempDir Path dir) throws Exception {
        UserConfigMirrorTest.publishAll(mirror, "", "", "");
        mirror.serve(REGISTRY, "[libraries]\nfoo = \"com.acme:foo\"\n");
        mirror.serve(JDK_FEED, "[]\n");
        userConfig("""

                [mirrors]
                libraries = "%slibs/"
                jdk = "%sjdk/"
                """.formatted(mirror.baseUrl(), mirror.baseUrl()));
        UserConfigMirrorTest.manifest(dir);

        assertThat(run("lock", "-C", dir.toString())).isZero();
        assertThat(run("new", dir.resolve("made").toString(), "-N")).isZero();
        // The engine's first maintenance pass reads both feeds too: wait for it to have asked.
        awaitRequested(REGISTRY, JDK_FEED);

        assertThat(mirror.requested()).contains(REGISTRY, JDK_FEED);
        // And it clones the official catalog from its mirror, the local repository.
        assertThat(awaitCatalogClone())
                .as("the catalog cloned from [templates] official")
                .isTrue();
        assertThat(proxy.hosts()).as("hosts dialed past the mirror").isEmpty();
    }

    /**
     * The catalog's git honours {@code [network] proxy} like jk's own requests: with the official
     * catalog left at GitHub, its clone is a CONNECT the proxy sees (and refuses), and {@code jk
     * new} still makes a project.
     */
    @Test
    void the_official_catalog_clone_goes_through_the_configured_proxy(@TempDir Path dir) throws Exception {
        UserConfigMirrorTest.publishAll(mirror, "", "", "");
        Path config = JkDirs.userConfigFile();
        Files.writeString(config, Files.readString(config) + """

                [network]
                proxy = "%s"

                [repositories.corp]
                url = "%s"
                mirror-of = ["*"]
                """.formatted(proxy.url(), mirror.baseUrl()));

        assertThat(run("new", dir.resolve("made").toString(), "-N")).isZero();
        for (int i = 0; i < 600 && !proxy.hosts().contains("github.com"); i++) Thread.sleep(100);

        assertThat(proxy.hosts()).as("the official catalog's clone").contains("github.com");
        assertThat(dir.resolve("made/jk.toml")).isRegularFile();
    }

    @Test
    void without_feed_mirrors_the_feeds_are_the_only_hosts_dialed_and_nothing_fails(@TempDir Path dir)
            throws Exception {
        UserConfigMirrorTest.publishAll(mirror, "", "", "");
        userConfig("");
        UserConfigMirrorTest.manifest(dir);

        int[] exits = {-1, -1};
        String out = UserConfigMirrorTest.output(() -> {
            exits[0] = run("lock", "-C", dir.toString());
            exits[1] = run("new", dir.resolve("made").toString(), "-N");
        });

        assertThat(exits).containsExactly(0, 0);
        assertThat(proxy.hosts()).isSubsetOf(FEED_HOSTS);
        for (String host : FEED_HOSTS) assertThat(out).doesNotContain(host);
        assertThat(out.toLowerCase(Locale.ROOT)).doesNotContain("warn");
    }

    /** Until a clone of {@link #catalog} sits in the store's templates directory, at most 60 s. */
    private boolean awaitCatalogClone() throws InterruptedException, IOException {
        Path templates = JkDirs.templates();
        for (int i = 0; i < 600; i++) {
            if (Files.isDirectory(templates)) {
                try (var clones = Files.list(templates)) {
                    if (clones.anyMatch(c -> Files.isDirectory(c.resolve("java/spring/hello.g8")))) return true;
                }
            }
            Thread.sleep(100);
        }
        return false;
    }

    /**
     * A bare repository holding a one-template catalog, standing in for a mirror of the official
     * one; its {@code file:} URL is the {@code [templates] official} value.
     */
    private static String catalog(Path dir) throws IOException, InterruptedException {
        Path work = dir.resolve("catalog-work");
        Path template = Files.createDirectories(work.resolve("java/spring/hello.g8"));
        Files.writeString(template.resolve("default.properties"), "name=hello\n");
        git(work, "init", "-q", "-b", "main");
        git(work, "add", ".");
        git(work, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "catalog");
        Path bare = dir.resolve("catalog.git");
        git(dir, "clone", "-q", "--bare", work.toString(), bare.toString());
        return bare.toUri().toString();
    }

    private static void git(Path cwd, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process git = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        assertThat(git.waitFor()).as("git %s", String.join(" ", args)).isZero();
    }

    /** Until the mirror was asked for every path, at most 30 s. */
    private void awaitRequested(String... paths) throws InterruptedException {
        for (int i = 0; i < 300 && !mirror.requested().containsAll(List.of(paths)); i++) Thread.sleep(100);
    }

    /**
     * The isolated home's {@code config.toml}: its starting text, the recording proxy, a mirror of
     * every repository, the official template catalog at a local repository, then {@code tables}.
     */
    private void userConfig(String tables) throws IOException, InterruptedException {
        Path config = JkDirs.userConfigFile();
        String official = catalog(Files.createTempDirectory(config.getParent(), "catalog"));
        Files.writeString(
                config, Files.readString(config) + """

                [network]
                proxy = "%s"

                [repositories.corp]
                url = "%s"
                mirror-of = ["*"]

                [templates]
                official = "%s"
                """.formatted(proxy.url(), mirror.baseUrl(), official) + tables);
    }
}
