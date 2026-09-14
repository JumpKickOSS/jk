// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.engine.EngineFleet;
import cc.jumpkick.cli.engine.EngineProcessControl;
import cc.jumpkick.cli.engine.EngineSpawn;
import cc.jumpkick.cli.engine.JvmClient;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.NerdFontDetect;
import cc.jumpkick.config.NerdFontMode;
import cc.jumpkick.config.UserConfigEditor;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Os;
import cc.jumpkick.http.Http;
import cc.jumpkick.jdk.HostPlatform;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.GroupCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.repo.ReleaseVerifier;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.version.Versions;
import cc.jumpkick.wire.EnginePaths;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
                new WriteLauncherSub(),
                new RetireOldEnginesSub(),
                new SetupTerminalSub(),
                new SelfNukeCommand());
    }

    /**
     * {@code jk self write-launcher [--jar <path>]} — hidden install-time seam for the JVM client:
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
            return List.of(Opt.value("<JAR>", "The client jar the launcher runs (default: this one).", "--jar"));
        }

        @Override
        public int run(Invocation in) throws Exception {
            Optional<Path> jar =
                    in.value("jar").map(Path::of).or(JvmClient::jar).or(WriteLauncherSub::ownJar);
            if (jar.isEmpty() || !Files.isRegularFile(jar.get())) {
                CommandWedge.printFail(
                        "Self",
                        "no client jar to write a launcher for — run `java -jar jk-" + JkVersion.VERSION
                                + ".jar self write-launcher`, or pass --jar");
                return Exit.USAGE;
            }
            Path launcher = JvmClientInstall.writeLauncher(
                    JkDirs.binDir(), jar.get().toAbsolutePath(), JvmClientInstall.runningJava(), Os.isWindows());
            CommandWedge.printOk(
                    "Self",
                    "wrote " + launcher + " — jk " + JkVersion.VERSION + " on " + JvmClientInstall.runningJava()
                            + " over " + jar.get().toAbsolutePath());
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
     * {@code jk self setup-terminal} — persist root-level {@code nerd-font} in {@code
     * ~/.jk/config.toml}. Also invoked from install.sh.
     *
     * <p>Writing {@code auto} is the useful default: detection now runs cheaply on every launch, so
     * pinning a value is only for overriding it. {@code --explain} reports what detection currently
     * concludes and why, without writing anything.
     */
    static final class SetupTerminalSub implements CliCommand {

        @Override
        public String name() {
            return "setup-terminal";
        }

        @Override
        public String description() {
            return "Write nerd-font in config.toml (default: auto)";
        }

        @Override
        public List<Opt> options() {
            return List.of(
                    Opt.value("<MODE>", "auto (default), on, off, wedge, or pill", "--mode"),
                    Opt.flag("Report what detection concludes; write nothing.", "--explain"));
        }

        @Override
        public int run(Invocation in) throws Exception {
            var detected = NerdFontDetect.detect();
            if (in.isSet("explain")) {
                CommandWedge.printOk("Self", explain(detected));
                return 0;
            }
            String raw = in.value("mode").orElse("auto");
            var mode = parseMode(raw);
            if (mode.isEmpty()) {
                CommandWedge.printFail("Self", "unknown --mode " + raw + " (expected auto|on|off|wedge|pill)");
                return Exit.USAGE;
            }
            Path cfg = JkDirs.userConfigFile();
            UserConfigEditor.setNerdFont(cfg, mode.get());
            String msg = "nerd-font = " + mode.get().toToml() + " → " + cfg;
            if (mode.get() == NerdFontMode.AUTO) msg += "\n  " + explain(detected);
            CommandWedge.printOk("Self", msg);
            return 0;
        }

        /**
         * Accepts the config spellings plus the friendlier {@code on}/{@code off} that a flag-style
         * CLI invites, which {@link cc.jumpkick.config.NerdFontMode#parse} already covers via the
         * jk-wide boolean truth set.
         */
        private static Optional<NerdFontMode> parseMode(String raw) {
            return NerdFontMode.parse(raw);
        }

        private static String explain(NerdFontDetect.Result r) {
            var caps = r.caps();
            String granted =
                    !caps.any() ? "none" : caps.wedge() && caps.pill() ? "wedge+pill" : caps.wedge() ? "wedge" : "pill";
            return "detected " + granted + " — " + r.reason() + " [" + r.source() + "]";
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
            Path engineJar = Path.of(in.positionals().get(1));
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
                                    + " assembly of " + JkVersion.VERSION + ", and the engine ships without a"
                                    + " classifier — materialize jk-engine-" + JkVersion.VERSION + ".jar"
                                    + " (the build writes one under target/dist/lib/)");
                    return Exit.SOFTWARE;
                }
                CommandWedge.printFail(
                        "Self",
                        "engine jar is " + jarVersion + ", this client is " + JkVersion.VERSION + " — refusing to"
                                + " materialize " + engineJar + " (build the matching engine, or run"
                                + " `jk self update " + jarVersion + "` to move the whole install)");
                return Exit.SOFTWARE;
            }
            EngineInstall install = EngineInstall.current();
            EngineInstall.Materialized m =
                    install.materializeFromFiles(JkVersion.VERSION, JkStores.storeCas(), engineJar);
            EngineInstall.wipeAotDirectory(JkDirs.state().resolve("aot"), JkVersion.VERSION);
            install.gc();
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
            return List.of(Opt.flag("Stop the engine immediately (no drain)", "--now"));
        }

        @Override
        public List<Param> parameters() {
            return List.of(
                    Param.of("version", Arity.ZERO_OR_ONE, "Target version x.y.z. Default: the latest release."));
        }

        @Override
        public int run(Invocation in) throws Exception {
            URI base = releasesBase();
            String target = in.positionals().isEmpty() ? null : in.positionals().get(0);
            Http http = new Http();
            String running = JkVersion.VERSION;
            if (target == null) {
                target = latestVersion(
                        ReleaseVerifier.current(GlobalConfig.releaseTrustedKeys()),
                        get(http, URI.create(base + "/latest/LATEST"), "latest-release pointer"),
                        get(http, URI.create(base + "/latest/LATEST.sig"), "latest-release pointer signature"),
                        running);
            }
            if (target.isEmpty()) {
                CommandWedge.printFail("Self", "could not resolve a target version");
                return Exit.SOFTWARE;
            }
            EngineInstall install = EngineInstall.current();
            Cas cas = JkStores.storeCas();
            if (target.equals(running) && install.resolve(target).isPresent()) {
                CommandWedge.printOk("Self", target + " is already current");
                return 0;
            }

            if (JvmClient.installed()) {
                // The install the launcher describes: a new jar under <home>/lib/jk and a launcher
                // rewritten over it, on the JVM this update runs on. Never a native binary — the
                // host may have none, and a PATH client the launcher does not name is a second jk.
                EngineInstall.Materialized engine = fetchAndMaterializeJvm(http, base, target, install, cas);
                CommandWedge.printOk("Self", target + " installed (" + engine.engineJar() + ")");
            } else {
                Fetched fetched = fetchAndMaterialize(http, base, target, install, cas);
                EngineInstall.installBinaries(cas.pathFor(fetched.clientSha()), JkDirs.binDir());
                CommandWedge.printOk(
                        "Self", target + " installed (" + fetched.engine().engineJar() + ")");
            }

            // Hand the engine over: --now stops the old daemon (killing its jobs) first;
            // otherwise the NEW engine's startup drains it gracefully — zero interrupted builds.
            var paths = EnginePaths.current();
            if (in.isSet("now")) {
                EngineProcessControl.forceStop(EnginePaths.activeSocket(paths));
            }
            Path newJk = pathClient(JkDirs.binDir());
            if (Files.isRegularFile(newJk)) {
                CliOutput.handOffTerminal(new ProcessBuilder(newJk.toString(), "engine", "start"))
                        .waitFor();
                CliOutput.out("engine " + target + " is taking over"
                        + (in.isSet("now") ? "" : " (running builds finish on the old engine)"));
            }
            return 0;
        }

        record Fetched(EngineInstall.Materialized engine, String clientSha) {}

        /**
         * The version the signed latest-release pointer names, once its signature verifies and it
         * is not older than {@code running}. The pointer is the one mutable input of an update, so
         * a bucket writer or a mirror that rolls it back to an older, validly signed release must
         * get a refusal here rather than a downgrade; an explicit {@code jk self update <version>}
         * never reads the pointer and stays the deliberate way down.
         */
        static String latestVersion(ReleaseVerifier verifier, byte[] pointer, byte[] signature, String running)
                throws IOException {
            verifier.verify(pointer, new String(signature, StandardCharsets.UTF_8));
            String latest = ReleaseVerifier.parsePointer(pointer).version();
            if (Versions.compare(latest, running) < 0) {
                throw new IOException("the latest-release pointer names " + latest + ", older than the " + running
                        + " this jk runs — REFUSING a rolled-back pointer (a mirror or the release site may be"
                        + " stale or compromised; `jk self update " + latest + "` downgrades deliberately)");
            }
            return latest;
        }

        static Path pathClient(Path binDir) {
            Path exe = binDir.resolve("jk.exe");
            if (Files.isRegularFile(exe)) return exe;
            Path bat = binDir.resolve("jk.bat");
            if (Files.isRegularFile(bat)) return bat;
            return binDir.resolve("jk");
        }

        /**
         * The JVM client's update: the engine jar and {@code jk-<version>.jar}, both verified
         * against the signed sums, the engine materialized, the client jar placed under {@code
         * <home>/lib/jk} and the launcher rewritten over it.
         */
        static EngineInstall.Materialized fetchAndMaterializeJvm(
                Http http, URI base, String version, EngineInstall install, Cas cas)
                throws IOException, InterruptedException {
            URI dir = URI.create(base + "/" + version + "/");
            byte[] sums = get(http, dir.resolve("SHA256SUMS"), "release checksums");
            var verifier = ReleaseVerifier.current(GlobalConfig.releaseTrustedKeys());
            byte[] sig = get(http, dir.resolve("SHA256SUMS.sig"), "release signature");
            verifier.verify(sums, new String(sig, StandardCharsets.UTF_8));

            String jarName = "jk-engine-" + version + ".jar";
            byte[] jar = verified(get(http, dir.resolve(jarName), "engine jar"), sums, jarName);
            String clientName = jvmClientArtifact(new String(sums, StandardCharsets.UTF_8), version);
            byte[] client = verified(get(http, dir.resolve(clientName), "client jar"), sums, clientName);

            String jarSha = Hashing.sha256Hex(jar);
            cas.put(jar, jarSha);
            EngineInstall.Materialized engine = install.materialize(version, cas, jarSha);
            Path placed = JvmClientInstall.installJar(client, JvmClientInstall.libDir(), version);
            JvmClientInstall.writeLauncher(JkDirs.binDir(), placed, JvmClientInstall.runningJava(), Os.isWindows());
            return engine;
        }

        /** {@code jk-<version>.jar} when the sums list it — the platform-neutral client. */
        static String jvmClientArtifact(String sumsText, String version) throws IOException {
            String jar = JvmClientInstall.jarName(version);
            if (sumHas(sumsText, jar)) return jar;
            throw new IOException("release SHA256SUMS has no " + jar
                    + " — this release ships no JVM client; refusing to install an unverifiable one");
        }

        static Fetched fetchAndMaterialize(Http http, URI base, String version, EngineInstall install, Cas cas)
                throws IOException, InterruptedException {
            URI dir = URI.create(base + "/" + version + "/");
            byte[] sums = get(http, dir.resolve("SHA256SUMS"), "release checksums");
            var verifier = ReleaseVerifier.current(GlobalConfig.releaseTrustedKeys());
            byte[] sig = get(http, dir.resolve("SHA256SUMS.sig"), "release signature");
            verifier.verify(sums, new String(sig, StandardCharsets.UTF_8));

            // Engine jar (platform-neutral) + the platform client. Prefer the .xz (every OS,
            // including Windows); Windows releases also ship a .zip for install.ps1 / jk.bat,
            // which have no system xz. The native CLI never inflates xz — the engine jar does.
            String jarName = "jk-engine-" + version + ".jar";
            byte[] jar = verified(get(http, dir.resolve(jarName), "engine jar"), sums, jarName);
            String clientName = pickClientArtifact(sums, version);
            byte[] clientArchive = verified(get(http, dir.resolve(clientName), "client binary"), sums, clientName);
            Path client = clientName.endsWith(".xz")
                    ? inflateXzViaEngine(jar, clientArchive)
                    : unzipSingleBinary(clientArchive);

            String jarSha = Hashing.sha256Hex(jar);
            cas.put(jar, jarSha);
            String clientSha = ingestClient(cas, client);
            EngineInstall.Materialized engine = install.materialize(version, cas, jarSha);
            return new Fetched(engine, clientSha);
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

        /**
         * {@code jk-<os>-<arch>-<version>.xz} when the sums list it, else the Windows {@code .zip}.
         * Unix releases do not ship a zip. The version is part of the name on purpose: the manifest
         * is signed but not bound to its directory, so a manifest copied from another release names
         * that release's artifacts and cannot satisfy a request for this one.
         */
        static String pickClientArtifact(byte[] sums, String version) throws IOException {
            String os = HostPlatform.currentOs().toLowerCase(Locale.ROOT);
            String arch = HostPlatform.currentArch();
            return pickClientArtifact(new String(sums, StandardCharsets.UTF_8), os, arch, version);
        }

        /** Visible for tests — pass HostPlatform vocabulary already lower-cased. */
        static String pickClientArtifact(String sumsText, String os, String arch, String version) throws IOException {
            String base = "jk-" + os + "-" + arch + "-" + version;
            String xz = base + ".xz";
            if (sumHas(sumsText, xz)) return xz;
            if ("windows".equals(os)) {
                String zip = base + ".zip";
                if (sumHas(sumsText, zip)) return zip;
            }
            throw new IOException("release SHA256SUMS has no " + xz
                    + ("windows".equals(os) ? " (or " + base + ".zip)" : "")
                    + " — refusing to install an unverifiable client binary");
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
                                xzTmp.toAbsolutePath().toString(),
                                out.toAbsolutePath().toString())
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

        private static byte[] verified(byte[] body, byte[] sums, String name) throws IOException {
            String expected = ReleaseVerifier.sha256For(sums, name);
            String actual = Hashing.sha256Hex(body);
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException(name + " checksum mismatch — expected " + expected + ", got " + actual);
            }
            return body;
        }

        private static byte[] get(Http http, URI uri, String what) throws IOException, InterruptedException {
            HttpResponse<byte[]> response = http.get(uri);
            if (response.statusCode() != 200) {
                throw new IOException(
                        "could not download the " + what + " from " + uri + " — HTTP " + response.statusCode());
            }
            return response.body();
        }

        static URI releasesBase() {
            String override = System.getenv("JK_RELEASES_URL");
            return URI.create(override == null || override.isBlank() ? "https://jumpkick.build/releases" : override);
        }
    }
}
