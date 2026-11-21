// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * A {@link DirWatch} over macOS FSEvents, bound through FFM: a change reaches the loop in tens of
 * milliseconds, where the JDK's watch service on macOS polls every 2 s.
 *
 * <p>One stream watches the fewest directories that cover every registered one, recursively, with
 * per-file events; a change is reported only when its parent directory is registered, as the JDK
 * reports it only for a registered directory. FSEvents names real paths ({@code /private/var}, not
 * {@code /var}), so each registered directory is keyed by its real path and reported as it was
 * registered. FSEvents coalesces a file's flags across events, so the kind is read from the disk: an
 * entry gone is deleted, one flagged created or renamed is created unless it is a directory already
 * watched, and any other is modified. Events FSEvents dropped, or a tree it says to rescan that
 * holds a watched directory, are an {@link StandardWatchEventKinds#OVERFLOW}.
 *
 * <p>A stream also delivers what happened just before it started, and two streams overlap while
 * one replaces the other. So each entry's status-change time is kept from when its directory was
 * registered and from its last change, and an event that finds it unchanged is not reported, as
 * the JDK reports no change made before a directory was registered.
 *
 * <p>FSEvents calls back on a dispatch queue's thread; the callback only queues the paths and
 * flags, and the thread that waits reads them, so the registered directories are touched by that
 * thread alone.
 */
final class FsEventsWatch implements DirWatch {

    /** Only this spelling finds the framework: it lives in the dyld shared cache, not on disk. */
    private static final String CORE_SERVICES = "/System/Library/Frameworks/CoreServices.framework/CoreServices";

    private static final String CORE_FOUNDATION = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";

    /** {@code kCFStringEncodingUTF8}. */
    private static final int UTF8 = 0x08000100;

    /** {@code kFSEventStreamEventIdSinceNow}. */
    private static final long SINCE_NOW = -1L;

    /** How long FSEvents holds events back to coalesce them, in seconds; the loop debounces after. */
    private static final double LATENCY_SECONDS = 0.01;

    /** {@code kFSEventStreamCreateFlagNoDefer | kFSEventStreamCreateFlagFileEvents}. */
    private static final int CREATE_FLAGS = 0x02 | 0x10;

    static final int MUST_SCAN_SUB_DIRS = 0x01;
    static final int USER_DROPPED = 0x02;
    static final int KERNEL_DROPPED = 0x04;
    static final int ITEM_CREATED = 0x100;
    static final int ITEM_MODIFIED = 0x1000;
    static final int ITEM_RENAMED = 0x800;

    /**
     * The callback: {@code (ConstFSEventStreamRef, void *info, size_t numEvents, char **paths, const
     * FSEventStreamEventFlags *flags, const FSEventStreamEventId *ids)}.
     */
    static final FunctionDescriptor CALLBACK =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS);

    /** The downcalls, each also registered for the native image in this module's reachability metadata. */
    static final FunctionDescriptor STRING_CREATE = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT);

    static final FunctionDescriptor ARRAY_CREATE = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS);
    static final FunctionDescriptor STREAM_CREATE =
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_DOUBLE, JAVA_INT);
    static final FunctionDescriptor SET_QUEUE = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
    static final FunctionDescriptor START = FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS);
    static final FunctionDescriptor RELEASE = FunctionDescriptor.ofVoid(ADDRESS);
    static final FunctionDescriptor QUEUE_CREATE = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);

    /** The open watches by the id each stream carries as its {@code info}: the callback's way back. */
    private static final Map<Long, FsEventsWatch> LIVE = new ConcurrentHashMap<>();

    private static final AtomicLong IDS = new AtomicLong();

    /** One path and its flags as FSEvents reported them. */
    record Raw(String path, int flags) {}

    private final long id = IDS.incrementAndGet();
    private final Fse fse;
    private final BlockingQueue<List<Raw>> queue = new LinkedBlockingQueue<>();

    /** Each registered directory by its real path, as it was registered. */
    private final Map<Path, Path> byReal = new HashMap<>();

    /** The status-change time of each entry of a registered directory, by its real path, as last seen. */
    private final Map<Path, FileTime> seen = new HashMap<>();

    /** The real directories the stream watches, none under another. */
    private List<Path> streamRoots = List.of();

    private MemorySegment stream = MemorySegment.NULL;
    private volatile boolean closed;

    private FsEventsWatch(Fse fse) {
        this.fse = fse;
    }

    /** A watch over FSEvents; null when it cannot be bound, so the caller falls back to the JDK's. */
    static @Nullable FsEventsWatch open() {
        Fse fse = bind();
        if (fse == null) return null;
        FsEventsWatch watch = new FsEventsWatch(fse);
        LIVE.put(watch.id, watch);
        return watch;
    }

    @Override
    public void register(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        Path real = dir.toRealPath();
        byReal.put(real, dir);
        PathUtil.forEachChild(real, (entry, attrs) -> {
            FileTime changed = statusChanged(entry);
            if (changed != null && changed != UNREAD) seen.put(entry, changed);
            return true;
        });
        if (streamRoots.stream().anyMatch(real::startsWith)) return;
        List<Path> roots = new ArrayList<>();
        roots.add(real);
        for (Path r : streamRoots) {
            if (!r.startsWith(real)) roots.add(r);
        }
        restart(roots);
    }

    @Override
    public boolean watches(Path dir) {
        return byReal.containsValue(dir.toAbsolutePath().normalize());
    }

    @Override
    public List<Change> take() throws InterruptedException {
        while (true) {
            List<Change> changes = changes(queue.take());
            if (!changes.isEmpty()) return changes;
        }
    }

    @Override
    public @Nullable List<Change> poll(long nanos) throws InterruptedException {
        long deadline = Clock.SYSTEM.nanos() + nanos;
        while (true) {
            long remaining = deadline - Clock.SYSTEM.nanos();
            List<Raw> batch = remaining > 0 ? queue.poll(remaining, TimeUnit.NANOSECONDS) : queue.poll();
            if (batch == null) return null;
            List<Change> changes = changes(batch);
            if (!changes.isEmpty()) return changes;
        }
    }

    /** What a batch from FSEvents means for the registered directories. */
    List<Change> changes(List<Raw> batch) {
        if (closed) throw new ClosedWatchServiceException();
        List<Change> out = new ArrayList<>();
        for (Raw raw : batch) {
            if ((raw.flags() & (USER_DROPPED | KERNEL_DROPPED)) != 0) {
                out.add(Change.OVERFLOW);
                continue;
            }
            Path path = Path.of(raw.path());
            if ((raw.flags() & MUST_SCAN_SUB_DIRS) != 0) {
                if (byReal.keySet().stream().anyMatch(d -> d.startsWith(path))) out.add(Change.OVERFLOW);
                continue;
            }
            Change change = change(path, raw.flags());
            if (change != null) out.add(change);
        }
        return out;
    }

    /**
     * What one event at {@code path} means: a change to an entry of its registered parent, and a
     * registered directory at or under it forgotten when it is gone. The disk is read only for those,
     * so the churn of a build's output under the stream costs no more than a lookup.
     */
    private @Nullable Change change(Path path, int flags) {
        Path parent = path.getParent();
        Path dir = parent == null ? null : byReal.get(parent);
        if (dir == null && byReal.keySet().stream().noneMatch(d -> d.startsWith(path))) return null;
        FileTime changed = statusChanged(path);
        boolean exists = changed != null;
        if (!exists) {
            byReal.keySet().removeIf(d -> d.startsWith(path));
            seen.keySet().removeIf(e -> e.startsWith(path));
        }
        Path name = path.getFileName();
        if (dir == null || name == null) return null;
        if (changed != null && changed != UNREAD && changed.equals(seen.put(path, changed))) return null;
        boolean created = (flags & (ITEM_CREATED | ITEM_RENAMED)) != 0 && !byReal.containsKey(path);
        WatchEvent.Kind<Path> kind = !exists
                ? StandardWatchEventKinds.ENTRY_DELETE
                : created ? StandardWatchEventKinds.ENTRY_CREATE : StandardWatchEventKinds.ENTRY_MODIFY;
        return new Change(kind, dir, dir.resolve(name.toString()));
    }

    /** An entry whose status-change time cannot be read: every event for it is reported. */
    private static final FileTime UNREAD = FileTime.fromMillis(Long.MIN_VALUE);

    /** When {@code path}'s status last changed; null when it is gone, {@link #UNREAD} when unreadable. */
    private static @Nullable FileTime statusChanged(Path path) {
        try {
            return (FileTime) Files.getAttribute(path, "unix:ctime", LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException | RuntimeException e) {
            return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? UNREAD : null;
        }
    }

    /** Watch {@code roots} with a new stream, started before the old one stops so no event falls between. */
    private synchronized void restart(List<Path> roots) throws IOException {
        if (closed) throw new ClosedWatchServiceException();
        MemorySegment next;
        try {
            next = createStream(roots);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("FSEvents could not watch " + roots, t);
        }
        stopStream();
        stream = next;
        streamRoots = List.copyOf(roots);
    }

    private MemorySegment createStream(List<Path> roots) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment values = arena.allocate(ADDRESS, roots.size());
            List<MemorySegment> strings = new ArrayList<>();
            try {
                for (Path root : roots) {
                    MemorySegment s = (MemorySegment)
                            fse.stringCreate.invokeExact(MemorySegment.NULL, arena.allocateFrom(root.toString()), UTF8);
                    if (s.equals(MemorySegment.NULL)) throw new IOException("FSEvents could not name " + root);
                    values.setAtIndex(ADDRESS, strings.size(), s);
                    strings.add(s);
                }
                MemorySegment paths = (MemorySegment) fse.arrayCreate.invokeExact(
                        MemorySegment.NULL, values, (long) roots.size(), fse.arrayCallBacks);
                if (paths.equals(MemorySegment.NULL)) throw new IOException("FSEvents could not list " + roots);
                try {
                    // FSEventStreamContext: version, info, retain, release, copyDescription.
                    MemorySegment context = arena.allocate(JAVA_LONG, 5);
                    context.setAtIndex(JAVA_LONG, 1, id);
                    MemorySegment created = (MemorySegment) fse.streamCreate.invokeExact(
                            MemorySegment.NULL, fse.callback, context, paths, SINCE_NOW, LATENCY_SECONDS, CREATE_FLAGS);
                    if (created.equals(MemorySegment.NULL)) throw new IOException("FSEvents could not watch " + roots);
                    fse.setDispatchQueue.invokeExact(created, fse.queue);
                    if (!(boolean) fse.start.invokeExact(created)) {
                        fse.invalidate.invokeExact(created);
                        fse.streamRelease.invokeExact(created);
                        throw new IOException("FSEvents could not start watching " + roots);
                    }
                    return created;
                } finally {
                    fse.cfRelease.invokeExact(paths);
                }
            } finally {
                for (MemorySegment s : strings) fse.cfRelease.invokeExact(s);
            }
        }
    }

    private void stopStream() {
        MemorySegment old = stream;
        stream = MemorySegment.NULL;
        if (old.equals(MemorySegment.NULL)) return;
        try {
            fse.stop.invokeExact(old);
            fse.invalidate.invokeExact(old);
            fse.streamRelease.invokeExact(old);
        } catch (Throwable t) {
            Log.debug("FSEvents stream did not stop", t);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        LIVE.remove(id);
        stopStream();
        queue.add(List.of());
    }

    /**
     * FSEvents' callback: queues each path and its flags for the watch the stream belongs to. It
     * never throws, since an upcall that throws ends the process; a batch it cannot read is a
     * dropped one.
     */
    @SuppressWarnings("restricted")
    private static void onEvents(
            MemorySegment stream,
            MemorySegment info,
            long count,
            MemorySegment paths,
            MemorySegment flags,
            MemorySegment ids) {
        FsEventsWatch watch = LIVE.get(info.address());
        if (watch == null) return;
        try {
            MemorySegment pathArray = paths.reinterpret(count * ADDRESS.byteSize());
            MemorySegment flagArray = flags.reinterpret(count * JAVA_INT.byteSize());
            List<Raw> batch = new ArrayList<>((int) count);
            for (long i = 0; i < count; i++) {
                String path = pathArray
                        .getAtIndex(ADDRESS, i)
                        .reinterpret(Long.MAX_VALUE)
                        .getString(0);
                batch.add(new Raw(path, flagArray.getAtIndex(JAVA_INT, i)));
            }
            watch.queue.add(batch);
        } catch (Throwable t) {
            watch.queue.add(List.of(new Raw("/", USER_DROPPED)));
        }
    }

    /** The bound entry points, the callback's stub and the queue every stream calls back on. */
    private record Fse(
            MethodHandle stringCreate,
            MethodHandle arrayCreate,
            MemorySegment arrayCallBacks,
            MethodHandle streamCreate,
            MethodHandle setDispatchQueue,
            MethodHandle start,
            MethodHandle stop,
            MethodHandle invalidate,
            MethodHandle streamRelease,
            MethodHandle cfRelease,
            MemorySegment callback,
            MemorySegment queue) {}

    /**
     * Bound on first use, never at class initialization: a native image refuses FFM handles made
     * while it is built.
     */
    private static volatile @Nullable Fse bound;

    private static volatile boolean bindAttempted;

    @SuppressWarnings("restricted")
    private static @Nullable Fse bind() {
        if (bindAttempted) return bound;
        synchronized (FsEventsWatch.class) {
            if (bindAttempted) return bound;
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup cs = SymbolLookup.libraryLookup(CORE_SERVICES, Arena.global());
                SymbolLookup cf = SymbolLookup.libraryLookup(CORE_FOUNDATION, Arena.global());
                MethodHandle target = MethodHandles.lookup()
                        .findStatic(
                                FsEventsWatch.class,
                                "onEvents",
                                MethodType.methodType(
                                        void.class,
                                        MemorySegment.class,
                                        MemorySegment.class,
                                        long.class,
                                        MemorySegment.class,
                                        MemorySegment.class,
                                        MemorySegment.class));
                MethodHandle queueCreate = linker.downcallHandle(
                        linker.defaultLookup().findOrThrow("dispatch_queue_create"), QUEUE_CREATE);
                MemorySegment queue = (MemorySegment)
                        queueCreate.invokeExact(Arena.global().allocateFrom("cc.jumpkick.watch"), MemorySegment.NULL);
                if (queue.equals(MemorySegment.NULL)) throw new IllegalStateException("no dispatch queue");
                bound = new Fse(
                        linker.downcallHandle(cf.findOrThrow("CFStringCreateWithCString"), STRING_CREATE),
                        linker.downcallHandle(cf.findOrThrow("CFArrayCreate"), ARRAY_CREATE),
                        cf.findOrThrow("kCFTypeArrayCallBacks"),
                        linker.downcallHandle(cs.findOrThrow("FSEventStreamCreate"), STREAM_CREATE),
                        linker.downcallHandle(cs.findOrThrow("FSEventStreamSetDispatchQueue"), SET_QUEUE),
                        linker.downcallHandle(cs.findOrThrow("FSEventStreamStart"), START),
                        linker.downcallHandle(cs.findOrThrow("FSEventStreamStop"), RELEASE),
                        linker.downcallHandle(cs.findOrThrow("FSEventStreamInvalidate"), RELEASE),
                        linker.downcallHandle(cs.findOrThrow("FSEventStreamRelease"), RELEASE),
                        linker.downcallHandle(cf.findOrThrow("CFRelease"), RELEASE),
                        linker.upcallStub(target, CALLBACK, Arena.global()),
                        queue);
            } catch (Throwable t) {
                Log.debug("FSEvents unavailable; the watch polls", t);
            } finally {
                bindAttempted = true;
            }
            return bound;
        }
    }
}
