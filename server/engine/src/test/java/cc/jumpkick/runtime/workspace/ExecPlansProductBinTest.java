// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.protocol.ExecPlan;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A module declaring {@code [install] product-bin} is jk's own client: its install plan links the
 * built native binary over the PATH entry of that name — the one name every other install is
 * refused under {@code bin/} — with no launcher script and no lib directory.
 */
class ExecPlansProductBinTest {

    @Test
    void the_native_client_is_linked_over_the_path_entry(@TempDir Path tmp) throws Exception {
        Path dir = client(tmp);
        Files.writeString(dir.resolve("target/jk"), "native client");
        Path bin = tmp.resolve("home/bin");

        ExecPlan plan = ExecPlans.execPlan(dir, tmp.resolve("cache"), "install", null, null, bin, null);

        assertThat(plan.error()).isNull();
        assertThat(plan.linkSrcs())
                .containsExactly(dir.resolve("target/jk").toAbsolutePath().toString());
        assertThat(plan.linkDests()).containsExactly(bin.resolve("jk").toString());
        assertThat(plan.binPath()).isEqualTo(bin.resolve("jk").toString());
        assertThat(plan.launcherScript()).isEmpty();
    }

    @Test
    void without_a_built_native_client_the_plan_names_what_is_missing(@TempDir Path tmp) throws Exception {
        Path dir = client(tmp);

        ExecPlan plan = ExecPlans.execPlan(dir, tmp.resolve("cache"), "install", null, null, tmp.resolve("bin"), null);

        assertThat(plan.error())
                .contains("product-bin")
                .contains(dir.resolve("target/jk").toString());
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
