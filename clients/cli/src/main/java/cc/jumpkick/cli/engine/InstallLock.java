// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.OwnerOnlyFiles;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.FileLockInterruptionException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The host-wide install lock: every session on a host shares one installed jk and the one resident
 * engine its state dir names, so a run that depends on that engine holds the lock shared for its
 * duration, and an install, a takeover or an engine stop holds it exclusively. An install waits for
 * the runs in progress to finish, and a run started during an install waits for it, so no run's
 * steps span two builds of the engine.
 *
 * <p>The lock is an OS byte-range lock on {@code <state>/engine/install.lock}: the kernel releases it
 * when its process dies, so a crashed holder never wedges the host. Who holds or waits is recorded
 * one file per hold under {@code <state>/engine/install.holders/}; a file whose process is gone is a
 * stale record and is pruned on the next read. Within one JVM, threads queue on a read-write lock in
 * front of the one channel (POSIX drops every lock a process holds on a file when any descriptor to
 * it closes). A thread that holds the lock shared and asks for it exclusively gives up its shared
 * holds while exclusive and takes them back after, never upgrading in place: two holders upgrading at
 * once would each wait for the other.
 *
 * <p>{@code JK_INSTALL_LOCK=off} turns the lock off for a process. A filesystem that refuses the lock
 * leaves the caller running unlocked, as it would have before the lock existed.
 */
public final class InstallLock {

    /** Environment switch: {@code off} skips the lock entirely. */
    public static final String ENV = "JK_INSTALL_LOCK";

    private static final long LOCK_POSITION = Long.MAX_VALUE - 1;

    private static final ConcurrentHashMap<Path, State> STATES = new ConcurrentHashMap<>();

    private static final AtomicLong SEQ = new AtomicLong();

    private static volatile Consumer<String> notices = CliOutput::err;

    private InstallLock() {}

    /** How a hold takes the lock, or waits for it. */
    public enum Mode {
        SHARED("shared"),
        EXCLUSIVE("exclusive"),
        WAITING_SHARED("waiting (shared)"),
        WAITING_EXCLUSIVE("waiting (exclusive)");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean waiting() {
            return this == WAITING_SHARED || this == WAITING_EXCLUSIVE;
        }
    }

    /** One live hold or wait, as its record names it. */
    public record Holder(long pid, Mode mode, String purpose, String cwd, Instant since) {

        /** One line for a notice or a status row: {@code pid 4242 jk snapshot (exclusive) in /x since 10:12:03}. */
        public String describe() {
            return "pid " + pid + " " + purpose + " (" + mode.label() + ") in " + cwd + " since " + since;
        }
    }

    /** A hold the caller closes when its run ends. */
    public interface Hold extends Closeable {
        @Override
        void close();
    }

    private static final Hold NOOP = () -> {};

    /** A hold that holds nothing, for a caller that takes the lock only sometimes. */
    public static Hold none() {
        return NOOP;
    }

    /** The lock file this process's state dir names. */
    public static Path lockFile() {
        return JkDirs.state().resolve("engine").resolve("install.lock");
    }

    private static Path holdersDir(Path lockFile) {
        return lockFile.resolveSibling("install.holders");
    }

    /** Hold the lock shared for a run that depends on the installed engine; waits out an install. */
    public static Hold shared(String purpose) throws IOException {
        return disabled() ? NOOP : shared(lockFile(), purpose);
    }

    static Hold shared(Path file, String purpose) throws IOException {
        State st = state(file);
        // A run nested under this thread's exclusive hold already has the lock to itself.
        if (st.jvm.isWriteLockedByCurrentThread()) return NOOP;
        st.jvm.readLock().lock();
        try {
            synchronized (st) {
                if (st.shared == 0) st.acquire(true, purpose);
                st.shared++;
            }
        } catch (IOException | RuntimeException e) {
            st.jvm.readLock().unlock();
            throw e;
        }
        Path record = record(file, Mode.SHARED, purpose);
        return new Hold() {
            private boolean open = true;

            @Override
            public synchronized void close() {
                if (!open) return;
                open = false;
                deleteQuietly(record);
                synchronized (st) {
                    if (--st.shared == 0) st.release();
                }
                st.jvm.readLock().unlock();
            }
        };
    }

    /**
     * Hold the lock exclusively, waiting for every shared run on the host to finish. The calling
     * thread's own shared holds are set aside while it is held and taken back on close.
     */
    public static Hold exclusive(String purpose) throws IOException {
        return disabled() ? NOOP : exclusive(lockFile(), purpose);
    }

    static Hold exclusive(Path file, String purpose) throws IOException {
        State st = state(file);
        if (st.jvm.isWriteLockedByCurrentThread()) return NOOP;
        int setAside = st.jvm.getReadHoldCount();
        if (setAside > 0) {
            synchronized (st) {
                st.shared -= setAside;
                if (st.shared == 0) st.release();
            }
            for (int i = 0; i < setAside; i++) st.jvm.readLock().unlock();
        }
        st.jvm.writeLock().lock();
        try {
            synchronized (st) {
                st.acquire(false, purpose);
            }
        } catch (IOException | RuntimeException e) {
            st.jvm.writeLock().unlock();
            restoreShared(st, setAside, purpose);
            throw e;
        }
        Path record = record(file, Mode.EXCLUSIVE, purpose);
        return new Hold() {
            private boolean open = true;

            @Override
            public synchronized void close() {
                if (!open) return;
                open = false;
                deleteQuietly(record);
                synchronized (st) {
                    st.release();
                }
                st.jvm.writeLock().unlock();
                try {
                    restoreShared(st, setAside, purpose);
                } catch (IOException e) {
                    // The run goes on unlocked rather than failing after its install succeeded.
                }
            }
        };
    }

    /** Take back the shared holds {@link #exclusive} set aside. */
    private static void restoreShared(State st, int count, String purpose) throws IOException {
        if (count == 0) return;
        for (int i = 0; i < count; i++) st.jvm.readLock().lock();
        synchronized (st) {
            if (st.shared == 0) st.acquire(true, purpose);
            st.shared += count;
        }
    }

    /** Every live hold and wait on this state dir's lock, holders first; prunes stale records. */
    public static List<Holder> holders() {
        return holders(lockFile());
    }

    static List<Holder> holders(Path lockFile) {
        Path dir = holdersDir(lockFile);
        if (!Files.isDirectory(dir)) return List.of();
        List<Holder> out = new ArrayList<>();
        try {
            PathUtil.forEachChild(dir, (f, attrs) -> {
                if (!f.getFileName().toString().endsWith(".hold")) return true;
                Optional<Holder> h = parse(f);
                if (h.isEmpty()) return true;
                if (alive(h.get().pid())) out.add(h.get());
                else deleteQuietly(f);
                return true;
            });
        } catch (IOException unreadable) {
            return List.of();
        }
        out.sort(Comparator.comparing((Holder h) -> h.mode().waiting()).thenComparing(Holder::since));
        return List.copyOf(out);
    }

    /** Route wait notices somewhere other than stderr (tests). */
    static void notices(Consumer<String> sink) {
        notices = sink;
    }

    private static boolean disabled() {
        return !EnvValues.bool(JkDirs::env, ENV).orElse(true);
    }

    private static State state(Path file) {
        return STATES.computeIfAbsent(file.toAbsolutePath().normalize(), State::new);
    }

    private static boolean alive(long pid) {
        if (pid == ProcessHandle.current().pid()) return true;
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** Write this process's record of a hold or wait; null when the state dir refuses it. */
    private static @Nullable Path record(Path lockFile, Mode mode, String purpose) {
        Path dir = holdersDir(lockFile);
        long pid = ProcessHandle.current().pid();
        Path f = dir.resolve(pid + "-" + SEQ.incrementAndGet() + ".hold");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("pid", Long.toString(pid));
        fields.put("mode", mode.name());
        fields.put("purpose", oneLine(purpose));
        fields.put("cwd", oneLine(System.getProperty("user.dir", "")));
        fields.put("since", Clock.SYSTEM.instant().toString());
        StringBuilder sb = new StringBuilder();
        fields.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        try {
            OwnerOnlyFiles.createDirectories(dir);
            OwnerOnlyFiles.writeString(f, sb.toString());
            return f;
        } catch (IOException | RuntimeException refused) {
            return null;
        }
    }

    private static String oneLine(String s) {
        return s.replace('\n', ' ').replace('\r', ' ');
    }

    static Optional<Holder> parse(Path f) {
        Map<String, String> kv = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(f)) {
                int eq = line.indexOf('=');
                if (eq > 0) kv.put(line.substring(0, eq), line.substring(eq + 1));
            }
            return Optional.of(new Holder(
                    Long.parseLong(kv.getOrDefault("pid", "")),
                    Mode.valueOf(kv.getOrDefault("mode", "")),
                    kv.getOrDefault("purpose", ""),
                    kv.getOrDefault("cwd", ""),
                    Instant.parse(kv.getOrDefault("since", ""))));
        } catch (IOException | IllegalArgumentException | DateTimeParseException torn) {
            // Mid-write or removed under us; its writer is either alive and rewrites nothing, or gone.
            return Optional.empty();
        }
    }

    private static void deleteQuietly(@Nullable Path f) {
        if (f == null) return;
        try {
            Files.deleteIfExists(f);
        } catch (IOException ignored) {
            // a stale record is pruned by the next reader once this process is gone
        }
    }

    /** One lock file as this JVM sees it. */
    private static final class State {
        final ReentrantReadWriteLock jvm = new ReentrantReadWriteLock(true);
        final Path file;

        /** Guarded by this: the open channel while the OS lock is held. */
        @Nullable
        FileChannel channel;

        @Nullable
        FileLock os;

        /** Guarded by this: shared holds in this JVM. */
        int shared;

        State(Path file) {
            this.file = file;
        }

        /**
         * Take the OS lock, shared or exclusive. A holder in another process makes this a wait,
         * announced once with who holds the lock; an interrupt propagates; a filesystem that refuses
         * locks leaves the caller unlocked.
         */
        void acquire(boolean shared, String purpose) throws IOException {
            try {
                Path parent = file.getParent();
                if (parent != null) OwnerOnlyFiles.createDirectories(parent);
                channel = OwnerOnlyFiles.inState(file)
                        ? OwnerOnlyFiles.channel(
                                file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ)
                        : FileChannel.open(
                                file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
            } catch (IOException | UnsupportedOperationException refused) {
                channel = null;
                return;
            }
            try {
                os = channel.tryLock(LOCK_POSITION, 1, shared);
                if (os != null) return;
                Mode waiting = shared ? Mode.WAITING_SHARED : Mode.WAITING_EXCLUSIVE;
                announceWait(purpose, shared);
                Path record = record(file, waiting, purpose);
                try {
                    os = channel.lock(LOCK_POSITION, 1, shared);
                } finally {
                    deleteQuietly(record);
                }
            } catch (FileLockInterruptionException | AsynchronousCloseException interrupted) {
                closeChannel();
                throw interrupted;
            } catch (IOException noLock) {
                closeChannel(); // e.g. NFS without lockd
            }
        }

        private void announceWait(String purpose, boolean shared) {
            List<Holder> holding =
                    holders(file).stream().filter(h -> !h.mode().waiting()).toList();
            String who = holding.isEmpty()
                    ? "another jk process"
                    : String.join("; ", holding.stream().map(Holder::describe).toList());
            String why = shared ? "an install or engine stop in progress" : "the jk runs using the engine";
            notices.accept(purpose + " waits for " + why + " (install lock held by " + who + ")");
        }

        void release() {
            try {
                if (os != null && os.isValid()) os.release();
            } catch (IOException ignored) {
                // closing the channel drops the lock regardless
            }
            os = null;
            closeChannel();
        }

        private void closeChannel() {
            FileChannel c = channel;
            channel = null;
            if (c == null) return;
            try {
                c.close();
            } catch (IOException ignored) {
                // nothing to do about a descriptor that will not close
            }
        }
    }
}
