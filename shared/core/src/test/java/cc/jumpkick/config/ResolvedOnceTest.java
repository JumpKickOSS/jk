// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.IoLedger;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A module's resolved manifest is a fact about the request's inputs: every step reading a module asks
 * for it, so it is resolved once per request for each manifest as read, and again only when the
 * member or its root is read anew.
 */
class ResolvedOnceTest {

    private static final int MEMBERS = 12;

    @AfterEach
    void clear() {
        RequestScope.clearAll();
    }

    @Test
    void a_request_resolves_each_member_once_however_often_it_is_parsed(@TempDir Path tmp) throws IOException {
        List<Path> manifests = members(workspace(tmp));

        long before = JkBuildParser.resolves();
        inRequest(() -> {
            for (int pass = 0; pass < 3; pass++) manifests.forEach(ResolvedOnceTest::parse);
        });
        assertThat(JkBuildParser.resolves() - before).isEqualTo(MEMBERS);
    }

    @Test
    void a_caller_with_no_request_resolves_on_every_parse(@TempDir Path tmp) throws IOException {
        List<Path> manifests = members(workspace(tmp));

        long before = JkBuildParser.resolves();
        manifests.forEach(ResolvedOnceTest::parse);
        manifests.forEach(ResolvedOnceTest::parse);
        assertThat(JkBuildParser.resolves() - before).isEqualTo(2L * MEMBERS);
    }

    @Test
    void a_member_written_mid_request_is_resolved_as_written(@TempDir Path tmp) throws IOException {
        Path manifest = members(workspace(tmp)).get(1);

        inRequest(() -> {
            assertThat(parse(manifest).project().description()).isNull();
            write(manifest, "description = \"added\"\n" + read(manifest));
            assertThat(parse(manifest).project().description()).isEqualTo("added");
        });
    }

    @Test
    void a_root_written_mid_request_is_resolved_again(@TempDir Path tmp) throws IOException {
        Path root = workspace(tmp);
        Path manifest = members(root).get(1);

        inRequest(() -> {
            assertThat(parse(manifest).project().version()).isEqualTo("1.0.0");
            write(root.resolve("jk.toml"), read(root.resolve("jk.toml")).replace("1.0.0", "2.0.0"));
            assertThat(parse(manifest).project().version())
                    .as("the member inherits the root's new version")
                    .isEqualTo("2.0.0");
        });
    }

    private static JkBuild parse(Path manifest) {
        try {
            return JkBuildParser.parse(manifest);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, String text) {
        try {
            Files.writeString(file, text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A workspace whose members each name their predecessor and inherit the root's version. */
    private static Path workspace(Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["mods/*"]
                """);
        for (int i = 0; i < MEMBERS; i++) {
            Path dir = Files.createDirectories(root.resolve("mods").resolve(name(i)));
            String deps = i == 0 ? "" : "\n[dependencies]\n" + name(i - 1) + ".workspace = true\n";
            Files.writeString(dir.resolve("jk.toml"), "name = \"" + name(i) + "\"\n" + deps);
        }
        return root;
    }

    private static List<Path> members(Path root) {
        List<Path> out = new ArrayList<>();
        for (int i = 0; i < MEMBERS; i++)
            out.add(root.resolve("mods").resolve(name(i)).resolve("jk.toml"));
        return out;
    }

    private static String name(int i) {
        return "m" + (i < 10 ? "0" + i : Integer.toString(i));
    }

    /** Run {@code body} the way a real request runs: with an {@link IoLedger} opened around it. */
    private static void inRequest(Runnable body) {
        IoLedger ledger = new IoLedger();
        Request.of(1, ledger)
                .run(() -> SessionContext.runWhere(Session.defaults().withIo(ledger), body));
    }
}
