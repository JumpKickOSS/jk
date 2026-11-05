// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every template under the repository's {@code templates/} (the tree the public catalog mirrors) is
 * scaffolded with {@code jk new -t <path>} in each layout it declares, and the project it writes
 * passes {@code jk build}, {@code jk test} and {@code jk guard} with no edits — the contract the
 * catalog's README promises. A template that stops compiling, fails NullAway or its own rule pack,
 * or ships a red sample test fails here under its own id and layout, with the command's console.
 *
 * <p>Slow tier: each scaffold resolves its framework's closure (Spring Boot, Ktor, JLine, Error
 * Prone) and builds and tests a whole project, minutes in all — framework end to end that breaks
 * when a template's pins move, the same cost and cadence as the other framework e2e suites, and
 * too long for the integration push gate.
 */
@Tag("slow")
class TemplateCatalogE2eTest {

    private static final Pattern LAYOUTS = Pattern.compile("(?m)^layouts\\s*=\\s*\\[([^]]*)]");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    @TempDir
    Path scratch;

    @TestFactory
    Stream<DynamicTest> every_template_scaffolds_builds_tests_and_guards_clean_in_each_layout() throws IOException {
        Path root = RepoRoot.find(TemplateCatalogE2eTest.class).resolve("templates");
        List<Path> templates;
        try (Stream<Path> walk =
                Files.find(root, 3, (p, a) -> a.isDirectory() && p.toString().endsWith(".g8"))) {
            templates = walk.sorted().toList();
        }
        // Fake-green floor: an empty or near-empty walk is a moved tree, not a clean catalog.
        assertThat(templates).as("templates under %s", root).hasSizeGreaterThanOrEqualTo(10);

        List<DynamicTest> tests = new ArrayList<>();
        for (Path template : templates) {
            readEveryFile(template);
            String id = root.relativize(template).toString().replace('\\', '/').replaceAll("\\.g8$", "");
            for (String layout : layouts(template.resolve(".jk-template.toml"))) {
                tests.add(DynamicTest.dynamicTest(
                        id + " (" + layout + ")", () -> scaffoldAndCheck(template, id, layout)));
            }
        }
        return tests.stream();
    }

    /**
     * Reads every file of {@code template} in this JVM. The scaffolds read the template in the
     * engine's process, where the suite's input recording does not look; read here, each file is a
     * discovered input that keys the suite, so a template edit re-runs it instead of replaying the
     * last green outcome.
     */
    private static void readEveryFile(Path template) throws IOException {
        try (Stream<Path> files = Files.walk(template)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Files.readAllBytes(file);
            }
        }
    }

    /** The layouts a template declares; one with none declared is scaffolded in the default layout. */
    static List<String> layouts(Path descriptor) throws IOException {
        Matcher list = LAYOUTS.matcher(Files.readString(descriptor));
        if (!list.find()) return List.of("traditional");
        List<String> layouts = new ArrayList<>();
        Matcher name = QUOTED.matcher(list.group(1));
        while (name.find()) layouts.add(name.group(1));
        return layouts.isEmpty() ? List.of("traditional") : layouts;
    }

    private void scaffoldAndCheck(Path template, String id, String layout) throws IOException {
        Path project = scratch.resolve(id.replace('/', '-') + "-" + layout);
        // The layout is passed explicitly: what the scaffold is checked in is the template's own
        // declaration, not whichever default `jk new` picks.
        jk(id, layout, "new", "-t", template.toString(), "-L", layout, "-N", "--name", "demo", project.toString());
        assertThat(project.resolve("jk.toml"))
                .as("%s (%s) writes a jk.toml at the project root", id, layout)
                .exists();
        for (String verb : List.of("build", "test", "guard")) {
            jk(id, layout, "-C", project.toString(), verb);
        }
    }

    /** Runs one in-process jk command; a non-zero exit fails naming the template, layout and command. */
    private static void jk(String id, String layout, String... args) {
        String[] all = Stream.concat(Stream.of("--no-ansi", "--no-progress"), Stream.of(args))
                .toArray(String[]::new);
        int[] exit = {0};
        Capture.Streams console = Capture.both(() -> exit[0] = Jk.execute(all));
        assertThat(exit[0])
                .as(
                        "template %s (%s): jk %s exited %d%n%s%s",
                        id, layout, String.join(" ", args), exit[0], console.out(), console.err())
                .isZero();
    }
}
