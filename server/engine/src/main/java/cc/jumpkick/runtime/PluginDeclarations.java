// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.jsonl.Jsonl;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What one plugin declared over the describe protocol: its tasks, its packager when it registers
 * one, and its commands. {@link #decode} is the reader of a describe reply and of the file
 * {@link PluginBuild#declarations} caches it under.
 */
public record PluginDeclarations(
        List<TaskDecl> steps, @Nullable PackagerDecl packager, List<CommandDecl> commands) {

    /** The registered packager, as declared. */
    public record PackagerDecl(String name, List<String> inputs) {}

    /** One registered plugin command, as declared. */
    public record CommandDecl(String name, String description) {}

    public @Nullable TaskDecl step(String name) {
        for (TaskDecl s : steps) if (s.name().equals(name)) return s;
        return null;
    }

    public @Nullable CommandDecl command(@Nullable String name) {
        for (CommandDecl v : commands) if (v.name().equals(name)) return v;
        return null;
    }

    /** Decode a describe reply's declaration lines (the cached file's exact content). */
    public static PluginDeclarations decode(List<String> lines) {
        List<TaskDecl> steps = new ArrayList<>();
        PackagerDecl packager = null;
        List<CommandDecl> commands = new ArrayList<>();
        for (String line : lines) {
            switch (String.valueOf(Jsonl.str(line, "t"))) {
                case "task", "step" ->
                    steps.add(new TaskDecl(
                            Jsonl.requiredStr(line, "name"),
                            Jsonl.strArray(line, "requires"),
                            Jsonl.strArray(line, "inputs"),
                            Jsonl.strArray(line, "outputs"),
                            Jsonl.strArray(line, "contributesClasses"),
                            Jsonl.strArray(line, "contributesResources"),
                            Jsonl.strArray(line, "contributesSources"),
                            Jsonl.strArray(line, "contributesTestSources"),
                            Jsonl.strArray(line, "contributesTestClasspath"),
                            Jsonl.strArray(line, "contributesTestJvmArgs"),
                            Jsonl.str(line, "transformsClasses"),
                            blankToNull(Jsonl.str(line, "stage"))));
                case "packager" ->
                    packager = new PackagerDecl(Jsonl.requiredStr(line, "name"), Jsonl.strArray(line, "inputs"));
                case "command" ->
                    commands.add(
                            new CommandDecl(Jsonl.requiredStr(line, "name"), Jsonl.requiredStr(line, "description")));
                default -> {
                    // labels etc. — irrelevant to declarations
                }
            }
        }
        return new PluginDeclarations(steps, packager, commands);
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
