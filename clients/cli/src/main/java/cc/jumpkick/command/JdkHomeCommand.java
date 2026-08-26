// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.CommonOpts;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JdkResolver;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** {@code jk jdk home} — print the pinned JDK's JAVA_HOME export line. */
public final class JdkHomeCommand implements CliCommand {

    @Override
    public String name() {
        return "home";
    }

    @Override
    public String description() {
        return "Print the pinned JDK's JAVA_HOME export line";
    }

    @Override
    public List<Opt> options() {
        return List.of(CommonOpts.jdksDir());
    }

    /** {@code eval "$(jk jdk home)"} — stdout is an export line; the no-pin failure stays a human wedge on stderr. */
    @Override
    public boolean scriptMode(Invocation in) {
        return true;
    }

    @Override
    public int run(Invocation in) throws IOException {
        Path jdksDir = CommonOpts.jdksDirValue(in);
        Path dir = GlobalOptions.from(in).workingDir();
        Optional<InstalledJdk> jdk = JdkResolver.forProject(dir, jdksDir);
        if (jdk.isEmpty()) {
            CommandWedge.printFail(
                    "JDK",
                    "no pinned JDK for " + PathDisplay.styledRaw(dir)
                            + " (write `.jdk-version` via `jk jdk use <spec>`)");
            return Exit.CONFIG;
        }
        CliOutput.out("export JAVA_HOME=" + shellQuote(jdk.get().home().toString()));
        return 0;
    }

    static String shellQuote(String value) {
        if (!needsQuoting(value)) return value;
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static boolean needsQuoting(String value) {
        if (value.isEmpty()) return true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '/' || c == ':'))
                return true;
        }
        return false;
    }
}
