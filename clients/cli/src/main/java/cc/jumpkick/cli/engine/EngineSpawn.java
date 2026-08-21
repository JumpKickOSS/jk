// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoLifecycle;
import cc.jumpkick.jdk.GlobalDefaultJdk;
import cc.jumpkick.jdk.HostPlatform;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkEnsure;
import cc.jumpkick.jsonl.Jsonl;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spawn, takeover, and AOT-cache selection for the resident engine. Mode, artifact, and
 * {@code awaitStartup} stay together so a torn AOT cache cannot be mapped without the self-heal
 * retry. Over the 800-line house cap by the AOT key/manifest comments.
 */
public final class EngineSpawn {

    /**
     * Ceiling for a normal (mapped-cache or no-cache) spawn to come up. A mapped-cache start is
     * sub-second; the pathological case is a <em>cold</em> boot (AOT ignored/disabled).
     */
    private static final Duration COLD_START_CEILING = Duration.ofSeconds(30);

    /**
     * After force-stop / hard-kill, wait this long for the OS process to exit before escalating.
     */
    private static final Duration STOP_DEATH_WAIT = Duration.ofMillis(1_500);

    private EngineSpawn() {}

    private static boolean buildIdCurrent(EngineClient.Handshake hs, String clientVersion) {
        if (hs.buildId().isEmpty()) return true;
        String expected = cc.jumpkick.cache.EngineInstall.current()
                .engineSha(clientVersion)
                .orElse("");
        if (expected.isEmpty()) return true;
        return expected.startsWith(hs.buildId());
    }

    static EngineClient.Handshake ensure(EnginePaths.Paths paths, String clientVersion) throws IOException {
        Path socket = EnginePaths.activeSocket(paths);
        Reachability reach = probe(socket, clientVersion);
        if (reach instanceof Reachability.Live live) {
            EngineClient.Handshake hs = live.handshake();
            // A draining engine has unbound its listener; this branch is the race before unbind.
            // Do not spawn a third copy on top of the successor that is already taking over.
            if (hs.draining()) {
                throw new IOException(
                        "the build engine is shutting down — wait for it to stop, or run `jk engine stop --force`");
            }
            if (clientVersion.equals(hs.version()) && buildIdCurrent(hs, clientVersion)) {
                // Already primary — do not wipe AOT (would thrash the live train). Wipe only on
                // materialize / new endpoint claim.
                return hs;
            }
            // Version skew (incl. same -SNAPSHOT with different content identity) → TAKEOVER, not
            // a kill: spawn this client's engine; its startup atomically repoints the endpoint and
            // drains the displaced engine — in-flight jobs finish untouched.
        } else if (reach instanceof Reachability.Silent silent) {
            // Accepts connections but never replies. Displace so startWithSelfHeal
            // can bind — do not wait for the 60m stream idle on the next build.
            long pid = silent.pidHint() > 0 ? silent.pidHint() : EngineClient.readPidForSocket(socket);
            logReason(
                    paths,
                    "displacing unresponsive engine"
                            + (pid > 0 ? " (pid " + pid + ")" : "")
                            + " — handshake timed out");
            if (pid > 0) EngineClient.hardKill(pid);
            else EngineClient.forceStop(socket); // best-effort; may still be false
            EngineClient.waitForDeathOrKill(pid, STOP_DEATH_WAIT);
        }
        // Absent / unusable / version skew → spawn (takeover or cold start).
        return startWithSelfHeal(paths, clientVersion);
    }

    /**
     * Outcome of a one-shot ensure probe: live handshake, nothing listening, silent peer (connect
     * works, no reply within {@link EngineWire#SOCKET_TIMEOUT_MILLIS}), or connected-but-not-usable (e.g.
     * newer protocol).
     */
    private sealed interface Reachability {
        record Live(EngineClient.Handshake handshake) implements Reachability {}

        record Absent() implements Reachability {}

        /** Socket accepted the connection but never completed handshake. */
        record Silent(long pidHint) implements Reachability {}

        /** Reached something that is not a usable same-generation engine. */
        record Unusable() implements Reachability {}
    }

    /**
     * Probe liveness beyond "socket exists": connect + hello with the short exchange watchdog.
     * Distinguishes a wedged peer (silent) from a missing engine so ensure can hard-kill once.
     */
    private static Reachability probe(Path socket, String clientVersion) {
        SocketChannel ch;
        try {
            ch = EngineWire.connect(socket);
        } catch (IOException e) {
            return new Reachability.Absent();
        }
        try (ch) {
            String ack = EngineWire.exchange(ch, ProtoLifecycle.hello(clientVersion));
            if (!EngineProtocol.HELLO_ACK.equals(EngineProtocol.typeOf(ack))) {
                return new Reachability.Unusable();
            }
            if (Jsonl.intValue(ack, "proto", EngineProtocol.PROTOCOL) > EngineProtocol.PROTOCOL) {
                return new Reachability.Unusable();
            }
            String ackBuildId = Jsonl.str(ack, "buildId");
            return new Reachability.Live(new EngineClient.Handshake(
                    Jsonl.str(ack, "version"),
                    Jsonl.longValue(ack, "pid", -1),
                    Jsonl.longValue(ack, "startedAt", -1),
                    Jsonl.bool(ack, "draining", false),
                    ackBuildId == null ? "" : ackBuildId));
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("did not reply")
                    || msg.contains("closed the connection without replying")
                    || msg.contains("no protocol traffic")) {
                return new Reachability.Silent(EngineClient.readPidForSocket(socket));
            }
            // Connect worked but mid-exchange failure (reset, etc.) — treat as unusable and let
            // spawn/takeover decide; avoid hard-killing a healthy peer on a flaky read.
            return new Reachability.Unusable();
        }
    }

    /**
     * Bring up a fresh engine with AOT self-heal: TRAIN/USE/NONE, drop a bad cache and retry once,
     * and wait out slow cold starts rather than reporting "could not start".
     */
    private static EngineClient.Handshake startWithSelfHeal(EnginePaths.Paths paths, String clientVersion)
            throws IOException {
        return startOnce(paths, clientVersion, resolveEngineTarget(paths, clientVersion));
    }

    /**
     * Spawn and wait until serving; re-picks AOT mode per attempt and retries once on early exit.
     */
    private static EngineClient.Handshake startOnce(EnginePaths.Paths paths, String clientVersion, EngineTarget target)
            throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            AotMode mode = chooseAotMode(target);
            StartResult r = awaitStartup(
                    paths,
                    clientVersion,
                    COLD_START_CEILING,
                    spawn(paths, target, mode).process());
            switch (r.outcome()) {
                case UP -> {
                    if (mode == AotMode.USE && scanLogForAotError(paths.log())) {
                        deleteQuietly(target.aotCache());
                        writeNoAotMarker(target.aotCache());
                        logReason(paths, "AOT cache was ignored by the engine JVM; skipping it for this key");
                    }
                    // EngineServer wipes state/aot after claiming the endpoint. Do not
                    // wipe again here — the sidecar may already be training into a fresh file.
                    return r.handshake();
                }
                case TIMED_OUT -> throw notStarted(paths); // alive but never served → genuine hang
                case CHILD_EXITED -> {
                    if (attempt == 0) {
                        logReason(paths, "engine exited before serving; retrying after backoff");
                        sleepQuietly(1_500);
                        continue;
                    }
                    throw notStarted(paths);
                }
            }
        }
        throw notStarted(paths); // unreachable
    }

    private static IOException notStarted(EnginePaths.Paths paths) {
        return new IOException("could not start the build engine — see " + paths.log() + " for details");
    }

    /** How a spawn should treat the AOT cache. */
    enum AotMode {
        TRAIN,
        USE,
        NONE
    }

    /** What {@link #spawn} launched: the child (same pid — it setsid()s, never forks). */
    private record Spawned(Process process) {}

    /**
     * The resolved engine to spawn: which artifact, the host JDK (JAR only), whether that JDK is a
     * HotSpot/C2 JVM (AOT is only stable there), the AOT cache path, and whether a {@code.noaot}
     * marker already says AOT can't apply for this key.
     */
    record EngineTarget(EngineArtifact engine, Path javaHome, boolean hotspot, Path aotCache, boolean noAotMarker) {}

    /** A host JDK for the engine: home, vendor, and version (from its {@code release} file). */
    record EngineJdk(Path home, cc.jumpkick.jdk.JdkVendor vendor, String version) {}

    /** Resolve everything the spawn/mode decision needs, self-healing a missing/skewed engine jar. */
    private static EngineTarget resolveEngineTarget(EnginePaths.Paths paths, String clientVersion) throws IOException {
        // Engine spawn is java -cp lib/jk-engine/<jar> EngineMain (or JK_ENGINE_EXE). The client binary
        // path is only needed for cache-prune re-invocation elsewhere — not for the daemon spawn.
        Optional<EngineArtifact> resolved = resolveEngineArtifact(System.getenv("JK_ENGINE_EXE"), clientVersion);
        // Self-heal a missing jar: the slim client never hosts the engine; download when allowed.
        if (resolved.isEmpty()
                && EngineJarFetcher.applicable(
                        clientVersion,
                        isNativeImage(),
                        cc.jumpkick.config.SessionContext.current().offline())) {
            System.err.println("jk: downloading the build engine (jk-engine-" + clientVersion + ".jar) ...");
            EngineJarFetcher.fetch(EngineJarFetcher.releasesBase(), clientVersion);
            resolved = resolveEngineArtifact(System.getenv("JK_ENGINE_EXE"), clientVersion);
        }
        EngineArtifact engine = resolved.orElseThrow(() -> new IOException("no build engine for jk " + clientVersion
                + " — materialize it (`./install.sh build/dist/jk` or `jk self materialize …`),"
                + " download a release (`jk self update`), or set JK_ENGINE_EXE"));
        if (engine.kind() != EngineArtifact.Kind.JAR) {
            return new EngineTarget(engine, null, false, null, false);
        }
        EngineJdk jdk = resolveEngineJdk();
        Path aot = aotCachePath(paths, Path.of(engine.path()), jdk);
        boolean marker = Files.exists(noAotMarkerPath(aot));
        return new EngineTarget(engine, jdk.home(), isHotSpot(jdk.vendor()), aot, marker);
    }

    /**
     * AOT mode for a target: only a JAR engine on a HotSpot JDK with no {@code.noaot} marker uses
     * AOT. Train-on-miss is skipped when {@link cc.jumpkick.util.AotSettings#trainingEnabled} is
     * false ({@code JK_AOT_TRAIN=off}) — still maps an existing cache. USE requires a
     * <em>non-empty</em> cache (mirror of {@code PluginAot.usableCache}): a zero-byte leftover from
     * a crashed trainer would otherwise map "forever" while never accelerating anything — it is
     * deleted here so the key can retrain.
     */
    static AotMode chooseAotMode(EngineTarget t) {
        if (t.engine().kind() != EngineArtifact.Kind.JAR) return AotMode.NONE;
        if (!t.hotspot()) return AotMode.NONE; // GraalVM host: its Graal JIT breaks the cache — skip cleanly
        if (t.noAotMarker()) return AotMode.NONE;
        if (usableAotCache(t.aotCache())) return AotMode.USE;
        deleteIfEmptyCache(t.aotCache()); // torn/zero-byte leftover: treat as missing so it retrains
        if (!cc.jumpkick.util.AotSettings.trainingEnabled()) return AotMode.NONE;
        return AotMode.TRAIN;
    }

    /** The one definition of "engine cache present": a non-empty regular file. */
    private static boolean usableAotCache(Path cache) {
        try {
            return cache != null && Files.isRegularFile(cache) && Files.size(cache) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteIfEmptyCache(Path cache) {
        try {
            if (cache != null && Files.isRegularFile(cache) && Files.size(cache) == 0) {
                Files.deleteIfExists(cache);
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /**
     * The JDK that hosts the engine JVM, pinned by vendor+major so the AOT cache is stable. Honours
     * {@code [toolchain].jdk} (or {@code JK_ENGINE_JDK}); defaults to the LTS Temurin at the engine's
     * floor release. Prefers an already-installed match (no network), else installs exactly the pin.
     * A HotSpot JDK is what {@code docs/architecture.md} wants (HotSpot's JIT + SHA-256 intrinsics) and is
     * required for a mappable AOT cache; a Graal pin is honoured but disables AOT (see {@link
     * #chooseAotMode}).
     */
    /**
     * {@code java} of the JDK that hosts the engine (installs the pin if none matches). Used by
     * one-shot engine roles such as {@code --inflate-xz} that must not start the daemon.
     */
    public static Path engineJava() throws IOException {
        Path home = resolveEngineJdk().home();
        return home.resolve("bin").resolve(HostPlatform.isWindows() ? "java.exe" : "java");
    }

    private static EngineJdk resolveEngineJdk() throws IOException {
        int floor = Runtime.version().feature();
        String pin = cc.jumpkick.config.GlobalConfig.engineJdkPin().orElse("temurin-" + floor);
        Optional<EngineJdk> installed = findInstalledEngineJdk(pin);
        if (installed.isPresent()) return installed.get();
        System.err.println("jk: installing the build engine's JDK (" + pin + ") ...");
        try {
            Path home = JdkEnsure.install(pin, System.err::println).home();
            return probeEngineJdk(home)
                    .orElseThrow(() -> new IOException("engine JDK installed at " + home + " is unreadable"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted installing the engine JDK " + pin, e);
        }
    }

    /** First already-installed JDK matching the pin's vendor+major, checked without any network. */
    private static Optional<EngineJdk> findInstalledEngineJdk(String pin) {
        Optional<Pin> want = parsePin(pin);
        if (want.isEmpty()) return Optional.empty(); // unparseable pin → force the install path
        List<Path> homes = new ArrayList<>();
        GlobalDefaultJdk defaults = GlobalDefaultJdk.current();
        defaults.currentHome().ifPresent(homes::add);
        defaults.defaultHome().ifPresent(homes::add);
        try {
            homes.add(JavaHomes.runningJavaHome());
        } catch (RuntimeException ignored) {
            // No running JVM home (native client) — the registry scan below still covers installs.
        }
        try {
            for (cc.jumpkick.jdk.JdkHit hit : new cc.jumpkick.jdk.JdkRegistry().listHits()) homes.add(hit.home());
        } catch (RuntimeException ignored) {
            // Registry probe failure is non-fatal — fall through to install.
        }
        for (Path home : homes) {
            Optional<EngineJdk> ej = probeEngineJdk(home);
            if (ej.isPresent()
                    && ej.get().vendor() == want.get().vendor()
                    && majorOf(ej.get().version()) == want.get().major()) {
                return ej;
            }
        }
        return Optional.empty();
    }

    private static Optional<EngineJdk> probeEngineJdk(Path home) {
        return cc.jumpkick.discovery.ProbeSupport.discoverJdk(home, "engine-host")
                .map(h -> new EngineJdk(h.home(), h.vendor(), h.version()));
    }

    /** A parsed engine-JDK pin, e.g. {@code "temurin-25"} → (TEMURIN, 25). */
    private record Pin(cc.jumpkick.jdk.JdkVendor vendor, int major) {}

    private static Optional<Pin> parsePin(String spec) {
        int dash = spec.lastIndexOf('-');
        if (dash <= 0 || dash == spec.length() - 1) return Optional.empty();
        int major;
        try {
            major = Integer.parseInt(spec.substring(dash + 1).split("\\.")[0]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        cc.jumpkick.jdk.JdkVendor vendor = vendorFromToken(spec.substring(0, dash));
        return vendor == cc.jumpkick.jdk.JdkVendor.UNKNOWN ? Optional.empty() : Optional.of(new Pin(vendor, major));
    }

    /** Map a spec vendor token (a {@code jbPrefix} like {@code "temurin"}/{@code "graalvm"}) to a vendor. */
    private static cc.jumpkick.jdk.JdkVendor vendorFromToken(String token) {
        for (cc.jumpkick.jdk.JdkVendor v : cc.jumpkick.jdk.JdkVendor.values()) {
            if (v.jbPrefix().map(p -> p.equalsIgnoreCase(token)).orElse(false)) return v;
        }
        return cc.jumpkick.jdk.JdkVendor.UNKNOWN;
    }

    private static int majorOf(String version) {
        int dot = version.indexOf('.');
        try {
            return Integer.parseInt(dot < 0 ? version : version.substring(0, dot));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** HotSpot/C2 JVMs (everything except GraalVM) produce a stable, mappable AOT cache. */
    private static boolean isHotSpot(cc.jumpkick.jdk.JdkVendor vendor) {
        return vendor != cc.jumpkick.jdk.JdkVendor.ORACLE_GRAALVM && vendor != cc.jumpkick.jdk.JdkVendor.GRAALVM_CE;
    }

    /**
     * Which engine artifact a spawn chose. {@code EXE}: {@code path} is an executable whose {@code
     * main} IS the engine loop. {@code JAR}: {@code path} is the engine's fat jar under {@code
     * $JK_HOME/lib/jk-engine/} (or {@code <data>/lib/jk-engine/}), launched as {@code
     * <managed-jdk>/bin/java … -cp <path> cc.jumpkick.engine.EngineMain} — the engine is a plain JVM
     * app, never a native image. There is no client-binary FALLBACK: the slim client never hosts the
     * engine.
     */
    record EngineArtifact(Kind kind, String path, String how) {
        enum Kind {
            EXE,
            JAR
        }
    }

    /**
     * Engine artifact resolution: (a) {@code JK_ENGINE_EXE}; (b) the product-lib jar paired with
     * this client version (live under {@code lib/jk-engine/}, or a parked {@code *.jar.old} during
     * drain). Empty when neither is available (caller may download / materialize, then retry).
     */
    static Optional<EngineArtifact> resolveEngineArtifact(String envOverride, String version) {
        return resolveEngineArtifact(envOverride, version, cc.jumpkick.cache.EngineInstall.current());
    }

    /** Root-injected variant — the testable seam. */
    static Optional<EngineArtifact> resolveEngineArtifact(
            String envOverride, String version, cc.jumpkick.cache.EngineInstall install) {
        if (envOverride != null && !envOverride.isBlank()) {
            return Optional.of(new EngineArtifact(EngineArtifact.Kind.EXE, envOverride, "JK_ENGINE_EXE"));
        }
        var materialized = install.resolve(version);
        if (materialized.isPresent()) {
            return Optional.of(new EngineArtifact(
                    EngineArtifact.Kind.JAR, materialized.get().engineJar().toString(), "lib"));
        }
        return Optional.empty();
    }

    /**
     * The engine's AOT cache path, keyed to the engine jar (name:size:mtime) <em>and</em> the host
     * JDK identity (version + vendor). A mismatched cache is silently ignored by {@code
     * AOTMode=auto} and never retrained, so folding the JDK into the key means a jar upgrade, a JDK
     * build bump (Temurin 25.0.3→25.0.4), or a vendor swap all yield a fresh key that trains cleanly.
     * Stale {@code.aot}/{@code.noaot} files from previous keys are deleted best-effort here.
     */
    static Path aotCachePath(EnginePaths.Paths paths, Path engineJar, EngineJdk jdk) {
        return aotCachePath(paths, engineJar, jdk, cc.jumpkick.cli.Jk.VERSION);
    }

    /** As above, version-scoped under {@code state/engine/<v>/} so engines never share AOT state. */
    static Path aotCachePath(EnginePaths.Paths paths, Path engineJar, EngineJdk jdk, String version) {
        StringBuilder signature = new StringBuilder();
        try {
            signature
                    .append(engineJar.getFileName())
                    .append(':')
                    .append(Files.size(engineJar))
                    .append(':')
                    .append(Files.getLastModifiedTime(engineJar).toMillis());
        } catch (IOException e) {
            // Key on the PATH too: two different unreadable jars must not share one AOT key.
            signature.append("unreadable-jar:").append(engineJar.toAbsolutePath());
        }
        signature
                .append(':')
                .append(
                        jdk == null
                                ? "no-jdk"
                                : jdk.version() + "|" + jdk.vendor().name());
        String hash = cc.jumpkick.util.Hashing.sha256Hex(signature.toString()).substring(0, 16);
        // ONE home for every AOT cache — engine and workers alike live in ~/.local/state/jk/aot/ so a
        // user (or `jk engine aot`) finds them all side by side. The engine's file
        // carries its jk version ("engine-<version>-<key>.aot") because its LIFETIME is
        // version-scoped: a new primary reaps other versions' engine AOT, and
        // EngineInstall.gc also retires leftover version trees. The sweep below stays
        // within one version so side-by-side keys for the same version never thrash each other.
        // Worker caches (kotlinc-/java-compiler-) have no version dimension.
        Path aotDir = cc.jumpkick.util.JkDirs.state().resolve("aot");
        try {
            Files.createDirectories(aotDir);
        } catch (IOException ignored) {
            // Falls through — a failed mkdir surfaces on the training write, with a real error.
        }
        String stem = "engine-" + version + "-" + hash;
        Path cache = aotDir.resolve(stem + ".aot");
        // Sweep THIS version's other keys — the cache, the JEP 514 ".aot.config" recording
        // intermediate, and any ".noaot" marker. The "<16-hex>." shape check keeps a version
        // whose name extends ours ("0.10.0" vs "0.10.1") out of the blast radius.
        String versionPrefix = "engine-" + version + "-";
        List<String> swept = new ArrayList<>();
        try (var entries = Files.newDirectoryStream(aotDir, "engine-*")) {
            for (Path p : entries) {
                String name = p.getFileName().toString();
                if (name.startsWith(versionPrefix)
                        && !name.startsWith(stem)
                        && name.substring(versionPrefix.length()).matches("[0-9a-f]{16}\\..*")) {
                    // Map sidecar names back to the primary .aot file key for aot.toml.
                    if (name.endsWith(".aot")) swept.add(name);
                    else if (name.endsWith(".noaot") && name.length() > ".noaot".length()) {
                        String primary = name.substring(0, name.length() - ".noaot".length()) + ".aot";
                        swept.add(primary);
                    }
                    Files.deleteIfExists(p);
                }
            }
        } catch (IOException ignored) {
            // Cleanup is opportunistic; a leftover cache costs disk, not correctness.
        }
        if (!swept.isEmpty()) {
            cc.jumpkick.util.AotManifest.remove(aotDir, swept);
            cc.jumpkick.util.AotManifest.reconcile(aotDir);
        }
        recordEngineAotManifest(cache, engineJar, jdk, version, hash);
        // Drop leftover per-version cache under engine-state so it is not confused with the
        // current content-addressed AOT key.
        deleteRecursivelyQuietly(paths.dir().resolve(version));
        return cache;
    }

    /**
     * Best-effort {@code aot.toml} row for the engine cache key (even before the file exists, so a
     * pending train is still documented). Updates size/status when the cache or {@code .noaot}
     * marker is present. {@code ready} means size &gt; 0 — the same predicate {@link
     * #chooseAotMode} maps by, so the manifest and the engine never disagree about one file.
     */
    static void recordEngineAotManifest(Path cache, Path engineJar, EngineJdk jdk, String version, String hash) {
        if (cache == null) return;
        Path aotDir = cache.getParent();
        if (aotDir == null) return;
        try {
            String name = cache.getFileName().toString();
            boolean ready = Files.isRegularFile(cache) && Files.size(cache) > 0;
            boolean noaot = Files.exists(noAotMarkerPath(cache));
            String status = ready ? "ready" : (noaot ? "noaot" : "pending");
            var b = cc.jumpkick.util.AotManifest.Entry.builder(name)
                    .tool("engine")
                    .key(hash)
                    .jkVersion(version)
                    .status(status)
                    .jvmFlags(List.of(
                            "-XX:+UseSerialGC",
                            "-XX:MinHeapFreeRatio=10",
                            "-XX:MaxHeapFreeRatio=25",
                            "-XX:-ShrinkHeapInSteps",
                            "--enable-native-access=ALL-UNNAMED"));
            if (ready) {
                b.sizeBytes(Files.size(cache)).lastUsed(cc.jumpkick.util.AotManifest.nowIso());
            }
            if (jdk != null) {
                b.jdkHome(jdk.home().toString())
                        .jdkVendor(jdk.vendor().name())
                        .jdkVersion(jdk.version())
                        .gc("serial");
            }
            if (engineJar != null) {
                b.engineJar(engineJar.getFileName().toString());
                try {
                    b.engineJarSize(Files.size(engineJar))
                            .engineJarMtimeMs(
                                    Files.getLastModifiedTime(engineJar).toMillis());
                } catch (IOException ignored) {
                    // identity without size/mtime still documents the name
                }
            }
            cc.jumpkick.util.AotManifest.upsert(aotDir, b.build());
        } catch (Exception ignored) {
            // never fail engine start for a human index
        }
    }

    private static void deleteRecursivelyQuietly(Path root) {
        if (!Files.isDirectory(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(EngineSpawn::deleteQuietly);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /** The sibling "this key can't AOT here" marker for an {@code engine-<version>-<key>.aot} path. */
    private static Path noAotMarkerPath(Path aotCache) {
        String name = aotCache.getFileName().toString();
        return aotCache.resolveSibling(name.substring(0, name.length() - ".aot".length()) + ".noaot");
    }

    /** Spawn a fresh engine, detached — mirrors {@link CachePruneScheduler}'s spawn-and-forget pattern. */
    private static Spawned spawn(EnginePaths.Paths paths, EngineTarget target, AotMode mode) throws IOException {
        EngineArtifact engine = target.engine();
        JkEngineConfig config = JkEngineConfig.resolve();
        Files.createDirectories(paths.dir());
        rotateLog(paths.log());
        List<String> command = new ArrayList<>();
        // The child detaches ITSELF into its own session (setsid(2) via PosixDetach, first thing
        // in the engine role) — without that it stays in THIS client's process group, and a
        // Ctrl-C/SIGTERM aimed at the client (or its whole group) would take down the engine and
        // every other build it is hosting.
        // Sizing the engine's heap (docs/architecture.md "Memory target") happens on the spawn line
        // the spawner is the only place that can, since a process can't shrink its own -Xmx, and
        // the -Xms pre-sizing matters for a long-lived process (no growth churn). How the numbers
        // ride along differs per artifact form below; user config max-heap-mb stays authoritative
        // everywhere.
        switch (engine.kind()) {
            case JAR -> {
                // The installed engine: a plain JVM app on the jk-managed JDK, one fat jar on the
                // classpath. Tuning is ordinary JVM flags — SerialGC (lowest footprint/latency; a
                // ≤256 MiB heap is well inside its comfort zone) plus the JkEngineConfig heap
                // numbers. The long-lived engine is exactly what HotSpot's JIT and SHA-256
                // intrinsics want; there is no native engine image. --enable-native-access:
                // PosixDetach's setsid(2) FFM downcall without the JDK's restricted-method
                // warning.
                command.add(target.javaHome()
                        .resolve("bin")
                        .resolve(HostPlatform.isWindows() ? "java.exe" : "java")
                        .toString());
                command.add("-XX:+UseSerialGC");
                // Heap-return ergonomics: SerialGC's defaults (MaxHeapFreeRatio=70,
                // ShrinkHeapInSteps) keep committed ≈ 3.3× live and shrink one slice per full GC —
                // an idle coordinator that GCs once at the build boundary never gives memory back.
                // Tight free ratios + whole-step shrink make that single idle GC snap committed to
                // ~live. Metaspace/stack mirror what workers already get from JvmOptions.
                command.add("-XX:MinHeapFreeRatio=10");
                command.add("-XX:MaxHeapFreeRatio=25");
                command.add("-XX:-ShrinkHeapInSteps");
                command.add("-XX:MaxMetaspaceSize=256m");
                command.add("-Xss512k");
                // AOT cache (JEP 514, JDK 25+): pre-parsed class metadata and AOT-compiled code.
                // USE maps an existing cache. TRAIN boots cold and spawns a sidecar trainer
                // (`EngineMain --aot-training`, isolated temp state, throwaway socket). NONE
                // omits the cache (non-HotSpot host JDK, or a key that already proved unmappable).
                // The cache is keyed to jar + host-JDK identity so an upgrade/JDK-swap retrains.
                switch (mode) {
                    case TRAIN -> command.add("-Djk.aot.train.output=" + target.aotCache());
                    case USE -> command.add("-XX:AOTCache=" + target.aotCache());
                    case NONE -> {
                        /* no AOT flag — a guaranteed cold-but-correct boot */
                    }
                }
                if (config.heapCapped()) {
                    command.add("-Xms" + config.minHeapMb() + "m");
                    command.add("-Xmx" + config.maxHeapMb() + "m");
                }
                // Forward plugin-jar location overrides (e.g. -Djk.test.runner.jar=… from Gradle
                // tests) into the engine JVM — PluginJar.locate reads System.getProperty there.
                // Also forward AOT switches so nested engines honor JK_AOT_TRAIN / jk.aot.train,
                // and jk.env.* layout overlays (JkDirs test seam) so a spawned engine resolves the
                // same store/state the client did.
                for (var e : System.getProperties().entrySet()) {
                    String key = String.valueOf(e.getKey());
                    if (!key.startsWith("jk.")) continue;
                    boolean jarOverride = key.endsWith(".jar");
                    boolean aotSwitch = key.equals("jk.aot.train") || key.equals("jk.worker.aot");
                    boolean envOverlay = key.startsWith("jk.env.");
                    if (!jarOverride && !aotSwitch && !envOverlay) continue;
                    String val = String.valueOf(e.getValue());
                    if (val == null || val.isBlank()) continue;
                    command.add("-D" + key + "=" + val);
                }
                command.add("--enable-native-access=ALL-UNNAMED");
                command.add("-cp");
                command.add(engine.path());
                command.add("cc.jumpkick.engine.EngineMain");
            }
            case EXE -> {
                // A dedicated engine executable (JK_ENGINE_EXE): its main IS the engine loop, no
                // flag. The -Xm* args land as argv; EngineMain ignores argv, so a wrapper that
                // doesn't consume them degrades to an unsized engine, never a dead one.
                command.add(engine.path());
                if (config.heapCapped()) {
                    command.add("-Xms" + config.minHeapMb() + "m");
                    command.add("-Xmx" + config.maxHeapMb() + "m");
                }
                // Same heap-return ergonomics as the JAR spawn; like -Xm* above these
                // land as argv for the wrapper to consume, and an ignoring wrapper stays alive.
                command.add("-XX:MinHeapFreeRatio=10");
                command.add("-XX:MaxHeapFreeRatio=25");
                command.add("-XX:-ShrinkHeapInSteps");
            }
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        // forward resolve budgets into the engine process. PubGrubSolver reads these from
        // its own env; client-only exports were previously ignored for resident engines.
        forwardResolveEnv(pb.environment());
        // Anchor the detached daemon's working directory to its own state dir (created just above),
        // never the spawning client's CWD. A resident engine outlives the shell that started it, and
        // if it inherited an ephemeral CWD (a /tmp scratch dir, a git worktree, a since-deleted
        // checkout) every subprocess it later forks — javac, workers — inherits that dead CWD and
        // dies at JVM init with "Could not determine current working directory". The state dir is
        // stable for the engine's whole life and is never removed by cache maintenance.
        pb.directory(paths.dir().toFile());
        // Merge stderr into stdout inside the child (one fd, no interleaving risk from two
        // independently-opened streams onto the same file), then route that to the log — a fresh
        // file every start, per docs/architecture.md. The spawner writes the log's first line itself
        // (which artifact it chose — the one fact the engine can't know), then the child appends;
        // if that header can't be written, fall back to plain truncate-and-redirect.
        pb.redirectErrorStream(true);
        pb.redirectOutput(
                writeSpawnHeader(paths.log(), engine)
                        ? ProcessBuilder.Redirect.appendTo(paths.log().toFile())
                        : ProcessBuilder.Redirect.to(paths.log().toFile()));
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        Process p = pb.start();
        p.getOutputStream().close(); // EOF immediately; the engine doesn't read stdin
        return new Spawned(p);
    }

    /** Copy PubGrub budget env vars from this process into the engine spawn environment. */
    /**
     * Hand the spawned engine the environment it cannot otherwise see.
     *
     * <p>A daemon does not inherit the client's environment, so anything set only in the caller's shell
     * is invisible to it. That is why {@code JK_STORE_DIR} did nothing beforethe engine
     * resolved its own {@code ~/.local/share/jk/store} regardless. Paired with the store being part of the engine
     * identity ({@link cc.jumpkick.engine.EnginePaths}), a different store now both spawns its own
     * engine and reaches it.
     */
    private static void forwardResolveEnv(Map<String, String> env) {
        for (String key : List.of(
                "JK_RESOLVE_TIMEOUT_MS",
                "JK_RESOLVE_MAX_DECISIONS",
                "JK_STORE_DIR",
                "JK_CACHE_DIR",
                "JK_M2_LOCAL",
                "JK_M2_LOOKUP",
                "JK_M2_LINK",
                "JK_CENTRAL_MIRROR")) {
            String v = System.getenv(key);
            if (v != null && !v.isBlank()) env.put(key, v);
        }
    }

    /**
     * Start the fresh log with the spawn decision, truncating whatever {@link #rotateLog} left
     * behind (it's best-effort). {@code false} — and no header — if the file isn't writable; the
     * caller then falls back to the truncating redirect so log semantics stay identical.
     */
    private static boolean writeSpawnHeader(Path log, EngineArtifact engine) {
        try {
            Files.writeString(
                    log,
                    "jk engine: spawning " + engine.path() + " (" + engine.how() + ")" + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** True when this client runs as a GraalVM native image (so the spawned engine will too). */
    private static boolean isNativeImage() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    }

    /**
     * Keep exactly one historical log ({@code <key>.log} → {@code <key>.log.1}) before each fresh
     * engine start truncates {@code <key>.log}. Without this, a crash followed by the next lazy
     * respawn (which happens automatically, often before anyone looks) would silently destroy the
     * crashed engine's own log — the one file {@link EngineClient#ensureRunning}'s error message and {@code jk
     * engine status} both point at for post-mortem. Best-effort: a failure here (e.g. permissions)
     * never blocks starting the engine.
     */
    private static void rotateLog(Path log) {
        if (!Files.exists(log)) return;
        try {
            Files.move(log, log.resolveSibling(log.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Best-effort — the next start still truncates/overwrites `log` either way.
        }
    }

    /** Outcome of waiting for a freshly spawned engine — lets the ladder tell a crash from a slow boot. */
    private record StartResult(Outcome outcome, EngineClient.Handshake handshake) {
        enum Outcome {
            UP,
            CHILD_EXITED,
            TIMED_OUT
        }

        static StartResult up(EngineClient.Handshake h) {
            return new StartResult(Outcome.UP, h);
        }

        static StartResult exited() {
            return new StartResult(Outcome.CHILD_EXITED, null);
        }

        static StartResult timedOut() {
            return new StartResult(Outcome.TIMED_OUT, null);
        }
    }

    private static StartResult awaitStartup(
            EnginePaths.Paths paths, String clientVersion, Duration timeout, Process spawned) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<EngineClient.Handshake> h = EngineClient.handshake(EnginePaths.activeSocket(paths), clientVersion);
            if (h.isPresent()) return StartResult.up(h.get());
            if (spawned != null && !spawned.isAlive()) {
                // The child died (setsid keeps the pid, so liveness is authoritative). One last
                // handshake: a concurrent spawn may have won the election and be serving already
                // our child exiting is then the healthy loser, not a failure.
                return EngineClient.handshake(EnginePaths.activeSocket(paths), clientVersion)
                        .map(StartResult::up)
                        .orElseGet(StartResult::exited);
            }
            sleepQuietly(50);
        }
        return StartResult.timedOut();
    }

    /**
     * Did the JVM ignore the AOT cache on this start? {@code AOTMode=auto} logs and boots cold on a
     * mismatch instead of failing — scan the fresh per-start log for those markers so the caller can
     * drop the cache and retrain next time. Best-effort and bounded (AOT diagnostics appear at boot).
     */
    static boolean scanLogForAotError(Path log) {
        if (log == null) return false;
        try {
            if (!Files.exists(log)) return false;
            String head = Files.readString(log);
            if (head.length() > 8192) head = head.substring(0, 8192);
            return head.contains("[error][aot]")
                    || head.contains("Mismatched values for property")
                    || head.contains("Disabling optimized module handling");
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /** Remember that AOT can't apply for this cache's key, so later starts skip straight to NONE. */
    private static void writeNoAotMarker(Path aotCache) {
        if (aotCache == null) return;
        try {
            Files.writeString(noAotMarkerPath(aotCache), "");
        } catch (IOException ignored) {
            // best-effort — worst case we retry AOT more often, never a failure
        }
    }

    /** Append a diagnostic to the engine log only — never the user's terminal. */
    private static void logReason(EnginePaths.Paths paths, String message) {
        try {
            Files.writeString(
                    paths.log(),
                    "jk engine: " + message + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
