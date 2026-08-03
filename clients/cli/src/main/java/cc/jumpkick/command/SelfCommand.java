// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cache.VersionStore;
import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.GroupCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.util.Hashing;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * {@code jk self} — self-update: download a verified release into {@code ~/.local/share/jk/versions/<v>/},
 * flip {@code bin/jk}, start the new engine (graceful drain). {@code --now} stops the old engine
 * first.
 */
public final class SelfCommand extends GroupCommand {

    @Override
    public String name() {
        return "self";
    }

    @Override
    public String description() {
        return "Manage this jk installation (update, purge)";
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(new UpdateSub(), new MaterializeSub(), new SetupTerminalSub(), new SelfPurgeCommand());
    }

    /**
     * {@code jk self setup-terminal} — detect Nerd Font capability and persist {@code
     * [global].nerdfont} in {@code ~/.config/jk/config.toml}. Also invoked from install.sh.
     */
    static final class SetupTerminalSub implements CliCommand {

        @Override
        public String name() {
            return "setup-terminal";
        }

        @Override
        public String description() {
            return "Detect Nerd Fonts; write [global].nerdfont";
        }

        @Override
        public List<Opt> options() {
            return List.of(
                    Opt.flag("Force nerdfont = true in config.", "--nerd"),
                    Opt.flag("Force nerdfont = false in config.", "--no-nerd"));
        }

        @Override
        public int run(Invocation in) throws Exception {
            boolean nerd;
            String reason;
            if (in.isSet("nerd") && in.isSet("no-nerd")) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Self", "cannot combine --nerd and --no-nerd"));
                return Exit.USAGE;
            }
            if (in.isSet("nerd")) {
                nerd = true;
                reason = "--nerd";
            } else if (in.isSet("no-nerd")) {
                nerd = false;
                reason = "--no-nerd";
            } else {
                var det = cc.jumpkick.config.NerdFontDetect.detect();
                nerd = det.nerdFont();
                reason = det.reason();
            }
            Path cfg = JkDirs.userConfigFile();
            cc.jumpkick.config.UserConfigEditor.setNerdfont(cfg, nerd);
            // Invalidate any process-local config memo so subsequent calls see the write.
            String msg = "Nerd Font glyphs " + (nerd ? "enabled" : "disabled") + " (" + reason + ") → " + cfg;
            cc.jumpkick.cli.tui.CommandWedge.printOk("Self", msg);
            return 0;
        }
    }

    /**
     * {@code jk self materialize <client-bin> <engine-jar>} — hidden install-time seam: ingest a
     * local dist's artifacts into the CAS and materialize {@code versions/<running>/} through the
     * ONE Java materializer. install.sh calls this through the freshly-installed client instead
     * of hand-rolling the layout in shell — a shell copy that skipped the CAS left pruned
     * versions unrecoverable (VersionStore.prune re-materializes from CAS blobs).
     */
    static final class MaterializeSub implements CliCommand {

        @Override
        public String name() {
            return "materialize";
        }

        @Override
        public String description() {
            return "Materialize versions/<running> from local artifacts";
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
            Path client = Path.of(in.positionals().get(0));
            Path engineJar = Path.of(in.positionals().get(1));
            if (!Files.isRegularFile(engineJar)) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Self", "engine jar not found: " + engineJar));
                return Exit.SOFTWARE;
            }
            VersionStore.Materialized m = VersionStore.current()
                    .materializeFromFiles(cc.jumpkick.cli.Jk.VERSION, JkStores.cas(JkDirs.cache()), engineJar, client);
            Path distLib = distLibFor(client);
            if (distLib != null) {
                // Dev dogfood (JK-1412): the client is a Gradle start script whose classpath is
                // "$APP_HOME/../lib/*.jar". Alone in the store it cannot start — sync its dist
                // jars beside it and repoint PATH entrypoints as symlinks (a hardlinked script
                // resolves APP_HOME to the bin dir of the LINK and dies the same way).
                syncDistLibs(distLib, m.root().resolve("lib"));
                Path storeClient = m.clientBin().orElse(null);
                if (storeClient != null) {
                    repointDevScript(JkDirs.binDir().resolve("jk"), storeClient);
                    repointDevScript(JkDirs.binDir().resolve("jkx"), storeClient);
                }
            }
            CliOutput.out("materialized " + m.root());
            // Best-effort install-time terminal probe; never fail materialize.
            try {
                new SetupTerminalSub().run(Invocation.builder().build());
            } catch (Exception ignored) {
                // ignore
            }
            return 0;
        }

        /**
         * The installDist {@code lib/} sibling when {@code client} is a start script inside a
         * {@code bin/} + {@code lib/} dist tree, else {@code null} (native image client).
         */
        static Path distLibFor(Path client) {
            try {
                if (client == null || !Files.isRegularFile(client)) return null;
                Path bin = client.toAbsolutePath().normalize().getParent();
                if (bin == null || !"bin".equals(String.valueOf(bin.getFileName()))) return null;
                Path lib = bin.resolveSibling("lib");
                if (!Files.isDirectory(lib)) return null;
                byte[] head = new byte[2];
                try (var is = Files.newInputStream(client)) {
                    if (is.read(head) < 2) return null;
                }
                return (head[0] == '#' && head[1] == '!') ? lib : null;
            } catch (IOException e) {
                return null;
            }
        }

        /** Copy the dist client jars into the store version's lib (engine jar name never clashes). */
        static void syncDistLibs(Path distLib, Path storeLib) throws IOException {
            Files.createDirectories(storeLib);
            try (var jars = Files.newDirectoryStream(distLib, "*.jar")) {
                for (Path jar : jars) {
                    Files.copy(jar, storeLib.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        /**
         * Dev-only entrypoint flip: symlink so the start script resolves {@code APP_HOME} to the
         * store version dir (where its lib now lives). Release/native installs keep the
         * hard-link/copy policy in {@link UpdateSub}. Best-effort — a failed link leaves the
         * existing entrypoint alone.
         */
        private static void repointDevScript(Path pointer, Path storeClient) {
            try {
                Files.createDirectories(pointer.getParent());
                Path tmp = pointer.resolveSibling("." + pointer.getFileName() + "-new");
                Files.deleteIfExists(tmp);
                Files.createSymbolicLink(tmp, storeClient);
                Files.move(tmp, pointer, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | UnsupportedOperationException e) {
                CliOutput.out("note: could not repoint " + pointer + " (" + e.getMessage() + ")");
            }
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
            return List.of(
                    Opt.flag("Stop the engine immediately (no drain)", "--now"));
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
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Self", "could not resolve a target version"));
                return Exit.SOFTWARE;
            }
            VersionStore store = VersionStore.current();
            Cas cas = JkStores.cas(JkDirs.cache());
            String running = cc.jumpkick.cli.Jk.VERSION;
            if (target.equals(running) && store.resolve(target).isPresent()) {
                cc.jumpkick.cli.tui.CommandWedge.printOk("Self", target + " is already current");
                return 0;
            }

            VersionStore.Materialized m = store.resolve(target).orElse(null);
            if (m == null) {
                m = fetchAndMaterialize(http, base, target, store, cas);
            }

            flipPointer(m);
            cc.jumpkick.cli.tui.CommandWedge.printOk("Self", target + " installed (" + m.root() + ")");

            // Hand the engine over: --now stops the old daemon (killing its jobs) first;
            // otherwise the NEW engine's startup drains it gracefully — zero interrupted builds.
            var paths = cc.jumpkick.engine.EnginePaths.current();
            if (in.isSet("now")) {
                cc.jumpkick.cli.engine.EngineClient.forceStop(cc.jumpkick.engine.EnginePaths.activeSocket(paths));
            }
            Path newClient = m.clientBin().orElse(null);
            if (newClient != null) {
                new ProcessBuilder(newClient.toString(), "engine", "start")
                        .inheritIO()
                        .start()
                        .waitFor();
                CliOutput.out("engine " + target + " is taking over"
                        + (in.isSet("now") ? "" : " (running builds finish on the old engine)"));
            }
            return 0;
        }

        static VersionStore.Materialized fetchAndMaterialize(
                cc.jumpkick.http.Http http, URI base, String version, VersionStore store, Cas cas)
                throws IOException, InterruptedException {
            URI dir = URI.create(base + "/" + version + "/");
            byte[] sums = get(http, dir.resolve("SHA256SUMS"), "release checksums");
            var verifier =
                    cc.jumpkick.repo.ReleaseVerifier.current(cc.jumpkick.config.GlobalConfig.releaseTrustedKeys());
            if (verifier.available()) {
                byte[] sig = get(http, dir.resolve("SHA256SUMS.sig"), "release signature");
                verifier.verify(sums, new String(sig, StandardCharsets.UTF_8));
            }

            // Engine jar (platform-neutral) + the platform client (.zip — the one archive format
            // the JDK opens natively; releases.md ships it on every platform).
            String jarName = "jk-engine-" + version + ".jar";
            byte[] jar = verified(get(http, dir.resolve(jarName), "engine jar"), sums, jarName);
            String clientName = clientArtifactName(version);
            byte[] clientZip = verified(get(http, dir.resolve(clientName), "client binary"), sums, clientName);
            Path client = unzipSingleBinary(clientZip);

            String jarSha = Hashing.sha256Hex(jar);
            cas.put(jar, jarSha);
            String clientSha = Hashing.sha256Hex(client);
            cas.putFile(client, clientSha);
            return store.materialize(version, cas, jarSha, clientSha);
        }

        /** {@code jk-<os>-<arch>.zip} in HostPlatform's release vocabulary (releases.md). */
        private static String clientArtifactName(String version) {
            String os = cc.jumpkick.jdk.HostPlatform.currentOs().toLowerCase(java.util.Locale.ROOT);
            String arch = cc.jumpkick.jdk.HostPlatform.currentArch();
            String suffix = "windows".equals(os) ? ".exe.zip" : ".zip";
            return "jk-" + os + "-" + arch + suffix;
        }

        private static Path unzipSingleBinary(byte[] zip) throws IOException {
            Path tmp = Files.createTempFile("jk-self-", ".bin");
            try (var zin = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
                java.util.zip.ZipEntry e;
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

        /**
         * Flip PATH entrypoints under {@link JkDirs#binDir()} to the materialized client;
         * {@code jkx} follows. Prefer a hard link (zero disk; survives deletion of the
         * versions tree while the inode remains), then a real byte copy. Symlinks are not
         * used: they dangle when product data is wiped and defeat the "CLI outlives state"
         * layout. Stages at a temp sibling and renames into place.
         */
        private static void flipPointer(VersionStore.Materialized m) throws IOException {
            Path client = m.clientBin()
                    .orElseThrow(() -> new IOException("materialized " + m.version() + " has no client binary"));
            Path bin = JkDirs.binDir();
            Files.createDirectories(bin);
            repoint(bin.resolve("jk"), client);
            repoint(bin.resolve("jkx"), client);
        }

        private static void repoint(Path pointer, Path client) throws IOException {
            Path tmp = pointer.resolveSibling("." + pointer.getFileName() + "-new");
            Files.deleteIfExists(tmp);
            try {
                Files.createLink(tmp, client);
            } catch (IOException | UnsupportedOperationException noHardlink) {
                Files.copy(client, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, pointer, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException replaceDenied) {
                // Windows can rename a RUNNING exe but never delete/replace it (the image is
                // memory-mapped): step the live pointer aside, then slide the new one in. The
                // parked old image stays locked until that process exits — the next flip's
                // deleteIfExists reclaims it.
                Path old = pointer.resolveSibling("." + pointer.getFileName() + "-old");
                try {
                    Files.deleteIfExists(old);
                } catch (IOException stillRunning) {
                    // a previous update's parked image is still executing; park beside it
                    old = pointer.resolveSibling("." + pointer.getFileName() + "-old-" + System.nanoTime());
                }
                Files.move(pointer, old, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, pointer);
                try {
                    Files.deleteIfExists(old);
                } catch (IOException ignored) {
                    // locked while the old image runs; reclaimed on a later flip
                }
            }
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
