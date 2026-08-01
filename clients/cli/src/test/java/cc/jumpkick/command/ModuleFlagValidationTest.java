// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1360: {@code jk compile} and {@code jk image} consume the shared {@code -m/--modules}
 * selection they advertise — an invalid selector fails fast instead of being silently ignored,
 * and {@code image} demands exactly one selected module.
 */
class ModuleFlagValidationTest {

    private static void workspace(Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "ws"
                version = "0.0.1"
                jdk = 25
                [workspace]
                modules = ["mods/a", "mods/b"]
                """);
        for (String m : new String[] {"a", "b"}) {
            Path mod = dir.resolve("mods/" + m);
            Files.createDirectories(mod);
            Files.writeString(mod.resolve("jk.toml"), """
                    [project]
                    group = "t"
                    name = "%s"
                    version = "0.0.1"
                    jdk = 25
                    java = 25
                    [application]
                    main = "t.Main"
                    """.formatted(m));
        }
    }

    private record Run(int exit, String err) {}

    private static Run run(String... args) throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream orig = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            return new Run(Jk.execute(args), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(orig);
        }
    }

    @Test
    void build_headless_rejects_an_unknown_selector(@TempDir Path dir) throws Exception {
        // JK-1363: the CI shape `jk build -m … --output json` validates the selector (and honors
        // it) instead of silently building everything.
        workspace(dir);
        Run r = run("build", "-C", dir.toString(), "-m", "bogus", "--output", "json");
        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("bogus");
    }

    @Test
    void compile_rejects_an_unknown_selector(@TempDir Path dir) throws Exception {
        workspace(dir);
        Run r = run("compile", "-C", dir.toString(), "-m", "bogus");
        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("bogus");
    }

    @Test
    void image_rejects_an_unknown_selector(@TempDir Path dir) throws Exception {
        workspace(dir);
        Run r = run("image", "-C", dir.toString(), "-m", "bogus");
        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("bogus");
    }

    @Test
    void image_demands_exactly_one_selected_module(@TempDir Path dir) throws Exception {
        workspace(dir);
        Run r = run("image", "-C", dir.toString(), "-m", "a,b");
        assertThat(r.exit()).isEqualTo(64);
        assertThat(r.err()).contains("exactly one module");
    }
}
