// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.run.JsonlShape;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Table;
import cc.jumpkick.config.TestHistory;
import cc.jumpkick.config.TestQuarantine;
import cc.jumpkick.config.TestQuarantineReport;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.GroupCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.MachineOutput;
import cc.jumpkick.model.command.Param;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@code jk flaky} — the workspace's flaky and quarantined test classes: list them, show one
 * with its history, track one under an issue, release one from {@value TestQuarantine#FILE}. Reads
 * and writes files only; no engine.
 */
public final class FlakyCommand extends GroupCommand {

    private static final String TITLE = "Flaky";

    @Override
    public String name() {
        return "flaky";
    }

    @Override
    public String description() {
        return "List, show, track and release flaky and quarantined tests";
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(new ListCommand(), new ShowCommand(), new IssueCommand(), new ReleaseCommand());
    }

    /** The workspace root of the invocation's working directory. */
    private static Path root(Invocation in) {
        Path wd = GlobalOptions.from(in).workingDir();
        return WorkspaceScan.owningRoot(wd).orElse(wd);
    }

    /** One {@code flaky} row: a class, its state and its history, {@code window} the policy's runs. */
    public static Consumer<JsonFields> fields(TestQuarantineReport.Row r, int window) {
        TestQuarantine.Entry e = r.entry();
        TestQuarantine.Issue issue = e == null ? null : e.issue();
        return f -> f.string("class", r.className())
                .string("module", r.module())
                .bool("quarantined", r.quarantined())
                .number("flakes", r.flakes(window))
                .number("runs", r.counted(window))
                .string("history", r.history())
                .optionalNonBlankString("since", e == null ? null : e.since())
                .optionalNonBlankString("reason", e == null ? null : e.reason())
                .optionalMap("issue", issue == null ? null : issue.fields());
    }

    /** {@code jk flaky list}. */
    static int list(Path stateDir, Path root, boolean json) {
        TestQuarantineReport report;
        try {
            report = TestQuarantineReport.of(stateDir, root);
        } catch (IOException | IllegalArgumentException e) {
            CommandWedge.printFail(TITLE, e.getMessage());
            return 1;
        }
        int window = report.policy().runs();
        if (json) {
            for (TestQuarantineReport.Row r : report.listed()) JsonlShape.row("flaky", fields(r, window));
            return 0;
        }
        if (report.listed().isEmpty()) {
            CliOutput.out("(no flaky or quarantined test classes)");
            return 0;
        }
        Table table =
                new Table("Flaky and quarantined tests").columns("Class", "Module", "State", "Re-run passes", "Issue");
        for (TestQuarantineReport.Row r : report.listed()) {
            TestQuarantine.Entry e = r.entry();
            TestQuarantine.Issue issue = e == null ? null : e.issue();
            table.row(
                    r.className(), r.module(), state(r, window), flakes(r, window), issue == null ? "" : issue.name());
        }
        table.print();
        return 0;
    }

    /** {@code jk flaky show <class>}. */
    static int show(Path stateDir, Path root, String name, boolean json) {
        List<TestQuarantineReport.Row> rows;
        int window;
        try {
            TestQuarantineReport report = TestQuarantineReport.of(stateDir, root);
            rows = report.named(name);
            window = report.policy().runs();
        } catch (IOException | IllegalArgumentException e) {
            CommandWedge.printFail(TITLE, e.getMessage());
            return 1;
        }
        if (rows.isEmpty()) {
            CommandWedge.printFail(TITLE, "no history or quarantine entry: " + name);
            return 1;
        }
        for (TestQuarantineReport.Row r : rows) {
            if (json) {
                JsonlShape.row("flaky", fields(r, window));
                continue;
            }
            CliOutput.out(r.className() + "  " + (r.module().isEmpty() ? "every module" : r.module()));
            CliOutput.out("  state:    " + state(r, window));
            TestQuarantine.Entry e = r.entry();
            if (e != null && !e.reason().isEmpty()) CliOutput.out("  reason:   " + e.reason());
            if (e != null && e.issue() != null)
                CliOutput.out("  issue:    " + e.issue().label());
            String h = r.history();
            if (h.isEmpty()) {
                CliOutput.out("  history:  none on this machine");
                continue;
            }
            String counted =
                    r.counted(window) + (TestHistory.released(TestHistory.last(h, window)) ? " since release" : "");
            CliOutput.out("  last " + counted + ":  " + TestHistory.flakes(h, window) + " passed on a re-run, "
                    + TestHistory.count(h, window, TestHistory.FAILED) + " failed, "
                    + TestHistory.count(h, window, TestHistory.PASSED) + " passed");
            CliOutput.out("  history:  " + h);
            CliOutput.out("            oldest first; . passed, F failed, R passed on a re-run,");
            CliOutput.out("            | released");
        }
        return 0;
    }

    /** {@code jk flaky issue <class> <id-or-url> [url]}. */
    static int issue(Path root, String name, List<String> given, boolean json) {
        TestQuarantine.Issue issue;
        List<TestQuarantine.Entry> tracked;
        try {
            issue = TestQuarantine.Issue.of(given);
            tracked = TestQuarantine.issue(root, name, issue);
        } catch (IOException | IllegalArgumentException e) {
            CommandWedge.printFail(TITLE, e.getMessage());
            return 1;
        }
        if (tracked.isEmpty()) {
            CommandWedge.printFail(TITLE, "not quarantined: " + name);
            return 1;
        }
        for (TestQuarantine.Entry e : tracked) {
            if (json) {
                JsonlShape.row("flaky-issue", f -> f.string("class", e.className())
                        .string("module", e.module())
                        .optionalMap("issue", issue.fields()));
            } else {
                CommandWedge.printOk(
                        TITLE,
                        e.className() + (e.module().isEmpty() ? "" : " in " + e.module()) + ": issue " + issue.label());
            }
        }
        return 0;
    }

    /** {@code jk flaky release <class>}. */
    static int release(Path stateDir, Path root, String name, boolean json) {
        List<TestQuarantine.Entry> released;
        try {
            released = TestQuarantine.release(stateDir, root, name);
        } catch (IOException | IllegalArgumentException e) {
            CommandWedge.printFail(TITLE, e.getMessage());
            return 1;
        }
        if (released.isEmpty()) {
            CommandWedge.printFail(TITLE, "not quarantined: " + name);
            return 1;
        }
        for (TestQuarantine.Entry e : released) {
            if (json) {
                JsonlShape.row(
                        "flaky-released", f -> f.string("class", e.className()).string("module", e.module()));
            } else {
                CommandWedge.printOk(
                        TITLE, "released " + e.className() + (e.module().isEmpty() ? "" : " in " + e.module()));
            }
        }
        return 0;
    }

    private static String state(TestQuarantineReport.Row r, int window) {
        TestQuarantine.Entry e = r.entry();
        if (e == null) {
            if (r.flakes(window) > 0) return "flaky";
            return TestHistory.released(r.history()) ? "released" : "not flaky";
        }
        return e.since().isEmpty() ? "quarantined" : "quarantined since " + e.since();
    }

    private static String flakes(TestQuarantineReport.Row r, int window) {
        int runs = r.counted(window);
        return runs == 0 ? "-" : r.flakes(window) + " of " + runs;
    }

    private static Param classParam(String what) {
        return Param.of("class", Arity.ONE, what);
    }

    /** {@code jk flaky list}. */
    static final class ListCommand implements CliCommand {
        @Override
        public String name() {
            return "list";
        }

        @Override
        public String description() {
            return "List flaky and quarantined test classes";
        }

        @Override
        public List<String> aliases() {
            return List.of("ls");
        }

        @Override
        public MachineOutput machineOutput() {
            return MachineOutput.JSONL;
        }

        @Override
        public int run(Invocation in) {
            return list(JkDirs.state(), root(in), GlobalOptions.outputIsJson(in));
        }
    }

    /** {@code jk flaky show <class>}. */
    static final class ShowCommand implements CliCommand {
        @Override
        public String name() {
            return "show";
        }

        @Override
        public String description() {
            return "Show one test class with its history";
        }

        @Override
        public List<Param> parameters() {
            return List.of(classParam("The class, fully qualified or its simple name."));
        }

        @Override
        public MachineOutput machineOutput() {
            return MachineOutput.JSONL;
        }

        @Override
        public int run(Invocation in) {
            return show(JkDirs.state(), root(in), in.positionals().getFirst(), GlobalOptions.outputIsJson(in));
        }
    }

    /** {@code jk flaky issue <class> <id-or-url> [url]}. */
    static final class IssueCommand implements CliCommand {
        @Override
        public String name() {
            return "issue";
        }

        @Override
        public String description() {
            return "Track a quarantined test class under an issue: its id, URL or both";
        }

        @Override
        public List<Param> parameters() {
            return List.of(
                    classParam("The class, fully qualified or its simple name."),
                    Param.of("id-or-url", Arity.ONE, "The issue's id (PROJ-88), or its URL when you give only one."),
                    Param.of("url", Arity.ZERO_OR_ONE, "The issue's URL, after its id."));
        }

        @Override
        public MachineOutput machineOutput() {
            return MachineOutput.JSONL;
        }

        @Override
        public int run(Invocation in) {
            List<String> args = in.positionals();
            return issue(root(in), args.getFirst(), args.subList(1, args.size()), GlobalOptions.outputIsJson(in));
        }
    }

    /** {@code jk flaky release <class>}. */
    static final class ReleaseCommand implements CliCommand {
        @Override
        public String name() {
            return "release";
        }

        @Override
        public String description() {
            return "Release a test class from quarantine";
        }

        @Override
        public List<Param> parameters() {
            return List.of(classParam("The class, fully qualified or its simple name."));
        }

        @Override
        public MachineOutput machineOutput() {
            return MachineOutput.JSONL;
        }

        @Override
        public int run(Invocation in) {
            return release(JkDirs.state(), root(in), in.positionals().getFirst(), GlobalOptions.outputIsJson(in));
        }
    }
}
