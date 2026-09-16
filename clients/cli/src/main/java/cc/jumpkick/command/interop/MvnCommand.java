// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.interop;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.compat.PassthroughEnv;
import cc.jumpkick.host.time.Clock;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk mvn ...} — passthrough to Maven. Provisioning is engine-hosted; {@code bin/mvn} execs
 * client-side with inherited stdio so Maven keeps its TTY and Ctrl-C semantics. When the event spy
 * jar is on hand ({@link MavenSpyJar}) it rides Maven's extension path and records the reactor to
 * a file the engine journals afterwards, so {@code jk results} and MCP see the run.
 */
public final class MvnCommand implements CliCommand {

    @Override
    public String name() {
        return "mvn";
    }

    @Override
    public String description() {
        return "Run Maven; only its three own options are jk's (jk --help mvn)";
    }

    @Override
    public boolean passthrough() {
        return true;
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<dir>", "Override the tools install root.", "--tools-dir"),
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
        Optional<Path> spy = MavenSpyJar.locate();
        Path events = spy.isPresent() ? eventsFile() : null;
        List<String> command = new ArrayList<>();
        command.add(mvnBin.toString());
        command.addAll(events == null ? args : MavenSpyJar.arguments(spy.get(), events, args));
        ProcessBuilder pb = new ProcessBuilder(command).directory(projectDir.toFile());
        PassthroughEnv.apply(pb.environment(), jdk.map(InstalledJdk::home).orElse(null));
        long started = Clock.SYSTEM.nanos();
        Process p = CliOutput.handOffTerminal(pb);
        // Skip the gap only once the exec actually started — a failed start() still owns
        // the terminal, and its error wedge has earned the envelope's trailing blank.
        CliOutput.skipTrailingBlank();
        int exit = p.waitFor();
        if (events != null) journal(projectDir, events, exit, (Clock.SYSTEM.nanos() - started) / 1_000_000L);
        return exit;
    }

    /** An empty file under jk's tmp dir for the spy to append to; deleted once journaled. */
    private static Path eventsFile() throws IOException {
        Files.createDirectories(JkDirs.tmp());
        return Files.createTempFile(JkDirs.tmp(), "mvn-events-", ".tsv");
    }

    /**
     * Hand the run to the engine, which writes {@code target/jk-results.md} and the history row.
     * An empty event file ({@code mvn -v}, a POM Maven could not read) journals nothing; a failure
     * to journal is one line on stderr, never Maven's exit code.
     */
    private void journal(Path projectDir, Path events, int exit, long millis) throws IOException {
        try {
            if (Files.size(events) == 0) return;
            HostedEvents.MvnResults r = EngineClient.mvnResults(
                    EnginePaths.current(), projectDir, events, exit, millis, String.join(" ", args));
            if (r.error() != null) CliOutput.err("jk mvn: run not journaled: " + r.error());
            else if (r.results() != null) CliOutput.err("results: " + projectDir.relativize(Path.of(r.results())));
        } catch (IOException e) {
            CliOutput.err("jk mvn: run not journaled: " + e.getMessage());
        } finally {
            Files.deleteIfExists(events);
        }
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
