// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.CommonOpts;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.jdk.JdkInventory;
import cc.jumpkick.jdk.JdkInventory.Finding;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk jdk verify} — compare JumpKick-managed JDK trees to {@code $JK_STATE_DIR/jk-jdks.toml}.
 */
public final class JdkVerifyCommand implements CliCommand {

    @Override
    public String name() {
        return "verify";
    }

    @Override
    public String description() {
        return "Verify managed JDK trees have not been tampered with";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Rewrite the inventory from trees on disk (rehash, drop missing).", "--repair"),
                CommonOpts.jdksDir());
    }

    @Override
    public int run(Invocation in) throws Exception {
        boolean repair = in.isSet("repair");
        Path jdksDir = CommonOpts.jdksDirValue(in);
        JdkInventory inventory = JdkInventory.of(jdksDir != null ? jdksDir : JkDirs.jdks());
        List<Finding> findings = repair ? inventory.repair() : inventory.verify();
        if (findings.isEmpty()) {
            CommandWedge.printOk("JDK", "No managed JDKs to verify.");
            return 0;
        }
        boolean anyFailure = false;
        boolean anyUnhashed = false;
        CommandWedge.envelopeStart();
        for (Finding f : findings) {
            if (f.ok()) {
                CliOutput.out("ok        " + f.id());
            } else if (f.kind() == Finding.Kind.UNHASHED) {
                // A missing fingerprint is a baseline gap, not evidence of tampering — the
                // migration deliberately records rows unhashed so the shell hook stays fast, so
                // every upgrader's first verify hits this. Warn and point at --repair.
                anyUnhashed = true;
                String detail = f.detail() == null ? "" : " — " + f.detail();
                CliOutput.out("unhashed  " + f.id() + detail);
            } else {
                anyFailure = true;
                String label =
                        switch (f.kind()) {
                            case UNTRACKED -> "untracked";
                            case MISSING -> "missing";
                            case TAMPERED -> "tampered";
                            case UNHASHED, OK -> "ok"; // handled above
                        };
                String detail = f.detail() == null ? "" : " — " + f.detail();
                CliOutput.out(label + "  " + f.id() + detail);
            }
        }
        if (!anyFailure) {
            CommandWedge.printOk(
                    "JDK",
                    anyUnhashed
                            ? "verified; some fingerprints not baselined yet (run `jk jdk verify --repair` to record them)."
                            : (findings.size() == 1
                                    ? "1 managed JDK verified."
                                    : findings.size() + " managed JDKs verified."));
            return 0;
        }
        CommandWedge.printFail(
                "JDK",
                repair
                        ? "inventory repaired; re-run `jk jdk verify` if findings remain."
                        : "one or more managed JDKs failed verification (try `jk jdk verify --repair`).");
        return 1;
    }
}
