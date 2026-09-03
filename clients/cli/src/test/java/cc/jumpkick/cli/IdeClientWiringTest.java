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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The IDE clients are wired to jk by string, and nothing else checks the strings.
 *
 * <p>{@code clients/intellij} and {@code clients/vscode} are modules of neither build, so no
 * compiler ever looks at them. That is how a rename survived 24 days: a tree-wide sed rewrote a VS
 * Code <em>command id</em> in {@code package.json}, leaving the palette entry pointing at a command
 * {@code extension.js} never registers — valid JSON naming a handler that does not exist.
 *
 * <p>Adopting either client as a build module would not help: an IntelliJ plugin build needs a
 * whole IDE distribution and a VS Code extension needs a Node toolchain, which is a large download
 * on the critical path of every gate for a check a text scan does in milliseconds. So it is checked
 * from {@code :cli}, which already owns {@code IdeCommand}, the IDE generators and the wire model
 * they parse.
 *
 * <p>Every arm reads its expectations from the owning file rather than restating them, and fails
 * loudly when its own scan comes back empty — a blind arm must not read as a passing one.
 */
class IdeClientWiringTest {

    private static final Path ROOT = RepoRoot.find(IdeClientWiringTest.class);
    private static final Path IDEA_JAVA = ROOT.resolve("clients/intellij/src/main/java");

    private static String read(String fromRoot) throws IOException {
        return Files.readString(RepoRoot.file(IdeClientWiringTest.class, fromRoot));
    }

    private static TreeSet<String> matches(Pattern p, String text, int group) {
        TreeSet<String> out = new TreeSet<>();
        Matcher m = p.matcher(text);
        while (m.find()) out.add(m.group(group));
        return out;
    }

    /**
     * A palette entry with no handler is unreachable in exactly the way a handler with no palette
     * entry is. One direction alone is half a check.
     */
    @Test
    void vscode_palette_entries_and_registered_handlers_are_the_same_set() throws IOException {
        TreeSet<String> declared =
                matches(Pattern.compile("\"command\"\\s*:\\s*\"([^\"]+)\""), read("clients/vscode/package.json"), 1);
        TreeSet<String> registered =
                matches(Pattern.compile("registerCommand\\(\\s*\"([^\"]+)\""), read("clients/vscode/extension.js"), 1);

        assertThat(declared)
                .as("package.json contributes no command at all — this arm is blind")
                .isNotEmpty();
        assertThat(declared)
                .as("package.json contributes commands extension.js never registers")
                .containsExactlyInAnyOrderElementsOf(registered);
    }

    /** Every verb either client shells out to is a real jk command name or alias. */
    @Test
    void every_shelled_out_verb_is_a_real_jk_command() throws IOException {
        TreeSet<String> verbs = new TreeSet<>();
        Pattern name = Pattern.compile("String name\\(\\)\\s*\\{\\s*return \"([a-z][a-z0-9-]*)\";");
        Pattern aliases = Pattern.compile(
                "List<String> aliases\\(\\)\\s*\\{[^}]*?return List\\.of\\(([^)]*)\\);", Pattern.DOTALL);
        Pattern quoted = Pattern.compile("\"([a-z][a-z0-9-]*)\"");
        for (Path f : SourceText.javaUnder(ROOT.resolve("clients/cli/src/main/java/cc/jumpkick/command"))) {
            if (!f.getFileName().toString().endsWith("Command.java")) continue;
            String raw = Files.readString(f);
            verbs.addAll(matches(name, raw, 1));
            for (String block : matches(aliases, raw, 1)) verbs.addAll(matches(quoted, block, 1));
        }
        assertThat(verbs.size())
                .as("verb owner scan under cc/jumpkick/command — the name()/aliases() shape moved and"
                        + " this arm is blind")
                .isGreaterThanOrEqualTo(40);

        TreeSet<String> shelled = matches(
                Pattern.compile("runJk\\(\\[\\s*\"([a-z][a-z0-9-]*)\""), read("clients/vscode/extension.js"), 1);
        Path cliAction = IDEA_JAVA.resolve("cc/jumpkick/idea/JkCliAction.java");
        if (Files.isRegularFile(cliAction)) {
            shelled.addAll(matches(
                    Pattern.compile("super\\(\\s*\"[^\"]*\"\\s*,\\s*\"([a-z][a-z0-9-]*)\""),
                    Files.readString(cliAction),
                    1));
        }
        Path syncService = IDEA_JAVA.resolve("cc/jumpkick/idea/JkSyncService.java");
        if (Files.isRegularFile(syncService)) {
            shelled.addAll(matches(
                    Pattern.compile("JkCliRunner\\.run\\([^,]+,\\s*List\\.of\\(\"([a-z][a-z0-9-]*)\""),
                    Files.readString(syncService),
                    1));
        }
        assertThat(shelled)
                .as("found no jk verbs invoked by either IDE client — the call shape moved and this arm is blind")
                .isNotEmpty();
        assertThat(verbs)
                .as("an IDE client shells out to a verb that is not a name or alias of any :cli command")
                .containsAll(shelled);
    }

    @Test
    void plugin_xml_names_classes_that_exist() throws IOException {
        TreeSet<String> classes = matches(
                Pattern.compile("(?:class|implementation)=\"(cc\\.jumpkick\\.idea\\.[A-Za-z0-9_.$]+)\""),
                read("clients/intellij/src/main/resources/META-INF/plugin.xml"),
                1);
        assertThat(classes)
                .as("plugin.xml declares no cc.jumpkick.idea implementation class — this arm is blind")
                .isNotEmpty();

        List<String> faults = new ArrayList<>();
        for (String fqcn : classes) {
            // An inner class (JkCliAction$Sync) lives in the outer class's file.
            String outer = fqcn.split("\\$", 2)[0];
            Path source = IDEA_JAVA.resolve(outer.replace('.', '/') + ".java");
            if (!Files.isRegularFile(source)) {
                faults.add("plugin.xml names " + fqcn + " but " + source.getFileName() + " does not exist");
            } else if (fqcn.contains("$")) {
                String inner = fqcn.split("\\$", 2)[1];
                if (!Files.readString(source).contains("class " + inner)) {
                    faults.add("plugin.xml names " + fqcn + " but " + source.getFileName() + " declares no class "
                            + inner);
                }
            }
        }
        assertThat(faults).isEmpty();
    }

    @Test
    void every_wire_field_the_plugin_reads_is_one_the_engine_emits() throws IOException {
        Path modelFile = IDEA_JAVA.resolve("cc/jumpkick/idea/JkWireModel.java");
        assertThat(modelFile)
                .as("clients/intellij's JkWireModel.java is gone — this arm is blind")
                .exists();
        String model = Files.readString(modelFile);

        TreeSet<String> read = matches(
                Pattern.compile("str(?:Field|Array)\\(\\s*(?:body|json)\\s*,\\s*\"([A-Za-z][A-Za-z0-9]*)\""), model, 1);
        // `workspace` is sniffed as a raw \"key\": literal rather than through the field helpers.
        read.addAll(matches(Pattern.compile("\\\\\"([A-Za-z][A-Za-z0-9]*)\\\\\"\\s*:"), model, 1));
        assertThat(read)
                .as("JkWireModel reads no recognised wire field — the parse shape moved and this arm is blind")
                .isNotEmpty();

        String emitted = read("shared/wire/src/main/java/cc/jumpkick/wire/protocol/IdeWireModel.java");
        List<String> missing = read.stream()
                .filter(field -> !emitted.contains("\\\"" + field + "\\\":"))
                .toList();
        assertThat(missing)
                .as("JkWireModel reads wire fields that IdeWireModel.encode() does not emit")
                .isEmpty();
    }

    /**
     * A pinned upper bound is a time bomb on JetBrains' cadence: {@code "252.*"} made the plugin
     * refuse to install on IDEA 2025.3 (build 253). Unset it with {@code provider { null }}.
     */
    @Test
    void the_intellij_plugin_pins_no_until_build() throws IOException {
        Matcher m = Pattern.compile("untilBuild\\s*(?:=|\\.set\\()\\s*\"([^\"]+)\"")
                .matcher(read("clients/intellij/build.gradle.kts"));
        assertThat(m.find())
                .as("clients/intellij pins untilBuild — the plugin becomes uninstallable the moment"
                        + " JetBrains ships the next build line")
                .isFalse();
    }
}
