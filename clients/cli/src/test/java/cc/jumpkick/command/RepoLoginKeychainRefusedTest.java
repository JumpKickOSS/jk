// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.IsolatedHome;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.testing.RecordingProxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code jk repo login} whose secret is in a keychain that does not give it up: the lock that
 * then fails on the repository's 401 says why the credential was missing, above the 401 and once.
 */
@Tag("integration")
@IsolatedHome
class RepoLoginKeychainRefusedTest {

    private static final String GROUP = "com.foo.keychain";

    private static final String SECURED = "/secured";

    /** A keychain no machine has, so the record's pointer fails the same way on every host. */
    private static final String POINTER = "no-such-keychain repo/private";

    /** Central (the JUnit defaults) at the root; the private repository under {@link #SECURED}, 401 to everyone. */
    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @RegisterExtension
    final RecordingProxy proxy = new RecordingProxy();

    @Test
    void the_report_of_a_failed_lock_names_the_keychain_that_kept_the_secret_above_the_401(@TempDir Path dir)
            throws Exception {
        new MavenStub(http)
                .leaf("org.junit.jupiter", "junit-jupiter", DefaultTestDepsFixture.JUNIT_VERSION)
                .leaf("org.junit.platform", "junit-platform-launcher", DefaultTestDepsFixture.JUNIT_VERSION);
        http.server().createContext(http.base().getPath() + SECURED, exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        String securedUrl = http.baseUrl() + SECURED.substring(1) + "/";
        Path config = JkDirs.userConfigFile();
        Files.writeString(
                config, Files.readString(config) + """

                [network]
                proxy = "%s"

                [repositories.central]
                url = "%s"

                [repositories.private]
                url = "%s"
                groups = ["%s"]
                """.formatted(proxy.url(), http.baseUrl(), securedUrl, GROUP));
        Path records = JkDirs.creds().resolve("repo");
        Files.createDirectories(records);
        Path record = records.resolve("private");
        Files.writeString(
                record,
                "bearer " + http.base().getScheme() + "://" + http.base().getAuthority() + " keychain\n" + POINTER
                        + "\n");
        Files.setPosixFilePermissions(record, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "keychain"
                version = "0.1.0"
                java = 25

                [dependencies]
                lib = { group = "%s", name = "lib", version = "1.0" }
                """.formatted(GROUP));

        int[] exit = {-1};
        String out = Capture.stdout(() -> exit[0] = run("lock", "--agent", "-C", dir.toString()));

        assertThat(exit[0]).as(out).isNotZero();
        List<String> lines = out.lines().toList();
        assertThat(lines.getFirst()).startsWith("FAIL lock");
        int warning = indexOf(lines, l -> l.contains("private: could not read the secret from the no-such-keychain"));
        int error = indexOf(lines, l -> l.startsWith("E "));
        assertThat(warning).as(out).isPositive().isLessThan(error);
        assertThat(lines.get(warning)).startsWith("W private: could not read the secret");
        assertThat(lines.stream().filter(l -> l.contains("could not read the secret")))
                .as(out)
                .hasSize(1);
        assertThat(String.join("\n", lines.subList(error, lines.size()))).contains("HTTP 401");
    }

    private static int indexOf(List<String> lines, Predicate<String> wanted) {
        for (int i = 0; i < lines.size(); i++) {
            if (wanted.test(lines.get(i))) return i;
        }
        return -1;
    }
}
