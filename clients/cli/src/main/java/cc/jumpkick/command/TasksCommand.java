// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.ProjectContext;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.ProjectInfo;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.runtime.ExplainPlan;
import cc.jumpkick.runtime.TaskForecast;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code jk tasks} — Mill-lite task list / show / inspect.
 *
 * <pre>
 * jk tasks
 * jk tasks show package-jar
 * jk tasks inspect compile-java
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
        return "List tasks, output paths, or inspect a task";
    }

    @Override
    public List<Opt> options() {
        var opts = new ArrayList<Opt>();
        opts.addAll(cc.jumpkick.cli.CommonOpts.moduleSelection());
        opts.add(cc.jumpkick.cli.CommonOpts.cacheDirHidden());
        return opts;
    }

    @Override
    public List<Param> parameters() {
        return List.of(
                Param.of("action", Arity.ZERO_OR_ONE, "show | inspect (default: list)"),
                Param.of("task", Arity.ZERO_OR_ONE, "Task name for show/inspect (e.g. package-jar)"));
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
            // Allow `jk tasks package-jar` as shorthand for show when first token is a known task.
            if (!action.equals("list") && !action.equals("show") && !action.equals("inspect") && !action.equals("ls")) {
                if (TaskCatalog.find(action).isPresent()) {
                    return showOrInspect("show", action, in, startDir, proj.buildFile());
                }
                cc.jumpkick.cli.tui.CommandWedge.printFail(
                        "Tasks", "unknown action `" + action + "` (list | show | inspect)");
                return Exit.USAGE;
            }
            if (action.equals("ls")) action = "list";

            return switch (action) {
                case "list" -> list(in, startDir, proj.buildFile());
                case "show", "inspect" -> {
                    if (pos.size() < 2) {
                        cc.jumpkick.cli.tui.CommandWedge.printFail(
                                "Tasks", action + " expects a task name (e.g. package-jar)");
                        yield Exit.USAGE;
                    }
                    yield showOrInspect(action, pos.get(1), in, startDir, proj.buildFile());
                }
                default -> {
                    cc.jumpkick.cli.tui.CommandWedge.printFail("Tasks", "unknown action `" + action + "`");
                    yield Exit.USAGE;
                }
            };
        } catch (IllegalStateException e) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Tasks", e.getMessage());
            return Exit.CONFIG;
        }
    }

    static int list(Invocation in, Path startDir, Path buildFile) throws Exception {
        Map<Path, ProjectInfo> modules = resolveModules(in, startDir);
        if (modules.isEmpty()) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Tasks", "no modules selected");
            return Exit.CONFIG;
        }
        boolean multi = modules.size() > 1;
        boolean first = true;
        for (var e : modules.entrySet()) {
            String title = "Tasks";
            if (multi) {
                title = "Tasks — " + e.getValue().coord() + " (" + rel(startDir, e.getKey()) + ")";
            }
            if (!first) CliOutput.out("");
            first = false;
            List<List<String>> rows = new ArrayList<>();
            for (TaskCatalog.TaskDef t : TaskCatalog.buildTasks()) {
                rows.add(List.of(t.name(), t.stage(), t.description()));
            }
            // Project build logic (.jk-build SPI / *Build — offline scan) rides the same table.
            for (String name : BuildLogicTaskScan.discoverNames(e.getKey())) {
                rows.add(List.of(
                        "build-logic:" + name,
                        "logic",
                        "Project build-logic task (anchor via SPI or AFTER_RESOURCES for *Build)"));
            }
            cc.jumpkick.cli.tui.CommandWedge.envelopeStart();
            for (String line : cc.jumpkick.cli.tui.Table.render(title, List.of("Name", "Stage", "Description"), rows)) {
                CliOutput.out(line);
            }
        }
        // Table is the wedge substitute; tip is post-table detail.
        CliOutput.err("tip: jk show package-jar · jk inspect compile-java · jk tasks show <task>");
        return 0;
    }

    static int showOrInspect(String action, String stepName, Invocation in, Path startDir, Path buildFile)
            throws Exception {
        Optional<TaskCatalog.TaskDef> def = TaskCatalog.find(stepName);
        if (def.isEmpty()) {
            cc.jumpkick.cli.tui.CommandWedge.printFail(
                    "Tasks", "unknown task `" + stepName + "` — run `jk tasks` for names");
            return Exit.CONFIG;
        }
        TaskCatalog.TaskDef task = def.get();
        Map<Path, ProjectInfo> modules = resolveModules(in, startDir);
        if (modules.isEmpty()) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Tasks", "no modules selected");
            return Exit.CONFIG;
        }
        boolean inspect = "inspect".equals(action);
        GlobalOptions global = GlobalOptions.from(in);
        Path cache = in.value("cache-dir").map(cc.jumpkick.cli.CliPaths::abs).orElse(null);
        if (cache == null) cache = JkDirs.cache();
        // One explain forecast for the entry project — maps tasks to hit/miss.
        ExplainPlan forecast = inspect ? explainBestEffort(startDir, cache, global) : null;
        for (var e : modules.entrySet()) {
            Path modDir = e.getKey();
            ProjectInfo info = e.getValue();
            String coord = info.coord();
            Optional<Path> out = TaskCatalog.output(task, info);
            boolean exists = out.isPresent() && Files.exists(out.get());

            if (inspect) {
                CliOutput.out("task:        " + task.name());
                if (!task.aliases().isEmpty()) {
                    CliOutput.out("aliases:     " + String.join(", ", task.aliases()));
                }
                CliOutput.out("stage:       " + task.stage());
                CliOutput.out("description: " + task.description());
                CliOutput.out("module:      " + coord);
                CliOutput.out("module-dir:  " + modDir);
                if (out.isPresent()) {
                    CliOutput.out("output:      " + out.get());
                    CliOutput.out("on-disk:     " + (exists ? "yes" : "no (not built yet)"));
                } else {
                    CliOutput.out("output:      (no primary path — intermediate / side-effect task)");
                }
                CliOutput.out("cache:       " + cacheLine(forecast, modDir, task.name()));
                if (modules.size() > 1) CliOutput.out("");
            } else {
                // show: path only (Mill-like), one line per module
                if (out.isEmpty()) {
                    cc.jumpkick.cli.tui.CommandWedge.printFail(
                            "Show", "task `" + task.name() + "` has no primary output path");
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

    private static Map<Path, ProjectInfo> resolveModules(Invocation in, Path startDir) {
        Path root = startDir.toAbsolutePath().normalize();
        String modulesSpec = in.value("modules").orElse(null);
        String affected = in.value("affected-since").orElse(null);
        var peek = BuildCommand.projectInfoOrNull(root);
        if (peek != null
                && !peek.workspaceRoot()
                && !peek.workspaceRootDir().isBlank()
                && ((modulesSpec != null && !modulesSpec.isBlank()) || (affected != null && !affected.isBlank()))) {
            root = Path.of(peek.workspaceRootDir()).toAbsolutePath().normalize();
        }
        var info = BuildCommand.projectInfoOrError(root, modulesSpec, affected);
        if (info.error() != null && !info.error().isBlank()) {
            throw new IllegalStateException(info.error());
        }
        Map<Path, ProjectInfo> out = new LinkedHashMap<>();
        if (info.moduleDirs().isEmpty()) {
            var self = BuildCommand.projectInfoOrNull(startDir);
            if (self != null) out.put(startDir.toAbsolutePath().normalize(), self);
            return out;
        }
        for (String d : info.moduleDirs()) {
            Path abs = Path.of(d).toAbsolutePath().normalize();
            var mod = BuildCommand.projectInfoOrNull(abs);
            if (mod != null) out.put(abs, mod);
        }
        return out;
    }

    private static String rel(Path root, Path p) {
        try {
            String s = root.relativize(p).toString().replace('\\', '/');
            return s.isEmpty() ? "." : s;
        } catch (IllegalArgumentException e) {
            return p.toString();
        }
    }

    /** Best-effort engine explain; null when engine unavailable (inspect still works offline). */
    private static ExplainPlan explainBestEffort(Path startDir, Path cache, GlobalOptions global) {
        try {
            boolean serial = global != null && global.jobsEffective() == 1;
            return EngineClient.explain(
                    EnginePaths.current(),
                    new EngineRequests.ExplainRequest(
                            startDir.toAbsolutePath().normalize(),
                            cache.toAbsolutePath().normalize(),
                            1,
                            false,
                            null,
                            null,
                            serial,
                            false,
                            global != null && global.verbose),
                    null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Format forecast hit/miss for a module step. Falls back when the engine is down or the step
     * is not in the forecast (side-effect / SPI-only names).
     */
    static String cacheLine(ExplainPlan plan, Path modDir, String stepName) {
        if (plan == null
                || plan.hasErrors()
                || plan.modules() == null
                || plan.modules().isEmpty()) {
            return "unknown (engine offline or forecast failed — try `jk explain`)";
        }
        Path abs = modDir.toAbsolutePath().normalize();
        TaskForecast.Module mod = null;
        for (TaskForecast.Module m : plan.modules()) {
            if (m.dir() != null && m.dir().toAbsolutePath().normalize().equals(abs)) {
                mod = m;
                break;
            }
        }
        if (mod == null && plan.modules().size() == 1) {
            mod = plan.modules().getFirst();
        }
        if (mod == null) {
            return "unknown (module not in forecast)";
        }
        TaskForecast.Task step = null;
        for (TaskForecast.Task s : mod.steps()) {
            if (s.name() != null && s.name().equals(stepName)) {
                step = s;
                break;
            }
        }
        if (step == null) {
            return "n/a (step not forecast — intermediate or plugin SPI)";
        }
        if (step.cached()) {
            String key = step.key() != null && !step.key().isBlank() ? " key=" + step.key() : "";
            return "hit" + key + (step.text() != null && !step.text().isBlank() ? " · " + step.text() : "");
        }
        String detail = step.text() != null && !step.text().isBlank() ? " · " + step.text() : "";
        String status = step.status() != null ? step.status().name().toLowerCase(Locale.ROOT) : "miss";
        return "miss (" + status + ")" + detail;
    }
}
