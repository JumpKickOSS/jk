// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.discovery.ProbeSupport;
import cc.jumpkick.host.AotCacheFiles;
import cc.jumpkick.host.EngineJvmFlags;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkEnsure;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.jdk.JdkInventory;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.jdk.JdkVendor;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.util.AotManifest;
import cc.jumpkick.util.AotSettings;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.OwnerOnlyFiles;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.HelloAckFrame;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

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

    /**
     * Whether the engine behind {@code hs} is the one this client must be served by: the client's
     * version, running the jar the home's engine pointer names ({@code pointerSha}). The
     * handshake's build id is a prefix of that jar's digest. Either side without an opinion — an
     * engine run from a classes directory, a home with no pointer — leaves the version rule alone.
     */
    static boolean serves(EngineProbe.Handshake hs, String clientVersion, Optional<String> pointerSha) {
        if (!clientVersion.equals(hs.version())) return false;
        if (hs.buildId().isEmpty()) return true;
        String expected = pointerSha.orElse("");
        if (expected.isEmpty()) return true;
        return expected.startsWith(hs.buildId());
    }

    private static Optional<String> pointerSha(String clientVersion) {
        return EngineInstall.current().engineSha(clientVersion);
    }

    static EngineProbe.Handshake ensure(EnginePaths.Paths paths, String clientVersion) throws IOException {
        Path socket = EnginePaths.activeSocket(paths);
        Reachability reach = probePatiently(socket, clientVersion, Patience.DEFAULT);
        if (reach instanceof Reachability.Live live) {
            EngineProbe.Handshake hs = live.handshake();
            // A draining engine has unbound its listener; this branch is the race before unbind.
            // Do not spawn a third copy on top of the successor that is already taking over.
            if (hs.draining()) {
                throw new IOException(
                        "the build engine is shutting down — wait for it to stop, or run `jk engine stop --force`");
            }
            if (serves(hs, clientVersion, pointerSha(clientVersion))) {
                // Already primary — do not wipe AOT (would thrash the live train). Wipe only on
                // materialize / new endpoint claim.
                return hs;
            }
            // Version skew (incl. same -SNAPSHOT with different content identity) → TAKEOVER, not
            // a kill: spawn this client's engine; its startup atomically repoints the endpoint and
            // drains the displaced engine — in-flight jobs finish untouched.
        } else if (reach instanceof Reachability.Silent) {
            displaceSilent(paths, socket);
        }
        // Absent / unusable / version skew → spawn (takeover or cold start).
        return startWithSelfHeal(paths, clientVersion);
    }

    /**
     * How long ensure tolerates a silent peer before believing the silence. The engine runs
     * SerialGC under a user-sized heap, so a stop-the-world pause of several seconds in the middle
     * of a large parallel build is an ordinary event, not a wedge — and a client arriving inside
     * that pause must wait it out, because displacing the engine kills every other terminal's
     * build with it. Each probe waits {@code replyTimeoutMillis} for the hello-ack; a silent one is
     * repeated {@code reprobes} more times with {@code backoff} between them, and only a peer
     * silent through all of them is displaced.
     */
    record Patience(int replyTimeoutMillis, int reprobes, Duration backoff) {
        static final Patience DEFAULT = new Patience(EngineWire.SOCKET_TIMEOUT_MILLIS, 3, Duration.ofSeconds(2));

        /** Every probe this patience allows, counting the first. */
        int probes() {
            return 1 + reprobes;
        }
    }

    /** Probe, and give a silent peer {@code patience} before reporting it silent. */
    static Reachability probePatiently(Path socket, String clientVersion, Patience patience) {
        Reachability reach = probe(socket, clientVersion, patience.replyTimeoutMillis());
        for (int again = 0; again < patience.reprobes() && reach instanceof Reachability.Silent; again++) {
            sleepQuietly(patience.backoff().toMillis());
            reach = probe(socket, clientVersion, patience.replyTimeoutMillis());
        }
        return reach;
    }

    /**
     * Displace a peer that accepted every probe's connection and answered none: the socket is held
     * by something that will never serve, and the next build would otherwise wait out the stream
     * idle instead of starting an engine that can bind. The pid file's process is hard-killed only
     * when it is visibly a JVM ({@link EngineProcessControl#unresponsiveHolderPid}); a recycled pid
     * never gets an engine's kill — the peer is merely asked to stop, over the socket, and the
     * spawn's election arbitrates from there.
     */
    static void displaceSilent(EnginePaths.Paths paths, Path socket) {
        long pid = EngineProcessControl.unresponsiveHolderPid(socket);
        logReason(
                paths,
                "displacing unresponsive engine"
                        + (pid > 0 ? " (pid " + pid + ")" : "")
                        + " — handshake stayed silent through " + Patience.DEFAULT.probes() + " probes");
        if (pid > 0) {
            EngineProcessControl.hardKill(pid);
            EngineProcessControl.waitForDeathOrKill(pid, STOP_DEATH_WAIT);
        } else {
            EngineProcessControl.stop(socket);
        }
    }

    /**
     * Outcome of one ensure probe: live handshake, nothing listening, silent peer (connect works,
     * no reply within the probe's wait), or connected-but-not-usable (e.g. newer protocol).
     */
    sealed interface Reachability {
        record Live(EngineProbe.Handshake handshake) implements Reachability {}

        record Absent() implements Reachability {}

        /** Socket accepted the connection but never completed handshake. */
        record Silent() implements Reachability {}

        /** Reached something that is not a usable same-generation engine. */
        record Unusable() implements Reachability {}
    }

    /**
     * Probe liveness beyond "socket exists": connect + hello, waiting {@code replyTimeoutMillis}
     * for the ack. Distinguishes a silent peer from a missing engine; {@link #probePatiently} decides
     * how much silence counts.
     */
    private static Reachability probe(Path socket, String clientVersion, int replyTimeoutMillis) {
        SocketChannel ch;
        try {
            ch = EngineWire.connect(socket);
        } catch (IOException e) {
            return new Reachability.Absent();
        }
        try (ch) {
            String ack = EngineWire.exchange(ch, ProtoLifecycle.hello(clientVersion), replyTimeoutMillis);
            if (!EngineProtocol.HELLO_ACK.equals(EngineProtocol.typeOf(ack))) {
                return new Reachability.Unusable();
            }
            if (Jsonl.intValue(ack, "proto", EngineProtocol.PROTOCOL) > EngineProtocol.PROTOCOL) {
                return new Reachability.Unusable();
            }
            // The record does not carry `proto` (checked above); an absent pid or start reads as
            // unknown (-1) here, where the record reads 0.
            HelloAckFrame hello = HelloAckFrame.decode(ack);
            String ackBuildId = hello.buildId();
            return new Reachability.Live(new EngineProbe.Handshake(
                    hello.version(),
                    Jsonl.has(ack, "pid") ? hello.pid() : -1,
                    Jsonl.has(ack, "startedAt") ? hello.startedAt() : -1,
                    hello.draining(),
                    ackBuildId == null ? "" : ackBuildId));
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("did not reply")
                    || msg.contains("closed the connection without replying")
                    || msg.contains("no protocol traffic")) {
                return new Reachability.Silent();
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
    private static EngineProbe.Handshake startWithSelfHeal(EnginePaths.Paths paths, String clientVersion)
            throws IOException {
        return startOnce(paths, clientVersion, resolveEngineTarget(paths, clientVersion));
    }

    /**
     * Spawn and wait until serving; re-picks AOT mode per attempt and retries once on early exit.
     */
    private static EngineProbe.Handshake startOnce(EnginePaths.Paths paths, String clientVersion, EngineTarget target)
            throws IOException {
        // The log about to be rotated is the previous engine's; if it ends in an OutOfMemoryError
        // exit, say so once, here, before the fresh start truncates the evidence.
        EngineHeapDump.reportExit(paths);
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
                        dropAotCache(
                                paths, target, "AOT cache was ignored by the engine JVM; skipping it for this key");
                    }
                    // EngineServer wipes state/aot after claiming the endpoint. Do not
                    // wipe again here — the sidecar may already be training into a fresh file.
                    return Objects.requireNonNull(r.handshake(), "UP without a handshake");
                }
                case TIMED_OUT -> throw notStarted(paths); // alive but never served → genuine hang
                case CHILD_EXITED -> {
                    if (attempt == 0) {
                        if (mode == AotMode.USE) dropCacheAfterEarlyExit(paths, target);
                        else logReason(paths, "engine exited before serving; retrying after backoff");
                        sleepQuietly(1_500);
                        continue;
                    }
                    throw notStarted(paths);
                }
            }
        }
        throw notStarted(paths); // unreachable
    }

    /**
     * The engine JVM died before it served while mapping an AOT cache: the cache is the suspect (a
     * JDK 25 JVM segfaults in {@code AOTLinkedClassBulkLoader} on a cache recorded under another
     * heap), so it is dropped and its key refused for the TTL, and the retry starts plain instead of
     * dying the same way and reporting "could not start".
     */
    static void dropCacheAfterEarlyExit(EnginePaths.Paths paths, EngineTarget target) {
        dropAotCache(
                paths,
                target,
                "engine exited before serving while mapping its AOT cache; dropped the cache for this key, retrying without it");
    }

    /** Delete the cache, refuse its key for the marker's TTL, and say why in the engine log. */
    private static void dropAotCache(EnginePaths.Paths paths, EngineTarget target, String reason) {
        deleteQuietly(target.aotCache());
        writeNoAotMarker(target.aotCache());
        logReason(paths, reason);
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
     * HotSpot/C2 JVM (AOT is only stable there), and the AOT cache path. A refusal marker is not
     * carried here: it expires, so it is read when the mode is chosen and nowhere else.
     */
    record EngineTarget(
            EngineArtifact engine,
            @Nullable Path javaHome,
            boolean hotspot,
            @Nullable Path aotCache) {}

    /** A host JDK for the engine: home, vendor, and version (from its {@code release} file). */
    record EngineJdk(Path home, JdkVendor vendor, String version) {}

    /** Resolve everything the spawn/mode decision needs, self-healing a missing/skewed engine jar. */
    private static EngineTarget resolveEngineTarget(EnginePaths.Paths paths, String clientVersion) throws IOException {
        // Engine spawn is java -cp lib/jk-engine/<jar> EngineMain (or JK_ENGINE_EXE). The client binary
        // path is only needed for cache-prune re-invocation elsewhere — not for the daemon spawn.
        Optional<EngineArtifact> resolved = resolveEngineArtifact(System.getenv("JK_ENGINE_EXE"), clientVersion);
        // Self-heal a missing jar: the slim client never hosts the engine; download when allowed.
        if (resolved.isEmpty()
                && EngineJarFetcher.applicable(
                        clientVersion,
                        isNativeImage() || JvmClient.installed(),
                        SessionContext.current().offline())) {
            CliOutput.err("jk: downloading the build engine (jk-engine-" + clientVersion + ".jar) ...");
            EngineJarFetcher.fetch(EngineJarFetcher.releasesBase(), clientVersion);
            resolved = resolveEngineArtifact(System.getenv("JK_ENGINE_EXE"), clientVersion);
        }
        EngineArtifact engine = resolved.orElseThrow(() -> new IOException("no build engine for jk " + clientVersion
                + " — materialize it (`./install.sh build/dist/jk` or `jk self materialize …`),"
                + " download a release (`jk self update`), or set JK_ENGINE_EXE"));
        if (engine.kind() != EngineArtifact.Kind.JAR) {
            return new EngineTarget(engine, null, false, null);
        }
        EngineJdk jdk = resolveEngineJdk();
        Path aot = aotCachePath(paths, Path.of(engine.path()), jdk);
        return new EngineTarget(engine, jdk.home(), isHotSpot(jdk.vendor()), aot);
    }

    /**
     * AOT mode for a target: only a JAR engine on a HotSpot JDK whose key is not under a live
     * refusal ({@link AotCacheFiles#blocked} — a refusal is a back-off, expired past its TTL on the
     * schedule the worker trainer also uses, not believed forever). Train-on-miss is skipped when
     * {@link cc.jumpkick.util.AotSettings#trainingEnabled} is false ({@code JK_AOT_TRAIN=off}) —
     * still maps an existing cache. USE requires a <em>non-empty</em> cache ({@link
     * AotCacheFiles#usable}): a zero-byte leftover from a crashed trainer would otherwise map
     * "forever" while never accelerating anything — it is deleted here so the key can retrain.
     */
    static AotMode chooseAotMode(EngineTarget t) {
        if (t.engine().kind() != EngineArtifact.Kind.JAR) return AotMode.NONE;
        if (!t.hotspot()) return AotMode.NONE; // GraalVM host: its Graal JIT breaks the cache — skip cleanly
        if (AotCacheFiles.blocked(t.aotCache())) return AotMode.NONE;
        if (AotCacheFiles.usable(t.aotCache())) return AotMode.USE;
        AotCacheFiles.deleteIfEmpty(t.aotCache()); // torn/zero-byte leftover: treat as missing so it retrains
        if (!AotSettings.trainingEnabled()) return AotMode.NONE;
        return AotMode.TRAIN;
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
        return JdkFingerprint.java(home);
    }

    private static EngineJdk resolveEngineJdk() throws IOException {
        int floor = Runtime.version().feature();
        Optional<String> pinned = GlobalConfig.engineJdkPin();
        if (pinned.isEmpty() && JvmClient.installed()) {
            // The installed JVM client runs on a JDK the user chose — on a host the JDK feed does
            // not cover, the only JDK there is, and by construction one that meets the floor. The
            // engine runs on it too, unless [toolchain] jdk names another; asking the feed for a
            // Temurin first would end in "not covered by the JetBrains JDK feed" on exactly the
            // hosts this client exists for.
            Optional<EngineJdk> own = ownJdk(floor);
            if (own.isPresent()) return own.get();
        }
        String pin = pinned.orElse("temurin-" + floor);
        Optional<EngineJdk> installed = findInstalledEngineJdk(pin);
        if (installed.isPresent()) return installed.get();
        CliOutput.err("jk: installing the build engine's JDK (" + pin + ") ...");
        try {
            Path home = JdkEnsure.install(pin, CliOutput.stderr()::println).home();
            return probeEngineJdk(home)
                    .orElseThrow(() -> new IOException("engine JDK installed at " + home + " is unreadable"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted installing the engine JDK " + pin, e);
        }
    }

    /** The JDK this JVM runs on, when it is a full JDK of at least {@code floor}. */
    private static Optional<EngineJdk> ownJdk(int floor) {
        Path home;
        try {
            home = JavaHomes.runningJavaHome();
        } catch (RuntimeException noHome) {
            return Optional.empty();
        }
        return probeEngineJdk(home).filter(jdk -> majorOf(jdk.version()) >= floor);
    }

    /** First already-installed JDK matching the pin's vendor+major, checked without any network. */
    private static Optional<EngineJdk> findInstalledEngineJdk(String pin) {
        Optional<Pin> want = parsePin(pin);
        if (want.isEmpty()) return Optional.empty(); // unparseable pin → force the install path
        List<Path> homes = new ArrayList<>();
        JdkInventory defaults = JdkInventory.current();
        defaults.defaultHome().ifPresent(homes::add);
        try {
            homes.add(JavaHomes.runningJavaHome());
        } catch (RuntimeException ignored) {
            // No running JVM home (native client) — the registry scan below still covers installs.
        }
        try {
            for (JdkHit hit : new JdkRegistry().listHits()) homes.add(hit.home());
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
        return ProbeSupport.discoverJdk(home, "engine-host").map(h -> new EngineJdk(h.home(), h.vendor(), h.version()));
    }

    /** A parsed engine-JDK pin, e.g. {@code "temurin-25"} → (TEMURIN, 25). */
    private record Pin(JdkVendor vendor, int major) {}

    private static Optional<Pin> parsePin(String spec) {
        int dash = spec.lastIndexOf('-');
        if (dash <= 0 || dash == spec.length() - 1) return Optional.empty();
        int major;
        try {
            major = Integer.parseInt(spec.substring(dash + 1).split("\\.")[0]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        JdkVendor vendor = vendorFromToken(spec.substring(0, dash));
        return vendor == JdkVendor.UNKNOWN ? Optional.empty() : Optional.of(new Pin(vendor, major));
    }

    /** Map a spec vendor token (a {@code jbPrefix} like {@code "temurin"}/{@code "graalvm"}) to a vendor. */
    private static JdkVendor vendorFromToken(String token) {
        for (JdkVendor v : JdkVendor.values()) {
            if (v.jbPrefix().map(p -> p.equalsIgnoreCase(token)).orElse(false)) return v;
        }
        return JdkVendor.UNKNOWN;
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
    private static boolean isHotSpot(JdkVendor vendor) {
        return vendor != JdkVendor.ORACLE_GRAALVM && vendor != JdkVendor.GRAALVM_CE;
    }

    /**
     * Which engine artifact a spawn chose. {@code EXE}: {@code path} is an executable whose {@code
     * main} IS the engine loop. {@code JAR}: {@code path} is the engine's fat jar under {@code
     * <home>/lib/jk-engine/}, launched as {@code
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
     * this client version (live under {@code lib/jk-engine/} via {@code jk-engine.toml}, or a leftover
     * jar of that version still in the directory until GC). Empty when neither is available (caller
     * may download / materialize, then retry).
     */
    static Optional<EngineArtifact> resolveEngineArtifact(String envOverride, String version) {
        return resolveEngineArtifact(envOverride, version, EngineInstall.current());
    }

    /** Root-injected variant — the testable seam. */
    static Optional<EngineArtifact> resolveEngineArtifact(String envOverride, String version, EngineInstall install) {
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
     * The engine's AOT cache path, keyed to the engine jar (name:size:mtime), the host JDK identity
     * (version + vendor) <em>and</em> the engine heap it will run under. A mismatched cache is
     * silently ignored by {@code AOTMode=auto} and never retrained, so folding the JDK into the key
     * means a jar upgrade, a JDK build bump (Temurin 25.0.3→25.0.4), or a vendor swap all yield a
     * fresh key that trains cleanly. The heap is in the key because a cache recorded under one
     * {@code -Xmx} is not merely ignored under another: JDK 25 segfaults mapping it, and the client
     * cannot tell that crash from an engine that failed to start. Stale {@code .aot}/{@code .noaot}
     * files from previous keys are deleted best-effort here.
     */
    static Path aotCachePath(EnginePaths.Paths paths, Path engineJar, EngineJdk jdk) {
        return aotCachePath(paths, engineJar, jdk, JkVersion.VERSION, heapKey(JkEngineConfig.resolve()));
    }

    /** The heap dimension of the AOT key: the cap the spawner will pass, or "uncapped". */
    static String heapKey(JkEngineConfig config) {
        return config.heapCapped() ? "heap=" + config.maxHeapMb() + "m" : "heap=uncapped";
    }

    /** As above, version-scoped under {@code state/engine/<v>/} so engines never share AOT state. */
    static Path aotCachePath(EnginePaths.Paths paths, Path engineJar, EngineJdk jdk, String version, String heapKey) {
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
                                : jdk.version() + "|" + jdk.vendor().name())
                .append(':')
                .append(heapKey);
        String hash = Hashing.sha256Hex(signature.toString()).substring(0, 16);
        // ONE home for every AOT cache — engine and workers alike live in ~/.jk/state/aot/ so a
        // user (or `jk engine aot`) finds them all side by side. The engine's file
        // carries its jk version ("engine-<version>-<key>.aot") because its LIFETIME is
        // version-scoped: a new primary reaps other versions' engine AOT. The sweep below stays
        // within one version so side-by-side keys for the same version never thrash each other.
        // Worker caches (kotlinc-/java-compiler-) have no version dimension.
        Path aotDir = JkDirs.state().resolve("aot");
        try {
            Files.createDirectories(aotDir);
        } catch (IOException ignored) {
            // Falls through — a failed mkdir surfaces on the training write, with a real error.
        }
        String stem = "engine-" + version + "-" + hash;
        Path cache = aotDir.resolve(stem + ".aot");
        // Sweep THIS version's other keys — the cache, the JEP 514 .aot.config recording
        // intermediate, and any refusal marker. The "<16-hex>." shape check keeps a version
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
                    if (AotCacheFiles.isMarker(name)) swept.add(AotCacheFiles.cacheOf(name));
                    else if (name.endsWith(AotCacheFiles.CACHE)) swept.add(name);
                    Files.deleteIfExists(p);
                }
            }
        } catch (IOException ignored) {
            // Cleanup is opportunistic; a leftover cache costs disk, not correctness.
        }
        if (!swept.isEmpty()) {
            AotManifest.remove(aotDir, swept);
            AotManifest.reconcile(aotDir);
        }
        recordEngineAotManifest(cache, engineJar, jdk, version, hash);
        // Drop leftover per-version cache under engine-state so it is not confused with the
        // current content-addressed AOT key.
        PathUtil.deleteRecursively(paths.dir().resolve(version));
        return cache;
    }

    /**
     * Best-effort {@code aot.toml} row for the engine cache key (even before the file exists, so a
     * pending train is still documented). {@code ready} means size &gt; 0 and {@code noaot} means a
     * refusal is still live — the same two predicates {@link #chooseAotMode} decides by, so the
     * manifest and the engine never disagree about one file.
     */
    static void recordEngineAotManifest(Path cache, Path engineJar, EngineJdk jdk, String version, String hash) {
        if (cache == null) return;
        Path aotDir = cache.getParent();
        if (aotDir == null) return;
        try {
            String name = cache.getFileName().toString();
            boolean ready = Files.isRegularFile(cache) && Files.size(cache) > 0;
            boolean noaot = AotCacheFiles.blocked(cache);
            String status = ready ? "ready" : (noaot ? "noaot" : "pending");
            var b = AotManifest.Entry.builder(name)
                    .tool("engine")
                    .key(hash)
                    .jkVersion(version)
                    .status(status)
                    .jvmFlags(EngineJvmFlags.AOT_SENSITIVE);
            if (ready) {
                b.sizeBytes(Files.size(cache)).lastUsed(AotManifest.nowIso());
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
            AotManifest.upsert(aotDir, b.build());
        } catch (Exception ignored) {
            // never fail engine start for a human index
        }
    }

    /**
     * The installed engine's spawn line: a plain JVM app on the jk-managed JDK, one fat jar on the
     * classpath, tuned by ordinary JVM flags. Sizing the heap happens here because only the spawner
     * can (a process cannot shrink its own {@code -Xmx}); {@code max-heap-mb} stays authoritative.
     */
    static List<String> jarCommand(EnginePaths.Paths paths, EngineTarget target, AotMode mode, JkEngineConfig config) {
        List<String> command = new ArrayList<>();
        Path javaHome = Objects.requireNonNull(target.javaHome(), "a jar engine runs on a host JDK");
        command.add(JdkFingerprint.java(javaHome).toString());
        // The shared serving/trainer flag list — one list with EngineMain.aotTrainerCommand,
        // because JEP 514 refuses to map an AOT cache whose dump-time and runtime property sets
        // differ. The OOM heap dump lands in the engine directory beside the log, one file per
        // exit (HotSpot names a dump into a directory java_pid<pid>.hprof).
        command.addAll(EngineJvmFlags.AOT_SENSITIVE);
        command.add(EngineJvmFlags.heapDumpPath(EnginePaths.heapDumpDir(paths)));
        // Metaspace/stack mirror what workers already get from JvmOptions.
        command.add("-XX:MaxMetaspaceSize=256m");
        command.add("-Xss512k");
        // AOT cache (JEP 514, JDK 25+): pre-parsed class metadata and AOT-compiled code.
        // USE maps an existing cache. TRAIN boots cold and spawns a sidecar trainer
        // (`EngineMain --aot-training`, isolated temp state, throwaway socket). NONE
        // omits the cache (non-HotSpot host JDK, or a key that already proved unmappable).
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
        command.addAll(forwardedJvmArgs());
        command.add("-cp");
        command.add(target.engine().path());
        command.add("cc.jumpkick.engine.EngineMain");
        return command;
    }

    /**
     * A dedicated engine executable ({@code JK_ENGINE_EXE}): its main IS the engine loop. The JVM
     * flags land as argv for the wrapper to consume; EngineMain ignores argv, so a wrapper that
     * does not consume them degrades to an unsized engine, never a dead one.
     */
    private static List<String> exeCommand(EnginePaths.Paths paths, EngineArtifact engine, JkEngineConfig config) {
        List<String> command = new ArrayList<>();
        command.add(engine.path());
        if (config.heapCapped()) {
            command.add("-Xms" + config.minHeapMb() + "m");
            command.add("-Xmx" + config.maxHeapMb() + "m");
        }
        command.add("-XX:MinHeapFreeRatio=10");
        command.add("-XX:MaxHeapFreeRatio=25");
        command.add("-XX:-ShrinkHeapInSteps");
        command.add("-XX:+ExitOnOutOfMemoryError");
        command.add("-XX:+HeapDumpOnOutOfMemoryError");
        command.add(EngineJvmFlags.heapDumpPath(EnginePaths.heapDumpDir(paths)));
        return command;
    }

    /** Spawn a fresh engine, detached — mirrors {@link CachePruneScheduler}'s spawn-and-forget pattern. */
    private static Spawned spawn(EnginePaths.Paths paths, EngineTarget target, AotMode mode) throws IOException {
        EngineArtifact engine = target.engine();
        JkEngineConfig config = JkEngineConfig.resolve();
        OwnerOnlyFiles.directory(paths.dir());
        rotateLog(paths.log());
        // The child detaches ITSELF into its own session (setsid(2) via PosixDetach, first thing
        // in the engine role) — without that it stays in THIS client's process group, and a
        // Ctrl-C/SIGTERM aimed at the client (or its whole group) would take down the engine and
        // every other build it is hosting.
        List<String> command =
                switch (engine.kind()) {
                    case JAR -> jarCommand(paths, target, mode, config);
                    case EXE -> exeCommand(paths, engine, config);
                };
        ProcessBuilder pb = new ProcessBuilder(command);
        // The allow-list of this shell's environment, never the whole of it: the daemon serves
        // every later terminal with whatever it started with (EngineEnvironment says what and why).
        EngineEnvironment.seed(pb.environment(), System.getenv());
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
    private record StartResult(Outcome outcome, EngineProbe.@Nullable Handshake handshake) {
        enum Outcome {
            UP,
            CHILD_EXITED,
            TIMED_OUT
        }

        static StartResult up(EngineProbe.Handshake h) {
            return new StartResult(Outcome.UP, h);
        }

        static StartResult exited() {
            return new StartResult(Outcome.CHILD_EXITED, null);
        }

        static StartResult timedOut() {
            return new StartResult(Outcome.TIMED_OUT, null);
        }
    }

    /**
     * Wait until the endpoint answers with the engine this client needs. During a takeover the
     * displaced engine keeps answering on the endpoint until the successor claims it, so a
     * handshake alone is not "up": only one that {@link #serves} this client is — the displaced
     * engine's answer is waited through. Without that, the request that follows a takeover
     * (the install's re-shelving pass) would stream to the engine the home no longer names.
     */
    private static StartResult awaitStartup(
            EnginePaths.Paths paths, String clientVersion, Duration timeout, Process spawned) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Optional<String> pointer = pointerSha(clientVersion);
        while (System.nanoTime() < deadline) {
            Optional<EngineProbe.Handshake> h = serving(paths, clientVersion, pointer);
            if (h.isPresent()) return StartResult.up(h.get());
            if (spawned != null && !spawned.isAlive()) {
                // The child died (setsid keeps the pid, so liveness is authoritative). One last
                // handshake: a concurrent spawn may have won the election and be serving already
                // our child exiting is then the healthy loser, not a failure.
                return serving(paths, clientVersion, pointer)
                        .map(StartResult::up)
                        .orElseGet(StartResult::exited);
            }
            sleepQuietly(50);
        }
        return StartResult.timedOut();
    }

    private static Optional<EngineProbe.Handshake> serving(
            EnginePaths.Paths paths, String clientVersion, Optional<String> pointer) {
        return EngineProbe.handshake(EnginePaths.activeSocket(paths), clientVersion)
                .filter(hs -> !hs.draining() && serves(hs, clientVersion, pointer));
    }

    /**
     * Did the JVM ignore the AOT cache on this start? {@code AOTMode=auto} logs and boots cold on a
     * mismatch instead of failing, so ask {@link AotCacheFiles#refused} about the fresh per-start
     * log and the caller can drop the cache and retrain next time. Bounded: AOT diagnostics land at
     * boot, so only the head of the log can hold them.
     */
    static boolean scanLogForAotError(Path log) {
        if (log == null) return false;
        try {
            if (!Files.exists(log)) return false;
            String head = Files.readString(log);
            if (head.length() > 8192) head = head.substring(0, 8192);
            return AotCacheFiles.refused(head);
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteQuietly(@Nullable Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /** Remember that AOT can't apply for this cache's key, so later starts skip straight to NONE. */
    private static void writeNoAotMarker(@Nullable Path aotCache) {
        if (aotCache == null) return;
        try {
            Files.writeString(AotCacheFiles.marker(aotCache), "");
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

    /**
     * The {@code -D} arguments that carry this JVM's forwarded properties ({@link #forwarded}) into
     * another JVM — the engine this client spawns, and any client a test forks as {@code java -cp …
     * cc.jumpkick.cli.Jk}: a forked client that does not carry them spawns an engine without the
     * worker-jar overrides the test run was handed, and that engine can only find workers in a
     * sandbox store that holds none.
     */
    public static List<String> forwardedJvmArgs() {
        List<String> args = new ArrayList<>();
        for (var e : System.getProperties().entrySet()) {
            String key = String.valueOf(e.getKey());
            if (!forwarded(key)) continue;
            String val = String.valueOf(e.getValue());
            if (val == null || val.isBlank()) continue;
            args.add("-D" + key + "=" + val);
        }
        return args;
    }

    /**
     * Which of this JVM's {@code jk.*} system properties travel into the engine JVM as {@code -D}:
     * plugin-jar location overrides (e.g. {@code -Djk.test.runner.jar=…} from a test JVM —
     * PluginJar.locate reads System.getProperty there), the AOT switches so nested engines honor
     * {@code JK_AOT_TRAIN} / {@code jk.aot.train}, the {@code jk.env.*} layout overlays (JkDirs
     * test seam) so a spawned engine resolves the same store/state the client did, and the owner
     * pid a sandbox names so its engine dies with it.
     *
     * <p>Never {@code jk.plugin.class}: that is a client/test-runner host signal that would load
     * workspace/test plugin overlays inside the engine.
     */
    static boolean forwarded(String key) {
        if (!key.startsWith("jk.")) return false;
        if (key.equals("jk.plugin.class")) return false;
        return key.endsWith(".jar")
                || key.equals("jk.aot.train")
                || key.equals("jk.worker.aot")
                || key.startsWith("jk.env.")
                || key.equals(OWNER_PID_PROPERTY);
    }

    /** Mirror of the engine's {@code OwnerWatchdog.PROPERTY}; the CLI cannot see engine classes. */
    static final String OWNER_PID_PROPERTY = "jk.engine.owner-pid";
}
