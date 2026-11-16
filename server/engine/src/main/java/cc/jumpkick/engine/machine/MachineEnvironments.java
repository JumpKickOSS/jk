// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.machine;

import cc.jumpkick.changes.Change;
import cc.jumpkick.changes.ChangeJournal;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.winhost.HostFacts;
import cc.jumpkick.winhost.MachineSetup;
import cc.jumpkick.winhost.ReleasePin;
import cc.jumpkick.winhost.WslDistros;
import cc.jumpkick.winhost.WslDownloads;
import cc.jumpkick.winhost.WslSetup;
import cc.jumpkick.winhost.WslShell;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The machine's environments as the Windows engine sees them, and the one operation that installs
 * JumpKick into a WSL distro or removes it there. The dashboard, {@code jk self setup-wsl}, the MCP
 * {@code machine} tool and the setup decision's rows all come here: each names distros, each name is
 * checked against the distros WSL lists, and the work runs one distro after another, in the order
 * asked, so a failing distro leaves the others to install. Every distro's progress is
 * kept for {@link #list} to report, and every outcome is a change-log entry.
 */
public final class MachineEnvironments {

    /** What an operation does to a distro. */
    public enum Action {
        INSTALL,
        REMOVE;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** How long a listing is reused: every listing asks each running distro which JumpKick it has. */
    static final Duration LIST_FOR = Duration.ofSeconds(3);

    /** How long a setup decision's row waits for its install. */
    static final Duration WAIT_FOR_ONE = Duration.ofMinutes(30);

    /** How long an installed distro's engine has to pair with the front door. */
    static final Duration PAIRS_WITHIN = Duration.ofSeconds(60);

    /** One distro's operation: queued, then running with its latest step, then done or failed. */
    public record Status(String distro, Action action, String state, String step, long at) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", action.wire());
            m.put("state", state);
            m.put("step", step);
            m.put("at", at);
            return m;
        }

        boolean busy() {
            return state.equals("queued") || state.equals("running");
        }
    }

    /** The engines the front door lists, each its identity map with {@code paired}. */
    @FunctionalInterface
    public interface Engines {
        List<Map<String, Object>> list();
    }

    /** Where the version an install pins comes from. */
    @FunctionalInterface
    public interface Pins {
        ReleasePin.@Nullable Pin pin() throws IOException;
    }

    private final Supplier<List<WslDistros.Distro>> distros;
    private final Engines engines;
    private final WslSetup setup;
    private final Pins pins;
    private final Path scratch;
    private final String hostVersion;
    private final Clock clock;
    private final Pause pause;
    private final Map<String, Status> status = new ConcurrentHashMap<>();
    /** The operations asked for and not yet run, in the order asked; one thread drains it at a time. */
    private final Queue<Runnable> queued = new ConcurrentLinkedQueue<>();

    private final AtomicBoolean draining = new AtomicBoolean();
    private volatile ReleasePin.@Nullable Pin pin;
    private volatile @Nullable List<WslDistros.Distro> listed;
    private volatile long listedAt;

    /**
     * @param distros the distros WSL lists, found without starting one
     * @param scratch the Windows folder each operation makes its own folder under
     */
    public MachineEnvironments(
            Supplier<List<WslDistros.Distro>> distros,
            Engines engines,
            WslSetup setup,
            Pins pins,
            Path scratch,
            String hostVersion,
            Clock clock) {
        this(distros, engines, setup, pins, scratch, hostVersion, clock, MachineEnvironments::sleep);
    }

    /** How the pairing wait waits; tests pass one that advances their clock. */
    @FunctionalInterface
    interface Pause {
        void millis(long ms);
    }

    MachineEnvironments(
            Supplier<List<WslDistros.Distro>> distros,
            Engines engines,
            WslSetup setup,
            Pins pins,
            Path scratch,
            String hostVersion,
            Clock clock,
            Pause pause) {
        this.pause = pause;
        this.distros = distros;
        this.engines = engines;
        this.setup = setup;
        this.pins = pins;
        this.scratch = scratch;
        this.hostVersion = hostVersion;
        this.clock = clock;
    }

    /**
     * The Windows engine's: the distros from the registry, the release site JumpKick updates from (or
     * the Linux dist {@link WslSetup#DIST_ENV} names in the engine's environment), each operation's
     * folder under the Windows temp folder, and the front door's engines.
     */
    public static MachineEnvironments windows(Engines engines, String hostVersion) {
        WslDownloads downloads = WslDownloads.live();
        String temp = ProcessEnv.get("TEMP");
        Path scratch = temp == null || temp.isBlank() ? JkDirs.state().resolve("tmp") : Path.of(temp);
        String named = ProcessEnv.get(WslSetup.DIST_ENV);
        Path dist = WslSetup.devDist(named, hostVersion);
        if (named != null && !named.isBlank()) {
            Log.info(
                    dist == null
                            ? "wsl setup: " + WslSetup.DIST_ENV
                                    + " ignored: only a -SNAPSHOT engine installs a dist, from an"
                                    + " absolute folder holding jk and lib/jk-engine-<version>.jar"
                            : "wsl setup: installs the Linux dist at " + dist + " (" + WslSetup.DIST_ENV + ")");
        }
        return new MachineEnvironments(
                () -> HostFacts.live().wslDistros(),
                engines,
                new WslSetup(WslShell.LIVE, downloads, dist),
                dist != null
                        ? () -> WslSetup.distPin(dist)
                        : () -> ReleasePin.of(hostVersion, downloads::published, downloads::latest),
                scratch,
                hostVersion,
                Clock.SYSTEM);
    }

    /** The distros WSL lists, reused for {@link #LIST_FOR}. */
    private List<WslDistros.Distro> distros() {
        List<WslDistros.Distro> l = listed;
        if (l != null && clock.millis() - listedAt < LIST_FOR.toMillis()) return l;
        l = distros.get();
        listed = l;
        listedAt = clock.millis();
        return l;
    }

    /**
     * The host and every WSL distro: its WSL version, whether it runs, the JumpKick it reports (asked of
     * a running distro only: a stopped one is not woken, so its state is {@code unknown}), the engine
     * the front door sees for it and whether that engine is paired, and its operation's progress.
     */
    public Map<String, Object> list() {
        List<Map<String, Object>> seen = engines.list();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WslDistros.Distro d : distros()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", "wsl");
            row.put("name", d.name());
            row.put("wsl", d.version());
            row.put("default", d.isDefault());
            row.put("running", d.running());
            row.put("jumpKick", d.jumpKick());
            row.put("state", state(d));
            row.put("engine", engineOf(d.name(), seen));
            Status s = status.get(key(d.name()));
            row.put("operation", s == null ? null : s.toMap());
            rows.add(row);
        }
        Map<String, Object> host = new LinkedHashMap<>();
        host.put("kind", "windows");
        host.put("version", hostVersion);
        ReleasePin.Pin p = pin;
        host.put("installs", p == null ? null : p.version());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("host", host);
        out.put("environments", rows);
        return out;
    }

    private static String state(WslDistros.Distro d) {
        if (d.version() == 1) return "wsl1";
        if (!d.running()) return "unknown";
        return d.jumpKick() == null ? "not-installed" : "installed";
    }

    /** The front door's engine for distro {@code name}: {@code id}, {@code port}, {@code paired}; null when none. */
    static @Nullable Map<String, Object> engineOf(String name, List<Map<String, Object>> seen) {
        for (Map<String, Object> e : seen) {
            if (!"wsl".equals(e.get("guest")) || !(e.get("guestName") instanceof String g)) continue;
            if (!g.equalsIgnoreCase(name)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.get("id"));
            m.put("port", e.get("port"));
            m.put("version", e.get("version"));
            m.put("paired", Boolean.TRUE.equals(e.get("paired")));
            return m;
        }
        return null;
    }

    /** What {@link #start} took on and what it refused, each refusal with its reason. */
    public record Started(List<String> accepted, Map<String, String> refused) {
        public Started {
            accepted = List.copyOf(accepted);
            refused = Map.copyOf(refused);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accepted", accepted);
            m.put("refused", new LinkedHashMap<>(refused));
            return m;
        }
    }

    /**
     * Queues {@code action} for each distro {@code names} names. A name WSL does not list, or a distro
     * with an operation already queued or running, is refused with its reason; nothing a name says is
     * used but to pick a listed distro.
     */
    public Started start(Action action, List<String> names) {
        List<WslDistros.Distro> known = distros.get();
        listed = known;
        listedAt = clock.millis();
        List<String> accepted = new ArrayList<>();
        Map<String, String> refused = new LinkedHashMap<>();
        for (String name : names) {
            String shown = name.length() > 64 ? name.substring(0, 64) + "…" : name;
            Optional<WslDistros.Distro> found = WslSetup.find(name, known);
            if (found.isEmpty()) {
                refused.put(shown, "WSL lists no distro of that name");
                continue;
            }
            WslDistros.Distro d = found.get();
            Status now = status.get(key(d.name()));
            if (now != null && now.busy()) {
                refused.put(
                        d.name(),
                        "already " + (now.state().equals("queued") ? "queued to " : "running ")
                                + now.action().wire());
                continue;
            }
            if (accepted.contains(d.name())) continue;
            update(d.name(), action, "queued", "waiting for its turn");
            accepted.add(d.name());
            queued.add(() -> run(action, d));
        }
        drain();
        return new Started(accepted, refused);
    }

    /** Runs the queued operations one after another on one thread, unless one is already at it. */
    private void drain() {
        if (queued.isEmpty() || !draining.compareAndSet(false, true)) return;
        SessionContext.startVirtual("jk-engine-wsl-setup", () -> {
            try {
                for (Runnable next; (next = queued.poll()) != null; ) next.run();
            } finally {
                draining.set(false);
            }
            // One asked for between the last poll and the release gets its own drain.
            drain();
        });
    }

    /**
     * Installs into {@code distro} and waits until it has ended, each new step to {@code progress}: a
     * setup decision's WSL row, applied after the person answered.
     */
    public MachineSetup.Result installAndWait(String distro, Consumer<String> progress) {
        Started started = start(Action.INSTALL, List.of(distro));
        if (started.accepted().isEmpty()) {
            String why = started.refused().isEmpty()
                    ? "nothing to do"
                    : started.refused().values().iterator().next();
            return new MachineSetup.Result("", false, distro + ": " + why);
        }
        String name = started.accepted().getFirst();
        String last = "";
        long deadline = clock.millis() + WAIT_FOR_ONE.toMillis();
        while (clock.millis() < deadline) {
            Status s = status.get(key(name));
            if (s != null && !s.step().equals(last)) {
                last = s.step();
                if (s.busy()) progress.accept(last);
            }
            if (s != null && !s.busy())
                return new MachineSetup.Result("", s.state().equals("done"), s.step());
            pause.millis(500);
        }
        return new MachineSetup.Result(
                "", false, "the install in " + name + " is still running; the dashboard shows it");
    }

    /** The current operation of distro {@code name}; null when it has had none. */
    public @Nullable Status status(String name) {
        return status.get(key(name));
    }

    private void run(Action action, WslDistros.Distro d) {
        update(d.name(), action, "running", action == Action.INSTALL ? "starting" : "removing");
        List<String> lines = new ArrayList<>();
        Path dir = null;
        WslSetup.Outcome outcome;
        try {
            dir = Files.createTempDirectory(Files.createDirectories(scratch), "jk-wsl-");
            outcome = switch (action) {
                case INSTALL -> install(d, dir, lines);
                case REMOVE -> setup.remove(d, dir, line -> step(d, action, lines, line));
            };
        } catch (IOException | RuntimeException e) {
            outcome = new WslSetup.Outcome(
                    false, action.wire() + " in " + d.name() + " failed: " + Errors.text(e), lines);
        } finally {
            if (dir != null) PathUtil.deleteRecursively(dir);
            listed = null;
        }
        update(d.name(), action, outcome.ok() ? "done" : "failed", outcome.line());
        Log.info("wsl setup: " + outcome.line());
        StringBuilder detail = new StringBuilder();
        for (String line : lines) detail.append(line).append('\n');
        ChangeJournal.note(
                "WSL " + d.name(),
                action == Action.INSTALL ? Change.INSTALL : Change.UNINSTALL,
                outcome.line(),
                detail.toString());
    }

    private WslSetup.Outcome install(WslDistros.Distro d, Path dir, List<String> lines) throws IOException {
        ReleasePin.Pin p = pin;
        if (p == null) {
            step(
                    d,
                    Action.INSTALL,
                    lines,
                    "finding the release to install (JumpKick for Windows is " + hostVersion + ")");
            p = pins.pin();
            if (p == null) {
                return new WslSetup.Outcome(
                        false, "no published JumpKick release fits " + hostVersion + "; nothing installed", lines);
            }
            pin = p;
        }
        WslSetup.Outcome installed = setup.install(d, p, dir, line -> step(d, Action.INSTALL, lines, line));
        if (!installed.ok()) return installed;
        step(d, Action.INSTALL, lines, "waiting for " + d.name() + "'s engine to pair with the front door");
        long deadline = clock.millis() + PAIRS_WITHIN.toMillis();
        boolean seen = false;
        while (clock.millis() < deadline) {
            Map<String, Object> e = engineOf(d.name(), engines.list());
            if (e != null && Boolean.TRUE.equals(e.get("paired"))) {
                return new WslSetup.Outcome(
                        true, installed.line() + "; its engine is paired with the front door", lines);
            }
            seen |= e != null;
            pause.millis(1_000);
        }
        String why = seen
                ? "its engine answers the front door but did not pair within " + PAIRS_WITHIN.toSeconds()
                        + " s: run `jk engine status` in " + d.name()
                : "its engine does not answer the front door: JumpKick " + p.version()
                        + " may predate the front door (a release before it never pairs), or its engine did not"
                        + " start: run `jk engine status` in " + d.name();
        return new WslSetup.Outcome(false, installed.line() + ", but " + why, lines);
    }

    private void step(WslDistros.Distro d, Action action, List<String> lines, String line) {
        lines.add(line);
        update(d.name(), action, "running", line);
    }

    private void update(String distro, Action action, String state, String step) {
        status.put(key(distro), new Status(distro, action, state, step, clock.millis()));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
