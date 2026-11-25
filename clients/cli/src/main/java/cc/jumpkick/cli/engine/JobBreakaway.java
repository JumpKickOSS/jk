// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Starts the engine outside the Windows job its client runs in. An ssh session on Windows runs in a
 * job that kills every process in it when the session ends ({@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE},
 * seen with Windows OpenSSH), and a child joins its parent's job, so an engine an agent, a CI step
 * or a remote shell started died with that shell. {@link ProcessBuilder} cannot ask {@code
 * CreateProcess} to break away, so a client in a job starts the engine itself: {@code
 * CREATE_BREAKAWAY_FROM_JOB}, no window, the builder's command, environment and directory, its
 * output appended to (or written over) the log, input from {@code NUL}, and only those two handles
 * inherited ({@code PROC_THREAD_ATTRIBUTE_HANDLE_LIST}), so the engine holds nothing else of the
 * client's. A job that does not allow breakaway, a client in no job, or any other OS starts it as
 * before.
 */
final class JobBreakaway {

    private JobBreakaway() {}

    private static final int JOB_OBJECT_LIMIT_BREAKAWAY_OK = 0x800;
    private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9;
    private static final int CREATE_BREAKAWAY_FROM_JOB = 0x01000000;
    private static final int CREATE_UNICODE_ENVIRONMENT = 0x400;
    private static final int EXTENDED_STARTUPINFO_PRESENT = 0x80000;
    private static final int CREATE_NO_WINDOW = 0x08000000;
    private static final int STARTF_USESTDHANDLES = 0x100;
    private static final long PROC_THREAD_ATTRIBUTE_HANDLE_LIST = 0x20002L;
    private static final int GENERIC_READ = 0x80000000;
    private static final int GENERIC_WRITE = 0x40000000;
    private static final int FILE_APPEND_DATA = 0x4;
    private static final int SHARE_ALL = 0x7;
    private static final int CREATE_ALWAYS = 2;
    private static final int OPEN_EXISTING = 3;
    private static final int OPEN_ALWAYS = 4;
    private static final int STILL_ACTIVE = 259;

    /**
     * {@code pb} started, its output going to {@code log} (appended when {@code append}); outside the
     * client's job when the client is in one that allows it, else by {@code fallback}.
     */
    static Process start(ProcessBuilder pb, Path log, boolean append, Starter fallback) throws IOException {
        if (!Os.isWindows()) return fallback.start(pb);
        Kernel32 k = Kernel32.get();
        if (k == null) {
            fellBack = "kernel32 calls unavailable: " + Kernel32.unbound;
            return fallback.start(pb);
        }
        if (!k.inBreakableJob()) {
            fellBack = "not in a job that allows breakaway";
            return fallback.start(pb);
        }
        Path dir = pb.directory() == null ? null : pb.directory().toPath();
        fellBack = null;
        Process p = k.create(pb.command(), pb.environment(), dir, log, append);
        return p != null ? p : fallback.start(pb);
    }

    /** Why the last start fell back to {@code fallback}, for the engine log and tests; null when it did not. */
    static volatile @Nullable String fellBack;

    /** The start a client in no breakable job makes. */
    interface Starter {
        Process start(ProcessBuilder pb) throws IOException;
    }

    /** {@code arg} quoted as {@code CommandLineToArgvW} reads it back. */
    static String quote(String arg) {
        if (!arg.isEmpty() && arg.chars().noneMatch(c -> c == ' ' || c == '\t' || c == '\n' || c == 0x0b || c == '"')) {
            return arg;
        }
        StringBuilder b = new StringBuilder("\"");
        int slashes = 0;
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '\\') {
                slashes++;
                continue;
            }
            if (c == '"') {
                b.append("\\".repeat(slashes * 2 + 1)).append('"');
            } else {
                b.append("\\".repeat(slashes)).append(c);
            }
            slashes = 0;
        }
        return b.append("\\".repeat(slashes * 2)).append('"').toString();
    }

    /** The command line for {@code command}. */
    static String commandLine(List<String> command) {
        StringBuilder b = new StringBuilder();
        for (String arg : command) {
            if (!b.isEmpty()) b.append(' ');
            b.append(quote(arg));
        }
        return b.toString();
    }

    /** A Unicode environment block: {@code name=value} entries sorted case-blind, each NUL-ended, then a NUL. */
    static String environmentBlock(Map<String, String> env) {
        TreeMap<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        sorted.putAll(env);
        StringBuilder b = new StringBuilder();
        sorted.forEach((k, v) -> b.append(k).append('=').append(v).append('\0'));
        if (sorted.isEmpty()) b.append('\0');
        return b.append('\0').toString();
    }

    /** The kernel32 calls, bound once; null where they cannot be. */
    private record Kernel32(
            MethodHandle getCurrentProcess,
            MethodHandle isProcessInJob,
            MethodHandle queryJob,
            MethodHandle createFile,
            MethodHandle initAttributes,
            MethodHandle updateAttribute,
            MethodHandle deleteAttributes,
            MethodHandle createProcess,
            MethodHandle closeHandle,
            MethodHandle exitCode,
            StructLayout callState,
            VarHandle lastError) {

        private static volatile @Nullable Kernel32 bound;
        private static volatile boolean tried;
        static volatile String unbound = "";

        static @Nullable Kernel32 get() {
            if (!tried) {
                synchronized (Kernel32.class) {
                    if (!tried) {
                        bound = bind();
                        tried = true;
                    }
                }
            }
            return bound;
        }

        private static @Nullable Kernel32 bind() {
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
                Linker.Option capture = Linker.Option.captureCallState("GetLastError");
                StructLayout state = Linker.Option.captureStateLayout();
                ValueLayout a = ValueLayout.ADDRESS;
                ValueLayout i = ValueLayout.JAVA_INT;
                ValueLayout l = ValueLayout.JAVA_LONG;
                return new Kernel32(
                        linker.downcallHandle(k32.findOrThrow("GetCurrentProcess"), FunctionDescriptor.of(a)),
                        linker.downcallHandle(k32.findOrThrow("IsProcessInJob"), FunctionDescriptor.of(i, a, a, a)),
                        linker.downcallHandle(
                                k32.findOrThrow("QueryInformationJobObject"), FunctionDescriptor.of(i, a, i, a, i, a)),
                        linker.downcallHandle(
                                k32.findOrThrow("CreateFileW"), FunctionDescriptor.of(a, a, i, i, a, i, i, a), capture),
                        linker.downcallHandle(
                                k32.findOrThrow("InitializeProcThreadAttributeList"),
                                FunctionDescriptor.of(i, a, i, i, a)),
                        linker.downcallHandle(
                                k32.findOrThrow("UpdateProcThreadAttribute"),
                                FunctionDescriptor.of(i, a, i, l, a, l, a, a)),
                        linker.downcallHandle(
                                k32.findOrThrow("DeleteProcThreadAttributeList"), FunctionDescriptor.ofVoid(a)),
                        linker.downcallHandle(
                                k32.findOrThrow("CreateProcessW"),
                                FunctionDescriptor.of(i, a, a, a, a, i, i, a, a, a, a),
                                capture),
                        linker.downcallHandle(k32.findOrThrow("CloseHandle"), FunctionDescriptor.of(i, a)),
                        linker.downcallHandle(k32.findOrThrow("GetExitCodeProcess"), FunctionDescriptor.of(i, a, a)),
                        state,
                        state.varHandle(MemoryLayout.PathElement.groupElement("GetLastError")));
            } catch (Throwable unavailable) {
                unbound = String.valueOf(unavailable);
                return null;
            }
        }

        /** Whether this process is in a job that lets a child break away. */
        boolean inBreakableJob() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment self = (MemorySegment) getCurrentProcess.invokeExact();
                MemorySegment in = arena.allocate(ValueLayout.JAVA_INT);
                if ((int) isProcessInJob.invokeExact(self, MemorySegment.NULL, in) == 0) return false;
                if (in.get(ValueLayout.JAVA_INT, 0) == 0) return false;
                // JOBOBJECT_EXTENDED_LIMIT_INFORMATION: 144 bytes on x64, LimitFlags at 16.
                MemorySegment info = arena.allocate(144, 8);
                if ((int) queryJob.invokeExact(
                                MemorySegment.NULL,
                                JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
                                info,
                                (int) info.byteSize(),
                                MemorySegment.NULL)
                        == 0) {
                    return false;
                }
                return (info.get(ValueLayout.JAVA_INT, 16) & JOB_OBJECT_LIMIT_BREAKAWAY_OK) != 0;
            } catch (Throwable unavailable) {
                return false;
            }
        }

        /** Starts {@code command} as described above; null when Windows refused (the caller falls back). */
        @Nullable
        Process create(List<String> command, Map<String, String> env, @Nullable Path dir, Path log, boolean append) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment state = arena.allocate(callState);
                // SECURITY_ATTRIBUTES { nLength, lpSecurityDescriptor, bInheritHandle = TRUE }
                MemorySegment sa = arena.allocate(24, 8);
                sa.set(ValueLayout.JAVA_INT, 0, 24);
                sa.set(ValueLayout.JAVA_INT, 16, 1);
                MemorySegment out = (MemorySegment) createFile.invokeExact(
                        state,
                        wide(arena, log.toString()),
                        append ? FILE_APPEND_DATA : GENERIC_WRITE,
                        SHARE_ALL,
                        sa,
                        append ? OPEN_ALWAYS : CREATE_ALWAYS,
                        0x80,
                        MemorySegment.NULL);
                if (invalid(out)) {
                    fellBack = "could not open the log (error " + lastError.get(state, 0L) + ")";
                    return null;
                }
                MemorySegment in = (MemorySegment) createFile.invokeExact(
                        state,
                        wide(arena, "NUL"),
                        GENERIC_READ,
                        SHARE_ALL,
                        sa,
                        OPEN_EXISTING,
                        0x80,
                        MemorySegment.NULL);
                if (invalid(in)) {
                    close(out);
                    return null;
                }
                try {
                    MemorySegment handles = arena.allocate(16, 8);
                    handles.set(ValueLayout.ADDRESS, 0, in);
                    handles.set(ValueLayout.ADDRESS, 8, out);
                    MemorySegment size = arena.allocate(ValueLayout.JAVA_LONG);
                    int ignored = (int) initAttributes.invokeExact(MemorySegment.NULL, 1, 0, size);
                    MemorySegment attributes = arena.allocate(size.get(ValueLayout.JAVA_LONG, 0), 8);
                    if ((int) initAttributes.invokeExact(attributes, 1, 0, size) == 0) return null;
                    try {
                        if ((int) updateAttribute.invokeExact(
                                        attributes,
                                        0,
                                        PROC_THREAD_ATTRIBUTE_HANDLE_LIST,
                                        handles,
                                        16L,
                                        MemorySegment.NULL,
                                        MemorySegment.NULL)
                                == 0) {
                            return null;
                        }
                        // STARTUPINFOEXW: 112 bytes on x64.
                        MemorySegment si = arena.allocate(112, 8);
                        si.set(ValueLayout.JAVA_INT, 0, 112);
                        si.set(ValueLayout.JAVA_INT, 60, STARTF_USESTDHANDLES);
                        si.set(ValueLayout.ADDRESS, 80, in);
                        si.set(ValueLayout.ADDRESS, 88, out);
                        si.set(ValueLayout.ADDRESS, 96, out);
                        si.set(ValueLayout.ADDRESS, 104, attributes);
                        MemorySegment pi = arena.allocate(24, 8);
                        MemorySegment application = Path.of(command.getFirst()).isAbsolute()
                                ? wide(arena, command.getFirst())
                                : MemorySegment.NULL;
                        MemorySegment line = wide(arena, commandLine(command));
                        MemorySegment environment = wide(arena, environmentBlock(env));
                        MemorySegment directory = dir == null ? MemorySegment.NULL : wide(arena, dir.toString());
                        int flags = CREATE_BREAKAWAY_FROM_JOB
                                | CREATE_UNICODE_ENVIRONMENT
                                | EXTENDED_STARTUPINFO_PRESENT
                                | CREATE_NO_WINDOW;
                        int ok = (int) createProcess.invokeExact(
                                state,
                                application,
                                line,
                                MemorySegment.NULL,
                                MemorySegment.NULL,
                                1,
                                flags,
                                environment,
                                directory,
                                si,
                                pi);
                        if (ok == 0) {
                            fellBack = "CreateProcessW failed (error " + lastError.get(state, 0L) + ")";
                            return null;
                        }
                        MemorySegment process = pi.get(ValueLayout.ADDRESS, 0);
                        close(pi.get(ValueLayout.ADDRESS, 8));
                        long pid = Integer.toUnsignedLong(pi.get(ValueLayout.JAVA_INT, 16));
                        return new Started(this, process, pid);
                    } finally {
                        deleteAttributes.invokeExact(attributes);
                    }
                } finally {
                    close(in);
                    close(out);
                }
            } catch (Throwable failed) {
                fellBack = "native start failed: " + failed;
                return null;
            }
        }

        private static boolean invalid(MemorySegment h) {
            return h.address() == 0 || h.address() == -1L;
        }

        void close(MemorySegment h) {
            try {
                int ignored = (int) closeHandle.invokeExact(h);
            } catch (Throwable unclosable) {
                // nothing more to do
            }
        }

        /** The exit code of {@code process}, or {@link #STILL_ACTIVE}. */
        int exitCodeOf(MemorySegment process) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment code = arena.allocate(ValueLayout.JAVA_INT);
                if ((int) exitCode.invokeExact(process, code) == 0) return STILL_ACTIVE;
                return code.get(ValueLayout.JAVA_INT, 0);
            } catch (Throwable unavailable) {
                return STILL_ACTIVE;
            }
        }

        private static MemorySegment wide(Arena arena, String s) {
            byte[] bytes = (s + '\0').getBytes(StandardCharsets.UTF_16LE);
            MemorySegment m = arena.allocate(bytes.length, 2);
            m.copyFrom(MemorySegment.ofArray(bytes));
            return m;
        }
    }

    /** The engine this started: no streams (its output is the log), its exit code from its handle. */
    private static final class Started extends Process {
        private final Kernel32 k;
        private final MemorySegment process;
        private final long pid;

        Started(Kernel32 k, MemorySegment process, long pid) {
            this.k = k;
            this.process = process;
            this.pid = pid;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            while (isAlive()) TimeUnit.MILLISECONDS.sleep(50);
            return exitValue();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            long until = Clock.SYSTEM.nanos() + unit.toNanos(timeout);
            while (isAlive()) {
                if (Clock.SYSTEM.nanos() >= until) return false;
                TimeUnit.MILLISECONDS.sleep(50);
            }
            return true;
        }

        @Override
        public int exitValue() {
            int code = k.exitCodeOf(process);
            if (code == STILL_ACTIVE && isAlive()) throw new IllegalThreadStateException("process has not exited");
            return code;
        }

        @Override
        public boolean isAlive() {
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        }

        @Override
        public void destroy() {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroy);
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public CompletableFuture<Process> onExit() {
            return ProcessHandle.of(pid)
                    .map(h -> h.onExit().thenApply(x -> (Process) this))
                    .orElse(CompletableFuture.completedFuture(this));
        }
    }
}
