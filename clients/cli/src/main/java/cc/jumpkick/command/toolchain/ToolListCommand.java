// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Table;
import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.compat.InstalledTool;
import cc.jumpkick.compat.ToolRegistry;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** {@code jk tool list} — print every CLI tool installed via {@code jk tool install}. */
public final class ToolListCommand implements CliCommand {

    @Override
    public String name() {
        return "list";
    }

    @Override
    public String description() {
        return "List installed CLI tools";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<dir>", "Override the tool state directory. Default: $JK_STATE_DIR.", "--state-dir")
                        .hide(),
                Opt.value("<dir>", "Override the bin directory. Default: ~/.jk/bin.", "--bin-dir")
                        .hide());
    }

    @Override
    public int run(Invocation in) throws IOException {
        Path stateDir = in.value("state-dir").map(Path::of).orElse(null);
        Path binDirOverride = in.value("bin-dir").map(Path::of).orElse(null);
        Path state = stateDir != null ? stateDir : JkDirs.state();
        Path binDir = binDirOverride != null ? binDirOverride : JkDirs.binDir();
        Path envsRoot = JkDirs.toolEnvsDir(state);
        List<List<String>> buildTools = buildToolRows();
        List<Path> envs = new ArrayList<>();
        if (Files.isDirectory(envsRoot)) {
            try (var stream = Files.list(envsRoot)) {
                stream.filter(Files::isDirectory).forEach(envs::add);
            }
        }
        if (envs.isEmpty() && buildTools.isEmpty()) {
            CliOutput.out("No tools installed. Try `jk tool install <coord>` or `jk tool install "
                    + BuildTool.KOTLIN.slug() + ":" + BuildTool.LATEST + "`.");
            return 0;
        }
        if (!buildTools.isEmpty()) {
            CommandWedge.envelopeStart();
            for (String line : Table.render("Build tools", List.of("Tool", "Version", "Home"), buildTools)) {
                CliOutput.out(line);
            }
        }
        if (envs.isEmpty()) return 0;
        envs.sort(Comparator.comparing(p -> p.getFileName().toString()));
        List<List<String>> rows = new ArrayList<>();
        for (Path envDir : envs) {
            String bin = envDir.getFileName().toString();
            Path envJson = envDir.resolve("env.json");
            String coord = readField(envJson, "primary").orElse("(unknown coord)");
            Path launcher = binDir.resolve(bin);
            // Provenance: how this tool was installed (kind + the spec the user typed).
            Optional<String> kind = readField(envJson, "kind");
            Optional<String> spec = readField(envJson, "spec");
            String source = kind.isPresent() && spec.isPresent() && !spec.get().equals(coord)
                    ? kind.get() + " " + spec.get()
                    : "";
            rows.add(List.of(bin, coord, source, Files.exists(launcher) ? launcher.toString() : "(not on PATH)"));
        }
        CommandWedge.envelopeStart();
        for (String line : Table.render("Tools", List.of("Tool", "Coordinates", "Source", "Launcher"), rows)) {
            CliOutput.out(line);
        }
        return 0;
    }

    /**
     * Provisioned build-tool distributions, read through {@link ToolRegistry} — the same layout the
     * engine writes, rather than a second walk that would drift from it.
     */
    private static List<List<String>> buildToolRows() throws IOException {
        ToolRegistry registry = new ToolRegistry(JkDirs.tools());
        List<List<String>> rows = new ArrayList<>();
        for (BuildTool tool : BuildTool.values()) {
            for (InstalledTool installed : registry.list(tool)) {
                rows.add(List.of(
                        tool.slug(), installed.version(), installed.home().toString()));
            }
        }
        return rows;
    }

    /**
     * A top-level string field of a tool's {@code env.json}, or empty when the file is missing,
     * unreadable, not JSON, or carries no such field. The scan this replaced took the first
     * {@code "field"} anywhere in the document and then the next two quotes after the colon, so a
     * nested key of the same name won and an escaped quote inside the value truncated it.
     */
    private static Optional<String> readField(Path envJson, String field) {
        if (!Files.exists(envJson)) return Optional.empty();
        try {
            return Optional.ofNullable(MiniJson.str(MiniJson.parse(Files.readString(envJson)), field));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
