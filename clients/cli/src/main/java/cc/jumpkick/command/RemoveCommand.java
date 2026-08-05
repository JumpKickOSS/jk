// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
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
                "Library short name, group:artifact[:ver], name@ver, or local path.\n"
                        + "Version is ignored when present; path uses the module project name."));
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
        Path file = dir.resolve("jk.toml");
        if (!Files.exists(file)) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Remove", "no jk.toml in current directory"));
            return Exit.CONFIG;
        }
        int selected = (test ? 1 : 0) + (runtime ? 1 : 0) + (provided ? 1 : 0) + (processor ? 1 : 0);
        if (selected > 1) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "Remove", "--test / --runtime / --provided / --processor are mutually exclusive"));
            return Exit.USAGE;
        }
        Scope scope = test
                ? Scope.TEST
                : runtime ? Scope.RUNTIME : provided ? Scope.PROVIDED : processor ? Scope.PROCESSOR : Scope.MAIN;
        String name;
        try {
            name = shortNameOf(nameArg, dir);
        } catch (IllegalArgumentException e) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Remove", e.getMessage()));
            return Exit.USAGE;
        }

        try {
            EngineEdits.apply(file, "remove-dependency", java.util.List.of(scope.canonical(), name));
        } catch (IOException e) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Remove", e.getMessage()));
            return 1;
        }
        CommandWedge.printOk(
                "Remove",
                "Removed "
                        + Theme.colorize(name, Theme.active().activeStep())
                        + " from "
                        + Theme.colorize("dependencies", Theme.active().cyan())
                        + "."
                        + Theme.colorize(scope.canonical(), Theme.active().cyan()));
        return 0;
    }

    /**
     * Manifest key (library short name) for {@code remove-dependency}. Path forms resolve to the
     * target module's project name; Maven coords use artifactId; {@code @version} is stripped.
     */
    static String shortNameOf(String arg, Path cwd) {
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
            var info = BuildCommand.projectInfoOrNull(target);
            if (info != null && info.name() != null && !info.name().isBlank()) {
                return info.name();
            }
            Path leaf = target.getFileName();
            if (leaf == null || leaf.toString().isBlank()) {
                throw new IllegalArgumentException("could not derive dependency name from path: " + arg);
            }
            return leaf.toString();
        }

        // Strip optional @version (library@1.2.3 / library@=1.2.3) — version is not needed to remove.
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
