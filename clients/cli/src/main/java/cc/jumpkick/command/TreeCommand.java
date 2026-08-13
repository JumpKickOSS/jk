// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.ProjectContext;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Icon;
import cc.jumpkick.cli.tui.RichText;
import cc.jumpkick.cli.tui.Tree;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.config.ConfigSources;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ModuleSelection;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.resolver.DependencyTree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/** {@code jk tree} — print the resolved dependency tree. */
public final class TreeCommand implements CliCommand {

    @Override
    public String name() {
        return "tree";
    }

    @Override
    public String description() {
        return "Print the resolved dependency tree";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<depth>", "Maximum tree depth. Default: 0 (declared).", "-d", "--depth"),
                Opt.flag("Show transitive lockfile dependencies.", "-t", "--transitive"),
                Opt.flag("Flatten each scope to a sorted, deduped list.", "-f", "--flatten"),
                Opt.flag("Blend all scopes into one tree, one badge row.", "-S", "--stack"),
                Opt.value("<scopes>", "Scopes to show, in order; meta: exec/run/all.", "-s", "--scopes"));
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("module", Arity.ZERO_OR_ONE, "Module (:name) or path. Default: workspace."));
    }

    @Override
    public int run(Invocation in) throws IOException {
        Integer depth = in.value("depth").map(Integer::parseInt).orElse(null);
        boolean flatten = in.isSet("flatten");
        boolean stack = in.isSet("stack");
        boolean transitive = in.isSet("transitive");

        // -s/--scopes: an explicit, ordered subset; default = export, main, runtime.
        List<Scope> scopes = new ArrayList<>(DependencyTree.defaultScopeOrder());
        var scopesArg = in.value("scopes");
        if (scopesArg.isPresent()) {
            List<String> tokens = Arrays.stream(scopesArg.get().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (tokens.isEmpty()) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                        "Tree", "--scopes requires at least one scope (valid: " + validScopes() + ")"));
                return Exit.CONFIG;
            }
            Set<Scope> ordered = new LinkedHashSet<>();
            for (String token : tokens) {
                List<Scope> expanded = resolveScopeToken(token);
                if (expanded == null) {
                    CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                            "Tree", "invalid scope '" + token + "' (valid: " + validScopes() + ")"));
                    return Exit.CONFIG;
                }
                ordered.addAll(expanded);
            }
            scopes = new ArrayList<>(ordered);
        }
        GlobalOptions global = GlobalOptions.from(in);
        Path cwd = global.workingDir();
        String moduleSpec = in.positionals().isEmpty() ? null : in.positionals().getFirst();
        TreeDir target = resolveTreeDir(cwd, moduleSpec);
        if (!target.ok()) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Tree", target.error()));
            return Exit.CONFIG;
        }
        Path dir = target.dir();
        var proj = ProjectContext.require(dir, "tree").orElse(null);
        if (proj == null) return Exit.CONFIG;
        int lockCode = cc.jumpkick.cli.EnsureFreshLock.ensure(dir, cc.jumpkick.util.JkDirs.cache(), global, "Tree");
        if (lockCode != 0) return lockCode;
        Path lockFile = proj.lockFile();
        if (!Files.isRegularFile(lockFile)) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "Tree",
                    "no jk-lock.toml in " + cc.jumpkick.cli.PathDisplay.styledRaw(dir)
                            + " (lock refresh did not produce one)"));
            return Exit.CONFIG;
        }

        int max = maxDepth(transitive, depth);

        boolean nerdfont = cc.jumpkick.config.GlobalConfig.nerdfont();
        Theme t = Theme.active();
        boolean ansi = t.isAnsi();

        // Composite-aware: walks path deps' own trees too (anchored at `dir`). The walk runs
        // engine-side (thin client) with marker-tag styling; this client substitutes its Theme.
        List<String> scopeNames = scopes.stream().map(Scope::canonical).toList();
        String tagged;
        try {
            tagged = cc.jumpkick.cli.engine.EngineClient.treeRender(
                    cc.jumpkick.engine.EnginePaths.current(), dir, max, flatten, stack, scopeNames);
        } catch (IOException | RuntimeException e) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Tree", e.getMessage()));
            return Exit.CONFIG;
        }
        String rendered = DependencyTree.applyStyling(tagged, styling(nerdfont, ansi));
        buildTree(rendered, scopeNames).print();
        if (rendered.contains(DependencyTree.MISSING_SUFFIX)) {
            CliOutput.out();
            CliOutput.out(
                    ansi
                            ? "Some dependencies are missing from your local cache. Run "
                                    + Theme.colorize("jk lock", t.warning())
                            : "Some dependencies are missing from your local cache. Run `jk lock`");
        }
        return 0;
    }

    /**
     * Declared-only ({@code 0}) unless {@code -t}/{@code --transitive} expands the lockfile
     * closure, or {@code -d} caps the walk. {@code -d} wins when both are set.
     */
    static int maxDepth(boolean transitive, Integer depth) {
        if (depth != null) return depth;
        return transitive ? Integer.MAX_VALUE : 0;
    }

    /**
     * Directory whose graph to print. No spec → workspace root (even from a member dir). {@code
     * :name} is a workspace module selector. Anything else is a filesystem path ({@code .}, {@code
     * foo}, {@code foo/bar}) that must contain {@code jk.toml}.
     */
    static TreeDir resolveTreeDir(Path cwd, String spec) throws IOException {
        Path start = cwd.toAbsolutePath().normalize();
        if (spec == null || spec.isBlank()) {
            Path project = nearestProject(start);
            if (project == null) {
                return TreeDir.fail("no jk.toml in " + start);
            }
            return TreeDir.ok(workspaceOrProject(project));
        }
        String token = spec.trim();
        if (token.startsWith(":")) {
            if (token.length() == 1 || token.substring(1).isBlank()) {
                return TreeDir.fail("`:name` requires a module name");
            }
            return resolveColonModule(start, token);
        }
        return resolveModulePath(start, token);
    }

    private static TreeDir resolveColonModule(Path start, String spec) throws IOException {
        Path project = nearestProject(start);
        if (project == null) {
            return TreeDir.fail("no jk.toml in " + start);
        }
        Path root = workspaceOrProject(project);
        JkBuild entry = JkBuildParser.parse(root.resolve("jk.toml"));
        ModuleSelection.Result selected = ModuleSelection.resolve(root, entry, spec);
        if (!selected.ok()) {
            return TreeDir.fail(selected.errorMessage());
        }
        if (selected.moduleDirs().size() != 1) {
            return TreeDir.fail(
                    "`" + spec + "` matched " + selected.moduleDirs().size() + " modules — pick one path or :name");
        }
        return TreeDir.ok(selected.moduleDirs().iterator().next());
    }

    private static TreeDir resolveModulePath(Path start, String spec) {
        Path relative = start.resolve(spec).normalize();
        if (isModuleDir(relative)) return TreeDir.ok(relative);
        Path project = nearestProject(start);
        if (project != null) {
            Path fromProject = workspaceOrProject(project).resolve(spec).normalize();
            if (isModuleDir(fromProject) && !fromProject.equals(relative)) {
                return TreeDir.ok(fromProject);
            }
        }
        if (Files.exists(relative) && !Files.isDirectory(relative)) {
            return TreeDir.fail("`" + spec + "` is not a directory");
        }
        if (Files.isDirectory(relative) && !Files.isRegularFile(relative.resolve("jk.toml"))) {
            return TreeDir.fail("no jk.toml in " + relative);
        }
        return TreeDir.fail("`" + spec + "` is not a module directory");
    }

    private static Path nearestProject(Path start) {
        Path toml = ConfigSources.findProjectConfig(start);
        return toml == null ? null : toml.getParent();
    }

    /** Workspace root when {@code project} is a member or the root itself; otherwise the project. */
    static Path workspaceOrProject(Path project) {
        Path dir = project.toAbsolutePath().normalize();
        if (WorkspaceScan.isWorkspaceRoot(dir)) return dir;
        return WorkspaceScan.findRoot(dir).orElse(dir);
    }

    private static boolean isModuleDir(Path dir) {
        return Files.isDirectory(dir) && Files.isRegularFile(dir.resolve("jk.toml"));
    }

    record TreeDir(Path dir, String error) {
        static TreeDir ok(Path dir) {
            return new TreeDir(dir, null);
        }

        static TreeDir fail(String error) {
            return new TreeDir(null, error);
        }

        boolean ok() {
            return error == null;
        }
    }

    /** Wedge + root coord + scope rail + engine body, as one {@link Tree}. */
    static Tree buildTree(String rendered, List<String> scopeNames) {
        String[] lines = rendered.split("\n", -1);
        String rootLine = lines.length == 0 ? "" : lines[0];
        List<String> body = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].isEmpty()) body.add(lines[i]);
        }
        Tree.Node root = Tree.node(Icon.pulse(), rootCoord(rootLine))
                .gap(Tree.Gap.NONE)
                .bodyFit(Tree.BodyFit.RAIL)
                .body(scopesSummary(scopeNames));
        for (Tree.Node child : Tree.forest(body)) {
            root.child(child);
        }
        return new Tree("Dependencies Tree").gap(Tree.Gap.NONE).root(root);
    }

    private static RichText rootCoord(String rootLine) {
        String vis = org.jline.utils.AttributedString.stripAnsi(rootLine == null ? "" : rootLine)
                .strip();
        if (vis.startsWith("● ")) vis = vis.substring(2);
        else if (vis.startsWith("* ")) vis = vis.substring(2);
        return boldGav(vis);
    }

    private static RichText boldGav(String gav) {
        if (gav == null || gav.isEmpty()) return RichText.empty();
        String[] p = gav.split(":", 3);
        if (p.length < 3) {
            return RichText.parse("[bold coord-group]" + RichText.escape(gav) + "[/]");
        }
        return RichText.parse("[bold coord-group]"
                + RichText.escape(p[0])
                + "[/]:[bold coord-name]"
                + RichText.escape(p[1])
                + "[/]:[bold coord-version]"
                + RichText.escape(p[2])
                + "[/]");
    }

    private static RichText scopesSummary(List<String> scopeNames) {
        String list = String.join(", ", scopeNames);
        return RichText.parse("[dark-gray]·[/] Scopes: " + RichText.escape(list));
    }

    /**
     * The {@code exec}/{@code run} meta-scope: the scopes that form the classpath needed to run the
     * project, in display order. Sourced from {@link ClasspathResolver#RUNTIME} so it stays in sync
     * with the real run classpath.
     */
    private static final List<Scope> EXEC_SCOPES = Arrays.stream(Scope.values())
            .filter(ClasspathResolver.RUNTIME::contains)
            .toList();

    /**
     * Resolve a user-supplied scope token (case-insensitive) to one or more scopes: {@code
     * exec}/{@code run} expand to the run classpath; any other token is a single scope. Returns null
     * if the token is not a valid scope or meta-scope.
     */
    private static List<Scope> resolveScopeToken(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        if (t.equals("all")) return DependencyTree.allScopeOrder();
        if (t.equals("exec") || t.equals("run")) return EXEC_SCOPES;
        Scope scope = coerceScope(t);
        return scope == null ? null : List.of(scope);
    }

    /**
     * Coerce a user-supplied scope token (case-insensitive) to a {@link Scope}, or null if invalid.
     */
    private static Scope coerceScope(String token) {
        try {
            // fromCanonical handles hyphenated scopes ("test-dev"); fall back to the
            // enum-name form ("TEST_DEV") for users typing underscores.
            return Scope.fromCanonical(token.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            try {
                return Scope.valueOf(token.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e2) {
                return null;
            }
        }
    }

    /**
     * Comma-separated list of valid scope names (incl. the {@code exec}/{@code run}/{@code all}
     * meta-scopes).
     */
    private static String validScopes() {
        return Arrays.stream(Scope.values())
                        .map(s -> s.name().toLowerCase(Locale.ROOT))
                        .collect(Collectors.joining(", "))
                + ", exec/run, all";
    }

    /**
     * Color pattern for the Maven coordinate — the canonical {@code
     * [blue]group[/]:[cyan]artifact[/]:[bright-blue]version[/]} from {@link Coords}. Rails get the
     * same dim dark-gray the wizard uses for its settled rails. {@link Theme#colorize} respects
     * {@code --color} / {@code NO_COLOR} / dumb terminals, so escapes are dropped cleanly when color
     * is off.
     */
    private static DependencyTree.Styling styling(boolean nerdfont, boolean ansi) {
        if (!ansi) {
            // No-ANSI: replace all Unicode connectors with ASCII equivalents,
            // use [scope] bracket badges, * root bullet, plain uncolored coords.
            UnaryOperator<String> asciiRail = s -> switch (s) {
                case "├─" -> "+-";
                case "╰─" -> "`-";
                case "├─ " -> "+- ";
                case "╰─ " -> "`- ";
                case "│  " -> "|  ";
                case "   " -> "   ";
                case "●" -> "*";
                default -> s;
            };
            UnaryOperator<String> plain = UnaryOperator.identity();
            // Back-reference rows: connector + coord + " ⎋" all arrive as one string.
            // Replace Unicode connectors and drop the ⎋ marker (no color = no dim cue).
            UnaryOperator<String> asciiReference =
                    s -> s.replace("╰─ ", "`- ").replace("├─ ", "+- ").replace(" ⎋", "");
            UnaryOperator<String> asciiBadge = s -> "[" + s + "]";
            UnaryOperator<String> asciiRoot = gav -> " * " + gav;
            return new DependencyTree.Styling(
                    asciiRail, plain, plain, plain, asciiReference, asciiBadge, plain, asciiRoot);
        }
        // Scope section badge: a rounded pill (Nerd Font) or space-padded chip.
        UnaryOperator<String> scopeBadge = s -> cc.jumpkick.cli.tui.Badge.pill(s, nerdfont);
        Theme t = Theme.active();
        // Root-line: ● bullet (dark-gray) + bold coord colors — no pill or background.
        return new DependencyTree.Styling(
                s -> Theme.colorize(s, t.darkGray()),
                s -> Theme.colorize(s, Coords.groupStyle()),
                s -> Theme.colorize(s, Coords.artifactStyle()),
                s -> Theme.colorize(s, Coords.versionStyle()),
                // ⎋ back-reference rows: the whole entry in bright-black (= darkGray).
                s -> Theme.colorize(s, t.darkGray()),
                scopeBadge,
                TreeCommand::boldCoord);
    }

    /**
     * The root project coordinate in bold — {@code group:artifact:version}, each segment bold and in
     * its usual {@link Coords} color. Bold must be baked into each segment's style (a wrapping bold
     * escape is cancelled by every segment's color reset). Input is the plain {@code
     * group:artifact:version}.
     */
    private static String boldCoord(String gav) {
        String[] p = gav.split(":", 3);
        if (p.length < 3) return Theme.colorize(gav, Coords.groupStyle().bold());
        return Theme.colorize(p[0], Coords.groupStyle().bold())
                + ":"
                + Theme.colorize(p[1], Coords.artifactStyle().bold())
                + ":"
                + Theme.colorize(p[2], Coords.versionStyle().bold());
    }
}
