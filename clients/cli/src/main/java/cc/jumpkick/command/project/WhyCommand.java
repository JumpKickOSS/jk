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
import java.util.List;

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
        Path buildFile = ManifestPaths.manifestIn(dir);
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
        if (report.matchNames().isEmpty() && report.exclusions().isEmpty()) {
            CommandWedge.printFail("Why", query + " is not in jk-lock.toml");
            return 1;
        }

        CliOutput.out(Theme.active().gradientHeaderAnsi("Jk - Dependency Lookup"));
        for (int i = 0; i < report.matchNames().size(); i++) {
            List<String> members = report.membersOf(i);
            String forMembers = members.isEmpty()
                    ? ""
                    : Theme.colorize(
                            " (for " + String.join(", ", members) + ")",
                            Theme.active().darkGray());
            String pinnedBy = report.pinnedByOf(i);
            String pinned = pinnedBy == null
                    ? ""
                    : Theme.colorize(
                            " (pinned by " + pinnedBy + ")", Theme.active().darkGray());
            CliOutput.out(Coords.module(
                            report.matchNames().get(i), report.matchVersions().get(i))
                    + forMembers
                    + pinned
                    + " is pulled in by:");
            boolean any = false;
            for (int j = 0; j < report.paths().size(); j++) {
                if (!report.pathOwners().get(j).equals(Integer.toString(i))) continue;
                any = true;
                CliOutput.out("  " + renderPath(report.paths().get(j), report.selectorsOf(j)));
            }
            if (!any) {
                CliOutput.out("  (not reachable from any declared dependency — orphan lockfile entry?)");
            }
            if (i + 1 < report.matchNames().size()) CliOutput.out();
        }
        if (!report.exclusions().isEmpty()) {
            if (!report.matchNames().isEmpty()) CliOutput.out();
            for (int i = 0; i < report.exclusions().size(); i++)
                CliOutput.out(renderExclusion(report.exclusionFields(i)));
        }
        return 0;
    }

    /**
     * One pruned edge: the child an exclusion kept out, the lock row whose expansion dropped it,
     * and who declared the exclusion — the manifest handle or the POM.
     */
    private static String renderExclusion(List<String> fields) {
        String child = fields.get(0);
        String origin = fields.size() > 1 ? fields.get(1) : "";
        String under = fields.size() > 2 ? fields.get(2) : "";
        int at = under.lastIndexOf('@');
        String row = at > 0 ? Coords.module(under.substring(0, at), under.substring(at + 1)) : under;
        StringBuilder out =
                new StringBuilder(child).append(" is excluded under ").append(row);
        if (!origin.isEmpty()) {
            out.append(Theme.colorize(
                    " (excluded by " + origin + ")", Theme.active().darkGray()));
        }
        return out.toString();
    }

    /**
     * Format a wire path ({@code module@version>module@version}) with colored coordinates. Each
     * step whose selector the lock knows says what was asked for and by whom — the manifest for
     * the root, the step before it otherwise — beside the version that was picked.
     */
    private static String renderPath(String path, List<String> selectors) {
        String[] steps = path.split(">");
        StringBuilder out = new StringBuilder();
        String arrow = Theme.colorize(" -> ", Theme.active().darkGray());
        for (int i = 0; i < steps.length; i++) {
            if (i > 0) out.append(arrow);
            String step = steps[i];
            int at = step.lastIndexOf('@');
            out.append(at > 0 ? Coords.module(step.substring(0, at), step.substring(at + 1)) : step);
            String selector = i < selectors.size() ? selectors.get(i) : "";
            if (selector.isEmpty()) continue;
            String by = i == 0 ? ManifestPaths.MANIFEST : moduleOf(steps[i - 1]);
            out.append(Theme.colorize(
                    " (declared " + selector + " by " + by + ")", Theme.active().darkGray()));
        }
        return out.toString();
    }

    /** The {@code module} half of a wire step ({@code module@version}). */
    private static String moduleOf(String step) {
        int at = step.lastIndexOf('@');
        return at > 0 ? step.substring(0, at) : step;
    }

    /** Strip the version component if present; return arg unchanged when no colon. */
    private static String moduleOnly(String arg) {
        int first = arg.indexOf(':');
        if (first < 0) return arg;
        int second = arg.indexOf(':', first + 1);
        return second < 0 ? arg : arg.substring(0, second);
    }
}
