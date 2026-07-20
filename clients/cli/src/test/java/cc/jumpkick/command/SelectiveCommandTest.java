// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelectiveCommandTest {

    @Test
    void resolve_modules_selector(@TempDir Path tempDir) throws Exception {
        writeWorkspace(tempDir);
        String out = capture(() -> Jk.execute(
                "selective", "resolve", "-C", tempDir.toString(), "--modules", "api,worker"));
        assertThat(out).contains("api").contains("worker");
        assertThat(out).doesNotContain("libs/core");
    }

    @Test
    void prepare_writes_plan(@TempDir Path tempDir) throws Exception {
        writeWorkspace(tempDir);
        int code = Jk.execute(
                "selective", "prepare", "-C", tempDir.toString(), "--modules", "api");
        assertThat(code).isEqualTo(0);
        Path plan = tempDir.resolve(SelectiveCommand.PLAN_REL);
        assertThat(Files.isRegularFile(plan)).isTrue();
        String body = Files.readString(plan);
        assertThat(body).contains("\"api\"");
    }

    @Test
    void resolve_unknown_module_fails(@TempDir Path tempDir) throws Exception {
        writeWorkspace(tempDir);
        int code = Jk.execute(
                "selective", "resolve", "-C", tempDir.toString(), "--modules", "nope");
        assertThat(code).isNotEqualTo(0);
    }

    private static void writeWorkspace(Path root) throws Exception {
        for (String m : new String[] {"api", "worker", "libs/core"}) {
            Files.createDirectories(root.resolve(m));
            Files.writeString(
                    root.resolve(m).resolve("jk.toml"),
                    """
                    [project]
                    group = "com.ex"
                    name = "%s"
                    version = "1.0.0"
                    java = 25
                    """
                            .formatted(m.replace('/', '-')));
        }
        Files.writeString(
                root.resolve("jk.toml"),
                """
                [project]
                group = "com.ex"
                name = "ws"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["api", "worker", "libs/core"]
                """);
    }

    private static String capture(IntSupplier body) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer));
        try {
            body.getAsInt();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
