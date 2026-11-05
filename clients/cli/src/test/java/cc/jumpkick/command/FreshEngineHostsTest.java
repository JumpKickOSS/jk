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
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every host a fresh engine and {@code jk lock} / {@code jk new} ask has a mirror spelling: with
 * a {@code mirror-of} table for the repositories and {@code [mirrors] libraries} and {@code jdk}
 * for the two feeds, nothing is dialed past the loopback stub. Without the feed mirrors the feeds
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
        assertThat(proxy.hosts()).as("hosts dialed past the mirror").isEmpty();
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

    /** Until the mirror was asked for every path, at most 30 s. */
    private void awaitRequested(String... paths) throws InterruptedException {
        for (int i = 0; i < 300 && !mirror.requested().containsAll(List.of(paths)); i++) Thread.sleep(100);
    }

    /** The isolated home's {@code config.toml}: its starting text, the recording proxy, a mirror of everything, then {@code tables}. */
    private void userConfig(String tables) throws IOException {
        Path config = JkDirs.userConfigFile();
        Files.writeString(config, Files.readString(config) + """

                [network]
                proxy = "%s"

                [repositories.corp]
                url = "%s"
                mirror-of = ["*"]
                """.formatted(proxy.url(), mirror.baseUrl()) + tables);
    }
}
