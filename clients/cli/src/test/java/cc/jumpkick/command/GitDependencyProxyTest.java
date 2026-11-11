// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.IsolatedHome;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.testing.RecordingProxy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A git dependency is fetched the way jk fetches everything else: through the user's {@code
 * [network] proxy}, and direct for a host on its {@code no-proxy} list. The proxy is a {@link
 * RecordingProxy}, which writes down each host it is asked for and refuses it, so the lock fails
 * either way; the proxy's log is what tells the two routes apart.
 */
@Tag("integration")
@IsolatedHome
class GitDependencyProxyTest {

    @RegisterExtension
    final RecordingProxy proxy = new RecordingProxy();

    @Test
    void a_git_dependency_is_cloned_through_the_configured_proxy(@TempDir Path dir) throws Exception {
        network();
        manifest(dir, "https://git.corp.test/acme/lib.git");

        assertThat(run("lock", "-C", dir.toString())).isNotZero();

        assertThat(proxy.hosts()).as("the clone's CONNECT").contains("git.corp.test");
    }

    @Test
    void a_git_dependency_on_a_no_proxy_host_goes_direct(@TempDir Path dir) throws Exception {
        network();
        manifest(dir, "https://git.direct.invalid/acme/lib.git");

        assertThat(run("lock", "-C", dir.toString())).isNotZero();

        assertThat(proxy.hosts()).as("hosts handed to the proxy").doesNotContain("git.direct.invalid");
    }

    private void network() throws IOException {
        Path config = JkDirs.userConfigFile();
        Files.writeString(config, Files.readString(config) + """

                [network]
                proxy = "%s"
                no-proxy = ["git.direct.invalid"]
                """.formatted(proxy.url()));
    }

    private static void manifest(Path dir, String url) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "consumer"
                version = "0.1.0"
                java = 25

                [dependencies]
                lib = { git = "%s", tag = "v1.0.0" }
                """.formatted(url));
    }
}
