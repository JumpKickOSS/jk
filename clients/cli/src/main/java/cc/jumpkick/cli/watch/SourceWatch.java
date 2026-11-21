// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.WorkingDirectory;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.NodeProject;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.layout.WalkSkip;
import cc.jumpkick.lock.ManifestPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Debounced recursive directory watch for live loops ({@code jk watch}, {@code jk dev}). One
 * implementation for all verbs; the host's {@link DirWatch} reports the changes, FSEvents on macOS.
 *
 * <p>By default only the module's source and resource trees are watched ({@link #defaultRoots}),
 * plus {@code jk.toml} at the project root, at the workspace root that lists it, and in every module
 * a root outside the project lies in ({@link #outerManifests}). In the simple layout a test suite made while the loop
 * runs is taken up once it holds sources ({@link #openDefault}). Build outputs ({@code target/},
 * {@code out/}, {@code build/}), {@code node_modules}, VCS dirs, editor junk and the {@code
 * skipped} trees a caller names are not registered — so they never wake the loop. A node module watches its whole directory, its root
 * files ({@code package.json}, a bundler's config) included.
 *
 * <p>A change it reports starts the loop's next pass ({@link SessionContext#nextPass}): what the
 * command read once, its keychain logins among them, is read again for the requests that pass
 * sends, so a login rotated or removed while the loop runs is what the next rebuild carries.
 */
public final class SourceWatch implements AutoCloseable {

    /** Coalesce editor save bursts (rename+write+chmod) into one notification. */
    public static final long DEBOUNCE_MILLIS = 150;

    /**
     * The longest a burst that never goes quiet holds a pass back, from its first event; a longer
     * debounce window stretches it to {@link #MAX_WAIT_WINDOWS} windows.
     */
    static final long MAX_WAIT_MILLIS = 2_000;

    /** How many debounce windows a burst may last when that is longer than {@link #MAX_WAIT_MILLIS}. */
    static final long MAX_WAIT_WINDOWS = 4;

    private final DirWatch watcher;
    private final Path projectDir;
    private final long debounceMillis;
    private final Set<Path> skipped;
    private final Clock clock;

    /** The roots asked for, as absolute paths: one missing at open is watched once it appears. */
    private final List<Path> roots = new ArrayList<>();

    /**
     * Directories between the project and a root, watched so a root deleted and made again (a
     * checkout, a {@code rm -rf} and restore) is seen to appear and is watched anew; and those
     * holding one of the {@link #manifests}.
     */
    private final Set<Path> rootParents = new HashSet<>();

    /**
     * The {@code jk.toml} files beyond the project's own whose edit is a manifest change, as
     * absolute paths. Their directories are watched for them alone.
     */
    private final Set<Path> manifests = new HashSet<>();

    /** The project directory is itself a root: its own files are sources, not only {@code jk.toml}. */
    private boolean wholeDir;

    /** The module a test suite may appear in while the loop runs; null when none is looked for. */
    private @Nullable Path suiteHome;

    /**
     * Directories of {@link #suiteHome} named as a suite may be that hold no sources yet: each is
     * watched with its {@code src/} tree, and wakes the loop only once it holds sources.
     */
    private final Set<Path> maybeSuites = new HashSet<>();

    private SourceWatch(DirWatch watcher, Path projectDir, long debounceMillis, Set<Path> skipped, Clock clock) {
        this.watcher = watcher;
        this.projectDir = projectDir;
        this.debounceMillis = Math.max(0, debounceMillis);
        this.skipped = skipped;
        this.clock = clock;
    }

    /** Open a watch over {@code roots} (and the project dir for {@code jk.toml}). */
    public static SourceWatch open(Path projectDir, List<Path> roots) throws IOException {
        return open(projectDir, roots, DEBOUNCE_MILLIS);
    }

    /** Open a watch with a custom debounce window (milliseconds). */
    public static SourceWatch open(Path projectDir, List<Path> roots, long debounceMillis) throws IOException {
        return open(projectDir, roots, debounceMillis, List.of());
    }

    /** As {@link #open(Path, List, long)}, never registering a tree under {@code skipped}. */
    public static SourceWatch open(Path projectDir, List<Path> roots, long debounceMillis, List<Path> skipped)
            throws IOException {
        List<Path> watched = new ArrayList<>();
        for (Path root : roots) watched.add(WorkingDirectory.resolve(root));
        watched.add(WorkingDirectory.resolve(projectDir));
        DirWatch ws = DirWatch.open(watched);
        Set<Path> skip = new HashSet<>();
        for (Path p : skipped) skip.add(WorkingDirectory.resolve(p));
        SourceWatch sw = new SourceWatch(ws, projectDir, debounceMillis, skip, Clock.SYSTEM);
        sw.wholeDir = roots.stream()
                .anyMatch(r -> WorkingDirectory.resolve(r).normalize().equals(WorkingDirectory.resolve(projectDir)));
        for (Path root : roots) {
            sw.roots.add(root.toAbsolutePath().normalize());
            if (Files.isRegularFile(root) && root.getParent() != null) {
                sw.registerDir(root.getParent());
                continue;
            }
            if (Files.isDirectory(root)) sw.registerTree(root);
            sw.anchor(root.toAbsolutePath().normalize());
        }
        sw.registerDir(projectDir);
        for (Path manifest : outerManifests(projectDir, roots)) {
            Path dir = Objects.requireNonNull(manifest.getParent());
            sw.manifests.add(manifest);
            sw.registerDir(dir);
            sw.rootParents.add(dir);
        }
        return sw;
    }

    /**
     * The {@code jk.toml} files beyond the project's own that its build reads: the workspace root's
     * that lists the project, and that of each module a root outside the project lies in.
     */
    static Set<Path> outerManifests(Path projectDir, List<Path> roots) {
        Path project = WorkingDirectory.resolve(projectDir).normalize();
        Set<Path> out = new LinkedHashSet<>();
        WorkspaceScan.findRoot(project).ifPresent(r -> out.add(ManifestPaths.manifestIn(r.normalize())));
        for (Path root : roots) {
            Path abs = WorkingDirectory.resolve(root).normalize();
            if (abs.startsWith(project)) continue;
            for (Path at = abs.getParent(); at != null && !project.startsWith(at); at = at.getParent()) {
                Path manifest = ManifestPaths.manifestIn(at);
                if (Files.isRegularFile(manifest)) {
                    out.add(manifest);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Open a watch over {@link #defaultRoots}, never registering a tree under {@code skipped}. In
     * the simple layout a directory of the module named as a test suite may be is watched too, and
     * once it holds sources its {@code src/} and {@code resources/} are roots, so a suite made while
     * the loop runs is tested without a restart; a stray file in it wakes nothing.
     */
    public static SourceWatch openDefault(Path projectDir, long debounceMillis, List<Path> skipped) throws IOException {
        List<Path> roots = defaultRoots(projectDir);
        SourceWatch sw = open(projectDir, roots, debounceMillis, skipped);
        if (!sw.wholeDir && ModuleLayout.isCompact(projectDir)) {
            sw.suiteHome = projectDir.toAbsolutePath().normalize();
            sw.lookForSuites();
        }
        return sw;
    }

    /** Block until a relevant change. */
    public Changes awaitChange() throws IOException, InterruptedException {
        while (true) {
            Changes c = drainEvents(watcher.take());
            if (c.any()) return nextPass(c);
        }
    }

    /**
     * Wait up to {@code timeout} for a change. Empty when the timeout elapses with nothing
     * interesting (caller can poll a child process, etc.).
     */
    public Optional<Changes> pollChange(long timeout, TimeUnit unit) throws IOException, InterruptedException {
        long deadline = Clock.SYSTEM.nanos() + unit.toNanos(timeout);
        while (true) {
            long remaining = deadline - Clock.SYSTEM.nanos();
            if (remaining <= 0) return Optional.empty();
            List<DirWatch.Change> batch = watcher.poll(remaining);
            if (batch == null) return Optional.empty();
            Changes c = drainEvents(batch);
            if (c.any()) return Optional.of(nextPass(c));
        }
    }

    private static Changes nextPass(Changes changes) {
        SessionContext.nextPass();
        return changes;
    }

    @Override
    public void close() throws IOException {
        watcher.close();
    }

    private void registerTree(Path root) throws IOException {
        if (skip(root)) return;
        PathUtil.forEachEntry(root, this::skip, (path, attrs) -> {
            if (attrs.isDirectory()) registerDir(path);
            return true;
        });
    }

    /** A tree no change under wakes the loop: build output, an install, VCS, or one the caller named. */
    private boolean skip(Path dir) {
        return WalkSkip.workspaceKey(dir) || skipped.contains(WorkingDirectory.resolve(dir));
    }

    private void registerDir(Path dir) throws IOException {
        watcher.register(dir);
    }

    public record Changes(boolean sources, boolean resources, boolean manifest) {
        public boolean any() {
            return sources || resources || manifest;
        }
    }

    private Changes drainEvents(List<DirWatch.Change> first) throws IOException, InterruptedException {
        boolean sources = false, resources = false, manifest = false;
        List<DirWatch.Change> batch = first;
        Settle settle = new Settle(clock, debounceMillis);
        while (batch != null) {
            for (DirWatch.Change event : batch) {
                Path dir = event.dir(), changed = event.entry();
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    // Events were dropped: which files changed is unknown, so all of them did, and
                    // a directory created in the gap is registered now.
                    reregisterRoots();
                    sources = resources = manifest = true;
                    continue;
                }
                if (dir == null || changed == null) continue;
                Changes c = event(dir, event.kind(), changed);
                if (c == null || !c.any()) continue;
                settle.event();
                sources |= c.sources();
                resources |= c.resources();
                manifest |= c.manifest();
            }
            batch = watcher.poll(settle.remainingNanos());
        }
        return new Changes(sources, resources, manifest);
    }

    /**
     * What one event for {@code changed}, an entry of the watched {@code dir}, asks for; null when
     * it is ignored. Under a directory watched only to see a root appear, only a new directory counts.
     */
    @Nullable
    Changes event(Path dir, WatchEvent.Kind<?> kind, Path changed) throws IOException {
        if (manifests.contains(changed.toAbsolutePath().normalize())) return new Changes(false, false, true);
        Path maybeSuite = maybeSuiteOf(dir);
        if (maybeSuite != null) return maybeSuiteEvent(maybeSuite, kind, changed);
        boolean created = kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed);
        if (created) return newDirectory(dir, changed);
        return anchorOnly(dir) ? null : classify(dir, changed);
    }

    /**
     * True when {@code dir} is watched only because a root lies below it or a manifest is in it,
     * not because it is in a root.
     */
    private boolean anchorOnly(Path dir) {
        Path abs = dir.toAbsolutePath().normalize();
        return rootParents.contains(abs) && roots.stream().noneMatch(abs::startsWith);
    }

    /** Whether {@code dir} is registered with the watch service now. */
    boolean watches(Path dir) {
        return watcher.watches(dir);
    }

    /**
     * A directory created under the watched {@code dir}: registered with everything under it, and
     * what its files ask for, since a file written before the registration raises no event. A
     * root missing at open, or a directory it will appear under, is taken up here too, as is a
     * directory of the module named as a test suite may be.
     */
    private @Nullable Changes newDirectory(Path dir, Path created) throws IOException {
        Path abs = created.toAbsolutePath().normalize();
        boolean underRoot = !anchorOnly(dir) && classify(dir, created) != null;
        Set<Path> below = new LinkedHashSet<>();
        for (Path root : roots) {
            if (root.equals(abs)) {
                underRoot = true;
            } else if (root.startsWith(abs) && !skip(created)) {
                registerDir(created);
                rootParents.add(abs);
                below.add(abs.resolve(abs.relativize(root).getName(0)));
            }
        }
        if (underRoot) return takeUp(created);
        if (below.isEmpty()
                && suiteHome != null
                && suiteHome.equals(dir.toAbsolutePath().normalize())) {
            return maybeSuite(abs);
        }
        Changes found = null;
        for (Path next : below) {
            if (Files.isDirectory(next)) found = merge(found, newDirectory(created, next));
        }
        return found;
    }

    /** Register {@code tree} with everything under it, and say what the files already in it ask for. */
    private Changes takeUp(Path tree) throws IOException {
        registerTree(tree);
        boolean[] seen = new boolean[3];
        PathUtil.forEachEntry(tree, this::skip, (path, attrs) -> {
            if (!attrs.isRegularFile() || path.getParent() == null) return true;
            Changes c = classify(path.getParent(), path);
            if (c != null) {
                seen[0] |= c.sources();
                seen[1] |= c.resources();
                seen[2] |= c.manifest();
            }
            return true;
        });
        return new Changes(seen[0], seen[1], seen[2]);
    }

    private static @Nullable Changes merge(@Nullable Changes a, @Nullable Changes b) {
        if (a == null) return b;
        if (b == null) return a;
        return new Changes(a.sources() || b.sources(), a.resources() || b.resources(), a.manifest() || b.manifest());
    }

    /** Take up each directory of {@link #suiteHome} named as a suite may be that no root covers yet. */
    private void lookForSuites() throws IOException {
        if (suiteHome == null) return;
        PathUtil.forEachChild(suiteHome, (child, attrs) -> {
            if (attrs.isDirectory()) maybeSuite(child.toAbsolutePath().normalize());
            return true;
        });
    }

    /**
     * {@code dir}, a directory of {@link #suiteHome}: when named as a suite may be and no root covers
     * it, a suite now if it holds sources, else watched until it does. What its files ask for once
     * it is a suite; null while it is not.
     */
    private @Nullable Changes maybeSuite(Path dir) throws IOException {
        Path home = suiteHome;
        if (home == null || skip(dir) || roots.stream().anyMatch(r -> r.startsWith(dir))) return null;
        if (!TestSuites.maySimpleSuiteDir(home, dir)) return null;
        String suite = dir.getFileName().toString();
        if (TestSuites.holdsSources(home, true, suite)) return addSuite(dir);
        maybeSuites.add(dir);
        registerDir(dir);
        Path src = TestSuites.simpleSuiteSrc(home, suite);
        if (Files.isDirectory(src)) registerTree(src);
        return null;
    }

    /** The directory watched in case it becomes a suite that {@code dir} is or lies in; null when none. */
    private @Nullable Path maybeSuiteOf(Path dir) {
        Path abs = dir.toAbsolutePath().normalize();
        for (Path suite : maybeSuites) {
            if (abs.startsWith(suite)) return suite;
        }
        return null;
    }

    /**
     * An event in {@code suite}, a directory not yet a suite, or its {@code src/} tree: a directory
     * made in the tree is watched, and the loop wakes only when the event leaves the suite holding
     * sources.
     */
    private @Nullable Changes maybeSuiteEvent(Path suite, WatchEvent.Kind<?> kind, Path changed) throws IOException {
        Path home = Objects.requireNonNull(suiteHome);
        String name = suite.getFileName().toString();
        Path src = TestSuites.simpleSuiteSrc(home, name).toAbsolutePath().normalize();
        Path abs = changed.toAbsolutePath().normalize();
        if (kind == StandardWatchEventKinds.ENTRY_CREATE && abs.startsWith(src) && Files.isDirectory(abs)) {
            registerTree(abs);
        }
        return TestSuites.holdsSources(home, true, name) ? addSuite(suite) : null;
    }

    /** {@code suite} holds sources: its trees become roots, watched, and what their files ask for. */
    private Changes addSuite(Path suite) throws IOException {
        maybeSuites.remove(suite);
        Changes found = null;
        for (Path root : ModuleLayout.suiteWatchDirs(
                Objects.requireNonNull(suiteHome), suite.getFileName().toString())) {
            roots.add(root);
            anchor(root);
            if (Files.isDirectory(root)) found = merge(found, takeUp(root));
        }
        return found == null ? new Changes(false, false, false) : found;
    }

    /**
     * Watch every existing directory between {@code root} and the project, so the root is seen to
     * appear, or to come back after it or a directory above it is deleted and made again. A root
     * outside the project, a sibling module's, is watched up to the directory holding both (the
     * workspace root), which a module made again appears in.
     */
    private void anchor(Path root) throws IOException {
        Path project = projectDir.toAbsolutePath().normalize();
        Path top = anchorTop(project, root);
        for (Path at = root.getParent(); at != null && at.startsWith(top) && !at.equals(project); at = at.getParent()) {
            if (Files.isDirectory(at) && (at.startsWith(project) || !skip(at))) {
                registerDir(at);
                rootParents.add(at);
            }
        }
    }

    /**
     * How far up {@link #anchor} watches for {@code root}: the project for a root in it, else the
     * nearest directory holding both, or only the root's parent when they share no more than a
     * filesystem root.
     */
    private static Path anchorTop(Path project, Path root) {
        if (root.startsWith(project)) return project;
        Path top = project.getParent();
        while (top != null && !root.startsWith(top)) top = top.getParent();
        if (top == null || top.getParent() == null) {
            Path parent = root.getParent();
            return parent == null ? root : parent;
        }
        return top;
    }

    private void reregisterRoots() throws IOException {
        for (Path root : List.copyOf(roots)) {
            if (Files.isDirectory(root)) registerTree(root);
        }
        registerDir(projectDir);
        lookForSuites();
    }

    /** What a change to {@code changed}, an entry of the watched {@code dir}, asks for; null when it is ignored. */
    @Nullable
    Changes classify(Path dir, Path changed) {
        if (skip(changed)) return null;
        String name = changed.getFileName().toString();
        boolean manifest = dir.equals(projectDir) && name.equals(ManifestPaths.MANIFEST);
        Changes manifestOnly = manifest ? new Changes(false, false, true) : null;
        if (dir.equals(projectDir) && !wholeDir) return manifestOnly;
        // A file in a resource tree is a resource whatever its name: a .java template there is copied,
        // not compiled.
        if (inResourceTree(changed)) return new Changes(false, true, manifest);
        if (name.endsWith(".java")
                || name.endsWith(".kt")
                || name.endsWith(".kts")
                || name.endsWith(".groovy")
                || name.endsWith(".scala")) {
            return new Changes(true, false, manifest);
        }
        return new Changes(false, !Files.isDirectory(changed) && isInteresting(name), manifest);
    }

    /**
     * Whether {@code changed} sits in a resource tree, told by the root holding it: a simple-layout
     * root is one when it is a {@code resources/} tree, and in a traditional module's {@code src/}
     * the trees are {@code src/<set>/resources/}. A source in a package named {@code resources} is
     * not in one.
     */
    private boolean inResourceTree(Path changed) {
        Path abs = changed.toAbsolutePath().normalize();
        Path root = holdingRoot(abs);
        Path name = root == null ? null : root.getFileName();
        if (root == null || name == null) return false;
        if (name.toString().equals("resources")) return true;
        Path module = root.getParent();
        if (!name.toString().equals("src") || module == null || !isModule(module) || ModuleLayout.isCompact(module)) {
            return false;
        }
        Path rel = root.relativize(abs);
        return rel.getNameCount() > 2 && rel.getName(1).toString().equals("resources");
    }

    /** The innermost root holding {@code abs}, an absolute normalized path; null when none does. */
    private @Nullable Path holdingRoot(Path abs) {
        Path found = null;
        for (Path r : roots) {
            if (abs.startsWith(r) && (found == null || r.getNameCount() > found.getNameCount())) found = r;
        }
        return found;
    }

    /** Whether {@code dir} is a module's directory, not a simple-layout suite's such as {@code test/}. */
    private boolean isModule(Path dir) {
        return dir.equals(projectDir.toAbsolutePath().normalize())
                || Files.isRegularFile(ManifestPaths.manifestIn(dir));
    }

    private static boolean isInteresting(String name) {
        return !name.startsWith(".") && !name.endsWith(".class") && !name.endsWith("~");
    }

    /**
     * When a burst of events is over: once it has been quiet for the debounce window since its last
     * relevant event (the trailing edge), or at the latest {@link #MAX_WAIT_MILLIS} (or {@link
     * #MAX_WAIT_WINDOWS} windows, when longer) after the burst began, so a tree that never stops
     * changing still gets its pass. Read from the monotonic clock: a wall-clock step cannot stretch
     * or cut the wait.
     */
    static final class Settle {
        private final Clock clock;
        private final long quietNanos;
        private final long deadline;
        private long last;

        Settle(Clock clock, long debounceMillis) {
            this.clock = clock;
            this.quietNanos = TimeUnit.MILLISECONDS.toNanos(debounceMillis);
            this.last = clock.nanos();
            long capMillis = Math.max(MAX_WAIT_MILLIS, MAX_WAIT_WINDOWS * debounceMillis);
            this.deadline = last + TimeUnit.MILLISECONDS.toNanos(capMillis);
        }

        /** A relevant event arrived now: the quiet window starts over. */
        void event() {
            last = clock.nanos();
        }

        /** How long to wait for the next event; zero or less when the burst is over. */
        long remainingNanos() {
            return Math.min(last + quietNanos, deadline) - clock.nanos();
        }
    }

    /**
     * The roots {@code jk watch} watches by default: the module's sources and resources, its tests'
     * among them, made yet or not ({@link ModuleLayout#watchDirs}); a node project's whole directory.
     */
    public static List<Path> defaultRoots(Path projectDir) {
        if (Files.isRegularFile(projectDir.resolve(NodeProject.PACKAGE_JSON))
                && !Files.isDirectory(projectDir.resolve("src/main"))) {
            return List.of(projectDir);
        }
        return ModuleLayout.watchDirs(projectDir, false);
    }
}
