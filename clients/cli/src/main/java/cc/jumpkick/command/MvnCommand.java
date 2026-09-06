// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.CommonOpts;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.compat.PassthroughEnv;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JdkResolver;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.runtime.HostedEvents;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk mvn ...} — passthrough to Maven. Provisioning is engine-hosted; {@code bin/mvn} execs
 * client-side with inherited stdio so Maven keeps its TTY and Ctrl-C semantics.
 */
public final class MvnCommand implements CliCommand {

    @Override
    public String name() {
        return "mvn";
    }

    @Override
    public String description() {
        return "Passthrough to Maven (jk manages the install)";
    }

    @Override
    public boolean passthrough() {
        return true;
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<dir>", "Override the tools install root.", "--tools-dir")
                        .hide(),
                CommonOpts.jdksDir(),
                Opt.flag("Skip tool discovery.", "--no-discover"));
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("args", Arity.ZERO_OR_MORE, "Arguments forwarded to Maven."));
    }

    @Nullable
    Path directory;

    @Nullable
    Path toolsDir;

    @Nullable
    Path jdksDir;

    boolean noDiscover;
    List<String> args = new ArrayList<>();

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.directory = in.value("dir").map(Path::of).orElse(null);
        this.toolsDir = in.value("tools-dir").map(Path::of).orElse(null);
        this.jdksDir = CommonOpts.jdksDirValue(in);
        this.noDiscover = in.isSet("no-discover");
        this.args = in.positionals();

        Path projectDir = directory != null
                ? directory.toAbsolutePath().normalize()
                : Path.of(".").toAbsolutePath().normalize();
        Path toolsRoot = toolsDir != null ? toolsDir : JkDirs.tools();

        // Provision Maven engine-side, get back the bin path.
        Path mvnBin = provision(projectDir, toolsRoot, noDiscover, false);
        if (mvnBin == null) return 1;

        // Exec Maven directly so stdio is inherited cleanly.
        Optional<InstalledJdk> jdk = JdkResolver.forProject(projectDir, jdksDir);
        List<String> command = new ArrayList<>();
        command.add(mvnBin.toString());
        command.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(command).directory(projectDir.toFile());
        PassthroughEnv.apply(pb.environment(), jdk.map(InstalledJdk::home).orElse(null));
        Process p = CliOutput.handOffTerminal(pb);
        // Skip the gap only once the exec actually started — a failed start() still owns
        // the terminal, and its error wedge has earned the envelope's trailing blank.
        CliOutput.skipTrailingBlank();
        return p.waitFor();
    }

    /**
     * Provision a Maven/Gradle distribution and return its launcher path, or {@code null} (with the
     * error already rendered) on failure. Engine-hosted: {@code CompatPlans.provision} links or
     * downloads the distribution in the engine JVM and answers with the launcher path.
     */
    static @Nullable Path provision(Path projectDir, Path toolsRoot, boolean noDiscover, boolean isGradle)
            throws IOException, InterruptedException {
        String tool = isGradle ? "gradle" : "mvn";
        HostedEvents.Provision p;
        try {
            p = EngineClient.provision(EnginePaths.current(), projectDir, toolsRoot, noDiscover, isGradle);
        } catch (IOException e) {
            CommandWedge.printFail(tool, e.getMessage());
            return null;
        }

        if (p.error() != null) CommandWedge.printFail(tool, p.error());
        String source = Objects.requireNonNullElse(p.source(), "");
        if ("LINKED".equals(source) || "DOWNLOADED".equals(source)) {
            CliOutput.err((isGradle ? "Gradle " : "Maven ") + p.version() + " " + source.toLowerCase(Locale.ROOT));
        }
        if (p.exit() != 0) return null;
        return p.bin() != null ? Path.of(p.bin()) : null;
    }
}
