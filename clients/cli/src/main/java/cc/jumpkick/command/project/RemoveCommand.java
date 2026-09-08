// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.PathDisplay;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.command.EngineEdits;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.host.Errors;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jk remove &lt;spec&gt;} — remove a dependency from {@code jk.toml} by library short name,
 * Maven coord, or local module path (same disambiguation as {@code jk add}).
 *
 * <pre>
 *   jk remove n           # library short name, or path if ./n is a directory
 *   jk remove g:n[:v]     # Maven coord (version ignored; uses artifactId)
 *   jk remove n@1.2.3     # library; @version ignored
 *   jk remove ./n  | n/   # path → module's project name (artifactId)
 * </pre>
 */
public final class RemoveCommand implements CliCommand {

    @Override
    public String name() {
        return "remove";
    }

    @Override
    public String description() {
        return "Remove a dependency, or workspace module, from jk.toml";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Test scope", "--test"),
                Opt.flag("Runtime scope", "--runtime"),
                Opt.flag("Provided scope", "--provided"),
                Opt.flag("Annotation processor scope", "--processor"));
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of(
                "name|path",
                Arity.ONE,
                "Library short name, group:artifact[:ver], name@ver, or path.\n"
                        + "Version is ignored; a path resolves to the module name."));
    }

    @Override
    public int run(Invocation in) throws IOException {
        GlobalOptions global = GlobalOptions.from(in);
        boolean test = in.isSet("test");
        boolean runtime = in.isSet("runtime");
        boolean provided = in.isSet("provided");
        boolean processor = in.isSet("processor");
        String nameArg = in.positionals().get(0);

        Path dir = global.workingDir();
        Path file = dir.resolve(ManifestPaths.MANIFEST);
        if (!Files.exists(file)) {
            CommandWedge.printFail("Remove", "no jk.toml in current directory");
            return Exit.CONFIG;
        }
        int selected = (test ? 1 : 0) + (runtime ? 1 : 0) + (provided ? 1 : 0) + (processor ? 1 : 0);
        if (selected > 1) {
            CommandWedge.printFail("Remove", "--test / --runtime / --provided / --processor are mutually exclusive");
            return Exit.USAGE;
        }
        Scope scope = test
                ? Scope.TEST
                : runtime ? Scope.RUNTIME : provided ? Scope.PROVIDED : processor ? Scope.PROCESSOR : Scope.MAIN;
        // Candidate manifest keys, most-literal first: for a bare name the manifest key wins over
        // a shadowing directory — an unrelated checkout ./jackson must not redirect
        // `jk remove jackson` to that module's project name. Explicit path syntax
        // (:m, ./m, m/) is unambiguous and resolves via the module only.
        boolean explicitPath = AddCommand.isExplicitPathSyntax(nameArg);
        List<String> candidates = new ArrayList<>(2);
        boolean pathCandidate = false;
        try {
            if (explicitPath) {
                candidates.add(shortNameOf(nameArg, dir));
                pathCandidate = true;
            } else {
                candidates.add(literalNameOf(nameArg));
                if (AddCommand.isLocalPathArg(nameArg, dir)) {
                    String viaPath = shortNameOf(nameArg, dir);
                    if (!candidates.contains(viaPath)) {
                        candidates.add(viaPath);
                        pathCandidate = true;
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            CommandWedge.printFail("Remove", e.getMessage());
            return Exit.USAGE;
        }

        String removed = null;
        IOException firstError = null;
        for (String candidate : candidates) {
            try {
                EngineEdits.apply(file, "remove-dependency", List.of(scope.canonical(), candidate));
                removed = candidate;
                break;
            } catch (IOException e) {
                if (firstError == null) firstError = e;
            }
        }
        if (removed == null) {
            CommandWedge.printFail("Remove", Errors.text(firstError));
            return 1;
        }
        CommandWedge.printOk(
                "Remove",
                "Removed "
                        + Theme.colorize(removed, Theme.active().activeStep())
                        + " from "
                        + Theme.colorize("dependencies", Theme.active().cyan())
                        + "."
                        + Theme.colorize(scope.canonical(), Theme.active().cyan()));

        // Path form: also drop the module from the enclosing workspace root's [workspace].modules
        // (symmetry with `jk add <path>`, which registers it). Bare-name removals that matched the
        // literal manifest key leave module registration alone.
        boolean removedViaPath =
                explicitPath || (pathCandidate && removed.equals(candidates.get(candidates.size() - 1)));
        if (removedViaPath) {
            unregisterWorkspaceModule(dir, nameArg);
        }
        return 0;
    }

    /** Best-effort removal of the path's module registration from the enclosing workspace root. */
    private static void unregisterWorkspaceModule(Path cwd, String arg) {
        String raw = arg.charAt(0) == ':' ? arg.substring(1) : arg;
        raw = raw.replace('\\', '/');
        while (raw.endsWith("/") && raw.length() > 1) {
            raw = raw.substring(0, raw.length() - 1);
        }
        Path target = cwd.resolve(raw).normalize();
        Path root = WorkspaceScan.findEnclosingWorkspace(cwd).orElse(cwd);
        Path rootToml = root.resolve(ManifestPaths.MANIFEST);
        if (!Files.exists(rootToml) || !target.startsWith(root)) return;
        String rel = root.relativize(target).toString().replace('\\', '/');
        if (rel.isBlank()) return;
        try {
            if (EngineEdits.apply(rootToml, "remove-workspace-module", List.of(rel))) {
                CliOutput.out("Unregistered module '" + rel + "' from workspace " + PathDisplay.styledRaw(root));
            }
        } catch (IOException | RuntimeException e) {
            CommandWedge.printFail("Remove", "could not unregister workspace module: " + e.getMessage());
        }
    }

    /**
     * Manifest key (library short name) for {@code remove-dependency}. Path forms resolve to the
     * target module's project name; Maven coords use artifactId; {@code @version} is stripped.
     */
    public static String shortNameOf(String arg, Path cwd) {
        if (arg == null || arg.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        // Path / local module (same rules as jk add).
        if (AddCommand.isLocalPathArg(arg, cwd)) {
            String raw = arg.charAt(0) == ':' ? arg.substring(1) : arg;
            raw = raw.replace('\\', '/');
            while (raw.endsWith("/") && raw.length() > 1) {
                raw = raw.substring(0, raw.length() - 1);
            }
            if (raw.isBlank()) {
                throw new IllegalArgumentException("empty module path");
            }
            Path target = cwd.resolve(raw).normalize();
            var info = ProjectInfos.orNull(target);
            if (info != null && info.name() != null && !info.name().isBlank()) {
                return info.name();
            }
            Path leaf = target.getFileName();
            if (leaf == null || leaf.toString().isBlank()) {
                throw new IllegalArgumentException("could not derive dependency name from path: " + arg);
            }
            return leaf.toString();
        }

        return literalNameOf(arg);
    }

    /**
     * The non-path interpretation of {@code arg}: optional {@code @version} stripped
     * ({@code library@1.2.3}), Maven coords reduced to the artifactId.
     */
    static String literalNameOf(String arg) {
        if (arg == null || arg.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        String core = arg;
        int at = arg.indexOf('@');
        if (at >= 0) {
            core = arg.substring(0, at);
            if (core.isBlank()) {
                throw new IllegalArgumentException("empty name before '@' in: " + arg);
            }
        }

        int first = core.indexOf(':');
        if (first < 0) return core; // bare library short name
        int second = core.indexOf(':', first + 1);
        String artifact = second < 0 ? core.substring(first + 1) : core.substring(first + 1, second);
        if (artifact.isBlank()) {
            throw new IllegalArgumentException("could not extract artifactId from: " + arg);
        }
        return artifact;
    }
}
