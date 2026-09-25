// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.tui.JdkInstallView;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.discovery.ProbeSupport;
import cc.jumpkick.host.EngineJvmFlags;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkEnsure;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.jdk.JdkInventory;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.jdk.JdkVendor;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.util.OwnerOnlyFiles;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.HelloAckFrame;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Spawn and takeover for the resident engine: which artifact, which host JDK, the JVM line, and
 * the wait until it serves.
 */
public final class EngineSpawn {

    /** Ceiling for a spawn to come up; a cold boot on a loaded host is the pathological case. */
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
        return ensure(paths, clientVersion, Patience.DEFAULT, SilentPeer.Grace.DEFAULT);
    }

    /** As {@link #ensure(EnginePaths.Paths, String)} with the probe patience and the silent peer's grace explicit. */
    static EngineProbe.Handshake ensure(
            EnginePaths.Paths paths, String clientVersion, Patience patience, SilentPeer.Grace grace)
            throws IOException {
        Path socket = EnginePaths.activeSocket(paths);
        Reachability reach = probePatiently(socket, clientVersion, patience);
        if (reach instanceof Reachability.Silent)
            reach = waitOutSilentPeer(paths, socket, clientVersion, patience, grace);
        if (reach instanceof Reachability.Live live) {
            EngineProbe.Handshake hs = live.handshake();
            // A draining engine has unbound its listener; this branch is the race before unbind.
            // Do not spawn a third copy on top of the successor that is already taking over.
            if (hs.draining()) {
                throw new IOException(
                        "the build engine is shutting down — wait for it to stop, or run `jk engine stop --force`");
            }
            if (serves(hs, clientVersion, pointerSha(clientVersion))) {
                return hs;
            }
            // Version skew (incl. same -SNAPSHOT with different content identity) → TAKEOVER, not
            // a kill: spawn this client's engine; its startup atomically repoints the endpoint and
            // drains the displaced engine — in-flight jobs finish untouched.
        }
        // Absent / unusable / version skew → spawn (takeover or cold start).
        return startWithSelfHeal(paths, clientVersion);
    }

    /**
     * A peer silent through the patience. The process the pid file names is read for the life it
     * shows without a reply — {@link SilentPeer}: age, worker children, CPU advancing — and probed
     * again between readings for as long as that life earns. A reply, or the holder's death, hands
     * the outcome back to the caller as any probe would. A holder that shows no life is displaced;
     * one that shows life past its patience is left alone and the command fails naming it, so a
     * busy shared engine is never killed mid-build by a second client. Nothing alive holding the
     * state is the stale-pid case, displaced as before over the socket.
     */
    static Reachability waitOutSilentPeer(
            EnginePaths.Paths paths, Path socket, String clientVersion, Patience patience, SilentPeer.Grace grace)
            throws IOException {
        Clock clock = Clock.SYSTEM;
        long pid = EngineProcessControl.unresponsiveHolderPid(socket);
        Optional<SilentPeer.Life> reading = pid > 0 ? SilentPeer.Life.of(pid, clock) : Optional.empty();
        if (reading.isEmpty()) {
            displaceSilent(paths, socket);
            return new Reachability.Absent();
        }
        SilentPeer.Life before = null;
        SilentPeer.Life now = reading.get();
        long since = clock.nanos();
        while (true) {
            Duration waited = Duration.ofNanos(clock.nanos() - since);
            switch (SilentPeer.judge(before, now, waited, grace)) {
                case DISPLACE -> {
                    displaceSilent(paths, socket);
                    return new Reachability.Absent();
                }
                case LEAVE_ALONE -> {
                    logReason(
                            paths,
                            "left a silent engine alone (pid " + pid + ", " + now.describe() + ") after "
                                    + SilentPeer.human(waited) + " — not displaced");
                    throw new IOException(SilentPeer.refusal(now, waited));
                }
                case KEEP_WAITING -> {
                    /* back off, probe, read again */
                }
            }
            sleepQuietly(patience.backoff().toMillis());
            Reachability again = probe(socket, clientVersion, patience.replyTimeoutMillis());
            if (!(again instanceof Reachability.Silent)) return again;
            Optional<SilentPeer.Life> next = SilentPeer.Life.of(pid, clock);
            if (next.isEmpty()) return new Reachability.Absent(); // the holder went away on its own
            before = now;
            now = next.get();
        }
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
     * Bring up a fresh engine: retry once on an early exit, and wait out slow cold starts rather
     * than reporting "could not start".
     */
    private static EngineProbe.Handshake startWithSelfHeal(EnginePaths.Paths paths, String clientVersion)
            throws IOException {
        return startOnce(paths, clientVersion, resolveEngineTarget(paths, clientVersion));
    }

    /**
     * Spawn and wait until serving. A plain spawn retries once on an early exit. On Linux a
     * delegated user scope is tried first when one can be started; if that process exits before it
     * serves, one plain spawn follows and the engine is told why. A scope that stays up but never
     * serves is the same hang as a plain spawn that does.
     */
    private static EngineProbe.Handshake startOnce(EnginePaths.Paths paths, String clientVersion, EngineTarget target)
            throws IOException {
        // The log about to be rotated is the previous engine's; if it ends in an OutOfMemoryError
        // exit, say so once, here, before the fresh start truncates the evidence.
        EngineHeapDump.reportExit(paths);
        EngineScope.Decision scope = EngineScope.current();
        String note = scope.note();
        // A failed scope gets one plain spawn, not the retry — the scope attempt was the first try.
        int attempts = 2;
        if (scope.scoped()) {
            ScopeAttempt scoped = scopedStart(paths, clientVersion, target, scope);
            if (scoped.handshake != null) return scoped.handshake;
            note = scoped.note;
            attempts = 1;
        }
        for (int attempt = 0; attempt < attempts; attempt++) {
            StartResult r = awaitStartup(
                    paths,
                    clientVersion,
                    COLD_START_CEILING,
                    spawn(paths, target, EngineScope.Decision.plain(note)).process());
            switch (r.outcome()) {
                case UP -> {
                    return Objects.requireNonNull(r.handshake(), "UP without a handshake");
                }
                case TIMED_OUT -> throw notStarted(paths); // alive but never served → genuine hang
                case CHILD_EXITED -> {
                    if (attempt + 1 < attempts) {
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

    /**
     * One scoped launch. {@link ScopeAttempt#handshake} is set when that engine is serving. Otherwise
     * {@link ScopeAttempt#note} is what the plain spawn should repeat. A timeout throws: the process
     * is alive, and a second spawn would strand it.
     */
    private static ScopeAttempt scopedStart(
            EnginePaths.Paths paths, String clientVersion, EngineTarget target, EngineScope.Decision scope)
            throws IOException {
        Spawned spawned;
        try {
            spawned = spawn(paths, target, scope);
        } catch (IOException e) {
            String note = EngineScope.failureNote(-1, e.getMessage());
            logReason(paths, "delegated scope failed (" + note + "); starting without it");
            return ScopeAttempt.failed(note);
        }
        StartResult r = awaitStartup(paths, clientVersion, COLD_START_CEILING, spawned.process());
        switch (r.outcome()) {
            case UP -> {
                return ScopeAttempt.up(Objects.requireNonNull(r.handshake(), "UP without a handshake"));
            }
            case TIMED_OUT -> throw notStarted(paths);
            case CHILD_EXITED -> {
                String note = EngineScope.failureNote(
                        exitCode(spawned.process()), readFrom(paths.log(), spawned.logOffset()));
                logReason(paths, "delegated scope failed (" + note + "); starting without it");
                return ScopeAttempt.failed(note);
            }
        }
        throw notStarted(paths); // unreachable
    }

    /** A scoped launch that is either serving or a note for the plain fallback. */
    private record ScopeAttempt(EngineProbe.@Nullable Handshake handshake, String note) {
        static ScopeAttempt up(EngineProbe.Handshake handshake) {
            return new ScopeAttempt(handshake, "");
        }

        static ScopeAttempt failed(String note) {
            return new ScopeAttempt(null, note);
        }
    }

    private static IOException notStarted(EnginePaths.Paths paths) {
        return new IOException("could not start the build engine — see " + paths.log() + " for details");
    }

    /**
     * What {@link #spawn} launched. The child setsid()s and never forks, and a systemd scope execs
     * the engine in place, so {@code process} stays the engine's pid. {@code logOffset} is where
     * that child's output begins in the engine log.
     */
    private record Spawned(Process process, long logOffset) {}

    /** The resolved engine to spawn: which artifact, and the host JDK (JAR only). */
    record EngineTarget(EngineArtifact engine, @Nullable Path javaHome) {}

    /** A host JDK for the engine: home, vendor, and version (from its {@code release} file). */
    record EngineJdk(Path home, JdkVendor vendor, String version) {}

    /** Resolve everything the spawn/mode decision needs, self-healing a missing/skewed engine jar. */
    private static EngineTarget resolveEngineTarget(EnginePaths.Paths paths, String clientVersion) throws IOException {
        // Engine spawn is java -cp lib/jk-engine/<jar> EngineMain (or JK_ENGINE_EXE). The client binary
        // path is only needed for cache-prune re-invocation elsewhere — not for the daemon spawn.
        Optional<EngineArtifact> resolved = resolveEngineArtifact(System.getenv("JK_ENGINE_EXE"), clientVersion);
        // Self-heal a missing jar: the slim client never hosts the engine; download when allowed.
        if (resolved.isEmpty()
                && ReleaseArtifacts.applicable(
                        clientVersion,
                        ReleaseArtifacts.releasedClient(),
                        SessionContext.current().offline())) {
            // The same bar, phase lines and done line `jk jdk install` renders, under an Engine
            // chip: the user is watching this download as they watch a JDK's.
            try (ReleaseDownloadView view = ReleaseDownloadView.engine(clientVersion)) {
                EngineJarFetcher.fetch(ReleaseArtifacts.releasesBase(), clientVersion, view);
            }
            resolved = resolveEngineArtifact(System.getenv("JK_ENGINE_EXE"), clientVersion);
        }
        EngineArtifact engine = resolved.orElseThrow(() -> new IOException("no build engine for jk " + clientVersion
                + " — materialize it (`./install.sh target/dist/jk` or `jk self materialize …`),"
                + " download a release (`jk self update`), or set JK_ENGINE_EXE"));
        if (engine.kind() != EngineArtifact.Kind.JAR) {
            return new EngineTarget(engine, null);
        }
        return new EngineTarget(engine, resolveEngineJdk().home());
    }

    /**
     * The JDK that hosts the engine JVM, pinned by vendor+major. Honours {@code [toolchain].jdk}
     * (or {@code JK_ENGINE_JDK}); defaults to the LTS Temurin at the engine's floor release. Prefers
     * an already-installed match (no network), else installs exactly the pin. A HotSpot JDK is what
     * {@code docs/architecture.md} wants (HotSpot's JIT + SHA-256 intrinsics).
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
        // The same bar, phases and done line `jk jdk install` renders — this is the client, and
        // the user is watching; the header says the download is the engine's runtime, not theirs.
        try (JdkInstallView view = new JdkInstallView(null).header("Installing the build engine's JDK (" + pin + ")")) {
            Path home =
                    JdkEnsure.install(pin, CliOutput.stderr()::println, view).home();
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
     * The installed engine's spawn line: a plain JVM app on the jk-managed JDK, one fat jar on the
     * classpath, tuned by ordinary JVM flags. Sizing the heap happens here because only the spawner
     * can (a process cannot shrink its own {@code -Xmx}); {@code max-heap-mb} stays authoritative.
     */
    static List<String> jarCommand(EnginePaths.Paths paths, EngineTarget target, JkEngineConfig config) {
        List<String> command = new ArrayList<>();
        Path javaHome = Objects.requireNonNull(target.javaHome(), "a jar engine runs on a host JDK");
        command.add(JdkFingerprint.java(javaHome).toString());
        // The OOM heap dump lands in the engine directory beside the log, one file per exit
        // (HotSpot names a dump into a directory java_pid<pid>.hprof).
        command.addAll(EngineJvmFlags.BASE);
        command.add(EngineJvmFlags.heapDumpPath(EnginePaths.heapDumpDir(paths)));
        // Metaspace/stack mirror what workers already get from JvmOptions.
        command.add("-XX:MaxMetaspaceSize=256m");
        command.add("-Xss512k");
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
    private static Spawned spawn(EnginePaths.Paths paths, EngineTarget target, EngineScope.Decision launch)
            throws IOException {
        EngineArtifact engine = target.engine();
        JkEngineConfig config = JkEngineConfig.resolve();
        OwnerOnlyFiles.directory(paths.dir());
        boolean freshLog = EngineLogRotation.rotate(paths.log(), Clock.SYSTEM);
        // The child detaches ITSELF into its own session (setsid(2) via PosixDetach, first thing
        // in the engine role) — without that it stays in THIS client's process group, and a
        // Ctrl-C/SIGTERM aimed at the client (or its whole group) would take down the engine and
        // every other build it is hosting. A delegated scope execs this same command in place.
        List<String> command =
                switch (engine.kind()) {
                    case JAR -> jarCommand(paths, target, config);
                    case EXE -> exeCommand(paths, engine, config);
                };
        command = EngineScope.command(launch.prefix(), command);
        ProcessBuilder pb = new ProcessBuilder(command);
        // The allow-list of this shell's environment, never the whole of it: the daemon serves
        // every later terminal with whatever it started with (EngineEnvironment says what and why).
        Map<String, String> shell = System.getenv();
        EngineEnvironment.seed(pb.environment(), shell);
        if (launch.scoped()) EngineScope.keepBus(pb.environment(), shell);
        EngineScope.applyNote(pb.environment(), launch.note());
        // Anchor the detached daemon's working directory to its own state dir (created just above),
        // never the spawning client's CWD. A resident engine outlives the shell that started it, and
        // if it inherited an ephemeral CWD (a /tmp scratch dir, a git worktree, a since-deleted
        // checkout) every subprocess it later forks — javac, workers — inherits that dead CWD and
        // dies at JVM init with "Could not determine current working directory". The state dir is
        // stable for the engine's whole life and is never removed by cache maintenance.
        pb.directory(paths.dir().toFile());
        // Merge stderr into stdout inside the child (one fd, no interleaving risk from two
        // independently-opened streams onto the same file), then route that to the log. The spawner
        // writes the log's first line itself (which artifact it chose — the one fact the engine
        // can't know), then the child appends; if that header can't be written, the child opens the
        // log the way the header would have.
        pb.redirectErrorStream(true);
        boolean headed = writeSpawnHeader(paths.log(), engine, freshLog);
        long logOffset = logSize(paths.log());
        pb.redirectOutput(
                headed || !freshLog
                        ? ProcessBuilder.Redirect.appendTo(paths.log().toFile())
                        : ProcessBuilder.Redirect.to(paths.log().toFile()));
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        Process p = pb.start();
        p.getOutputStream().close(); // EOF immediately; the engine doesn't read stdin
        return new Spawned(p, logOffset);
    }

    /** Bytes already in {@code log}, or 0 when the file cannot be sized. */
    private static long logSize(Path log) {
        try {
            return Files.size(log);
        } catch (IOException e) {
            return 0;
        }
    }

    /** Up to 4 KiB of {@code log} from {@code offset}, or empty when it cannot be read. */
    private static String readFrom(Path log, long offset) {
        if (offset < 0) return "";
        try (InputStream in = Files.newInputStream(log)) {
            long left = offset;
            while (left > 0) {
                long skipped = in.skip(left);
                if (skipped <= 0) return "";
                left -= skipped;
            }
            return new String(in.readNBytes(4096), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** {@code -1} when {@code process} has not exited. */
    private static int exitCode(Process process) {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException stillAlive) {
            return -1;
        }
    }

    /**
     * Write the spawn decision as the log's next line: the first of a fresh file, or appended to a
     * spawn moments old ({@link EngineLogRotation}). {@code false} — and no header — if the file
     * isn't writable.
     */
    private static boolean writeSpawnHeader(Path log, EngineArtifact engine, boolean fresh) {
        try {
            Files.writeString(
                    log,
                    EngineLogRotation.header(engine.path(), engine.how(), Clock.SYSTEM) + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    fresh ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** True when this client runs as a GraalVM native image (so the spawned engine will too). */
    static boolean isNativeImage() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
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
     * PluginJar.locate reads System.getProperty there), the worker startup-cache switch so nested
     * engines honour {@code jk.worker.aot}, the {@code jk.env.*} layout overlays (JkDirs test seam)
     * so a spawned engine resolves the same store/state the client did, and the owner pid a sandbox
     * names so its engine dies with it.
     *
     * <p>Never {@code jk.plugin.class}: that is a client/test-runner host signal that would load
     * workspace/test plugin overlays inside the engine.
     */
    static boolean forwarded(String key) {
        if (!key.startsWith("jk.")) return false;
        if (key.equals("jk.plugin.class")) return false;
        return key.endsWith(".jar")
                || key.equals("jk.worker.aot")
                || key.startsWith("jk.env.")
                || key.equals(OWNER_PID_PROPERTY);
    }

    /** Mirror of the engine's {@code OwnerWatchdog.PROPERTY}; the CLI cannot see engine classes. */
    static final String OWNER_PID_PROPERTY = "jk.engine.owner-pid";
}
