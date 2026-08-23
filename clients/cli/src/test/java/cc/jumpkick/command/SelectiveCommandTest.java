// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.testing.Capture;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class SelectiveCommandTest {

    @Test
    void resolve_modules_selector(@TempDir Path tempDir) throws Exception {
        writeWorkspace(tempDir);
        String out = Capture.stdout(
                () -> Jk.execute("selective", "resolve", "-C", tempDir.toString(), "--modules", "api,worker"));
        assertThat(out).contains("api").contains("worker");
        assertThat(out).doesNotContain("libs/core");
        // Script mode: the module list is the payload, so no envelope around it.
        assertThat(out).doesNotStartWith("\n");
    }

    @Test
    void prepare_settles_inside_the_envelope(@TempDir Path tempDir) throws Exception {
        writeWorkspace(tempDir);
        // prepare calls resolve() as a helper; that must not carry resolve's script mode over.
        String out =
                Capture.stdout(() -> Jk.execute("selective", "prepare", "--modules", "api", "-C", tempDir.toString()));
        assertThat(out).startsWith("\n").endsWith("\n\n");
    }

    @Test
    void prepare_writes_plan(@TempDir Path tempDir) throws Exception {
        writeWorkspace(tempDir);
        int code = Jk.execute("selective", "prepare", "-C", tempDir.toString(), "--modules", "api");
        assertThat(code).isEqualTo(0);
        Path plan = tempDir.resolve(SelectiveCommand.PLAN_REL);
        assertThat(Files.isRegularFile(plan)).isTrue();
        String body = Files.readString(plan);
        assertThat(body).contains("\"api\"");
        assertThat(body).contains("contentHashes");
        assertThat(body).contains("sha256:");
    }

    @Test
    void run_skips_when_content_hashes_match(@TempDir Path tempDir) throws Exception {
        writeWorkspace(tempDir);
        Files.createDirectories(tempDir.resolve("api/src/main/java"));
        Files.writeString(tempDir.resolve("api/src/main/java/A.java"), "class A {}");
        assertThat(Jk.execute("selective", "prepare", "-C", tempDir.toString(), "--modules", "api"))
                .isZero();
        // Second prepare not needed — run with plan should see matching hashes and skip.
        String out = Capture.stdout(() -> Jk.execute("selective", "run", "build", "-C", tempDir.toString()));
        assertThat(out).contains("nothing changed");
    }

    @Test
    void content_hash_changes_when_source_edits(@TempDir Path tempDir) throws Exception {
        writeWorkspace(tempDir);
        Files.createDirectories(tempDir.resolve("api/src/main/java"));
        Files.writeString(tempDir.resolve("api/src/main/java/A.java"), "class A {}");
        String h1 = SelectiveCommand.fingerprintModule(tempDir.resolve("api"));
        Files.writeString(tempDir.resolve("api/src/main/java/A.java"), "class A { int x; }");
        String h2 = SelectiveCommand.fingerprintModule(tempDir.resolve("api"));
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void dirty_run_passes_modules_filter_not_full_workspace(@TempDir Path tempDir) throws Exception {
        writeWorkspace(tempDir);
        Files.createDirectories(tempDir.resolve("api/src/main/java"));
        Files.writeString(tempDir.resolve("api/src/main/java/A.java"), "class A {}");
        assertThat(Jk.execute("selective", "prepare", "-C", tempDir.toString(), "--modules", "api"))
                .isZero();
        Files.writeString(tempDir.resolve("api/src/main/java/A.java"), "class A { int x; }");
        // Capture stderr for dirty line; stdout for module completion lines.
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream origErr = System.err;
        System.setErr(new PrintStream(err));
        String out;
        try {
            out = Capture.stdout(() -> Jk.execute("selective", "run", "build", "-C", tempDir.toString()));
        } finally {
            System.setErr(origErr);
        }
        String e = err.toString(StandardCharsets.UTF_8);
        assertThat(e).contains("content-hash dirty modules: api");
        // Must not claim a full two-module workspace cascade when only api is dirty.
        assertThat(out + e).doesNotContain("2 of 2");
    }

    @Test
    void resolve_unknown_module_fails(@TempDir Path tempDir) throws Exception {
        writeWorkspace(tempDir);
        int code = Jk.execute("selective", "resolve", "-C", tempDir.toString(), "--modules", "nope");
        assertThat(code).isNotEqualTo(0);
    }

    private static void writeWorkspace(Path root) throws Exception {
        for (String m : new String[] {"api", "worker", "libs/core"}) {
            Files.createDirectories(root.resolve(m));
            Files.writeString(root.resolve(m).resolve("jk.toml"), """
                    group = "com.ex"
                    name = "%s"
                    version = "1.0.0"
                    java = 25
                    """.formatted(m.replace('/', '-')));
        }
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["api", "worker", "libs/core"]
                """);
    }
}
