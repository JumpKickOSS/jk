// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import cc.jumpkick.host.time.Clock;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.FileObject;
import javax.tools.ForwardingFileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.ForwardingJavaFileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import org.jspecify.annotations.Nullable;

/**
 * What a compile asked the filesystem to do, counted and timed, when {@code JK_FILE_OPS} names a file
 * to append to.
 *
 * <p>A platform difference in compile time is only actionable once it is a count of operations times
 * a price per operation. The prices are known — on this host an open-and-close costs 92 µs on NTFS
 * against 4 µs on ext4, while appending into an already-open container costs about 2 µs on both — so
 * what is missing is the counts, and they can only be had from inside, because every file a compile
 * touches goes through the file manager.
 *
 * <p>Off unless the variable is set, and when off nothing is wrapped at all: {@link #wrap} hands back
 * the manager it was given, so a normal build pays one null check per compile rather than a delegate
 * on every file operation.
 *
 * <p>Totals are written per compile rather than accumulated for the process, because the worker is
 * force-killed at job teardown and a shutdown hook would never run.
 */
final class FileOps {

    private static final String ENV = "JK_FILE_OPS";

    private final @Nullable Path sink;
    private final Clock clock;

    /** Operation name to {@code {count, nanos}}, in first-seen order so the line reads consistently. */
    private final Map<String, long[]> tally = new LinkedHashMap<>();

    private FileOps(@Nullable Path sink, Clock clock) {
        this.sink = sink;
        this.clock = clock;
    }

    /** A recorder that writes where {@code JK_FILE_OPS} points, or one that does nothing. */
    static FileOps open() {
        String path = System.getenv(ENV);
        if (path == null || path.isBlank()) return new FileOps(null, Clock.SYSTEM);
        return new FileOps(Path.of(path.trim()), Clock.SYSTEM);
    }

    boolean enabled() {
        return sink != null;
    }

    /** The manager javac should use: instrumented when recording, otherwise the original. */
    JavaFileManager wrap(StandardJavaFileManager fm) {
        return sink == null ? fm : new CountingManager(fm);
    }

    /** Source units have to be wrapped too, or the reads that open them are invisible. */
    Iterable<? extends JavaFileObject> wrapUnits(Iterable<? extends JavaFileObject> units) {
        if (sink == null) return units;
        List<JavaFileObject> wrapped = new ArrayList<>();
        for (JavaFileObject unit : units) wrapped.add(new CountingJavaFileObject(unit));
        return wrapped;
    }

    private void add(String op, long nanos) {
        tally.computeIfAbsent(op, k -> new long[2])[0]++;
        tally.get(op)[1] += nanos;
    }

    private <T> T timed(String op, Call<T> call) throws IOException {
        long t0 = clock.nanos();
        try {
            return call.get();
        } finally {
            add(op, clock.nanos() - t0);
        }
    }

    private interface Call<T> {
        T get() throws IOException;
    }

    /**
     * Timing the open alone would miss most of the cost. Windows blocks in the close while the
     * filesystem filter stack finishes with the file, so on NTFS the close is the expensive half of
     * producing a file and has to be counted separately from the open.
     */
    private OutputStream timingClose(OutputStream out) {
        return new FilterOutputStream(out) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                long t0 = clock.nanos();
                try {
                    super.close();
                } finally {
                    add("closeOutputStream", clock.nanos() - t0);
                }
            }
        };
    }

    private InputStream timingClose(InputStream in) {
        return new FilterInputStream(in) {
            @Override
            public void close() throws IOException {
                long t0 = clock.nanos();
                try {
                    super.close();
                } finally {
                    add("closeInputStream", clock.nanos() - t0);
                }
            }
        };
    }

    /**
     * Append one line for {@code module}. Best-effort for the same reason {@link CompilePhases} is: a
     * measurement must never be the thing that fails a compile.
     */
    void write(Path classOutput) {
        if (sink == null || tally.isEmpty()) return;
        StringBuilder line = new StringBuilder("module=").append(classOutput);
        for (Map.Entry<String, long[]> e : tally.entrySet()) {
            line.append(' ')
                    .append(e.getKey())
                    .append('=')
                    .append(e.getValue()[0])
                    .append('/')
                    .append(e.getValue()[1] / 1_000_000)
                    .append("ms");
        }
        try {
            Path parent = sink.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(
                    sink,
                    line.append('\n').toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // A measurement artifact is never worth failing a build for.
        }
    }

    /**
     * Unwrapping before forwarding is not optional: javac's own file manager downcasts the file
     * objects it is handed back — {@code inferBinaryName} and {@code contains} both do — so a
     * delegate that passes its wrapper through makes the compile fail rather than slow.
     */
    private static FileObject unwrap(FileObject file) {
        if (file instanceof CountingJavaFileObject counting) return counting.delegate();
        if (file instanceof CountingFileObject counting) return counting.delegate();
        return file;
    }

    private static JavaFileObject unwrap(JavaFileObject file) {
        return file instanceof CountingJavaFileObject counting ? counting.delegate() : file;
    }

    private final class CountingManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

        CountingManager(StandardJavaFileManager fm) {
            super(fm);
        }

        @Override
        public Iterable<JavaFileObject> list(
                Location location, String pkg, Set<JavaFileObject.Kind> kinds, boolean recurse) throws IOException {
            Iterable<JavaFileObject> listed = timed("list", () -> super.list(location, pkg, kinds, recurse));
            List<JavaFileObject> wrapped = new ArrayList<>();
            for (JavaFileObject file : listed) wrapped.add(new CountingJavaFileObject(file));
            return wrapped;
        }

        @Override
        public @Nullable JavaFileObject getJavaFileForInput(
                Location location, String className, JavaFileObject.Kind kind) throws IOException {
            JavaFileObject file =
                    timed("getJavaFileForInput", () -> super.getJavaFileForInput(location, className, kind));
            return file == null ? null : new CountingJavaFileObject(file);
        }

        @Override
        public @Nullable JavaFileObject getJavaFileForOutput(
                Location location, String className, JavaFileObject.Kind kind, @Nullable FileObject sibling)
                throws IOException {
            FileObject bare = sibling == null ? null : unwrap(sibling);
            JavaFileObject file =
                    timed("getJavaFileForOutput", () -> super.getJavaFileForOutput(location, className, kind, bare));
            return file == null ? null : new CountingJavaFileObject(file);
        }

        @Override
        public @Nullable FileObject getFileForInput(Location location, String pkg, String name) throws IOException {
            FileObject file = timed("getFileForInput", () -> super.getFileForInput(location, pkg, name));
            return file == null ? null : new CountingFileObject(file);
        }

        @Override
        public @Nullable FileObject getFileForOutput(
                Location location, String pkg, String name, @Nullable FileObject sibling) throws IOException {
            FileObject bare = sibling == null ? null : unwrap(sibling);
            FileObject file = timed("getFileForOutput", () -> super.getFileForOutput(location, pkg, name, bare));
            return file == null ? null : new CountingFileObject(file);
        }

        @Override
        public String inferBinaryName(Location location, JavaFileObject file) {
            long t0 = clock.nanos();
            try {
                return super.inferBinaryName(location, unwrap(file));
            } finally {
                add("inferBinaryName", clock.nanos() - t0);
            }
        }

        @Override
        public boolean contains(Location location, FileObject file) throws IOException {
            return timed("contains", () -> super.contains(location, unwrap(file)));
        }

        @Override
        public boolean isSameFile(FileObject a, FileObject b) {
            long t0 = clock.nanos();
            try {
                return super.isSameFile(unwrap(a), unwrap(b));
            } finally {
                add("isSameFile", clock.nanos() - t0);
            }
        }
    }

    /** Times the operations that actually touch the disk, which the manager itself never does. */
    private final class CountingJavaFileObject extends ForwardingJavaFileObject<JavaFileObject> {

        CountingJavaFileObject(JavaFileObject delegate) {
            super(delegate);
        }

        JavaFileObject delegate() {
            return fileObject;
        }

        @Override
        public InputStream openInputStream() throws IOException {
            return timingClose(timed("openInputStream", super::openInputStream));
        }

        @Override
        public OutputStream openOutputStream() throws IOException {
            return timingClose(timed("openOutputStream", super::openOutputStream));
        }

        @Override
        public Reader openReader(boolean ignoreEncodingErrors) throws IOException {
            return timed("openReader", () -> super.openReader(ignoreEncodingErrors));
        }

        @Override
        public Writer openWriter() throws IOException {
            return timed("openWriter", super::openWriter);
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
            return timed("getCharContent", () -> super.getCharContent(ignoreEncodingErrors));
        }

        @Override
        public long getLastModified() {
            long t0 = clock.nanos();
            try {
                return super.getLastModified();
            } finally {
                add("getLastModified", clock.nanos() - t0);
            }
        }

        @Override
        public boolean delete() {
            long t0 = clock.nanos();
            try {
                return super.delete();
            } finally {
                add("delete", clock.nanos() - t0);
            }
        }
    }

    private final class CountingFileObject extends ForwardingFileObject<FileObject> {

        CountingFileObject(FileObject delegate) {
            super(delegate);
        }

        FileObject delegate() {
            return fileObject;
        }

        @Override
        public InputStream openInputStream() throws IOException {
            return timingClose(timed("openInputStream", super::openInputStream));
        }

        @Override
        public OutputStream openOutputStream() throws IOException {
            return timingClose(timed("openOutputStream", super::openOutputStream));
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
            return timed("getCharContent", () -> super.getCharContent(ignoreEncodingErrors));
        }

        @Override
        public long getLastModified() {
            long t0 = clock.nanos();
            try {
                return super.getLastModified();
            } finally {
                add("getLastModified", clock.nanos() - t0);
            }
        }
    }
}
