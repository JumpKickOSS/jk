// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.WorkingDirectory;
import cc.jumpkick.layout.NodeProject;
import cc.jumpkick.layout.WalkSkip;
import cc.jumpkick.lock.ManifestPaths;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Debounced recursive directory watch for live loops ({@code jk watch}, {@code jk dev}). One
 * implementation for all verbs.
 *
 * <p>By default only {@code src/} and {@code test/} trees are watched (plus {@code jk.toml} at the
 * project root). Build outputs ({@code target/}, {@code out/}, {@code build/}), {@code
 * node_modules}, VCS dirs, editor junk and the {@code skipped} trees a caller names are not
 * registered — so they never wake the loop. A node module watches its whole directory, its root
 * files ({@code package.json}, a bundler's config) included.
 *
 * <p>A change it reports starts the loop's next pass ({@link SessionContext#nextPass}): what the
 * command read once, its keychain logins among them, is read again for the requests that pass
 * sends, so a login rotated or removed while the loop runs is what the next rebuild carries.
 */
public final class SourceWatch implements AutoCloseable {

    /** Coalesce editor save bursts (rename+write+chmod) into one notification. */
    public static final long DEBOUNCE_MILLIS = 150;

    private final WatchService watcher;
    private final Map<WatchKey, Path> keys = new HashMap<>();
    private final Path projectDir;
    private final long debounceMillis;
    private final Set<Path> skipped;

    /** The roots asked for, as absolute paths: one missing at open is watched once it appears. */
    private final List<Path> roots = new ArrayList<>();

    /** Directories watched only because a missing root will appear under them. */
    private final Set<Path> rootParents = new HashSet<>();

    /** The project directory is itself a root: its own files are sources, not only {@code jk.toml}. */
    private boolean wholeDir;

    private SourceWatch(WatchService watcher, Path projectDir, long debounceMillis, Set<Path> skipped) {
        this.watcher = watcher;
        this.projectDir = projectDir;
        this.debounceMillis = Math.max(0, debounceMillis);
        this.skipped = skipped;
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
        WatchService ws = FileSystems.getDefault().newWatchService();
        Set<Path> skip = new HashSet<>();
        for (Path p : skipped) skip.add(WorkingDirectory.resolve(p));
        SourceWatch sw = new SourceWatch(ws, projectDir, debounceMillis, skip);
        sw.wholeDir = roots.stream()
                .anyMatch(r -> WorkingDirectory.resolve(r).normalize().equals(WorkingDirectory.resolve(projectDir)));
        for (Path root : roots) {
            sw.roots.add(root.toAbsolutePath().normalize());
            if (Files.isDirectory(root)) sw.registerTree(root);
            else if (Files.isRegularFile(root) && root.getParent() != null) sw.registerDir(root.getParent());
            else sw.watchForRoot(root.toAbsolutePath().normalize());
        }
        sw.registerDir(projectDir);
        return sw;
    }

    /** Block until a relevant change. */
    public Changes awaitChange() throws IOException, InterruptedException {
        while (true) {
            WatchKey key = watcher.take();
            Changes c = drainEvents(key);
            if (c.any()) return nextPass(c);
        }
    }

    /**
     * Wait up to {@code timeout} for a change. Empty when the timeout elapses with nothing
     * interesting (caller can poll a child process, etc.).
     */
    public Optional<Changes> pollChange(long timeout, TimeUnit unit) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return Optional.empty();
            WatchKey key = watcher.poll(remaining, TimeUnit.NANOSECONDS);
            if (key == null) return Optional.empty();
            Changes c = drainEvents(key);
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
        if (!Files.isDirectory(dir)) return;
        WatchKey key = dir.register(
                watcher,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        keys.put(key, dir);
    }

    public record Changes(boolean sources, boolean resources, boolean manifest) {
        public boolean any() {
            return sources || resources || manifest;
        }
    }

    private Changes drainEvents(WatchKey first) throws IOException, InterruptedException {
        boolean sources = false, resources = false, manifest = false;
        WatchKey key = first;
        long settleUntil = System.currentTimeMillis() + debounceMillis;
        while (key != null) {
            Path dir = keys.get(key);
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    // Events were dropped: which files changed is unknown, so all of them did, and
                    // a directory created in the gap is registered now.
                    reregisterRoots();
                    sources = resources = manifest = true;
                    continue;
                }
                if (!(event.context() instanceof Path rel) || dir == null) continue;
                Path changed = dir.resolve(rel);
                boolean created = event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed);
                Changes c = created ? newDirectory(dir, changed) : classify(dir, changed);
                if (c == null) continue;
                sources |= c.sources();
                resources |= c.resources();
                manifest |= c.manifest();
            }
            if (!key.reset()) keys.remove(key);
            long remaining = settleUntil - System.currentTimeMillis();
            key = remaining > 0 ? watcher.poll(remaining, TimeUnit.MILLISECONDS) : watcher.poll();
        }
        return new Changes(sources, resources, manifest);
    }

    /**
     * A directory created under the watched {@code dir}: registered with everything under it, and
     * what its files ask for, since a file written before the registration raises no event. A
     * root missing at open, or a directory it will appear under, is taken up here too.
     */
    private @Nullable Changes newDirectory(Path dir, Path created) throws IOException {
        Path abs = created.toAbsolutePath().normalize();
        boolean underRoot = !rootParents.contains(dir.toAbsolutePath().normalize()) && classify(dir, created) != null;
        for (Path root : roots) {
            if (root.equals(abs)) {
                underRoot = true;
            } else if (root.startsWith(abs) && !skip(created)) {
                registerDir(created);
                rootParents.add(abs);
                Path below = abs.resolve(abs.relativize(root).getName(0));
                if (Files.isDirectory(below)) return newDirectory(created, below);
            }
        }
        if (!underRoot) return null;
        registerTree(created);
        boolean[] seen = new boolean[3];
        PathUtil.forEachEntry(created, this::skip, (path, attrs) -> {
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

    /** {@code root} does not exist yet: watch its nearest existing ancestor inside the project to see it made. */
    private void watchForRoot(Path root) throws IOException {
        Path project = projectDir.toAbsolutePath().normalize();
        Path at = root.getParent();
        while (at != null && at.startsWith(project) && !Files.isDirectory(at)) at = at.getParent();
        if (at == null || !at.startsWith(project) || at.equals(project)) return; // the project dir is watched anyway
        registerDir(at);
        rootParents.add(at);
    }

    private void reregisterRoots() throws IOException {
        for (Path root : roots) {
            if (Files.isDirectory(root)) registerTree(root);
        }
        registerDir(projectDir);
    }

    /** What a change to {@code changed}, an entry of the watched {@code dir}, asks for; null when it is ignored. */
    @Nullable
    Changes classify(Path dir, Path changed) {
        if (skip(changed)) return null;
        String name = changed.getFileName().toString();
        boolean manifest = dir.equals(projectDir) && name.equals(ManifestPaths.MANIFEST);
        Changes manifestOnly = manifest ? new Changes(false, false, true) : null;
        if (dir.equals(projectDir) && !wholeDir) return manifestOnly;
        String relPath;
        try {
            relPath = projectDir.relativize(changed).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return manifestOnly;
        }
        if (relPath.contains("/resources/")) return new Changes(false, true, manifest);
        if (name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".kts") || name.endsWith(".groovy")) {
            return new Changes(true, false, manifest);
        }
        return new Changes(false, !Files.isDirectory(changed) && isInteresting(name), manifest);
    }

    private static boolean isInteresting(String name) {
        return !name.startsWith(".") && !name.endsWith(".class") && !name.endsWith("~");
    }

    /** Default source roots for a simple project layout. */
    public static List<Path> defaultRoots(Path projectDir) {
        if (Files.isRegularFile(projectDir.resolve(NodeProject.PACKAGE_JSON))
                && !Files.isDirectory(projectDir.resolve("src/main"))) {
            return List.of(projectDir);
        }
        return List.of(projectDir.resolve("src"), projectDir.resolve("test").resolve("src")).stream()
                .filter(Files::isDirectory)
                .toList();
    }
}
