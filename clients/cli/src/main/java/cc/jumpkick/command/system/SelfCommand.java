// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cache.PreparedUpdate;
import cc.jumpkick.cache.StoreCasGc;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.CliPaths;
import cc.jumpkick.cli.engine.EngineFleet;
import cc.jumpkick.cli.engine.EngineProcessControl;
import cc.jumpkick.cli.engine.EngineSpawn;
import cc.jumpkick.cli.engine.JvmClient;
import cc.jumpkick.cli.engine.ReleaseArtifacts;
import cc.jumpkick.cli.engine.ReleaseDownloadView;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Confirm;
import cc.jumpkick.cli.tui.GlyphProbe;
import cc.jumpkick.cli.tui.Interactivity;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.config.NerdFontDetect;
import cc.jumpkick.config.NerdFontMode;
import cc.jumpkick.config.NerdFontNames;
import cc.jumpkick.config.SelfUpdateState;
import cc.jumpkick.config.TerminalCapsCache;
import cc.jumpkick.config.UserConfigEditor;
import cc.jumpkick.config.WhatsNew;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.WorkingDirectory;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.GroupCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.repo.ReleaseVerifier;
import cc.jumpkick.terminal.Terminals;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.FileLocks;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.version.Versions;
import cc.jumpkick.wire.EnginePaths;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk self} — self-update: install under {@code <home>/lib/jk-engine/} (parking the previous
 * jar as {@code .old}), flip PATH {@code jk} (parking {@code jk.old} / {@code jk.exe.old}), start
 * the new engine (graceful drain). {@code --now} stops the old engine first.
 */
public final class SelfCommand extends GroupCommand {

    @Override
    public String name() {
        return "self";
    }

    @Override
    public String description() {
        return "Manage this jk installation (update, nuke)";
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(
                new UpdateSub(),
                new MaterializeSub(),
                new SelfShelveCommand(),
                new WriteLauncherSub(),
                new RetireOldEnginesSub(),
                new SetupTerminalSub(),
                new SelfDesktopCommand(),
                new SelfNukeCommand());
    }

    /**
     * {@code jk self write-launcher [-A <path>]} — hidden install-time seam for the JVM client:
     * write {@code bin/jk} ({@code bin/jk.bat}) starting this JVM on the fat jar, and point
     * {@code jkx} at it. The installers run it as {@code java -jar <jar> self write-launcher} right
     * after placing the jar, so the JVM that ran the installer's version check is the one the
     * launcher bakes in, and the launcher text has one author ({@link JvmClientInstall}). With no
     * {@code --jar}, the jar is the one this process runs from.
     */
    public static final class WriteLauncherSub implements CliCommand {

        @Override
        public String name() {
            return "write-launcher";
        }

        @Override
        public String description() {
            return "Write the JVM client's launcher under <home>/bin";
        }

        @Override
        public boolean hidden() {
            return true;
        }

        @Override
        public List<Opt> options() {
            return List.of(Opt.value("<JAR>", "The client jar the launcher runs (default: this one).", "-A", "--jar"));
        }

        @Override
        public int run(Invocation in) throws Exception {
            Optional<Path> jar =
                    in.value("jar").map(CliPaths::abs).or(JvmClient::jar).or(WriteLauncherSub::ownJar);
            if (jar.isEmpty() || !Files.isRegularFile(jar.get())) {
                CommandWedge.printFail(
                        "Self",
                        "no client jar found; run `java -jar jk-" + JkVersion.VERSION
                                + ".jar self write-launcher`, or pass --jar");
                return Exit.USAGE;
            }
            Path launcher = JvmClientInstall.writeLauncher(
                    JkDirs.binDir(),
                    WorkingDirectory.resolve(jar.get()),
                    JvmClientInstall.runningJava(),
                    Os.isWindows());
            CommandWedge.printOk(
                    "Self",
                    "wrote " + launcher + ": runs " + WorkingDirectory.resolve(jar.get()) + " on "
                            + JvmClientInstall.runningJava());
            return 0;
        }

        /** The jar this class was loaded from, when it was a jar. */
        static Optional<Path> ownJar() {
            try {
                var source = JvmClientInstall.class.getProtectionDomain().getCodeSource();
                if (source == null) return Optional.empty();
                Path p = Path.of(source.getLocation().toURI());
                return p.getFileName().toString().endsWith(".jar") ? Optional.of(p) : Optional.empty();
            } catch (RuntimeException | URISyntaxException e) {
                return Optional.empty();
            }
        }
    }

    /** Hidden installer seam that removes only engines from the superseded platform default. */
    static final class RetireOldEnginesSub implements CliCommand {

        @Override
        public String name() {
            return "retire-old-engines";
        }

        @Override
        public String description() {
            return "Stop engines from the superseded platform-default home";
        }

        @Override
        public boolean hidden() {
            return true;
        }

        @Override
        public int run(Invocation in) {
            return EngineFleet.retireOldDefaultLayoutEngines().stream()
                            .anyMatch(result -> result.outcome() == EngineFleet.Outcome.SURVIVED)
                    ? Exit.FAILURE
                    : Exit.SUCCESS;
        }
    }

    /**
     * {@code jk self setup-terminal} — settle which Nerd Font glyphs this terminal draws. With
     * {@code --mode} it pins root-level {@code nerd-font} in {@code ~/.jk/config.toml}; without, at a
     * terminal, it shows the glyphs, asks what the user sees, and remembers the answer for this
     * terminal until its font, config or installed fonts change. Also invoked from install.sh.
     *
     * <p>{@code --explain} reports what detection concludes and why, without writing anything.
     */
    static final class SetupTerminalSub implements CliCommand {

        @Override
        public String name() {
            return "setup-terminal";
        }

        @Override
        public String description() {
            return "Confirm Nerd Font glyphs for this terminal";
        }

        @Override
        public List<Opt> options() {
            return List.of(
                    Opt.value("<MODE>", "pin nerd-font: auto, on, off, wedge, or pill", "-m", "--mode"),
                    Opt.flag("Report what detection concludes; write nothing.", "-e", "--explain"),
                    Opt.flag("Forget the remembered glyph answer.", "-f", "--forget"));
        }

        @Override
        public int run(Invocation in) throws Exception {
            var detected = NerdFontDetect.detect();
            if (in.isSet("explain")) {
                CommandWedge.printOk("Self", SetupTerminalReport.body(SetupTerminalReport.explain(detected)));
                return 0;
            }
            if (in.isSet("forget")) {
                TerminalCapsCache.real().remove(TerminalCapsCache.currentSlot());
                CommandWedge.printOk("Self", "forgot the remembered glyph answer for this terminal");
                return 0;
            }
            Optional<String> rawMode = in.value("mode");
            if (rawMode.isPresent()) return pin(rawMode.get(), detected);
            if (Interactivity.canPrompt()) return confirmAtTerminal(detected);
            Path cfg = JkDirs.userConfigFile();
            UserConfigEditor.setNerdFont(cfg, NerdFontMode.AUTO);
            var lines = new ArrayList<String>();
            lines.add("nerd-font = " + NerdFontMode.AUTO.toToml());
            lines.addAll(SetupTerminalReport.explain(detected));
            CommandWedge.printOk("Self", SetupTerminalReport.body(lines) + "\n  saved in " + cfg);
            return 0;
        }

        private static int pin(String raw, NerdFontDetect.Result detected) throws IOException {
            var mode = parseMode(raw);
            if (mode.isEmpty()) {
                CommandWedge.printFail("Self", "unknown --mode " + raw + " (expected auto|on|off|wedge|pill)");
                return Exit.USAGE;
            }
            Path cfg = JkDirs.userConfigFile();
            UserConfigEditor.setNerdFont(cfg, mode.get());
            String msg = "nerd-font = " + mode.get().toToml() + " → " + cfg;
            if (mode.get() == NerdFontMode.AUTO) {
                msg += "\n  " + SetupTerminalReport.body(SetupTerminalReport.explain(detected));
            }
            CommandWedge.printOk("Self", msg);
            return 0;
        }

        /** Show the glyphs, ask what the user sees, and remember it for this terminal. */
        private static int confirmAtTerminal(NerdFontDetect.Result detected) throws IOException {
            var tty = Terminals.controlling();
            var findings = GlyphProbe.run(tty);
            var out = CliOutput.stdout();
            SetupTerminalReport.explain(detected).forEach(l -> out.println("  " + l));
            findings.reportedFont().ifPresent(f -> SetupTerminalReport.wrap(
                            "The terminal reports its font as " + f + " ("
                                    + SetupTerminalReport.describe(NerdFontNames.caps(f)) + ").",
                            SetupTerminalReport.WIDTH)
                    .forEach(l -> out.println("  " + l)));
            findings.glyphCells()
                    .ifPresent(c -> out.println(
                            c == 1
                                    ? "  A powerline glyph takes one cell."
                                    : "  A powerline glyph takes " + c + " cells; a missing one is often wide."));
            out.println();
            out.println("  triangles:  \uE0B0 \uE0B2     (two arrow-shaped caps)");
            out.println("  rounded:    \uE0B6 \uE0B4     (two half-circle caps)");
            out.println();
            NerdFontCaps hint = detected.caps()
                    .max(NerdFontNames.caps(findings.reportedFont().orElse("")));
            boolean wedge = Confirm.of("Do you see two arrow-shaped caps on the triangles line?", hint.wedge())
                    .ask(tty);
            boolean pill = Confirm.of("Do you see two half-circle caps on the rounded line?", hint.pill())
                    .ask(tty);
            NerdFontCaps caps = new NerdFontCaps(wedge, pill);

            Path cfg = JkDirs.userConfigFile();
            UserConfigEditor.setNerdFont(cfg, NerdFontMode.AUTO);
            boolean saved = NerdFontDetect.confirm(caps, "confirmed");
            CommandWedge.printOk(
                    "Self",
                    "glyphs: " + SetupTerminalReport.describe(caps)
                            + (saved ? ", remembered for this terminal" : ", could not be saved")
                            + "\n  jk asks again if the terminal's font or config changes");
            return saved ? 0 : Exit.FAILURE;
        }

        /**
         * Accepts the config spellings plus the friendlier {@code on}/{@code off} that a flag-style
         * CLI invites, which {@link cc.jumpkick.config.NerdFontMode#parse} already covers via the
         * jk-wide boolean truth set.
         */
        private static Optional<NerdFontMode> parseMode(String raw) {
            return NerdFontMode.parse(raw);
        }
    }

    /**
     * {@code jk self materialize <client-bin> <engine-jar>} — hidden install-time seam: ingest a
     * local dist's engine jar into the CAS and install under {@code <home>/lib/jk-engine/}.
     * install.sh calls this through the freshly-installed client. The client-bin argument is the
     * PATH binary already written by the installer (not copied into the product lib).
     *
     * <p>The jar must be this client's own version. A client only ever spawns {@code
     * jk-engine-<own version>.jar}, so materializing someone else's engine publishes bytes nothing
     * will load — and, because the install is keyed by the client's version, it lands under a name
     * that lies about what it contains. Refuse instead: {@code jk self update} is the seam that
     * crosses versions.
     */
    public static final class MaterializeSub implements CliCommand {

        @Override
        public String name() {
            return "materialize";
        }

        @Override
        public String description() {
            return "Install the engine jar under <home>/lib/jk-engine from local artifacts";
        }

        @Override
        public boolean hidden() {
            return true;
        }

        @Override
        public List<Param> parameters() {
            return List.of(
                    Param.of("client-bin", Arity.ONE, "The jk client binary."),
                    Param.of("engine-jar", Arity.ONE, "The matching jk-engine jar."));
        }

        @Override
        public int run(Invocation in) throws Exception {
            Path engineJar = WorkingDirectory.resolve(in.positionals().get(1));
            if (!Files.isRegularFile(engineJar)) {
                CommandWedge.printFail("Self", "engine jar not found: " + engineJar);
                return Exit.SOFTWARE;
            }
            String jarVersion = EngineInstall.versionFromJarName(
                            engineJar.getFileName().toString())
                    .orElse(null);
            if (jarVersion == null) {
                CommandWedge.printFail(
                        "Self",
                        "not a jk-engine jar name: " + engineJar.getFileName() + " (expected jk-engine-"
                                + JkVersion.VERSION + ".jar)");
                return Exit.SOFTWARE;
            }
            if (!jarVersion.equals(JkVersion.VERSION)) {
                // A classifier is not a version. `jk-engine-<version>-all.jar` is the assembly this
                // build produces; the shipped name carries no classifier, because a client only ever
                // spawns `jk-engine-<its own version>.jar`. Reading `-all` as part of the version
                // turns "you handed me the assembly" into "you handed me a different release",
                // which sends the reader looking for a version problem that does not exist.
                if (jarVersion.startsWith(JkVersion.VERSION + "-")) {
                    CommandWedge.printFail(
                            "Self",
                            "that is the " + jarVersion.substring(JkVersion.VERSION.length() + 1)
                                    + " jar; use target/dist/lib/jk-engine-" + JkVersion.VERSION + ".jar");
                    return Exit.SOFTWARE;
                }
                CommandWedge.printFail(
                        "Self",
                        "engine jar is " + jarVersion + " but this client is " + JkVersion.VERSION
                                + "; build the matching engine, or run `jk self update " + jarVersion + "`");
                return Exit.SOFTWARE;
            }
            EngineInstall install = EngineInstall.current();
            EngineInstall.Materialized m =
                    install.materializeFromFiles(JkVersion.VERSION, JkStores.storeCas(), engineJar);
            install.gc();
            // The install two back is no longer pinned: its blobs go now, not at the weekly prune.
            StoreCasGc.afterInstall();
            try {
                UserConfigEditor.setNerdFont(JkDirs.userConfigFile(), NerdFontMode.AUTO);
            } catch (Exception ignored) {
                // Best-effort nerd-font seed; never fail materialize.
            }
            CommandWedge.printOk("Self", "Materialized JumpKick " + m.version());
            return 0;
        }
    }

    public static final class UpdateSub implements CliCommand {

        @Override
        public String name() {
            return "update";
        }

        @Override
        public String description() {
            return "Update jk to latest (or a given) release";
        }

        @Override
        public List<Opt> options() {
            // No --version option: the global -V/--version flag owns that name (the dispatcher
            // rejects duplicates), so the target rides as a positional.
            return List.of(
                    Opt.flag("Stop the engine immediately (no drain)", "-n", "--now"),
                    Opt.flag("Report whether a newer release is out; install nothing", "-c", "--check"),
                    Opt.flag("Download and verify the release; install it later", "-d", "--download"));
        }

        @Override
        public List<Param> parameters() {
            return List.of(
                    Param.of("version", Arity.ZERO_OR_ONE, "Target version x.y.z. Default: the latest release."));
        }

        @Override
        public int run(Invocation in) throws Exception {
            URI base = ReleaseArtifacts.releasesBase();
            String target = in.positionals().isEmpty() ? null : in.positionals().get(0);
            String running = JkVersion.VERSION;
            ReleaseVerifier verifier = ReleaseVerifier.current(GlobalConfig.releaseTrustedKeys());
            if (in.isSet("check")) return check(base, verifier, running);
            if (target == null) target = ReleaseArtifacts.latestVersion(base, verifier, running);
            if (target.isEmpty()) {
                CommandWedge.printFail("Self", "could not resolve a target version");
                return Exit.SOFTWARE;
            }
            String version = target;
            boolean download = in.isSet("download");
            boolean downgrade = Versions.compare(version, running) < 0;
            EngineInstall install = EngineInstall.current();
            Cas cas = JkStores.storeCas();
            if (version.equals(running) && install.resolve(version).isPresent()) {
                PreparedUpdate.delete(install.engineHome());
                if (!download) releaseHold();
                CommandWedge.printOk("Self", version + " is already current");
                return 0;
            }

            // One update at a time per home: the engine's own update and a prompt's answer run this too.
            Optional<String> installed = FileLocks.withLock(install.engineHome().resolve(UPDATE_LOCK), () -> {
                PreparedUpdate prepared = preparedFor(install, cas, version).orElse(null);
                if (prepared == null) prepared = prepare(base, verifier, version, running, install, cas);
                return download ? Optional.<String>empty() : Optional.of(apply(prepared, install, cas, downgrade));
            });
            if (installed.isEmpty()) {
                CommandWedge.printOk("Self", version + " downloaded; `jk self update " + version + "` installs it");
                return 0;
            }
            CommandWedge.printOk("Self", version + " installed (" + installed.get() + ")");
            // The new engine's bell and the next terminal command each say so once.
            SelfUpdateState.update(s -> s.withUpdated(running, version, Clock.SYSTEM.instant()));
            if (downgrade) {
                SelfUpdateState.update(s -> s.withHold(version, running));
                CliOutput.out("auto-update is held at " + version + "; `jk self update` resumes it");
            } else {
                releaseHold();
            }

            // Hand the engine over: --now stops the old daemon (killing its jobs) first;
            // otherwise the NEW engine's startup drains it gracefully — zero interrupted builds.
            var paths = EnginePaths.current();
            if (in.isSet("now")) {
                EngineProcessControl.forceStop(EnginePaths.activeSocket(paths));
            }
            Path newJk = EngineInstall.pathClient(JkDirs.binDir());
            if (Files.isRegularFile(newJk)) {
                CliOutput.handOffTerminal(new ProcessBuilder(newJk.toString(), "engine", "start"))
                        .waitFor();
                CliOutput.out("engine " + target + " is taking over"
                        + (in.isSet("now") ? "" : " (running builds finish on the old engine)"));
            }
            return 0;
        }

        /** The lock file under the engine home that one download-or-install holds. */
        static final String UPDATE_LOCK = ".jk-update.lock";

        /** Drop a downgrade's hold, saying so when there was one. */
        private static void releaseHold() throws IOException {
            if (!SelfUpdateState.read().isHeld()) return;
            SelfUpdateState.update(SelfUpdateState::withoutHold);
            CliOutput.out("auto-update resumes");
        }

        /**
         * {@code --check}: the signed latest-release pointer against the running version, recorded
         * in the update state the engine's scheduler reads. The pointer's ETag rides back as {@code
         * If-None-Match}, so an unchanged pointer is a 304 that keeps the recorded version. A
         * pointer older than the running version is a completed check, not a failure: a development
         * build, or a release whose pointer has not moved yet, is simply ahead of the site. A pointer
         * that cannot be read is recorded as an attempt, so the scheduler backs off.
         */
        private static int check(URI base, ReleaseVerifier verifier, String running) throws IOException {
            SelfUpdateState before = SelfUpdateState.read();
            String sentEtag = before.latest() == null ? null : before.latestEtag();
            ReleaseArtifacts.PointerCheck found;
            try {
                found = ReleaseArtifacts.checkLatest(base, verifier, sentEtag);
            } catch (IOException unreachable) {
                SelfUpdateState.update(s -> s.withAttempt(Clock.SYSTEM.instant()));
                throw unreachable;
            }
            String latest = found.notModified() ? before.latest() : found.version();
            SelfUpdateState.update(s -> s.withCheck(Clock.SYSTEM.instant(), latest, found.etag()));
            CommandWedge.printOk("Self", checkSummary(latest, running));
            return 0;
        }

        /** What {@code --check} says about {@code latest} against {@code running}. */
        static String checkSummary(@Nullable String latest, String running) {
            if (latest == null || Versions.compare(latest, running) == 0) {
                return "jk " + running + " is the latest release";
            }
            if (Versions.compare(latest, running) > 0) {
                return "jk " + latest + " is available (this is " + running + ")";
            }
            return "jk " + running + " is newer than the latest release, " + latest;
        }

        /**
         * The prepared record for {@code target} when one is on disk with both blobs intact. A record
         * for any other version, or one whose blobs a store wipe took, is dropped.
         */
        static Optional<PreparedUpdate> preparedFor(EngineInstall install, Cas cas, String target) throws IOException {
            Optional<PreparedUpdate> found = PreparedUpdate.read(install.engineHome());
            if (found.isEmpty()) return Optional.empty();
            if (found.get().version().equals(target) && found.get().blobsIntact(cas)) return found;
            PreparedUpdate.delete(install.engineHome());
            return Optional.empty();
        }

        /**
         * Download {@code target}'s engine jar and this host's client, verify both against the
         * release's signed manifest, put them in the CAS and record them. Nothing live changes.
         */
        static PreparedUpdate prepare(
                URI base, ReleaseVerifier verifier, String target, String running, EngineInstall install, Cas cas)
                throws IOException {
            ReleaseArtifacts.Manifest release = ReleaseArtifacts.manifest(base, target, verifier);
            boolean jvm = JvmClient.installed();
            // A JVM client moves to the native one as soon as a release lists it for this host,
            // unless JK_CLIENT=jvm asks to stay; a native client has nothing else to move to.
            String nativeClient = !jvm
                    ? pickClientArtifact(release.text(), target)
                    : "jvm".equals(System.getenv("JK_CLIENT"))
                            ? null
                            : nativeClientArtifact(release.text(), target).orElse(null);
            ReleaseArtifacts.Verified engine = fetch(release, engineJarName(target), "engine jar", target);
            String clientSha;
            PreparedUpdate.Client kind;
            if (nativeClient == null) {
                ReleaseArtifacts.Verified client =
                        fetch(release, jvmClientArtifact(release.text(), target), "client jar", target);
                cas.put(client.bytes(), client.sha256());
                clientSha = client.sha256();
                kind = PreparedUpdate.Client.JVM;
            } else {
                // The native CLI never inflates xz; the engine jar does.
                ReleaseArtifacts.Verified archive = fetch(release, nativeClient, "client binary", target);
                Path client = nativeClient.endsWith(".xz")
                        ? inflateXzViaEngine(engine.bytes(), archive.bytes())
                        : unzipSingleBinary(archive.bytes());
                clientSha = ingestClient(cas, client);
                kind = PreparedUpdate.Client.NATIVE;
            }
            cas.put(engine.bytes(), engine.sha256());
            fetchWhatsNew(release, target, install);
            PreparedUpdate prepared =
                    new PreparedUpdate(target, running, engine.sha256(), kind, clientSha, Clock.SYSTEM.instant());
            prepared.write(install.engineHome());
            return prepared;
        }

        /** {@code jk-whats-new-<version>.md}: the release's highlights, shown once it is installed. */
        static String whatsNewName(String version) {
            return "jk-whats-new-" + version + ".md";
        }

        /**
         * Keep the release's highlights beside the prepared record, verified like every artifact.
         * A release from before they shipped has none, and highlights never fail an update.
         */
        private static void fetchWhatsNew(ReleaseArtifacts.Manifest release, String target, EngineInstall install) {
            String name = whatsNewName(target);
            try {
                if (!release.has(name)) return;
                ReleaseArtifacts.Verified notes =
                        release.fetch(name, "release highlights", ReleaseArtifacts.Progress.NONE);
                PreparedUpdate.writeWhatsNew(install.engineHome(), notes.bytes());
            } catch (IOException e) {
                Log.debug("self update: highlights of " + target + " not kept", e);
            }
        }

        /**
         * Install a prepared update: the engine pointer moves to its jar, then the PATH client is
         * replaced (a native binary parked and renamed in, or the JVM client's jar placed under
         * {@code <home>/lib/jk} with the launcher rewritten over it), and the record goes. {@code
         * downgrade} lets an explicit older target replace the live engine. Returns the engine jar.
         */
        static String apply(PreparedUpdate prepared, EngineInstall install, Cas cas, boolean downgrade)
                throws IOException {
            EngineInstall.Materialized engine =
                    install.materialize(prepared.version(), cas, prepared.engineSha256(), downgrade);
            Path client = cas.pathFor(prepared.clientSha256());
            if (prepared.client() == PreparedUpdate.Client.JVM) {
                Path placed = JvmClientInstall.installJar(
                        Files.readAllBytes(client), JvmClientInstall.libDir(), prepared.version());
                JvmClientInstall.writeLauncher(JkDirs.binDir(), placed, JvmClientInstall.runningJava(), Os.isWindows());
            } else {
                EngineInstall.installBinaries(client, JkDirs.binDir());
                if (JvmClient.installed()) {
                    JvmClientInstall.retire(JkDirs.binDir(), JvmClientInstall.libDir(), Os.isWindows());
                }
            }
            keepWhatsNew(install);
            PreparedUpdate.delete(install.engineHome());
            StoreCasGc.afterInstall();
            return engine.engineJar().toString();
        }

        /**
         * The installed release's highlights move from beside the prepared record to the state
         * directory, where the bell and the terminal read them; a release without them leaves none,
         * so an older release's file is never shown for this one.
         */
        private static void keepWhatsNew(EngineInstall install) {
            try {
                Optional<byte[]> notes = PreparedUpdate.readWhatsNew(install.engineHome());
                if (notes.isPresent()) {
                    AtomicWrites.replace(WhatsNew.file(), notes.get());
                } else {
                    Files.deleteIfExists(WhatsNew.file());
                }
            } catch (IOException e) {
                Log.debug("self update: highlights not kept", e);
            }
        }

        /** {@code jk-engine-<version>.jar}, the release's platform-neutral engine. */
        static String engineJarName(String version) {
            return "jk-engine-" + version + ".jar";
        }

        /**
         * One artifact of the update through the release's verified manifest, under the download
         * bar the engine self-heal and {@code jk mvn}'s extension fetch render.
         */
        private static ReleaseArtifacts.Verified fetch(
                ReleaseArtifacts.Manifest release, String artifactName, String what, String version)
                throws IOException {
            try (ReleaseDownloadView view = new ReleaseDownloadView("Self", "jk " + version)) {
                return release.fetch(artifactName, what, view);
            }
        }

        /** {@code jk-<version>.jar} when the sums list it — the platform-neutral client. */
        static String jvmClientArtifact(String sumsText, String version) throws IOException {
            String jar = JvmClientInstall.jarName(version);
            if (sumHas(sumsText, jar)) return jar;
            throw new IOException("release SHA256SUMS has no " + jar + "; cannot verify the JVM client");
        }

        /**
         * CAS-ingest the inflated client and reclaim its temp file: {@link Cas#putFile} copies
         * (temp + atomic move), so the multi-MB {@code jk-self-*.bin} would otherwise be
         * stranded in the system temp dir on every update — xz and zip paths alike.
         */
        static String ingestClient(Cas cas, Path client) throws IOException {
            try {
                String clientSha = Hashing.sha256Hex(client);
                cas.putFile(client, clientSha);
                return clientSha;
            } finally {
                Files.deleteIfExists(client);
            }
        }

        /** This host's native client in the release, or a refusal naming what the sums lack. */
        static String pickClientArtifact(String sumsText, String version) throws IOException {
            return pickClientArtifact(sumsText, hostOs(), hostArch(), version);
        }

        /** Visible for tests — pass release vocabulary ({@link #hostOs}, {@link #hostArch}). */
        static String pickClientArtifact(String sumsText, String os, String arch, String version) throws IOException {
            var found = nativeClientArtifact(sumsText, os, arch, version);
            if (found.isPresent()) return found.get();
            String base = "jk-" + os + "-" + arch + "-" + version;
            throw new IOException("release SHA256SUMS has no " + base + ".xz"
                    + ("windows".equals(os) ? " (or " + base + ".zip)" : "")
                    + "; cannot verify the client");
        }

        /** This host's native client when the release lists one. */
        static Optional<String> nativeClientArtifact(String sumsText, String version) throws IOException {
            return nativeClientArtifact(sumsText, hostOs(), hostArch(), version);
        }

        /**
         * {@code jk-<os>-<arch>-<version>.xz} when the sums list it, else the Windows {@code .zip}.
         * Unix releases do not ship a zip. The version is part of the name on purpose: the manifest
         * is signed but not bound to its directory, so a manifest copied from another release names
         * that release's artifacts and cannot satisfy a request for this one.
         */
        static Optional<String> nativeClientArtifact(String sumsText, String os, String arch, String version)
                throws IOException {
            String base = "jk-" + os + "-" + arch + "-" + version;
            String xz = base + ".xz";
            if (sumHas(sumsText, xz)) return Optional.of(xz);
            if ("windows".equals(os)) {
                String zip = base + ".zip";
                if (sumHas(sumsText, zip)) return Optional.of(zip);
            }
            return Optional.empty();
        }

        /**
         * This OS as release artifacts name it, the vocabulary the installers and wrappers derive
         * from {@code uname -s}: {@code linux}, {@code macos}, {@code windows}, else the OS name
         * lower-cased ({@code freebsd}, {@code sunos}, {@code aix}).
         */
        static String hostOs() {
            return releaseOs(Os.name());
        }

        static String releaseOs(String osName) {
            String n = osName.toLowerCase(Locale.ROOT);
            if (n.startsWith("windows")) return "windows";
            if (n.startsWith("mac") || n.startsWith("darwin")) return "macos";
            return releaseToken(n);
        }

        /** This architecture as release artifacts name it: {@code x86_64}, {@code aarch64}, else as reported. */
        static String hostArch() {
            return releaseArch(System.getProperty("os.arch", ""));
        }

        static String releaseArch(String osArch) {
            String a = osArch.toLowerCase(Locale.ROOT);
            return switch (a) {
                case "amd64", "x86_64", "x64" -> "x86_64";
                case "aarch64", "arm64" -> "aarch64";
                default -> releaseToken(a);
            };
        }

        private static String releaseToken(String s) {
            String t = s.replaceAll("[^a-z0-9_]", "_");
            return t.isEmpty() ? "unknown" : t;
        }

        private static boolean sumHas(String sumsText, String name) throws IOException {
            return ReleaseVerifier.find(sumsText.getBytes(StandardCharsets.UTF_8), name)
                    .isPresent();
        }

        /**
         * Inflate {@code xz} by launching {@code EngineMain --inflate-xz} on the just-downloaded
         * engine jar (or the running version's jar). tukaani stays out of the native image.
         */
        private static Path inflateXzViaEngine(byte[] engineJar, byte[] xz) throws IOException {
            Path engineTmp = Files.createTempFile("jk-engine-", ".jar");
            Path xzTmp = Files.createTempFile("jk-self-", ".xz");
            Path out = Files.createTempFile("jk-self-", ".bin");
            try {
                Path inflater = inflaterEngineJar(engineJar, engineTmp);
                Files.write(xzTmp, xz);
                Path java = EngineSpawn.engineJava();
                Process p = new ProcessBuilder(
                                java.toString(),
                                "-cp",
                                inflater.toString(),
                                "cc.jumpkick.engine.EngineMain",
                                "--inflate-xz",
                                WorkingDirectory.resolve(xzTmp).toString(),
                                WorkingDirectory.resolve(out).toString())
                        .redirectErrorStream(true)
                        .start();
                String err = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int rc;
                try {
                    rc = p.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted inflating the client binary", e);
                }
                if (rc != 0) {
                    throw new IOException(
                            "engine xz inflate failed (exit " + rc + ")" + (err.isBlank() ? "" : ": " + err.strip()));
                }
                return out;
            } catch (IOException e) {
                Files.deleteIfExists(out);
                throw e;
            } finally {
                Files.deleteIfExists(engineTmp);
                Files.deleteIfExists(xzTmp);
            }
        }

        /**
         * Engine jar that implements {@code --inflate-xz}. An older jar ignores unknown flags and
         * would start the daemon — never hand it this role. Prefer the just-downloaded jar (the
         * version being installed); if that line predates inflate, fall back to the running
         * version's jar.
         */
        private static Path inflaterEngineJar(byte[] downloadedJar, Path downloadedTmp) throws IOException {
            Files.write(downloadedTmp, downloadedJar);
            if (hasInflateXz(downloadedTmp)) return downloadedTmp;
            var current = EngineInstall.current().resolve(JkVersion.VERSION);
            if (current.isPresent()) {
                Path jar = current.get().engineJar();
                if (Files.isRegularFile(jar) && hasInflateXz(jar)) return jar;
            }
            throw new IOException(
                    "neither the downloaded engine jar nor the running jk-engine implements --inflate-xz");
        }

        private static boolean hasInflateXz(Path engineJar) {
            try (var jar = new JarFile(engineJar.toFile())) {
                return jar.getEntry("cc/jumpkick/engine/Xz.class") != null;
            } catch (IOException e) {
                return false;
            }
        }

        private static Path unzipSingleBinary(byte[] zip) throws IOException {
            Path tmp = Files.createTempFile("jk-self-", ".bin");
            try (var zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    Files.copy(zin, tmp, StandardCopyOption.REPLACE_EXISTING);
                    return tmp;
                }
            }
            throw new IOException("release client archive contains no file");
        }
    }
}
