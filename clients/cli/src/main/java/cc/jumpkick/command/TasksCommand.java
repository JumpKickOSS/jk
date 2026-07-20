// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.ProjectContext;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ModuleSelection;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code jk tasks} — Mill-lite task list / show / inspect (JK-1047).
 *
 * <pre>
 *   jk tasks
 *   jk tasks show package-jar
 *   jk tasks inspect compile-java
 * </pre>
 *
 * Top-level {@code jk show} / {@code jk inspect} are thin aliases ({@link ShowCommand},
 * {@link InspectCommand}).
 */
public final class TasksCommand implements CliCommand {

    @Override
    public String name() {
        return "tasks";
    }

    @Override
    public String description() {
        return "List pipeline tasks; show output paths; inspect a step (Mill resolve-lite)";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value(
                        "<sel>",
                        "Module selector (comma list, globs, braces). Default: current project/module.",
                        "--modules"),
                Opt.value(
                        "<git-ref>",
                        "Intersect selection with modules changed since this git ref.",
                        "--affected-since"));
    }

    @Override
    public List<Param> parameters() {
        return List.of(
                Param.of("action", Arity.ZERO_OR_ONE, "show | inspect (default: list)"),
                Param.of("step", Arity.ZERO_OR_ONE, "Step name for show/inspect (e.g. package-jar)"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        GlobalOptions global = GlobalOptions.from(in);
        Path startDir = global.workingDir();
        var proj = ProjectContext.require(startDir, "tasks").orElse(null);
        if (proj == null) return Exit.CONFIG;

        try {
            List<String> pos = in.positionals();
            String action = pos.isEmpty() ? "list" : pos.getFirst().trim().toLowerCase(Locale.ROOT);
            // Allow `jk tasks package-jar` as shorthand for show when first token is a known step.
            if (!action.equals("list")
                    && !action.equals("show")
                    && !action.equals("inspect")
                    && !action.equals("ls")) {
                if (TaskCatalog.find(action).isPresent()) {
                    return showOrInspect("show", action, in, startDir, proj.buildFile());
                }
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                        "Tasks", "unknown action `" + action + "` (list | show | inspect)"));
                return Exit.USAGE;
            }
            if (action.equals("ls")) action = "list";

            return switch (action) {
                case "list" -> list(in, startDir, proj.buildFile());
                case "show", "inspect" -> {
                    if (pos.size() < 2) {
                        CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                                "Tasks", action + " expects a step name (e.g. package-jar)"));
                        yield Exit.USAGE;
                    }
                    yield showOrInspect(action, pos.get(1), in, startDir, proj.buildFile());
                }
                default -> {
                    CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Tasks", "unknown action `" + action + "`"));
                    yield Exit.USAGE;
                }
            };
        } catch (IllegalStateException e) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Tasks", e.getMessage()));
            return Exit.CONFIG;
        }
    }

    static int list(Invocation in, Path startDir, Path buildFile) throws Exception {
        Map<Path, JkBuild> modules = resolveModules(in, startDir, buildFile);
        if (modules.isEmpty()) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Tasks", "no modules selected"));
            return Exit.CONFIG;
        }
        boolean multi = modules.size() > 1;
        for (var e : modules.entrySet()) {
            if (multi) {
                String label = e.getValue().project().group() + ":" + e.getValue().project().name();
                CliOutput.out("# " + label + "  (" + rel(startDir, e.getKey()) + ")");
            }
            CliOutput.out(String.format("%-28s %-10s %s", "NAME", "PHASE", "DESCRIPTION"));
            for (TaskCatalog.TaskDef t : TaskCatalog.buildTasks()) {
                CliOutput.out(String.format("%-28s %-10s %s", t.name(), t.phase(), t.description()));
            }
            if (multi) CliOutput.out("");
        }
        // Table is the wedge substitute; tip is post-table detail.
        CliOutput.err("tip: jk show package-jar · jk inspect compile-java · jk tasks show <step>");
        return 0;
    }

    static int showOrInspect(String action, String stepName, Invocation in, Path startDir, Path buildFile)
            throws Exception {
        Optional<TaskCatalog.TaskDef> def = TaskCatalog.find(stepName);
        if (def.isEmpty()) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "Tasks", "unknown step `" + stepName + "` — run `jk tasks` for names"));
            return Exit.CONFIG;
        }
        TaskCatalog.TaskDef task = def.get();
        Map<Path, JkBuild> modules = resolveModules(in, startDir, buildFile);
        if (modules.isEmpty()) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Tasks", "no modules selected"));
            return Exit.CONFIG;
        }
        boolean inspect = "inspect".equals(action);
        for (var e : modules.entrySet()) {
            Path modDir = e.getKey();
            JkBuild build = e.getValue();
            BuildLayout layout = BuildLayout.of(modDir, build);
            String coord = build.project().group() + ":" + build.project().name();
            Optional<Path> out = task.output(layout);
            boolean exists = out.isPresent() && Files.exists(out.get());

            if (inspect) {
                CliOutput.out("step:        " + task.name());
                if (!task.aliases().isEmpty()) {
                    CliOutput.out("aliases:     " + String.join(", ", task.aliases()));
                }
                CliOutput.out("phase:       " + task.phase());
                CliOutput.out("description: " + task.description());
                CliOutput.out("module:      " + coord);
                CliOutput.out("module-dir:  " + modDir);
                if (out.isPresent()) {
                    CliOutput.out("output:      " + out.get());
                    CliOutput.out("on-disk:     " + (exists ? "yes" : "no (not built yet)"));
                } else {
                    CliOutput.out("output:      (no primary path — intermediate / side-effect step)");
                }
                CliOutput.out("cache:       unknown offline (use `jk explain` for forecast hit/miss)");
                if (modules.size() > 1) CliOutput.out("");
            } else {
                // show: path only (Mill-like), one line per module
                if (out.isEmpty()) {
                    CliOutput.err("jk show: step `" + task.name() + "` has no primary output path");
                    return Exit.CONFIG;
                }
                if (modules.size() > 1) {
                    CliOutput.out(coord + "\t" + out.get());
                } else {
                    CliOutput.out(out.get().toString());
                }
            }
        }
        return 0;
    }

    private static Map<Path, JkBuild> resolveModules(Invocation in, Path startDir, Path buildFile)
            throws Exception {
        JkBuild entry = JkBuildParser.parse(buildFile);
        Path root = startDir.toAbsolutePath().normalize();
        String modulesSpec = in.value("modules").orElse(null);
        String affected = in.value("affected-since").orElse(null);

        Map<Path, JkBuild> all = new LinkedHashMap<>();
        if (entry.isWorkspaceRoot()) {
            all.putAll(WorkspaceLoader.loadModules(root, entry));
            // Include root only if it has its own artifact-ish project name and is not pure coordinator
            // — for task show we stick to workspace modules.
        } else {
            all.put(root, entry);
        }

        ModuleSelection.Result selected =
                ModuleSelection.resolveOptional(startDir, entry, modulesSpec, affected);
        if (selected != null && !selected.ok()) {
            throw new IllegalStateException(selected.errorMessage());
        }
        if (selected == null) {
            // Single project: current dir. Workspace with no selector: all modules.
            return all;
        }
        Set<Path> want = selected.moduleDirs();
        Map<Path, JkBuild> filtered = new LinkedHashMap<>();
        for (var e : all.entrySet()) {
            Path abs = e.getKey().toAbsolutePath().normalize();
            if (want.contains(abs)) filtered.put(abs, e.getValue());
        }
        // Selection may resolve dirs not in all if user is in a module — re-parse those
        for (Path p : want) {
            Path abs = p.toAbsolutePath().normalize();
            if (!filtered.containsKey(abs) && Files.isRegularFile(abs.resolve("jk.toml"))) {
                filtered.put(abs, JkBuildParser.parse(abs.resolve("jk.toml")));
            }
        }
        return filtered;
    }

    private static String rel(Path root, Path p) {
        try {
            String s = root.relativize(p).toString().replace('\\', '/');
            return s.isEmpty() ? "." : s;
        } catch (IllegalArgumentException e) {
            return p.toString();
        }
    }
}
