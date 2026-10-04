// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.IsolatedStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The real Vite generator from the npm registry under a Node.js from nodejs.org, then a jk build of it. */
@Tag("network")
@IsolatedStore
@DisabledOnOs(OS.WINDOWS)
class NewNodeNetworkTest {

    @Test
    void vite_react_generates_installs_and_builds(@TempDir Path dir) throws IOException {
        assertThat(run(
                        "new",
                        "--lang",
                        "node",
                        "-t",
                        "vite-react",
                        "--group",
                        "com.acme",
                        dir.resolve("web").toString()))
                .isZero();
        Path web = dir.resolve("web");
        assertThat(web.resolve("package-lock.json")).exists();
        assertThat(Files.readString(web.resolve("jk.toml"))).contains("node = ");

        assertThat(run("build", "-C", web.toString())).isZero();
        assertThat(web.resolve("dist/index.html")).exists();
    }
}
