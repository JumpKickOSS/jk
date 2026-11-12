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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parsing a member resolves it against the whole workspace, and a request parses many members: the
 * module list is a fact about the request's inputs, so it is built once per request and shared.
 */
class WorkspaceLoaderMemoTest {

    private static final int MEMBERS = 30;

    private static final int RACERS = 8;

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
    void racing_first_callers_of_one_request_load_the_module_list_once(@TempDir Path tmp) throws Exception {
        Path root = workspace(tmp, MEMBERS);
        JkBuild rootManifest = JkBuildParser.parseLocal(root.resolve("jk.toml"));
        IoLedger ledger = new IoLedger();
        CyclicBarrier start = new CyclicBarrier(RACERS);
        List<Thread> racers = new ArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        long before = WorkspaceLoader.loads();
        for (int i = 0; i < RACERS; i++) {
            racers.add(Thread.ofPlatform().start(() -> {
                try {
                    Request.of(1, ledger)
                            .run(() ->
                                    SessionContext.runWhere(Session.defaults().withIo(ledger), () -> {
                                        try {
                                            start.await();
                                            WorkspaceLoader.loadModules(root, rootManifest);
                                        } catch (Exception e) {
                                            failures.add(e);
                                        }
                                    }));
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
        }
        for (Thread t : racers) t.join();

        assertThat(failures).isEmpty();
        assertThat(WorkspaceLoader.loads() - before)
                .as("%d first callers of one request racing on a cold scope", RACERS)
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
        assertThat(WorkspaceLoader.loads() - before)
                .as("every member that names a sibling loads the list again; the first names none")
                .isEqualTo(2);
    }

    @Test
    void a_member_naming_no_sibling_loads_no_module_list(@TempDir Path tmp) throws IOException {
        Path root = workspace(tmp, 3);
        Path first = memberManifests(root, 3).getFirst();

        long before = WorkspaceLoader.loads();
        JkBuild parsed = JkBuildParser.parse(first);
        assertThat(parsed.project().group()).as("still inherits from the root").isEqualTo("com.example");
        assertThat(WorkspaceLoader.loads() - before)
                .as("only workspace:<name> placeholders need the sibling list")
                .isZero();
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
        Request.of(1, ledger).run(() -> {
            SessionContext.runWhere(Session.defaults().withIo(ledger), body);
        });
    }
}
