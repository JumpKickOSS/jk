// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.tool.AppLauncher;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A module declaring {@code [install] product-bin} is jk's own client: its install plan links the
 * built native binary over the PATH entry of that name — the one name every other install is
 * refused under {@code bin/} — and beside it renders the JVM launcher {@code jk-jvm}, a {@code
 * java -cp} script over the thin jar's closure, which is the whole install where no native client
 * was built.
 */
class ExecPlansProductBinTest {

    @Test
    void the_native_client_is_linked_over_the_path_entry_with_the_jvm_launcher_beside_it(@TempDir Path tmp)
            throws Exception {
        Path dir = client(tmp);
        Files.writeString(dir.resolve("target/jk"), "native client");
        Path bin = tmp.resolve("home/bin");

        ExecPlan plan = ExecPlans.execPlan(dir, tmp.resolve("cache"), "install", null, null, bin, null);

        assertThat(plan.error()).isNull();
        assertThat(plan.linkSrcs())
                .containsExactly(dir.resolve("target/jk").toAbsolutePath().toString());
        assertThat(plan.linkDests()).containsExactly(bin.resolve("jk").toString());
        assertThat(plan.binPath()).isEqualTo(bin.resolve("jk").toString());
        assertThat(plan.launcherPath())
                .isEqualTo(bin.resolve(AppLauncher.launcherFileName("jk-jvm")).toString());
        assertThat(plan.launcherScript())
                .contains("cc.jumpkick.cli.Jk")
                .contains("-cp")
                .contains("jk-cli-0.1.0.jar");
    }

    @Test
    void without_a_built_native_client_the_jvm_launcher_is_the_whole_install(@TempDir Path tmp) throws Exception {
        Path dir = client(tmp);
        Path bin = tmp.resolve("bin");

        ExecPlan plan = ExecPlans.execPlan(dir, tmp.resolve("cache"), "install", null, null, bin, null);

        assertThat(plan.error()).isNull();
        assertThat(plan.linkSrcs()).as("nothing to link over the PATH client").isEmpty();
        assertThat(plan.launcherPath())
                .isEqualTo(bin.resolve(AppLauncher.launcherFileName("jk-jvm")).toString());
        assertThat(plan.binPath()).isEqualTo(plan.launcherPath());
        assertThat(plan.launcherScript()).contains("cc.jumpkick.cli.Jk");
    }

    private static Path client(Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("cli"));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "cc.jumpkick"
                name = "jk-cli"
                version = "0.1.0"
                java = 25

                [application]
                main = "cc.jumpkick.cli.Jk"

                [native]
                enabled = "always"
                name = "jk"

                [install]
                product-bin = "jk"
                """);
        Files.createDirectories(dir.resolve("target"));
        return dir;
    }
}
