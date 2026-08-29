// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.RepoRoot;
import cc.jumpkick.testing.SourceText;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * House rules whose whole corpus is {@code clients/cli}.
 *
 * <p>They were guards in the tree-wide gate, and the gate is the wrong home for a rule that reads
 * one module: the charter's warning about guards-as-tests is about <em>cross-module</em> reads
 * (`ActionTreeTest` passed with a violation reintroduced because nothing declared other modules'
 * sources as inputs). A rule scoped to this module is covered by this module's own test inputs
 * under both builds, and as a test it gets assertions, a debugger and a failure report.
 *
 * <p>Each rule keeps its self-fail arm. A scan that comes back implausibly small fails rather than
 * passes — a green result has to be evidence about the tree, not about the scan.
 */
class CliSourceRulesTest {

    private static final Path ROOT = RepoRoot.find(CliSourceRulesTest.class);
    private static final Path MAIN = ROOT.resolve("clients/cli/src/main/java");
    private static final Path TEST = ROOT.resolve("clients/cli/src/test/java");

    /**
     * Types that drag the TOML parser or ANTLR into the native image's reachability graph.
     *
     * <p>{@code TomlScan} and {@code MinimalToml} are deliberately absent — they are the line
     * scanner and the scalar codec the image is <em>supposed</em> to reach.
     */
    private static final List<String> BANNED_PARSE_TYPES = List.of(
            "JkBuildParser",
            "PluginDescriptor",
            "PluginTableRegistry",
            "org.tomlj",
            "TomlValues",
            "LockFreshness",
            "LockManifestDigest",
            "PluginContributions",
            "Giter8LocalApply",
            "Giter8Apply",
            "Giter8ShortNames",
            "Giter8TemplateIndex",
            "org.stringtemplate",
            "org.antlr.runtime");

    /** Names that must not reach the native client's runtime through its manifest. */
    private static final Set<String> FORBIDDEN_RUNTIME_DEPS = Set.of(
            "jk-plugin-sdk",
            "maven-artifact",
            "plexus-utils",
            "jline-terminal-ffm",
            "junit-jupiter",
            "junit-platform-engine",
            "junit-platform-launcher",
            "assertj-core");

    @Test
    void cli_main_names_no_parser_or_plugin_schema_type() throws IOException {
        List<Path> main = SourceText.javaUnder(MAIN);
        assertThat(main.size())
                .as("scan of clients/cli/src/main/java; measured against 274 files")
                .isGreaterThanOrEqualTo(200);

        List<String> hits = new ArrayList<>();
        for (Path f : main) {
            String raw = Files.readString(f);
            for (String banned : BANNED_PARSE_TYPES) {
                if (raw.contains(banned)) hits.add(SourceText.rel(ROOT, f) + ": " + banned);
            }
        }
        assertThat(hits)
                .as("CLI main must not reference parser / plugin-schema types — every one of them"
                        + " drags tomlj or ANTLR into the native image's reachability graph")
                .isEmpty();
    }

    /**
     * The slim client links no plugin SPI and no test framework.
     *
     * <p>Gradle asks the resolved {@code runtimeClasspath}; the manifest is where the mistake is
     * actually typed, and it is the half jk can see. Both builds check the same fact from the place
     * each can reach.
     */
    @Test
    void the_cli_manifest_puts_no_spi_or_test_framework_on_the_runtime() throws IOException {
        Path manifest = RepoRoot.file(CliSourceRulesTest.class, "clients/cli/jk.toml");
        Pattern dep = Pattern.compile("^([\\w-]+)\\s*[.=]");
        Pattern testKind = Pattern.compile("kind\\s*=\\s*\"tests\"");

        List<String> hits = new ArrayList<>();
        int mainEdges = 0;
        String table = "";
        for (String raw : Files.readAllLines(manifest)) {
            String line = raw.split("#", 2)[0].trim();
            if (line.startsWith("[")) {
                table = line.replace("[", "").replace("]", "");
                continue;
            }
            if (!table.endsWith("dependencies")) continue;
            // Only main-tier tables reach the native image; [test-*], [provided-*] and
            // [processor-*] do not.
            if (table.startsWith("test-") || table.startsWith("provided-") || table.startsWith("processor-")) {
                continue;
            }
            Matcher m = dep.matcher(line);
            if (!m.find()) continue;
            String name = m.group(1);
            mainEdges++;
            if (FORBIDDEN_RUNTIME_DEPS.contains(name)) {
                hits.add("[" + table + "] declares " + name);
            }
            if (testKind.matcher(line).find()) {
                hits.add("[" + table + "] takes " + name + "'s test classes — that is test code on"
                        + " the native client's runtime");
            }
        }

        assertThat(mainEdges)
                .as("clients/cli/jk.toml declares no main-tier dependency, so this test verified nothing")
                .isPositive();
        assertThat(hits)
                .as("everything heavy reaches the engine over the wire; a type the CLI genuinely"
                        + " needs belongs on :host or :wire, not on the SPI")
                .isEmpty();
    }

    /**
     * The terminal is handed to a child in exactly one place.
     *
     * <p>{@code CliOutput.handOffTerminal(pb)} restores the terminal out of whatever mode jk put it
     * in and <em>then</em> starts the child on inherited stdio. Those two steps are a pair, and
     * spelling them separately is how three handoff sites came to skip the restore — an interactive
     * child's own line editing did not work. The shape is {@code inheritIO}, not {@code new
     * ProcessBuilder}: this module forks plenty of children that must NOT inherit stdio.
     */
    @Test
    void stdio_is_inherited_only_by_the_terminal_handoff_owner() throws IOException {
        List<Path> main = SourceText.javaUnder(MAIN);
        assertThat(main.size())
                .as("scan of clients/cli/src/main/java; measured against 285 files")
                .isGreaterThanOrEqualTo(200);

        List<Path> owners = main.stream()
                .filter(p -> p.getFileName().toString().equals("CliOutput.java"))
                .toList();
        assertThat(owners)
                .as("CliOutput.java is the owner this rule exempts; it is not under src/main/java any more")
                .hasSize(1);
        Path owner = owners.get(0);
        assertThat(SourceText.withoutComments(Files.readString(owner)))
                .as("CliOutput no longer calls inheritIO(), so handOffTerminal has stopped being the"
                        + " handoff and this rule is exempting a file that does nothing")
                .contains("inheritIO()");

        List<String> hits = new ArrayList<>();
        for (Path f : main) {
            if (f.equals(owner)) continue;
            if (SourceText.withoutComments(Files.readString(f)).contains("inheritIO(")) {
                hits.add(SourceText.rel(ROOT, f));
            }
        }
        assertThat(hits)
                .as("handing this terminal to a child means restoring it out of jk's mode first, and"
                        + " that pair has one owner: CliOutput.handOffTerminal(pb). A child that"
                        + " inherits jk's raw mode gets no line editing of its own")
                .isEmpty();
    }

    /**
     * A test that names the ambient state root declares it throwaway.
     *
     * <p>The tier's {@code JK_HOME} is one root shared by every parallel fork and by every run
     * before this one, so {@code state/} is ambient input. A test that resolves it and then asserts
     * on what it finds is asserting against the last run: one suite planted a fixture in the shared
     * {@code state/aot} and a sibling fork's sweep deleted it mid-assertion — three greens and a red
     * from the same bytes. The rule is not "never touch the root", it is "name the root, declare the
     * isolation".
     */
    @Test
    void a_cli_test_reading_the_state_root_declares_isolation() throws IOException {
        List<Path> tests = SourceText.javaUnder(TEST);
        assertThat(tests)
                .as("no tests under clients/cli/src/test/java, so this verified nothing")
                .isNotEmpty();

        List<String> accessors = List.of("JkDirs.state()", "JkDirs.builds()");
        List<String> hits = new ArrayList<>();
        for (Path f : tests) {
            String raw = Files.readString(f);
            if (raw.contains("@IsolatedState")) continue;
            for (String accessor : accessors) {
                if (raw.contains(accessor)) hits.add(SourceText.rel(ROOT, f) + ": " + accessor);
            }
        }
        assertThat(hits)
                .as("the tier's JK_HOME is shared across forks and across runs, so a test that reads"
                        + " the ambient state root inherits state instead of establishing it."
                        + " Annotate the class with @IsolatedState")
                .isEmpty();
    }
}
