// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Parsing a member resolves it against the whole workspace, and a request parses many members: the
 * module list is a fact about the request's inputs, so it is built once per request and shared.
 */
class WorkspaceLoaderMemoTest {

    private static final int MEMBERS = 30;

    @AfterEach
    void clear() {
        RequestScope.clearAll();
    }

    @Test
    void parsing_every_member_of_a_workspace_loads_the_module_list_once_per_request(@TempDir Path tmp)
            throws IOException {
        Path root = workspace(tmp, MEMBERS);
        List<Path> manifests = memberManifests(root, MEMBERS);

        long before = WorkspaceLoader.loads();
        inRequest(() -> manifests.forEach(WorkspaceLoaderMemoTest::parse));
        assertThat(WorkspaceLoader.loads() - before)
                .as("one request, one module list, however many members it parses")
                .isEqualTo(1);
    }

    @Test
    void a_second_request_builds_its_own_module_list(@TempDir Path tmp) throws IOException {
        Path root = workspace(tmp, 3);
        List<Path> manifests = memberManifests(root, 3);

        long before = WorkspaceLoader.loads();
        inRequest(() -> manifests.forEach(WorkspaceLoaderMemoTest::parse));
        inRequest(() -> manifests.forEach(WorkspaceLoaderMemoTest::parse));
        assertThat(WorkspaceLoader.loads() - before).isEqualTo(2);
    }

    @Test
    void a_root_manifest_edited_mid_request_is_reloaded(@TempDir Path tmp) throws IOException {
        Path root = workspace(tmp, 2);
        List<Path> manifests = memberManifests(root, 2);

        long before = WorkspaceLoader.loads();
        inRequest(() -> {
            manifests.forEach(WorkspaceLoaderMemoTest::parse);
            try {
                Files.writeString(root.resolve("jk.toml"), rootManifest(2) + "description = \"edited\"\n");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            manifests.forEach(WorkspaceLoaderMemoTest::parse);
        });
        assertThat(WorkspaceLoader.loads() - before)
                .as("the memo is keyed on the root manifest's stamp, not only its path")
                .isEqualTo(2);
    }

    @Test
    void a_caller_with_no_request_rebuilds_the_list_every_time(@TempDir Path tmp) throws IOException {
        Path root = workspace(tmp, 3);
        List<Path> manifests = memberManifests(root, 3);

        long before = WorkspaceLoader.loads();
        manifests.forEach(WorkspaceLoaderMemoTest::parse);
        assertThat(WorkspaceLoader.loads() - before).isEqualTo(3);
    }

    private static void parse(Path manifest) {
        try {
            JkBuildParser.parse(manifest);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A workspace whose members are selected by a glob, each depending on its predecessor. */
    private static Path workspace(Path tmp, int members) throws IOException {
        Path root = tmp.resolve("ws");
        Files.createDirectories(root.resolve("mods"));
        Files.writeString(root.resolve("jk.toml"), rootManifest(members));
        for (int i = 0; i < members; i++) {
            Path dir = root.resolve("mods").resolve(name(i));
            Files.createDirectories(dir);
            String deps = i == 0 ? "" : "\n[dependencies]\n" + name(i - 1) + ".workspace = true\n";
            Files.writeString(dir.resolve("jk.toml"), "name = \"" + name(i) + "\"\n" + deps);
        }
        return root;
    }

    private static String rootManifest(int members) {
        return """
                group = "com.example"
                name = "ws"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["mods/*"]
                """;
    }

    private static List<Path> memberManifests(Path root, int members) {
        List<Path> out = new ArrayList<>();
        for (int i = 0; i < members; i++)
            out.add(root.resolve("mods").resolve(name(i)).resolve("jk.toml"));
        return out;
    }

    private static String name(int i) {
        return "m" + (i < 10 ? "0" + i : Integer.toString(i));
    }

    /** Run {@code body} the way a real request runs: with an {@link IoLedger} opened around it. */
    private static void inRequest(Runnable body) {
        IoLedger ledger = new IoLedger();
        IoLedger.open(ledger);
        try {
            SessionContext.runWhere(Session.defaults().withIo(ledger), body);
        } finally {
            IoLedger.close();
        }
    }
}
