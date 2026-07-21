// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Debounced recursive directory watch for live loops ({@code jk watch}, {@code jk dev}). One
 * implementation for all verbs.
 *
 * <p>By default only {@code src/} and {@code test/} trees are watched (plus {@code jk.toml} at the
 * project root). Build outputs ({@code target/}, {@code out/}, {@code build/}), VCS dirs, and editor
 * junk are not registered — so they never wake the loop.
 */
public final class SourceWatch implements AutoCloseable {

    /** Coalesce editor save bursts (rename+write+chmod) into one notification. */
    public static final long DEBOUNCE_MILLIS = 150;

    private final WatchService watcher;
    private final Map<WatchKey, Path> keys = new HashMap<>();
    private final Path projectDir;
    private final long debounceMillis;

    private SourceWatch(WatchService watcher, Path projectDir, long debounceMillis) {
        this.watcher = watcher;
        this.projectDir = projectDir;
        this.debounceMillis = Math.max(0, debounceMillis);
    }

    /** Open a watch over {@code roots} (and the project dir for {@code jk.toml}). */
    public static SourceWatch open(Path projectDir, List<Path> roots) throws IOException {
        return open(projectDir, roots, DEBOUNCE_MILLIS);
    }

    /** Open a watch with a custom debounce window (milliseconds). */
    public static SourceWatch open(Path projectDir, List<Path> roots, long debounceMillis) throws IOException {
        WatchService ws = FileSystems.getDefault().newWatchService();
        SourceWatch sw = new SourceWatch(ws, projectDir, debounceMillis);
        for (Path root : roots) {
            if (Files.isDirectory(root)) sw.registerTree(root);
            else if (Files.isRegularFile(root) && root.getParent() != null) sw.registerDir(root.getParent());
        }
        sw.registerDir(projectDir);
        return sw;
    }

    /** Block until a relevant change. */
    public Changes awaitChange() throws IOException, InterruptedException {
        while (true) {
            WatchKey key = watcher.take();
            Changes c = drainEvents(key);
            if (c.any()) return c;
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
            if (c.any()) return Optional.of(c);
        }
    }

    @Override
    public void close() throws IOException {
        watcher.close();
    }

    private void registerTree(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                registerDir(dir);
            }
        }
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
                if (!(event.context() instanceof Path rel) || dir == null) continue;
                Path changed = dir.resolve(rel);
                String name = rel.getFileName().toString();
                if (dir.equals(projectDir)) {
                    if (name.equals("jk.toml")) manifest = true;
                    continue;
                }
                if (Files.isDirectory(changed) && event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    registerTree(changed);
                }
                String relPath;
                try {
                    relPath = projectDir.relativize(changed).toString().replace('\\', '/');
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (relPath.contains("/resources/")) {
                    resources = true;
                } else if (name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".kts")) {
                    sources = true;
                } else if (!Files.isDirectory(changed) && isInteresting(name)) {
                    resources = true;
                }
            }
            if (!key.reset()) keys.remove(key);
            long remaining = settleUntil - System.currentTimeMillis();
            key = remaining > 0 ? watcher.poll(remaining, TimeUnit.MILLISECONDS) : watcher.poll();
        }
        return new Changes(sources, resources, manifest);
    }

    private static boolean isInteresting(String name) {
        return !name.startsWith(".") && !name.endsWith(".class") && !name.endsWith("~");
    }

    /** Default source roots for a simple project layout. */
    public static List<Path> defaultRoots(Path projectDir) {
        return List.of(projectDir.resolve("src"), projectDir.resolve("test")).stream()
                .filter(Files::isDirectory)
                .toList();
    }
}
