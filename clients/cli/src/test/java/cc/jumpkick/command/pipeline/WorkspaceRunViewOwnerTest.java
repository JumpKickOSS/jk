// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The fold, enforced. {@link WorkspaceRunViewTest} says what the shared renderer does; this says
 * there is nowhere else to say it, which is the only thing that makes "change the owner and every
 * caller moves" true rather than true-today.
 *
 * <p>Measured against <b>139</b> files in {@code cc.jumpkick.command}, of which <b>3</b> construct a
 * {@code WorkspaceBuildListener} at all and <b>1</b> is the shared renderer. Hand-written listener
 * graphs across eight verbs rendered the same events four different ways; the scan below asserts
 * the surviving count file by file, so a new one fails here rather than drifting for six months and
 * taking a {@code [01 of 01]} denominator with it. Every arm self-fails on an empty scan.
 */
class WorkspaceRunViewOwnerTest {

    /** The token every hand-written listener graph starts with. */
    private static final String ANON_LISTENER = "new WorkspaceBuildListener()";

    /**
     * Files allowed to construct one, and how many. Anything not listed must construct zero.
     *
     * <ul>
     *   <li>{@code WorkspaceRunView} — the owner: one live listener, one headless listener. They are
     *       siblings on the mode axis (a painted region vs an append-only block under a print
     *       mutex), not a flag apart, so they are two objects on purpose.
     *   <li>{@code NativeCommand}, once. Its single-project path is a cascade of one and attaches
     *       {@code BuildPlanConsole.chooseConsoleListener} to the only module there is; it renders
     *       no aggregate. Its workspace arms — live and headless — are {@link WorkspaceRunView}.
     *   <li>{@code VerifyBuildCommand} — collects diagnostic strings into a list and renders
     *       nothing. Not a view.
     * </ul>
     *
     * <p>Judged by spec, not by body: the two exceptions above answer a different question than
     * "what does a workspace build look like while it runs", which is the only question
     * {@link WorkspaceRunView} answers.
     */
    private static final Map<String, Integer> ALLOWED =
            Map.of("WorkspaceRunView.java", 2, "NativeCommand.java", 1, "VerifyBuildCommand.java", 1);

    @Test
    void only_the_owner_and_the_two_stated_exceptions_build_a_workspace_listener() throws Exception {
        Map<String, Integer> found = new TreeMap<>();
        int scanned = 0;
        for (Path f : commandSources()) {
            scanned++;
            int n = count(Files.readString(f), ANON_LISTENER);
            if (n > 0) found.put(f.getFileName().toString(), n);
        }
        assertThat(scanned)
                .as("scanned no command sources — the guard would pass having verified nothing")
                .isGreaterThan(40);
        assertThat(found).containsExactlyInAnyOrderEntriesOf(ALLOWED);
    }

    @Test
    void the_module_completion_line_has_one_caller() throws Exception {
        var hits = new TreeMap<String, Integer>();
        int scanned = 0;
        for (Path f : commandSources()) {
            scanned++;
            String name = f.getFileName().toString();
            if (name.equals("BuildTails.java")) continue; // the declaration
            int n = count(Files.readString(f), "completionLine(");
            if (n > 0) hits.put(name, n);
        }
        assertThat(scanned).isGreaterThan(40);
        // FormatCommand has a completionLine of its own — a per-file format line, not a per-module
        // build line, and nothing about the two is shared. Named here so the day they converge is a
        // decision rather than an accident.
        assertThat(hits.keySet()).containsExactlyInAnyOrder("WorkspaceRunView.java", "FormatCommand.java");
    }

    /**
     * The {@code workspace-*} vocabulary has one emitter. A verb that hand-rolls even one of these
     * calls is how {@code jk native --output json} came to emit {@code workspace-progress} with no
     * {@code workspace-start} and no terminal at all — a stream a parser cannot close.
     */
    @Test
    void only_the_owner_emits_the_workspace_vocabulary() throws Exception {
        var hits = new TreeMap<String, Integer>();
        int scanned = 0;
        for (Path f : commandSources()) {
            scanned++;
            int n = count(Files.readString(f), "JsonlShape.workspace");
            if (n > 0) hits.put(f.getFileName().toString(), n);
        }
        assertThat(scanned).isGreaterThan(40);
        assertThat(hits.keySet()).containsExactly("WorkspaceRunView.java");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    private static List<Path> commandSources() throws Exception {
        return javaFiles(
                RepoRoot.dir(WorkspaceRunViewOwnerTest.class, "clients/cli/src/main/java/cc/jumpkick/command"));
    }

    private static List<Path> javaFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(Objects::nonNull)
                    .sorted()
                    .toList();
        }
    }
}
