// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineHeapDump;
import cc.jumpkick.cli.engine.EngineProbe;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.command.interop.MavenSpyJar;
import cc.jumpkick.command.toolchain.Shell;
import cc.jumpkick.command.toolchain.ShellInstallerBlock;
import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.compat.InstalledTool;
import cc.jumpkick.compat.ToolRegistry;
import cc.jumpkick.config.JkCacheConfig;
import cc.jumpkick.discovery.SymlinkProvisioner;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.terminal.posix.PosixPasswd;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.CacheInventoryAck;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk doctor} — host health checklist. Prints a wedge header plus one row per subsystem
 * (engine, dirs, state, jdk, lock, shell, mvn, tools, workers) and a summary. The worker and repository
 * rows are answered by a running engine only — a health check never starts one unless
 * {@code --engine} asks it to, and {@code --no-engine} skips those rows. {@code --output json}
 * emits machine output.
 */
public final class DoctorCommand implements CliCommand {

    @Override
    public String name() {
        return "doctor";
    }

    @Override
    public String description() {
        return "Check host health (engine, cache, JDKs, lock, shell, workers)";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<dir>", "Override the tools install root. Default: $JK_STORE_DIR/tools.", "--tools-dir")
                        .hide(),
                // Kept under HelpWidthTest's 78-column budget: 20 columns go to the indent and the
                // flag itself, so this string has 58 to spend.
                Opt.flag("Fingerprint each linked install and report drift", "--verify-linked"),
                Opt.flag("Start the engine if needed for worker/repo rows", "--engine"),
                Opt.flag("Skip the engine-answered worker/repo rows", "--no-engine"));
    }

    @Override
    public int run(Invocation in) throws IOException {
        GlobalOptions global = GlobalOptions.from(in);
        Path toolsDir = in.value("tools-dir").map(Path::of).orElse(null);
        boolean verifyLinked = in.isSet("verify-linked");
        Path root = toolsDir != null ? toolsDir : JkDirs.tools();

        // Collect checks first so JSON and human share the same facts. Tools are scanned (and
        // repaired — broken links unlinked, fingerprints written) exactly once, here, so
        // `--output json` performs the same repair the human view does instead of only reporting it.
        Check engine = withHeapDump(checkEngine(), EngineHeapDump.find(EnginePaths.current()));
        Check cache = checkDirs();
        Check state = checkStateMode();
        Check jdk = checkJdk();
        Check lock = checkLock();
        Check shell = checkShell();
        Check mvn = checkMavenSpy(MavenSpyJar.current());
        List<ToolRow> toolRows;
        String toolsError = null;
        try {
            toolRows = scanTools(root, verifyLinked);
        } catch (IOException e) {
            toolRows = List.of();
            toolsError = e.getMessage();
        }
        Tally tally = Tally.of(toolRows);
        int healthy = tally.healthy(), pruned = tally.pruned(), verified = tally.verified();
        int drifted = tally.drifted(), firstSeen = tally.firstSeen(), empty = tally.empty();
        // The worker and repository rows are the engine's answers. A diagnostic must not change
        // the state it inspects, so by default only an engine that is already running answers;
        // --engine starts one for this check, --no-engine asks nothing.
        boolean ask = !in.isSet("no-engine");
        boolean ifRunning = !in.isSet("engine");
        Workers workers =
                ask ? workers(() -> queryInventory("workers", ifRunning)) : new Workers(List.of(), ROWS_SKIPPED);
        RepoStores.Stores repos = ask
                ? queryRepos(() -> queryInventory("repos", ifRunning))
                : new RepoStores.Stores(List.of(), ROWS_SKIPPED);
        MavenSettingsRows.Rows settings = ask
                ? MavenSettingsRows.query(() -> queryInventory("m2-settings", ifRunning))
                : MavenSettingsRows.Rows.none(ROWS_SKIPPED);

        boolean hasFail = engine.status == Status.FAIL
                || cache.status == Status.FAIL
                || state.status == Status.FAIL
                || jdk.status == Status.FAIL
                || lock.status == Status.FAIL; // tools alone (including a scan error) never fails

        if (global.outputIsJson()) {
            CliOutput.out(reportJson(
                    engine,
                    cache,
                    state,
                    jdk,
                    lock,
                    shell,
                    mvn,
                    healthy,
                    pruned,
                    verified,
                    drifted,
                    firstSeen,
                    empty,
                    toolsError,
                    workers,
                    repos,
                    settings));
            return hasFail ? 1 : 0;
        }

        Theme t = Theme.active();
        CommandWedge.envelopeStart();
        CliOutput.out(CommandWedge.menu("Doctor"));

        printCheck(engine, t);
        printCheck(cache, t);
        printCheck(state, t);
        printCheck(jdk, t);
        printCheck(lock, t);
        printCheck(shell, t);
        printCheck(mvn, t);

        printTools(toolRows, toolsError, t);

        for (String line : renderWorkers(workers, global.verbose, t)) CliOutput.out(line);
        for (String line : renderShelf(workers, pointerEngineSha(), t)) CliOutput.out(line);
        for (String line : RepoStores.render(repos, t)) CliOutput.out(line);
        for (String line : MavenSettingsRows.render(settings, t)) CliOutput.out(line);

        CliOutput.out(Theme.paint("---", t.darkGray()));
        String summary = Theme.colorize(String.valueOf(healthy), t.focused())
                + " healthy"
                + (pruned > 0 ? ", " + Theme.colorize(String.valueOf(pruned), t.focused()) + " pruned" : "")
                + (verified > 0 ? ", " + Theme.colorize(String.valueOf(verified), t.focused()) + " unchanged" : "")
                + (firstSeen > 0 ? ", " + Theme.colorize(String.valueOf(firstSeen), t.focused()) + " recorded" : "")
                + (drifted > 0 ? ", " + Theme.colorize(String.valueOf(drifted), t.focused()) + " drifted" : "")
                + (empty > 0 ? ", " + Theme.colorize(String.valueOf(empty), t.focused()) + " empty" : "");
        // Append subsystem warnings to summary when any check failed.
        if (hasFail) {
            summary += Theme.colorize(" — issues found", t.warning());
        }
        CliOutput.out(summary);
        return hasFail ? 1 : 0;
    }

    // ---- workers ----

    /**
     * One installed plugin worker as the engine sees it: where its jar came from, the POM its
     * launch classpath is rebuilt from, and that classpath. {@code error} is the resolution
     * failure when the engine could not rebuild it; {@code classpath} is then empty. {@code
     * refused} is the loader's reason when the jar's root descriptor is another plugin's: the jar
     * is on the shelf but not registered, so the table it should own has no owner. {@code
     * packagedBy} is the sha256 of the engine jar that shelved the worker, when its memo records
     * one — what the shelf row compares with the engine the home names.
     */
    public record Worker(
            String artifact,
            String version,
            String source,
            String jar,
            String pom,
            int declared,
            List<String> classpath,
            @Nullable String error,
            @Nullable String refused,
            @Nullable String packagedBy) {}

    /** The worker rows, or the reason there are none (engine unreachable, query refused). */
    public record Workers(List<Worker> rows, @Nullable String error) {}

    /** An engine inventory query behind the worker or repo rows; a seam so the rendering is testable without an engine. */
    interface WorkerProbe {
        CacheInventoryAck workers() throws IOException;
    }

    /** What the engine-answered rows say when {@code --no-engine} asked for none. */
    static final String ROWS_SKIPPED = "skipped (--no-engine)";

    /** What the engine-answered rows say when no engine is running and none was to be started. */
    static final String ENGINE_NOT_RUNNING = "engine not running — start it with `jk engine start`, or run"
            + " `jk doctor --engine` to start one for this check";

    /** One engine inventory query; {@code ifRunning} asks only an engine that is already up. */
    private static CacheInventoryAck queryInventory(String query, boolean ifRunning) throws IOException {
        return ifRunning
                ? EngineClient.cacheInventoryIfRunning(
                        EnginePaths.current(), query, JkDirs.cache(), JkStores.store(), List.of(), List.of(), false)
                : EngineClient.cacheInventory(
                        EnginePaths.current(), query, JkDirs.cache(), JkStores.store(), List.of(), List.of(), false);
    }

    /** The repository stores and their origins, so a wrong-origin cache is one line apart from the symptom. */
    static RepoStores.Stores queryRepos(WorkerProbe probe) {
        try {
            return RepoStores.decode(probe.workers());
        } catch (EngineClient.EngineNotRunningException e) {
            return new RepoStores.Stores(List.of(), ENGINE_NOT_RUNNING);
        } catch (IOException | RuntimeException e) {
            return new RepoStores.Stores(List.of(), "engine query failed: " + e.getMessage());
        }
    }

    /**
     * Decode the engine's {@code workers} inventory: rows are
     * {@code artifact|version|source|jar|pom|declared|entries|error|refused|packagedBy}, classpath entries
     * {@code artifact|path}. The engine answers this the way a fork resolves, so a worker that
     * runs on the wrong jar shows it here — a Guava flavour, a stale self-installed POM shadowing
     * the published one — in one line instead of an evening.
     */
    static Workers workers(WorkerProbe probe) {
        CacheInventoryAck ack;
        try {
            ack = probe.workers();
        } catch (EngineClient.EngineNotRunningException e) {
            return new Workers(List.of(), ENGINE_NOT_RUNNING);
        } catch (IOException | RuntimeException e) {
            return new Workers(List.of(), "engine query failed: " + e.getMessage());
        }
        if (ack.error() != null) return new Workers(List.of(), ack.error());
        Map<String, List<String>> classpaths = new LinkedHashMap<>();
        for (String entry : ack.entries()) {
            int bar = entry.indexOf('|');
            if (bar <= 0) continue;
            classpaths
                    .computeIfAbsent(entry.substring(0, bar), k -> new ArrayList<>())
                    .add(entry.substring(bar + 1));
        }
        List<Worker> rows = new ArrayList<>();
        for (String line : ack.lines()) {
            String[] f = line.split("\\|", 10);
            if (f.length < 9) continue;
            String error = f[7].isBlank() ? null : f[7];
            String refused = f[8].isBlank() ? null : f[8];
            String packagedBy = f.length > 9 && !f[9].isBlank() ? f[9] : null;
            rows.add(new Worker(
                    f[0],
                    f[1],
                    f[2],
                    f[3],
                    f[4],
                    parseIntOrZero(f[5]),
                    List.copyOf(classpaths.getOrDefault(f[0], List.of())),
                    error,
                    refused,
                    packagedBy));
        }
        return new Workers(List.copyOf(rows), null);
    }

    /** The engine jar the home's pointer names, by digest; empty when the home has none. */
    private static Optional<String> pointerEngineSha() {
        return EngineInstall.current().currentInstall().map(EngineInstall.Materialized::engineSha);
    }

    private static int parseIntOrZero(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * One line per worker. {@code source} names the store repo the jar was located in: a
     * {@code jk-local} worker was installed from a checkout and shadows the published one under
     * {@code jumpkick}; {@code override} is a {@code -D} jar property. A jar the loader refused is a
     * warning carrying the loader's reason, which names the descriptor it found and the fix.
     * {@code --verbose} lists the launch classpath entry by entry.
     */
    static List<String> renderWorkers(Workers workers, boolean verbose, Theme t) {
        List<String> out = new ArrayList<>();
        if (workers.error() != null) {
            out.add(Theme.colorize("warn:    ", t.warning()) + Theme.colorize("workers", t.cyan()) + " — "
                    + workers.error());
            return out;
        }
        if (workers.rows().isEmpty()) {
            out.add(Theme.colorize("ok:      ", t.completedStep()) + " " + Theme.colorize("workers", t.cyan())
                    + " — none installed in the store (fetched from jumpkick.build on first use)");
            return out;
        }
        for (Worker w : workers.rows()) {
            String label = Theme.colorize(w.artifact(), t.cyan()) + " " + w.version();
            String from = "from " + Theme.colorize(w.source(), t.path());
            if (w.refused() != null) {
                out.add(Theme.colorize("warn:    ", t.warning()) + " " + label + " " + from + " — " + w.refused());
                continue;
            }
            if (w.error() != null) {
                out.add(Theme.colorize("warn:    ", t.warning()) + " " + label + " " + from
                        + " — launch classpath did not resolve: " + w.error());
                continue;
            }
            out.add(Theme.colorize("worker:  ", t.completedStep()) + " " + label + " " + from + " · POM declares "
                    + w.declared() + (w.declared() == 1 ? " dep" : " deps") + " · "
                    + w.classpath().size()
                    + (w.classpath().size() == 1 ? " entry" : " entries") + " on the launch classpath");
            if (verbose) {
                out.add("           pom " + Theme.colorize(w.pom().isEmpty() ? "(none)" : w.pom(), t.path()));
                for (String entry : w.classpath()) {
                    out.add("           " + Theme.colorize(entry, t.path()));
                }
            }
        }
        return out;
    }

    /**
     * The shelf row: whether the workers under {@code jk-local} were packaged by the engine the
     * home's pointer names ({@code pointerSha}). A shelf packaged by another engine — an
     * interrupted install, an older client, a takeover that raced — has no other standing symptom,
     * and {@code jk install} is what brings it to the live engine. No row when no shelved worker
     * records its packager or the home names no engine.
     */
    static List<String> renderShelf(Workers workers, Optional<String> pointerSha, Theme t) {
        if (workers.error() != null || pointerSha.isEmpty()) return List.of();
        Map<String, List<String>> byEngine = new LinkedHashMap<>();
        for (Worker w : workers.rows()) {
            if (w.packagedBy() == null || !RepositorySpec.JK_LOCAL.equals(w.source())) continue;
            byEngine.computeIfAbsent(w.packagedBy().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(w.artifact());
        }
        if (byEngine.isEmpty()) return List.of();
        String live = pointerSha.get().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (byEngine.size() == 1 && byEngine.containsKey(live)) {
            out.add(Theme.colorize("ok:      ", t.completedStep()) + " " + Theme.colorize("shelf", t.cyan())
                    + " — packaged by the engine the home names (" + short12(live) + ")");
            return out;
        }
        for (Map.Entry<String, List<String>> e : byEngine.entrySet()) {
            if (e.getKey().equals(live)) continue;
            int n = e.getValue().size();
            out.add(Theme.colorize("warn:    ", t.warning()) + " " + Theme.colorize("shelf", t.cyan()) + " — " + n
                    + (n == 1 ? " worker" : " workers") + " packaged by engine " + short12(e.getKey())
                    + " while the home names engine " + short12(live) + " (" + String.join(", ", e.getValue())
                    + ") — run `jk install` so the shelf is the live engine's");
        }
        return out;
    }

    /** The {@code workers} member of the JSON report: an array of worker objects, or an error string. */
    static String workersJson(Workers workers) {
        if (workers.error() != null) {
            return JsonFields.object().string("error", workers.error()).finish();
        }
        StringBuilder array = new StringBuilder("[");
        for (int i = 0; i < workers.rows().size(); i++) {
            Worker w = workers.rows().get(i);
            if (i > 0) array.append(',');
            array.append(JsonFields.object()
                    .string("artifact", w.artifact())
                    .string("version", w.version())
                    .string("source", w.source())
                    .string("jar", w.jar())
                    .string("pom", w.pom())
                    .number("declared", w.declared())
                    .array("classpath", w.classpath())
                    .string("error", w.error())
                    .string("refused", w.refused())
                    .string("packagedBy", w.packagedBy())
                    .finish());
        }
        return array.append(']').toString();
    }

    // ---- checks ----

    public enum Status {
        OK,
        WARN,
        FAIL
    }

    public record Check(Status status, String label, String detail) {}

    /**
     * The verdicts a tool install can carry. The four fingerprint verdicts are reachable only under
     * {@code --verify-linked}: {@link #VERIFIED} matches the stored baseline, {@link #DRIFTED}
     * does not, {@link #FIRST_SEEN} had no baseline to compare against, and {@link #EMPTY} means the
     * digest was {@link JdkFingerprint#EMPTY_TREE} — nothing was hashed, which is a finding, not a
     * fingerprint.
     */
    private enum ToolRowKind {
        PRUNED,
        VERIFIED,
        DRIFTED,
        FIRST_SEEN,
        EMPTY,
        LINKED,
        OK
    }

    /** One installed/linked tool, after {@link #scanTools} has already applied any repair. */
    private record ToolRow(
            BuildTool tool,
            InstalledTool installed,
            ToolRowKind kind,
            @Nullable String detail) {}

    /** The tool-row counts the summary and the JSON report both print; every row but a pruned one is healthy. */
    private record Tally(int healthy, int pruned, int verified, int drifted, int firstSeen, int empty) {

        static Tally of(List<ToolRow> rows) {
            int healthy = 0, pruned = 0, verified = 0, drifted = 0, firstSeen = 0, empty = 0;
            for (ToolRow row : rows) {
                if (row.kind() == ToolRowKind.PRUNED) {
                    pruned++;
                    continue;
                }
                healthy++;
                switch (row.kind()) {
                    case VERIFIED -> verified++;
                    case DRIFTED -> drifted++;
                    case FIRST_SEEN -> firstSeen++;
                    case EMPTY -> empty++;
                    default -> {}
                }
            }
            return new Tally(healthy, pruned, verified, drifted, firstSeen, empty);
        }
    }

    private static Check checkEngine() {
        EnginePaths.Paths paths = EnginePaths.current();
        Path socket = EnginePaths.activeSocket(paths);
        Optional<EngineProbe.Status> st = EngineProbe.status(socket);
        if (st.isEmpty()) {
            // A socket that accepts connections but never answers status is a wedged engine, not
            // an absent one — `jk build` would hang on it instead of lazy-starting a fresh one.
            if (EngineProbe.reachable(socket)) {
                return new Check(
                        Status.FAIL,
                        "engine",
                        "listening but not answering status — likely wedged; try `jk engine stop`");
            }
            return new Check(Status.WARN, "engine", "not running (lazy start on next build)");
        }
        EngineProbe.Status s = st.get();
        String detail = "running pid " + s.pid() + " · " + s.version() + " · up "
                + formatUptime((System.currentTimeMillis() - s.startedAtMillis()) / 1000);
        if (s.httpError() != null && !s.httpError().isBlank()) {
            return new Check(Status.WARN, "engine", detail + " · http: " + s.httpError());
        }
        return new Check(Status.OK, "engine", detail);
    }

    /**
     * A heap dump beside the engine log means an earlier engine of this identity exited on
     * OutOfMemoryError; the finding names it and the remedy, and an OK engine row becomes a WARN.
     */
    static Check withHeapDump(Check engine, Optional<Path> dump) {
        if (dump.isEmpty()) return engine;
        Status status = engine.status() == Status.FAIL ? Status.FAIL : Status.WARN;
        return new Check(status, engine.label(), engine.detail() + " · " + EngineHeapDump.finding(dump.get()));
    }

    private static Check checkDirs() {
        return checkDirs(JkDirs.cache(), JkDirs.store(), JkDirs.state(), JkCacheConfig.resolve());
    }

    /**
     * The Maven spy jar {@code jk mvn} attaches: found somewhere in its lookup order, or missing
     * — in which case Maven still runs, without a run report, and the fix is one command.
     */
    static Check checkMavenSpy(MavenSpyJar spy) {
        return spy.locate()
                .map(jar -> new Check(Status.OK, "mvn", "run-report extension at " + jar))
                .orElseGet(() -> new Check(
                        Status.WARN,
                        "mvn",
                        spy.jarName() + " not found — `jk mvn` runs Maven without a run report; run `jk mvn -v` once"
                                + " online to fetch it into " + spy.expected().getParent()
                                + " (from a checkout: `jk install`)"));
    }

    /**
     * The cache directory is created by the first command that resolves something, so a fresh
     * install has none and that is healthy — the row says so instead of failing a cold runner
     * before its first {@code jk sync}. A cache path that exists but is not a readable directory
     * is a real fault, as is a missing parent of the store or state directories.
     */
    static Check checkDirs(Path cache, Path store, Path state, JkCacheConfig cfg) {
        List<String> problems = new ArrayList<>();
        boolean cacheAbsent = !Files.exists(cache);
        if (!cacheAbsent && !Files.isDirectory(cache)) problems.add("cache is not a directory: " + cache);
        if (!cacheAbsent && Files.isDirectory(cache) && !Files.isReadable(cache)) {
            problems.add("cache is not readable: " + cache);
        }
        Path storeParent = store.getParent();
        Path stateParent = state.getParent();
        if (storeParent == null || !Files.isDirectory(storeParent)) problems.add("store parent missing");
        if (stateParent == null || !Files.isDirectory(stateParent)) problems.add("state parent missing");
        if (!problems.isEmpty()) return new Check(Status.FAIL, "dirs", String.join("; ", problems));
        String cacheWord =
                cacheAbsent ? "cache " + cache + " not created yet (the first resolve will)" : "cache " + cache;
        String detail = cacheWord + " · store " + store + " · " + JkCacheConfig.formatGb(cfg.maxCacheSizeGb())
                + "G cache budget · store unbudgeted";
        return new Check(Status.OK, "dirs", detail);
    }

    private static Check checkStateMode() {
        return checkStateMode(JkDirs.state());
    }

    /**
     * The engine socket under {@code <state>/engine} is trusted on directory permissions alone, so
     * a state or engine directory that lets group or others in is a finding, with the chmod that
     * closes it. Non-POSIX filesystems (Windows, where the loopback token gates the engine) are OK.
     */
    static Check checkStateMode(Path state) {
        Path engine = state.resolve("engine");
        List<String> loose = new ArrayList<>();
        for (Path dir : List.of(state, engine)) {
            if (!Files.isDirectory(dir)) continue;
            try {
                Set<PosixFilePermission> mode = Files.getPosixFilePermissions(dir);
                if (!OWNER_ONLY_DIR.equals(mode)) loose.add(dir + " is " + PosixFilePermissions.toString(mode));
            } catch (UnsupportedOperationException notPosix) {
                return new Check(Status.OK, "state", "no POSIX modes on this filesystem");
            } catch (IOException e) {
                return new Check(Status.WARN, "state", "probe failed: " + e.getMessage());
            }
        }
        if (loose.isEmpty()) return new Check(Status.OK, "state", state + " owner-only");
        return new Check(
                Status.FAIL,
                "state",
                String.join("; ", loose) + " — any local user can drive the engine socket; fix: chmod 700 " + state
                        + " " + engine);
    }

    private static final Set<PosixFilePermission> OWNER_ONLY_DIR = PosixFilePermissions.fromString("rwx------");

    private static Check checkJdk() {
        try {
            Path jdksDir = JkDirs.jdks();
            long count = 0;
            if (Files.isDirectory(jdksDir)) {
                // Closed, not left to the collector: the stream holds a directory handle, and on
                // Windows an open one refuses that directory its own delete.
                try (Stream<Path> installs = Files.list(jdksDir)) {
                    count = installs.count();
                }
            }
            String javaHome = System.getenv("JAVA_HOME");
            String detail = count + " installs under " + jdksDir + (javaHome != null ? " · JAVA_HOME=" + javaHome : "");
            return new Check(Status.OK, "jdk", detail);
        } catch (IOException e) {
            return new Check(Status.WARN, "jdk", "probe failed: " + e.getMessage());
        }
    }

    public static Check checkShell() {
        Path home = Path.of(System.getProperty("user.home", ""));
        return checkShell(
                home,
                Shell.live(System.getenv("SHELL"), PosixPasswd.loginShell().orElse(null), parentShellCommand()));
    }

    /**
     * Login and current shells only — not every rc greedy-activate would touch. Missing block is a
     * WARN; doctor never writes profiles.
     */
    public static Check checkShell(Path home, List<Shell> live) {
        if (live.isEmpty()) {
            return new Check(Status.OK, "shell", "no login or current shell to check");
        }
        List<String> hooked = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Shell shell : live) {
            String display = shell.rcFileDisplay();
            if (hasInstallerBlock(shell.rcFile(home))) {
                hooked.add(display);
            } else {
                missing.add(display);
            }
        }
        if (missing.isEmpty()) {
            return new Check(Status.OK, "shell", "hooks in " + String.join(", ", hooked));
        }
        String verb = missing.size() == 1 ? " has no jk block" : " have no jk block";
        String detail = String.join(", ", missing) + verb + " — run `jk activate`";
        if (!hooked.isEmpty()) {
            detail = "hooks in " + String.join(", ", hooked) + "; " + detail;
        }
        return new Check(Status.WARN, "shell", detail);
    }

    private static boolean hasInstallerBlock(Path rcFile) {
        try {
            return Files.isRegularFile(rcFile) && ShellInstallerBlock.present(Files.readString(rcFile));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * First ancestor that is not jk/java. {@code sh}/{@code dash} are filtered later in
     * {@link Shell#live} so a script wrapper is not treated as an interactive bash.
     */
    static @Nullable String parentShellCommand() {
        try {
            Optional<ProcessHandle> p = ProcessHandle.current().parent();
            int hops = 0;
            while (p.isPresent() && hops++ < 8) {
                ProcessHandle handle = p.get();
                String cmd = handle.info().command().orElse("");
                if (cmd.isBlank() || processWrapper(cmd)) {
                    p = handle.parent();
                    continue;
                }
                return cmd;
            }
        } catch (RuntimeException ignored) {
            // ProcessHandle is best-effort on some hosts
        }
        return null;
    }

    private static final Set<String> PROCESS_WRAPPERS =
            Set.of("java", "javaw", "jk", "jkx", "jk.cmd", "jk.bat", "sudo", "env", "nice", "nohup", "timeout", "time");

    private static boolean processWrapper(String command) {
        String base = command.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        String name = (slash >= 0 ? base.substring(slash + 1) : base).toLowerCase(Locale.ROOT);
        if (name.endsWith(".exe")) {
            name = name.substring(0, name.length() - 4);
        }
        return PROCESS_WRAPPERS.contains(name);
    }

    private static Check checkLock() {
        try {
            Path cwd = Path.of(System.getProperty("user.dir", "."));
            Path lock = LockPaths.lockFile(cwd);
            Path proj = lock.getParent();
            if (!Files.isRegularFile(lock))
                return new Check(Status.WARN, "lock", "no jk-lock.toml at " + proj + " (run jk lock)");
            String text = Files.readString(lock);
            if (!text.contains("version = 1")) return new Check(Status.WARN, "lock", "unexpected lock version");
            long artifacts = text.lines()
                    .filter(l -> l.trim().startsWith("[[artifact]]"))
                    .count();
            return new Check(Status.OK, "lock", artifacts + " artifacts · " + lock);
        } catch (IOException e) {
            return new Check(Status.WARN, "lock", "check failed: " + e.getMessage());
        }
    }

    /**
     * Scan every installed/linked tool, applying repair as it goes (unlink a broken symlink,
     * fingerprint a verified one) — the single pass both JSON and human output render from, so
     * {@code --output json} performs the same repair the human view reports.
     *
     * <p>Only a symlinked home is fingerprinted, because a link is the only install whose contents
     * jk does not control: {@code ToolProvisioning} links a discovered host install rather than
     * downloading one, and that tree can change underneath jk at any time. A directory jk installed
     * itself is {@code OK} without a digest. On Windows that means {@code --verify-linked}
     * fingerprints nothing, and deliberately so — {@code SymlinkProvisioner.canSymlink()} is false
     * there, so jk never creates a linked tool home in the first place and there is no drift to
     * detect. (A junction, which {@code Files.isSymbolicLink} also reports false for, is not
     * something jk creates either.)
     */
    private static List<ToolRow> scanTools(Path root, boolean verifyLinked) throws IOException {
        List<ToolRow> rows = new ArrayList<>();
        for (BuildTool tool : BuildTool.values()) {
            for (InstalledTool installed : listIncludingBrokenLinks(root, tool)) {
                Path home = installed.home();
                if (SymlinkProvisioner.isBrokenLink(home)) {
                    String wasPointingAt = readlinkSafe(home);
                    SymlinkProvisioner.unlink(home);
                    rows.add(new ToolRow(tool, installed, ToolRowKind.PRUNED, wasPointingAt));
                    continue;
                }
                if (Files.isSymbolicLink(home) && verifyLinked) {
                    rows.add(verifyLinkedTool(root, tool, installed, home));
                } else if (Files.isSymbolicLink(home)) {
                    rows.add(new ToolRow(tool, installed, ToolRowKind.LINKED, readlinkSafe(home)));
                } else {
                    rows.add(new ToolRow(tool, installed, ToolRowKind.OK, null));
                }
            }
        }
        return rows;
    }

    /**
     * Fingerprint one symlinked tool home and compare it against the digest the last run stored at
     * {@code <slug>/<version>.fingerprint}. Before that marker was written and never read by
     * anything in the tree, so {@code --verify-linked} verified nothing; the read is what makes the
     * write mean something.
     *
     * <p>The baseline is re-written on every verdict, drift included, so a change is reported once
     * and the next run is quiet. That matches what {@code scanTools} already is — a repair pass, not
     * a monitor — and doctor's exit code is deliberately unaffected either way.
     */
    private static ToolRow verifyLinkedTool(Path root, BuildTool tool, InstalledTool installed, Path home)
            throws IOException {
        String fingerprint = JdkFingerprint.compute(home);
        // Nothing was hashed. Report it and do NOT store it: a digest of no files is not a baseline
        // a later run should be able to match.
        if (JdkFingerprint.EMPTY_TREE.equals(fingerprint)) {
            return new ToolRow(tool, installed, ToolRowKind.EMPTY, readlinkSafe(home));
        }
        Path marker = root.resolve(tool.slug()).resolve(installed.version() + ".fingerprint");
        String baseline = Files.isRegularFile(marker) ? Files.readString(marker).strip() : null;
        Files.writeString(marker, fingerprint);
        if (baseline == null) return new ToolRow(tool, installed, ToolRowKind.FIRST_SEEN, fingerprint);
        if (baseline.equals(fingerprint)) return new ToolRow(tool, installed, ToolRowKind.VERIFIED, fingerprint);
        return new ToolRow(
                tool, installed, ToolRowKind.DRIFTED, short12(baseline) + "… → " + short12(fingerprint) + "…");
    }

    /** The leading 12 hex characters a digest is quoted by, tolerant of a short/absent value. */
    private static String short12(@Nullable String digest) {
        if (digest == null) return "?";
        return digest.length() <= 12 ? digest : digest.substring(0, 12);
    }

    /** One row per installed tool, or the one line that says why there are none. */
    private static void printTools(List<ToolRow> toolRows, @Nullable String toolsError, Theme t) {
        if (toolsError != null) {
            CliOutput.out(Theme.colorize("warn:    ", t.warning()) + Theme.colorize("tools", t.cyan())
                    + " — probe failed: " + toolsError);
        } else if (toolRows.isEmpty()) {
            // If no tools at all, emit a row so the checklist looks complete.
            CliOutput.out(Theme.colorize("ok:      ", t.completedStep()) + " tools — no installs found");
        } else {
            for (ToolRow row : toolRows) {
                String toolName = Theme.colorize(row.tool().slug(), t.cyan());
                String label = toolName + " " + row.installed().version();
                switch (row.kind()) {
                    case PRUNED ->
                        CliOutput.out(Theme.colorize("pruned:  ", t.warning()) + " " + label + " (link target missing: "
                                + Theme.colorize(row.detail(), t.path()) + ")");
                    case VERIFIED ->
                        CliOutput.out(Theme.colorize("verified:", t.completedStep()) + " " + label + " (sha256-tree="
                                + short12(row.detail()) + "…, unchanged)");
                    case FIRST_SEEN ->
                        CliOutput.out(Theme.colorize("recorded:", t.completedStep()) + " " + label + " (sha256-tree="
                                + short12(row.detail()) + "…, first fingerprint)");
                    case DRIFTED ->
                        CliOutput.out(Theme.colorize("drifted: ", t.warning()) + " " + label + " — link target changed "
                                + Theme.colorize(row.detail(), t.path()) + " (baseline updated)");
                    case EMPTY ->
                        CliOutput.out(Theme.colorize("warn:    ", t.warning()) + " " + label
                                + " — link target holds no files; there is nothing to fingerprint ("
                                + Theme.colorize(row.detail(), t.path()) + ")");
                    case LINKED ->
                        CliOutput.out("linked:   " + label
                                + " " + Theme.colorize("→", t.darkGray()) + " "
                                + Theme.colorize(row.detail(), t.path()));
                    case OK -> CliOutput.out(Theme.colorize("ok:      ", t.completedStep()) + " " + label);
                }
            }
        }
    }

    private static void printCheck(Check c, Theme t) {
        String prefix;
        switch (c.status) {
            case OK -> prefix = Theme.colorize("ok:      ", t.completedStep());
            case WARN -> prefix = Theme.colorize("warn:    ", t.warning());
            case FAIL -> prefix = Theme.colorize("fail:    ", t.error());
            default -> prefix = "";
        }
        CliOutput.out(prefix + Theme.colorize(c.label, t.cyan()) + " — " + c.detail);
    }

    /**
     * The `--output json` report: the six checks, the tool tallies and the scan error, then the
     * installed workers with their launch classpaths, the repository stores and Maven's settings.
     */
    public static String reportJson(
            Check engine,
            Check cache,
            Check state,
            Check jdk,
            Check lock,
            Check shell,
            Check mvn,
            int healthy,
            int pruned,
            int verified,
            int drifted,
            int firstSeen,
            int empty,
            @Nullable String toolsError,
            Workers workers,
            RepoStores.Stores repos,
            MavenSettingsRows.Rows settings) {
        return JsonFields.object()
                .token("engine", checkJson(engine))
                .token("cache", checkJson(cache))
                .token("state", checkJson(state))
                .token("jdk", checkJson(jdk))
                .token("lock", checkJson(lock))
                .token("shell", checkJson(shell))
                .token("mvn", checkJson(mvn))
                .token(
                        "tools",
                        JsonFields.object()
                                .number("healthy", healthy)
                                .number("pruned", pruned)
                                .number("verified", verified)
                                .number("drifted", drifted)
                                .number("firstSeen", firstSeen)
                                .number("empty", empty)
                                .string("error", toolsError)
                                .finish())
                .token("workers", workersJson(workers))
                .token("repos", RepoStores.json(repos))
                .token("settings", MavenSettingsRows.json(settings))
                .finish();
    }

    public static String checkJson(Check c) {
        return JsonFields.object()
                .string("status", c.status.name().toLowerCase(Locale.ROOT))
                .string("detail", c.detail)
                .finish();
    }

    private static String formatUptime(long secs) {
        if (secs < 60) return secs + "s";
        if (secs < 3600) return (secs / 60) + "m";
        return (secs / 3600) + "h" + ((secs % 3600) / 60) + "m";
    }

    private static List<InstalledTool> listIncludingBrokenLinks(Path root, BuildTool tool) throws IOException {
        return new ToolRegistry(root).list(tool, /* includeBrokenLinks */ true);
    }

    private static String readlinkSafe(Path link) {
        try {
            return Files.readSymbolicLink(link).toString();
        } catch (IOException e) {
            return "?";
        }
    }
}
