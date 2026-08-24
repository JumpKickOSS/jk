// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.GroupCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.util.JkDirs;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
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
 * {@code jk self} — self-update: install under {@code <data>/lib/jk-engine/} (parking the previous
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
        return List.of(new UpdateSub(), new MaterializeSub(), new SetupTerminalSub(), new SelfNukeCommand());
    }

    /**
     * {@code jk self setup-terminal} — persist root-level {@code nerd-font} in {@code
     * ~/.config/jk/config.toml}. Also invoked from install.sh.
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
            var detected = cc.jumpkick.config.NerdFontDetect.detect();
            if (in.isSet("explain")) {
                cc.jumpkick.cli.tui.CommandWedge.printOk("Self", explain(detected));
                return 0;
            }
            String raw = in.value("mode").orElse("auto");
            var mode = parseMode(raw);
            if (mode.isEmpty()) {
                cc.jumpkick.cli.tui.CommandWedge.printFail(
                        "Self", "unknown --mode " + raw + " (expected auto|on|off|wedge|pill)");
                return Exit.USAGE;
            }
            Path cfg = JkDirs.userConfigFile();
            cc.jumpkick.config.UserConfigEditor.setNerdFont(cfg, mode.get());
            String msg = "nerd-font = " + mode.get().toToml() + " → " + cfg;
            if (mode.get() == cc.jumpkick.config.NerdFontMode.AUTO) msg += "\n  " + explain(detected);
            cc.jumpkick.cli.tui.CommandWedge.printOk("Self", msg);
            return 0;
        }

        /**
         * Accepts the config spellings plus the friendlier {@code on}/{@code off} that a flag-style
         * CLI invites, which {@link cc.jumpkick.config.NerdFontMode#parse} already covers via the
         * jk-wide boolean truth set.
         */
        private static Optional<cc.jumpkick.config.NerdFontMode> parseMode(String raw) {
            return cc.jumpkick.config.NerdFontMode.parse(raw);
        }

        private static String explain(cc.jumpkick.config.NerdFontDetect.Result r) {
            var caps = r.caps();
            String granted =
                    !caps.any() ? "none" : caps.wedge() && caps.pill() ? "wedge+pill" : caps.wedge() ? "wedge" : "pill";
            return "detected " + granted + " — " + r.reason() + " [" + r.source() + "]";
        }
    }

    /**
     * {@code jk self materialize <client-bin> <engine-jar>} — hidden install-time seam: ingest a
     * local dist's engine jar into the CAS and install under {@code <data>/lib/jk-engine/}.
     * install.sh calls this through the freshly-installed client. The client-bin argument is the
     * PATH binary already written by the installer (not copied into the product lib).
     */
    static final class MaterializeSub implements CliCommand {

        @Override
        public String name() {
            return "materialize";
        }

        @Override
        public String description() {
            return "Install the engine jar under <data>/lib/jk-engine from local artifacts";
        }

        @Override
        public boolean hidden() {
            return true;
        }

        @Override
        public List<cc.jumpkick.model.command.Param> parameters() {
            return List.of(
                    cc.jumpkick.model.command.Param.of(
                            "client-bin", cc.jumpkick.model.command.Arity.ONE, "The jk client binary."),
                    cc.jumpkick.model.command.Param.of(
                            "engine-jar", cc.jumpkick.model.command.Arity.ONE, "The matching jk-engine jar."));
        }

        @Override
        public int run(Invocation in) throws Exception {
            Path engineJar = Path.of(in.positionals().get(1));
            if (!Files.isRegularFile(engineJar)) {
                cc.jumpkick.cli.tui.CommandWedge.printFail("Self", "engine jar not found: " + engineJar);
                return Exit.SOFTWARE;
            }
            EngineInstall install = EngineInstall.current();
            EngineInstall.Materialized m =
                    install.materializeFromFiles(cc.jumpkick.cli.Jk.VERSION, JkStores.cas(JkDirs.cache()), engineJar);
            EngineInstall.wipeAotDirectory(JkDirs.state().resolve("aot"), cc.jumpkick.cli.Jk.VERSION);
            install.gc();
            try {
                cc.jumpkick.config.UserConfigEditor.setNerdFont(
                        JkDirs.userConfigFile(), cc.jumpkick.config.NerdFontMode.AUTO);
            } catch (Exception ignored) {
                // Best-effort nerd-font seed; never fail materialize.
            }
            cc.jumpkick.cli.tui.CommandWedge.printOk("Self", "Materialized JumpKick " + m.version());
            return 0;
        }
    }

    static final class UpdateSub implements CliCommand {

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
        public List<cc.jumpkick.model.command.Param> parameters() {
            return List.of(cc.jumpkick.model.command.Param.of(
                    "version",
                    cc.jumpkick.model.command.Arity.ZERO_OR_ONE,
                    "Target version x.y.z. Default: the latest release."));
        }

        @Override
        public int run(Invocation in) throws Exception {
            URI base = releasesBase();
            String target = in.positionals().isEmpty() ? null : in.positionals().get(0);
            cc.jumpkick.http.Http http = new cc.jumpkick.http.Http();
            if (target == null) {
                target = new String(
                                get(http, URI.create(base + "/latest/VERSION"), "latest version pointer"),
                                StandardCharsets.UTF_8)
                        .trim();
            }
            if (target.isEmpty()) {
                cc.jumpkick.cli.tui.CommandWedge.printFail("Self", "could not resolve a target version");
                return Exit.SOFTWARE;
            }
            EngineInstall install = EngineInstall.current();
            Cas cas = JkStores.cas(JkDirs.cache());
            String running = cc.jumpkick.cli.Jk.VERSION;
            if (target.equals(running) && install.resolve(target).isPresent()) {
                cc.jumpkick.cli.tui.CommandWedge.printOk("Self", target + " is already current");
                return 0;
            }

            Fetched fetched = fetchAndMaterialize(http, base, target, install, cas);
            EngineInstall.installBinaries(cas.pathFor(fetched.clientSha()), JkDirs.binDir());
            cc.jumpkick.cli.tui.CommandWedge.printOk(
                    "Self", target + " installed (" + fetched.engine().engineJar() + ")");

            // Hand the engine over: --now stops the old daemon (killing its jobs) first;
            // otherwise the NEW engine's startup drains it gracefully — zero interrupted builds.
            var paths = cc.jumpkick.engine.EnginePaths.current();
            if (in.isSet("now")) {
                cc.jumpkick.cli.engine.EngineClient.forceStop(cc.jumpkick.engine.EnginePaths.activeSocket(paths));
            }
            Path newJk = pathClient(JkDirs.binDir());
            if (Files.isRegularFile(newJk)) {
                new ProcessBuilder(newJk.toString(), "engine", "start")
                        .inheritIO()
                        .start()
                        .waitFor();
                CliOutput.out("engine " + target + " is taking over"
                        + (in.isSet("now") ? "" : " (running builds finish on the old engine)"));
            }
            return 0;
        }

        record Fetched(EngineInstall.Materialized engine, String clientSha) {}

        static Path pathClient(Path binDir) {
            Path exe = binDir.resolve("jk.exe");
            if (Files.isRegularFile(exe)) return exe;
            return binDir.resolve("jk");
        }

        static Fetched fetchAndMaterialize(
                cc.jumpkick.http.Http http, URI base, String version, EngineInstall install, Cas cas)
                throws IOException, InterruptedException {
            URI dir = URI.create(base + "/" + version + "/");
            byte[] sums = get(http, dir.resolve("SHA256SUMS"), "release checksums");
            var verifier =
                    cc.jumpkick.repo.ReleaseVerifier.current(cc.jumpkick.config.GlobalConfig.releaseTrustedKeys());
            if (verifier.available()) {
                byte[] sig = get(http, dir.resolve("SHA256SUMS.sig"), "release signature");
                verifier.verify(sums, new String(sig, StandardCharsets.UTF_8));
            }

            // Engine jar (platform-neutral) + the platform client. Prefer the .xz (every OS,
            // including Windows); Windows releases also ship a .zip for install.ps1 / jk.bat,
            // which have no system xz. The native CLI never inflates xz — the engine jar does.
            String jarName = "jk-engine-" + version + ".jar";
            byte[] jar = verified(get(http, dir.resolve(jarName), "engine jar"), sums, jarName);
            String clientName = pickClientArtifact(sums);
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
         * {@code jk-<os>-<arch>.xz} when the sums list it, else the Windows {@code .zip}.
         * Unix releases do not ship a zip.
         */
        static String pickClientArtifact(byte[] sums) throws IOException {
            String os = cc.jumpkick.jdk.HostPlatform.currentOs().toLowerCase(Locale.ROOT);
            String arch = cc.jumpkick.jdk.HostPlatform.currentArch();
            return pickClientArtifact(new String(sums, StandardCharsets.UTF_8), os, arch);
        }

        /** Visible for tests — pass HostPlatform vocabulary already lower-cased. */
        static String pickClientArtifact(String sumsText, String os, String arch) throws IOException {
            String base = "jk-" + os + "-" + arch;
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

        private static boolean sumHas(String sumsText, String name) {
            for (String line : sumsText.split("\n")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2 && parts[1].equals(name)) return true;
            }
            return false;
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
                Path java = cc.jumpkick.cli.engine.EngineSpawn.engineJava();
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
            var current = EngineInstall.current().resolve(cc.jumpkick.cli.Jk.VERSION);
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
            String expected = null;
            for (String line : new String(sums, StandardCharsets.UTF_8).split("\n")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2 && parts[1].equals(name)) expected = parts[0];
            }
            if (expected == null) throw new IOException(name + " is not covered by the release's SHA256SUMS");
            String actual = Hashing.sha256Hex(body);
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException(name + " checksum mismatch — expected " + expected + ", got " + actual);
            }
            return body;
        }

        private static byte[] get(cc.jumpkick.http.Http http, URI uri, String what)
                throws IOException, InterruptedException {
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
