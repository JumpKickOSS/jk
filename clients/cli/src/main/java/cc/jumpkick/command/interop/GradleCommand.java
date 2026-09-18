// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.interop;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.compat.PassthroughEnv;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JdkResolver;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code jk gradle ...} — passthrough to Gradle. Provisions engine-side, then execs
 * {@code bin/gradle} client-side (same split as {@link MvnCommand}).
 */
public final class GradleCommand implements CliCommand {

    @Override
    public String name() {
        return "gradle";
    }

    @Override
    public String description() {
        return "Run Gradle; only its four own options are jk's (jk --help gradle)";
    }

    @Override
    public boolean passthrough() {
        return true;
    }

    @Override
    public List<Opt> options() {
        return MvnCommand.ownOptions();
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("args", Arity.ZERO_OR_MORE, "Arguments forwarded to Gradle."));
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        Path directory = in.value("dir").map(Path::of).orElse(null);
        Path toolsDir = in.value("tools-dir").map(Path::of).orElse(null);
        Path jdksDir = CommonOpts.jdksDirValue(in);
        boolean noDiscover = in.isSet("no-discover");
        boolean acceptUnverified = CommonOpts.acceptUnverifiedTool(in);
        List<String> args = in.positionals();

        Path projectDir = directory != null
                ? directory.toAbsolutePath().normalize()
                : Path.of(".").toAbsolutePath().normalize();
        Path toolsRoot = toolsDir != null ? toolsDir : JkDirs.tools();

        Path gradleBin = MvnCommand.provision(projectDir, toolsRoot, noDiscover, acceptUnverified, true);
        if (gradleBin == null) return 1;

        Optional<InstalledJdk> jdk = JdkResolver.forProject(projectDir, jdksDir);
        List<String> command = new ArrayList<>();
        command.add(gradleBin.toString());
        command.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(command).directory(projectDir.toFile());
        PassthroughEnv.apply(pb.environment(), jdk.map(InstalledJdk::home).orElse(null));
        Process p = CliOutput.handOffTerminal(pb);
        // Skip the gap only once the exec actually started — a failed start() still owns
        // the terminal, and its error wedge has earned the envelope's trailing blank.
        CliOutput.skipTrailingBlank();
        return p.waitFor();
    }
}
