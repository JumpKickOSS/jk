// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.ProjectContext;
import cc.jumpkick.cli.api.Reentry;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.lock.ManifestPaths;
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
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * — Mill-style selective prepare / resolve / run over git-affected + module selectors.
 *
 * <pre>
 * jk selective resolve --since=origin/main
 * jk selective prepare --since=origin/main
 * jk selective run build
 * jk selective run test
 * </pre>
 */
public final class SelectiveCommand implements CliCommand {

    private final Reentry reentry;

    public SelectiveCommand(Reentry reentry) {
        this.reentry = reentry;
    }

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
                Opt.value("<git-ref>", "Modules changed since this git ref", "--since", "--affected-since"),
                Opt.value("<sel>", "Module selector (list/globs)", "-m", "--modules"),
                Opt.flag("Machine-readable module list", "--json"),
                Opt.value("<file>", "Plan file path (selective)", "--plan"));
    }

    @Override
    public List<Param> parameters() {
        return List.of(
                Param.of("action", Arity.ONE, "resolve | prepare | run"),
                Param.of("verb", Arity.ZERO_OR_ONE, "with run: build | test"));
    }

    /** The action positional, lowercased: {@code resolve | prepare | run}, or {@code ""}. */
    private static String action(Invocation in) {
        return in.positionals().isEmpty()
                ? ""
                : in.positionals().getFirst().trim().toLowerCase(Locale.ROOT);
    }

    /** {@code jk selective resolve} prints one module path per line (or {@code --json}) for a CI step to read. */
    @Override
    public boolean scriptMode(Invocation in) {
        // prepare/run reuse resolve() as a helper and settle with a human wedge, so the mode cannot
        // be decided inside it.
        return "resolve".equals(action(in));
    }

    @Override
    public int run(Invocation in) throws Exception {
        GlobalOptions global = GlobalOptions.from(in);
        Path dir = global.workingDir();
        var proj = ProjectContext.require(dir, "selective").orElse(null);
        if (proj == null) return Exit.CONFIG;

        if (in.positionals().isEmpty()) {
            CommandWedge.printFail("Selective", "expected resolve | prepare | run");
            return Exit.USAGE;
        }
        String action = action(in);
        String since = in.value("since").or(() -> in.value("affected-since")).orElse(null);
        String modules = in.value("modules").orElse(null);
        boolean json = in.isSet("json");
        Path planPath = in.value("plan").map(Path::of).orElse(dir.resolve(PLAN_REL));

        return switch (action) {
            case "resolve" -> resolve(dir, proj.buildFile(), since, modules, json, null);
            case "prepare" -> prepare(dir, proj.buildFile(), since, modules, planPath);
            case "run" -> runVerb(in, dir, proj.buildFile(), since, modules, planPath);
            default -> {
                CommandWedge.printFail("Selective", "unknown action `" + action + "` (resolve | prepare | run)");
                yield Exit.USAGE;
            }
        };
    }

    private static int resolve(
            Path dir,
            Path buildFile,
            @Nullable String since,
            @Nullable String modules,
            boolean json,
            @Nullable Set<Path> into)
            throws Exception {
        if ((since == null || since.isBlank()) && (modules == null || modules.isBlank())) {
            CommandWedge.printFail("Selective", "pass --since=<ref> and/or --modules=<sel>");
            return Exit.USAGE;
        }
        var selected = ProjectInfos.orError(dir, modules, since);
        if (selected.error() != null && !selected.error().isBlank()) {
            CommandWedge.printFail("Selective", selected.error());
            return Exit.CONFIG;
        }
        Set<Path> selectedDirs = new LinkedHashSet<>();
        for (String d : selected.moduleDirs()) {
            selectedDirs.add(Path.of(d).toAbsolutePath().normalize());
        }
        if (into != null) into.addAll(selectedDirs);
        List<String> rels = toRelPaths(dir, selectedDirs);
        if (json) {
            CliOutput.out(JsonFields.object().array("modules", rels).finish());
        } else if (rels.isEmpty()) {
            CliOutput.out("(no modules selected)");
        } else {
            for (String r : rels) CliOutput.out(r);
        }
        return 0;
    }

    private static int prepare(
            Path dir, Path buildFile, @Nullable String since, @Nullable String modules, Path planPath)
            throws Exception {
        Set<Path> dirs = new LinkedHashSet<>();
        int code = resolve(dir, buildFile, since, modules, false, dirs);
        if (code != 0) return code;
        List<String> rels = toRelPaths(dir, dirs);
        String gitHead = gitRevParse(dir);
        Map<String, String> hashes = contentHashes(dir, rels);
        String hashesJson = hashes.entrySet().stream()
                .map(e -> q(e.getKey()) + ": " + q(e.getValue()))
                .reduce((a, b) -> a + ", " + b)
                .map(s -> "{ " + s + " }")
                .orElse("{}");
        String body = """
                {
                  "since": %s,
                  "modulesSpec": %s,
                  "gitHead": %s,
                  "createdAt": %s,
                  "modules": [%s],
                  "contentHashes": %s
                }
                """.formatted(
                        q(since == null ? "" : since),
                        q(modules == null ? "" : modules),
                        q(gitHead == null ? "" : gitHead),
                        q(Instant.now().toString()),
                        String.join(",", rels.stream().map(SelectiveCommand::q).toList()),
                        hashesJson);
        Path parent = planPath.getParent();
        if (parent != null) Files.createDirectories(parent);
        AtomicWrites.replace(planPath, body);
        CommandWedge.printOk(
                "Selective",
                "Wrote "
                        + planPath
                        + " ("
                        + rels.size()
                        + " module"
                        + (rels.size() == 1 ? "" : "s")
                        + ", content hashes recorded)");
        return 0;
    }

    private int runVerb(
            Invocation in, Path dir, Path buildFile, @Nullable String since, @Nullable String modules, Path planPath)
            throws Exception {
        if (in.positionals().size() < 2) {
            CommandWedge.printFail("Selective", "expected build | test");
            return Exit.USAGE;
        }
        String verb = in.positionals().get(1).trim().toLowerCase(Locale.ROOT);
        if (!verb.equals("build") && !verb.equals("test")) {
            CommandWedge.printFail("Selective", "expected build | test (got " + verb + ")");
            return Exit.USAGE;
        }

        String effectiveSince = since;
        String effectiveModules = modules;
        Plan plan = null;
        if ((effectiveSince == null || effectiveSince.isBlank())
                && (effectiveModules == null || effectiveModules.isBlank())
                && Files.isRegularFile(planPath)) {
            plan = readPlan(planPath);
            if (plan.modules.isEmpty()
                    && (plan.since == null || plan.since.isBlank())
                    && (plan.modulesSpec == null || plan.modulesSpec.isBlank())) {
                CommandWedge.printFail("Selective", "plan " + planPath + " has no modules");
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
            CommandWedge.printFail("Selective", "pass --since / --modules, or run `jk selective prepare` first");
            return Exit.USAGE;
        }

        // Content-hash skipwhen plan has contentHashes, only re-run modules whose
        // fingerprints changed (unless --force / --redo).
        GlobalOptions g = GlobalOptions.from(in);
        if (plan != null && !plan.contentHashes.isEmpty() && !g.force && !g.rebuild) {
            List<String> planned = !plan.modules.isEmpty()
                    ? plan.modules
                    : Arrays.stream(effectiveModules == null ? new String[0] : effectiveModules.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList();
            List<String> plannedClean = new ArrayList<>();
            for (String m : planned) {
                String t = m == null ? "" : m.trim();
                if (!t.isEmpty()) plannedClean.add(t);
            }
            Map<String, String> now = contentHashes(dir, plannedClean);
            List<String> dirty = new ArrayList<>();
            for (String m : plannedClean) {
                String prev = plan.contentHashes.get(m);
                String cur = now.get(m);
                if (prev == null || cur == null || !prev.equals(cur)) dirty.add(m);
            }
            if (dirty.isEmpty()) {
                CommandWedge.printOk(
                        "Selective",
                        "content hashes match plan — nothing changed (" + plannedClean.size()
                                + " module"
                                + (plannedClean.size() == 1 ? "" : "s")
                                + ")");
                return 0;
            }
            effectiveModules = String.join(",", dirty);
            effectiveSince = null; // modules list is authoritative
            CommandWedge.printFail("Selective", "content-hash dirty modules: " + effectiveModules);
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
        if (g.force) args.add("--force");
        if (g.rebuild) args.add("--redo");

        return reentry.execute(args.toArray(String[]::new));
    }

    private record Plan(String since, String modulesSpec, List<String> modules, Map<String, String> contentHashes) {}

    private static Plan readPlan(Path planPath) throws Exception {
        Object root = MiniJson.parse(Files.readString(planPath, StandardCharsets.UTF_8));
        List<String> modules = new ArrayList<>();
        for (Object m : MiniJson.list(root, "modules")) {
            if (m instanceof String rel) modules.add(rel);
        }
        Map<String, String> hashes = new LinkedHashMap<>();
        if (MiniJson.get(root, "contentHashes") instanceof Map<?, ?> table) {
            for (Map.Entry<?, ?> e : table.entrySet()) {
                if (e.getValue() instanceof String hash) hashes.put(String.valueOf(e.getKey()), hash);
            }
        }
        return new Plan(MiniJson.str(root, "since"), MiniJson.str(root, "modulesSpec"), modules, hashes);
    }

    /**
     * Fingerprint a module for selective skip: relative paths under {@code jk.toml} + {@code src/}
     * (file path + sha256). Absolute paths are not stored — only content — so agents can share
     * plans when trees match. Generated / target trees are ignored.
     *
     * <p><b>Transitive blind spot</b> fingerprints do not yet include dependency
     * siblings. An unchanged module can be skipped even when an upstream it depends on changed.
     * Prefer full rebuilds when in doubt; see guide selective section.
     */
    static Map<String, String> contentHashes(Path workspaceRoot, List<String> moduleRels) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (String rel : moduleRels) {
            Path mod = ".".equals(rel) ? workspaceRoot : workspaceRoot.resolve(rel);
            out.put(rel, fingerprintModule(mod));
        }
        return out;
    }

    static String fingerprintModule(Path moduleDir) throws Exception {
        MessageDigest md = Hashing.newSha256();
        List<Path> files = new ArrayList<>();
        Path toml = moduleDir.resolve(ManifestPaths.MANIFEST);
        if (Files.isRegularFile(toml)) files.add(toml);
        Path src = moduleDir.resolve("src");
        if (Files.isDirectory(src)) {
            try (Stream<Path> walk = Files.walk(src)) {
                walk.filter(Files::isRegularFile).forEach(files::add);
            }
        }
        files.sort(Comparator.comparing(p -> moduleDir.relativize(p).toString().replace('\\', '/')));
        for (Path f : files) {
            String rel = moduleDir.relativize(f).toString().replace('\\', '/');
            md.update(rel.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(Files.readAllBytes(f));
            md.update((byte) 0);
        }
        return "sha256:" + Hashing.hex(md.digest());
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

    private static @Nullable String gitRevParse(Path root) {
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

    /**
     * JSON string literal, via the one escaper; null renders as an empty string rather than the
     * {@code null} literal, which is this file's existing contract.
     *
     * <p>The local copy this replaced escaped only backslash and quote, so a newline or a control
     * character in a module name or git head produced invalid JSON.
     */
    private static String q(String s) {
        return Jsonl.quote(s == null ? "" : s);
    }
}
