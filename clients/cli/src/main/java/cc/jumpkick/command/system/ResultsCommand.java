// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.cli.api.AgentMode;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.config.ConfigSources;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.MarkdownReports;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk results} — print the latest run's {@code jk-results.md} (journal copy under the state
 * dir, with a {@code target/jk-results.md} fallback). {@code --details} prints that run's {@code
 * details.jsonl} instead. No engine round-trip: the files are already on disk. MCP: {@code
 * run} / {@code details}.
 */
public final class ResultsCommand implements CliCommand {

    @Override
    public String name() {
        return "results";
    }

    @Override
    public String description() {
        return "Print the latest run report (jk-results.md)";
    }

    @Override
    public List<Opt> options() {
        return List.of(Opt.flag("Print the latest run's details.jsonl transcript instead.", "--details"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        GlobalOptions global = GlobalOptions.from(in);
        boolean details = in.isSet("details");
        Path root = projectRoot(global.workingDir());
        if (global.agent && !details) {
            Optional<Path> agent = AgentMode.find(root);
            if (agent.isEmpty()) {
                CommandWedge.printFail("results", "no run report for this project (run a build first)");
                return Exit.FAILURE;
            }
            String text = Files.readString(agent.get(), StandardCharsets.UTF_8);
            System.out.write(text.getBytes(StandardCharsets.UTF_8));
            System.out.flush();
            return Exit.SUCCESS;
        }
        Optional<Path> file = findFile(JkDirs.builds(), root, details);
        if (file.isEmpty()) {
            CommandWedge.printFail(
                    "results",
                    details
                            ? "no details.jsonl for this project (run a build first)"
                            : "no jk-results.md for this project (run a build first)");
            return Exit.FAILURE;
        }
        if (global.verbose) {
            CliOutput.err((details ? "Details: " : "Results: ") + file.get());
        }
        try {
            if (details) {
                // JSONL carries no mark, and a transcript is unbounded: stream it.
                Files.copy(file.get(), System.out);
            } else {
                // The report on disk leads with a UTF-8 BOM so a Windows `cat` decodes it
                // (MarkdownReports). A terminal is not that reader: the mark would print as ï»¿
                // under PowerShell's ANSI codepage, and piping this into a parser would hand it a
                // leading U+FEFF. The bytes after it are the report, byte for byte.
                String md = MarkdownReports.strip(Files.readString(file.get(), StandardCharsets.UTF_8));
                System.out.write(md.getBytes(StandardCharsets.UTF_8));
            }
            System.out.flush();
            return Exit.SUCCESS;
        } catch (IOException e) {
            CommandWedge.printFail("results", "reading " + file.get() + ": " + e.getMessage());
            return Exit.FAILURE;
        }
    }

    /**
     * Workspace root when {@code start} is a member, else the nearest ancestor with {@code jk.toml},
     * else {@code start}. Journal identity is keyed on that root.
     */
    static Path projectRoot(@Nullable Path start) {
        Path abs = start == null
                ? Path.of("").toAbsolutePath().normalize()
                : start.toAbsolutePath().normalize();
        Path toml = ConfigSources.findProjectConfig(abs);
        Path dir = toml != null ? Objects.requireNonNull(toml.getParent(), "jk.toml dir") : abs;
        try {
            return WorkspaceLocator.findRoot(dir).orElse(dir);
        } catch (IOException e) {
            return dir;
        }
    }

    /**
     * Latest journal file for this checkout. Markdown also falls back to {@code
     * target/jk-results.md} when no run dir has a report yet.
     */
    static Optional<Path> findFile(Path buildsRoot, Path projectDir, boolean details) {
        String name = details ? ProjectBuilds.DETAILS : ProjectBuilds.RESULTS;
        Optional<Path> journal = ProjectBuilds.latestRunFile(buildsRoot, projectDir, name);
        if (journal.isPresent()) return journal;
        if (!details) {
            Path latest = projectDir.resolve(BuildLayout.TARGET).resolve(ProjectBuilds.RESULTS);
            if (Files.isRegularFile(latest)) return Optional.of(latest);
        }
        return Optional.empty();
    }
}
