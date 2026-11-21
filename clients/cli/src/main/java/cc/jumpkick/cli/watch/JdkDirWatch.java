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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/** A {@link DirWatch} over the JDK's {@link WatchService}: one key per directory. */
final class JdkDirWatch implements DirWatch {

    private final WatchService watcher;
    private final Map<WatchKey, Path> keys = new HashMap<>();

    private JdkDirWatch(WatchService watcher) {
        this.watcher = watcher;
    }

    static JdkDirWatch open() throws IOException {
        return new JdkDirWatch(FileSystems.getDefault().newWatchService());
    }

    @Override
    public void register(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        WatchKey key = dir.register(
                watcher,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        keys.put(key, dir);
    }

    @Override
    public boolean watches(Path dir) {
        return keys.containsValue(dir.toAbsolutePath().normalize());
    }

    @Override
    public List<Change> take() throws InterruptedException {
        return changes(watcher.take());
    }

    @Override
    public @Nullable List<Change> poll(long nanos) throws InterruptedException {
        WatchKey key = nanos > 0 ? watcher.poll(nanos, TimeUnit.NANOSECONDS) : watcher.poll();
        return key == null ? null : changes(key);
    }

    private List<Change> changes(WatchKey key) {
        Path dir = keys.get(key);
        List<Change> out = new ArrayList<>();
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                out.add(Change.OVERFLOW);
            } else if (event.context() instanceof Path rel && dir != null) {
                out.add(new Change(event.kind(), dir, dir.resolve(rel)));
            }
        }
        if (!key.reset()) keys.remove(key);
        return out;
    }

    @Override
    public void close() throws IOException {
        watcher.close();
    }
}
