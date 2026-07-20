// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.ProjectContext;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ModuleSelection;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.util.AtomicWrites;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ticket-1040 — Mill-style selective prepare / resolve / run over git-affected + module selectors.
 *
 * <pre>
 *   jk selective resolve --since=origin/main
 *   jk selective prepare --since=origin/main
 *   jk selective run build
 *   jk selective run test
 * </pre>
 */
public final class SelectiveCommand implements CliCommand {

    public static final String PLAN_REL = ".jk/selective-plan.json";

    @Override
    public String name() {
        return "selective";
    }

    @Override
    public String description() {
        return "Selective CI: resolve / prepare / run module subsets";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<git-ref>", "Select modules changed since this git ref.", "--since", "--affected-since"),
                Opt.value(
                        "<sel>",
                        "Module selector (comma list, globs, braces). Intersects with --since.",
                        "--modules"),
                Opt.flag("Machine-readable module list (one path per line).", "--json"),
                Opt.value("<file>", "Plan file path (default: .jk/selective-plan.json).", "--plan"));
    }

    @Override
    public List<Param> parameters() {
        return List.of(
                Param.of("action", Arity.ONE, "resolve | prepare | run"),
                Param.of("verb", Arity.ZERO_OR_ONE, "with run: build | test"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        GlobalOptions global = GlobalOptions.from(in);
        Path dir = global.workingDir();
        var proj = ProjectContext.require(dir, "selective").orElse(null);
        if (proj == null) return Exit.CONFIG;

        if (in.positionals().isEmpty()) {
            CliOutput.err("jk selective: expected resolve | prepare | run");
            return Exit.USAGE;
        }
        String action = in.positionals().getFirst().trim().toLowerCase(Locale.ROOT);
        String since = in.value("since").or(() -> in.value("affected-since")).orElse(null);
        String modules = in.value("modules").orElse(null);
        boolean json = in.isSet("json");
        Path planPath = in.value("plan").map(Path::of).orElse(dir.resolve(PLAN_REL));

        return switch (action) {
            case "resolve" -> resolve(dir, proj.buildFile(), since, modules, json, null);
            case "prepare" -> prepare(dir, proj.buildFile(), since, modules, planPath);
            case "run" -> runVerb(in, dir, proj.buildFile(), since, modules, planPath);
            default -> {
                CliOutput.err("jk selective: unknown action `" + action + "` (resolve | prepare | run)");
                yield Exit.USAGE;
            }
        };
    }

    private static int resolve(
            Path dir, Path buildFile, String since, String modules, boolean json, Set<Path> into)
            throws Exception {
        JkBuild entry = JkBuildParser.parse(buildFile);
        if ((since == null || since.isBlank()) && (modules == null || modules.isBlank())) {
            CliOutput.err("jk selective resolve: pass --since=<ref> and/or --modules=<sel>");
            return Exit.USAGE;
        }
        var selected = ModuleSelection.resolveOptional(dir, entry, modules, since);
        if (selected == null) {
            CliOutput.err("jk selective resolve: pass --since=<ref> and/or --modules=<sel>");
            return Exit.USAGE;
        }
        if (!selected.ok()) {
            CliOutput.err("jk selective: " + selected.errorMessage());
            return Exit.CONFIG;
        }
        if (into != null) into.addAll(selected.moduleDirs());
        List<String> rels = toRelPaths(dir, selected.moduleDirs());
        if (json) {
            CliOutput.out("{\"modules\":[" + String.join(",", rels.stream().map(SelectiveCommand::q).toList()) + "]}");
        } else if (rels.isEmpty()) {
            CliOutput.out("(no modules selected)");
        } else {
            for (String r : rels) CliOutput.out(r);
        }
        return 0;
    }

    private static int prepare(Path dir, Path buildFile, String since, String modules, Path planPath)
            throws Exception {
        Set<Path> dirs = new LinkedHashSet<>();
        int code = resolve(dir, buildFile, since, modules, false, dirs);
        if (code != 0) return code;
        List<String> rels = toRelPaths(dir, dirs);
        String gitHead = gitRevParse(dir);
        String body = """
                {
                  "since": %s,
                  "modulesSpec": %s,
                  "gitHead": %s,
                  "createdAt": %s,
                  "modules": [%s]
                }
                """
                .formatted(
                        q(since == null ? "" : since),
                        q(modules == null ? "" : modules),
                        q(gitHead == null ? "" : gitHead),
                        q(Instant.now().toString()),
                        String.join(",", rels.stream().map(SelectiveCommand::q).toList()));
        Files.createDirectories(planPath.getParent());
        AtomicWrites.replace(planPath, body);
        CliOutput.out("Wrote " + planPath + " (" + rels.size() + " module" + (rels.size() == 1 ? "" : "s") + ")");
        return 0;
    }

    private static int runVerb(
            Invocation in, Path dir, Path buildFile, String since, String modules, Path planPath)
            throws Exception {
        if (in.positionals().size() < 2) {
            CliOutput.err("jk selective run: expected build | test");
            return Exit.USAGE;
        }
        String verb = in.positionals().get(1).trim().toLowerCase(Locale.ROOT);
        if (!verb.equals("build") && !verb.equals("test")) {
            CliOutput.err("jk selective run: expected build | test (got " + verb + ")");
            return Exit.USAGE;
        }

        String effectiveSince = since;
        String effectiveModules = modules;
        if ((effectiveSince == null || effectiveSince.isBlank())
                && (effectiveModules == null || effectiveModules.isBlank())
                && Files.isRegularFile(planPath)) {
            Plan plan = readPlan(planPath);
            if (plan.modules.isEmpty()
                    && (plan.since == null || plan.since.isBlank())
                    && (plan.modulesSpec == null || plan.modulesSpec.isBlank())) {
                CliOutput.err("jk selective run: plan " + planPath + " has no modules");
                return Exit.CONFIG;
            }
            // Prefer plan modules as an explicit --modules list of relative paths.
            if (!plan.modules.isEmpty()) {
                effectiveModules = String.join(",", plan.modules);
            } else {
                effectiveSince = plan.since;
                effectiveModules = plan.modulesSpec;
            }
        }

        if ((effectiveSince == null || effectiveSince.isBlank())
                && (effectiveModules == null || effectiveModules.isBlank())) {
            CliOutput.err("jk selective run: pass --since / --modules, or run `jk selective prepare` first");
            return Exit.USAGE;
        }

        List<String> args = new ArrayList<>();
        args.add(verb);
        args.add("-C");
        args.add(dir.toString());
        if (effectiveModules != null && !effectiveModules.isBlank()) {
            args.add("--modules");
            args.add(effectiveModules);
        }
        // When modules come from a plan, skip --since (modules already capture the set).
        if (effectiveSince != null
                && !effectiveSince.isBlank()
                && (effectiveModules == null || effectiveModules.isBlank())) {
            args.add("--affected-since");
            args.add(effectiveSince);
        }
        GlobalOptions g = GlobalOptions.from(in);
        if (g.force) args.add("--force");
        if (g.rebuild) args.add("--rebuild");

        return cc.jumpkick.cli.Jk.execute(args.toArray(String[]::new));
    }

    private record Plan(String since, String modulesSpec, List<String> modules) {}

    private static Plan readPlan(Path planPath) throws Exception {
        String text = Files.readString(planPath, StandardCharsets.UTF_8);
        String since = extractJsonString(text, "since");
        String modulesSpec = extractJsonString(text, "modulesSpec");
        List<String> modules = extractJsonStringArray(text, "modules");
        return new Plan(since, modulesSpec, modules);
    }

    private static String extractJsonString(String json, String field) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static List<String> extractJsonStringArray(String json, String field) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*\\[(.*?)]", java.util.regex.Pattern.DOTALL)
                        .matcher(json);
        if (!m.find()) return List.of();
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher s = java.util.regex.Pattern.compile("\"([^\"]*)\"").matcher(m.group(1));
        while (s.find()) out.add(s.group(1));
        return out;
    }

    private static List<String> toRelPaths(Path root, Set<Path> dirs) {
        Path abs = root.toAbsolutePath().normalize();
        List<String> out = new ArrayList<>();
        for (Path d : dirs) {
            Path n = d.toAbsolutePath().normalize();
            if (n.equals(abs)) out.add(".");
            else {
                try {
                    out.add(abs.relativize(n).toString().replace('\\', '/'));
                } catch (IllegalArgumentException e) {
                    out.add(n.toString());
                }
            }
        }
        return out;
    }

    private static String gitRevParse(Path root) {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.waitFor() == 0 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String q(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
