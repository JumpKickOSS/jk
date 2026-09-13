// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.command.system.SelfCommand;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk self materialize} settles with one Self wedge naming the product version. */
class SelfMaterializeCommandTest {

    @TempDir
    Path isolatedHome;

    private String prevHome;
    private String prevState;

    @BeforeEach
    void isolateHome() throws Exception {
        prevHome = System.getProperty("jk.env.JK_HOME");
        prevState = System.getProperty("jk.env.JK_STATE_DIR");
        System.setProperty("jk.env.JK_HOME", isolatedHome.toString());
        System.setProperty(
                "jk.env.JK_STATE_DIR",
                Files.createDirectories(isolatedHome.resolve("state")).toString());
        CliOutput.beginCommand(false);
    }

    @AfterEach
    void restoreHome() {
        if (prevHome == null) System.clearProperty("jk.env.JK_HOME");
        else System.setProperty("jk.env.JK_HOME", prevHome);
        if (prevState == null) System.clearProperty("jk.env.JK_STATE_DIR");
        else System.setProperty("jk.env.JK_STATE_DIR", prevState);
    }

    @Test
    void materialize_settles_with_versioned_self_wedge(@TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("jk-engine-" + JkVersion.VERSION + ".jar");
        Files.writeString(jar, "engine-bytes");
        Path client = tmp.resolve("jk.exe");
        Files.writeString(client, "client");

        int[] exit = {0};
        var streams = Capture.both(() -> {
            exit[0] = materialize(client.toString(), jar.toString());
        });
        String plain = TestAnsi.strip(streams.out());

        assertThat(exit[0]).isZero();
        assertThat(plain).contains("Self");
        assertThat(plain).contains("Materialized JumpKick " + JkVersion.VERSION);
        assertThat(plain).doesNotContain("nerd-font");
        assertThat(plain).doesNotContain("materialized ");
        assertThat(plain).doesNotContain(jar.toString());
        assertThat(streams.err()).isEmpty();

        Path engineHome = JkDirs.productLib().resolve("jk-engine");
        assertThat(engineHome.resolve(jar.getFileName())).exists();
        Path cfg = JkDirs.userConfigFile();
        assertThat(cfg).exists();
        assertThat(Files.readString(cfg)).contains("nerd-font = \"auto\"");
    }

    @Test
    void missing_engine_jar_fails_without_materialize_wedge() {
        Path missing = isolatedHome.resolve("no-such-engine.jar");
        int[] exit = {0};
        var streams = Capture.both(() -> {
            exit[0] = materialize("jk.exe", missing.toString());
        });

        assertThat(exit[0]).isNotZero();
        assertThat(TestAnsi.strip(streams.err())).contains("Self").contains("engine jar not found");
        assertThat(TestAnsi.strip(streams.out())).doesNotContain("Materialized JumpKick");
    }

    /**
     * A client only ever spawns its own version's engine jar, so materializing someone else's is a
     * write nothing will read — worse, the install is keyed by the client's version, so the bytes
     * would land under a name that lies about them. The refusal is loud (non-zero, both versions
     * named) because its callers are installers: {@code jk install} and install.sh both report
     * success for anything that exits 0.
     */
    @Test
    void engine_jar_of_another_version_is_refused_naming_both(@TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("jk-engine-0.0.1-other.jar");
        Files.writeString(jar, "engine-bytes");

        int[] exit = {0};
        var streams = Capture.both(() -> {
            exit[0] = materialize("jk.exe", jar.toString());
        });

        assertThat(exit[0]).isNotZero();
        assertThat(TestAnsi.strip(streams.err()))
                .contains("Self")
                .contains("0.0.1-other")
                .contains(JkVersion.VERSION);
        assertThat(TestAnsi.strip(streams.out())).doesNotContain("Materialized JumpKick");
        assertThat(JkDirs.productLib().resolve("jk-engine")).doesNotExist();
    }

    @Test
    void a_jar_that_is_not_named_jk_engine_is_refused() throws Exception {
        Path jar = isolatedHome.resolve("engine.jar");
        Files.writeString(jar, "engine-bytes");

        int[] exit = {0};
        var streams = Capture.both(() -> {
            exit[0] = materialize("jk.exe", jar.toString());
        });

        assertThat(exit[0]).isNotZero();
        assertThat(TestAnsi.strip(streams.err())).contains("Self").contains("not a jk-engine jar name");
        assertThat(TestAnsi.strip(streams.out())).doesNotContain("Materialized JumpKick");
    }

    private static int materialize(String client, String engineJar) {
        try {
            return new SelfCommand.MaterializeSub()
                    .run(Invocation.builder()
                            .addPositional(client)
                            .addPositional(engineJar)
                            .build());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
