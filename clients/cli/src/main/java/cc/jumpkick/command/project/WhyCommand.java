// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.EnsureFreshLock;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.WhyReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** {@code jk why &lt;module&gt;} — explain why a module is in the dependency graph. */
public final class WhyCommand implements CliCommand {

    @Override
    public String name() {
        return "why";
    }

    @Override
    public String description() {
        return "Explain why an item is in the dependency graph";
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("dependency", Arity.ONE, "group or artifact or substring"));
    }

    @Override
    public int run(Invocation in) throws IOException {
        GlobalOptions global = GlobalOptions.from(in);
        Path dir = global.workingDir();
        Path buildFile = dir.resolve(ManifestPaths.MANIFEST);
        if (!Files.exists(buildFile)) {
            CommandWedge.printFail("Why", "project must have jk.toml (run `jk init` first)");
            return Exit.CONFIG;
        }
        int lockCode = EnsureFreshLock.ensure(dir, JkDirs.cache(), global, "Why");
        if (lockCode != 0) return lockCode;
        Path lockFile = LockPaths.lockFile(dir);
        if (!Files.exists(lockFile)) {
            CommandWedge.printFail("Why", "project must have jk-lock.toml (lock refresh did not produce one)");
            return Exit.CONFIG;
        }

        String query = moduleOnly(in.positionals().get(0));
        // The graph reasoning is engine-side (thin client): matching + provenance ride WHY_ACK.
        WhyReport report = EngineClient.why(EnginePaths.current(), dir, query);
        if (report.error() != null) {
            CommandWedge.printFail("Why", report.error());
            return Exit.CONFIG;
        }
        if (report.matchNames().isEmpty()) {
            CommandWedge.printFail("Why", query + " is not in jk-lock.toml");
            return 1;
        }

        CliOutput.out(Theme.active().gradientHeaderAnsi("Jk - Dependency Lookup"));
        for (int i = 0; i < report.matchNames().size(); i++) {
            CliOutput.out(Coords.module(
                            report.matchNames().get(i), report.matchVersions().get(i)) + " is pulled in by:");
            boolean any = false;
            for (int j = 0; j < report.paths().size(); j++) {
                if (!report.pathOwners().get(j).equals(Integer.toString(i))) continue;
                any = true;
                CliOutput.out("  " + renderPath(report.paths().get(j)));
            }
            if (!any) {
                CliOutput.out("  (not reachable from any declared dependency — orphan lockfile entry?)");
            }
            if (i + 1 < report.matchNames().size()) CliOutput.out();
        }
        return 0;
    }

    /** Format a wire path ({@code module@version>module@version}) with colored coordinates. */
    private static String renderPath(String path) {
        return Arrays.stream(path.split(">"))
                .map(step -> {
                    int at = step.lastIndexOf('@');
                    return at > 0 ? Coords.module(step.substring(0, at), step.substring(at + 1)) : step;
                })
                .collect(
                        Collectors.joining(Theme.colorize(" -> ", Theme.active().darkGray())));
    }

    /** Strip the version component if present; return arg unchanged when no colon. */
    private static String moduleOnly(String arg) {
        int first = arg.indexOf(':');
        if (first < 0) return arg;
        int second = arg.indexOf(':', first + 1);
        return second < 0 ? arg : arg.substring(0, second);
    }
}
