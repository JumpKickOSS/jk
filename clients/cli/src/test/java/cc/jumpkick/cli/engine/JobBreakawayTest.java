// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The engine outlives the shell that started it on Windows, where an ssh session's job kills every
 * process in it when the session ends: a client in such a job (here one the test makes, with {@code
 * KILL_ON_JOB_CLOSE} and breakaway allowed, as OpenSSH's is) starts its child outside it, and the
 * child survives the job; a plain start does not. Plus the command line and environment block the
 * native start builds.
 */
class JobBreakawayTest {

    @TempDir
    Path dir;

    @Test
    void arguments_come_back_as_they_went_in() {
        assertThat(JobBreakaway.quote("plain")).isEqualTo("plain");
        assertThat(JobBreakaway.quote("C:\\Program Files\\Java\\bin\\java.exe"))
                .isEqualTo("\"C:\\Program Files\\Java\\bin\\java.exe\"");
        assertThat(JobBreakaway.quote("")).isEqualTo("\"\"");
        assertThat(JobBreakaway.quote("say \"hi\"")).isEqualTo("\"say \\\"hi\\\"\"");
        assertThat(JobBreakaway.quote("ends in a slash\\")).isEqualTo("\"ends in a slash\\\\\"");
        assertThat(JobBreakaway.quote("C:\\no\\spaces\\")).isEqualTo("C:\\no\\spaces\\");
        assertThat(JobBreakaway.commandLine(List.of("a b", "c"))).isEqualTo("\"a b\" c");
    }

    @Test
    void the_environment_block_is_sorted_case_blind_and_double_nul_ended() {
        assertThat(JobBreakaway.environmentBlock(Map.of("b", "2", "A", "1", "Path", "x")))
                .isEqualTo("A=1\0b=2\0Path=x\0\0");
        assertThat(JobBreakaway.environmentBlock(Map.of())).isEqualTo("\0\0");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void a_child_started_from_a_job_outlives_it_and_a_plain_one_dies_with_it() throws Throwable {
        Started breakaway = startInJob("breakaway");
        assumeTrue(
                breakaway.how().startsWith("Started "),
                "the test JVM's own job does not allow breakaway (" + breakaway.how() + ")");
        Started plain = startInJob("plain");
        try {
            breakaway.job().close();
            plain.job().close();
            ProcessHandle b = ProcessHandle.of(breakaway.child()).orElseThrow();
            long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (ProcessHandle.of(plain.child()).map(ProcessHandle::isAlive).orElse(false)
                    && System.nanoTime() < until) {
                Thread.sleep(100);
            }
            assertThat(ProcessHandle.of(plain.child())
                            .map(ProcessHandle::isAlive)
                            .orElse(false))
                    .as("a plain child dies with its parent's job")
                    .isFalse();
            assertThat(b.isAlive())
                    .as("a child started by JobBreakaway outlives the job")
                    .isTrue();
        } finally {
            ProcessHandle.of(breakaway.child()).ifPresent(ProcessHandle::destroyForcibly);
            ProcessHandle.of(plain.child()).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    private record Started(long child, String how, Job job) {}

    /** A parent in a fresh kill-on-close job with breakaway allowed, told to start its child. */
    private Started startInJob(String how) throws Throwable {
        String java = ProcessHandle.current().info().command().orElse("java");
        Process parent = new ProcessBuilder(List.of(
                        java,
                        "-cp",
                        System.getProperty("java.class.path"),
                        "--enable-native-access=ALL-UNNAMED",
                        BreakawayParentMain.class.getName(),
                        how,
                        dir.resolve(how + ".log").toString()))
                .redirectErrorStream(true)
                .start();
        Job job = Job.create();
        job.assign(parent.pid());
        try (OutputStream in = parent.getOutputStream()) {
            in.write("go\n".getBytes(StandardCharsets.UTF_8));
        }
        String line =
                new BufferedReader(new InputStreamReader(parent.getInputStream(), StandardCharsets.UTF_8)).readLine();
        assertThat(line).startsWith("child ");
        String[] parts = line.split(" ", 4);
        return new Started(Long.parseLong(parts[1]), parts[2] + " " + parts[3], job);
    }

    /** A Windows job object that kills its processes when closed and lets them break away. */
    private record Job(MemorySegment handle, MethodHandle closer) implements AutoCloseable {

        static Job create() throws Throwable {
            Linker linker = Linker.nativeLinker();
            SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
            ValueLayout a = ValueLayout.ADDRESS;
            ValueLayout i = ValueLayout.JAVA_INT;
            MethodHandle create =
                    linker.downcallHandle(k32.findOrThrow("CreateJobObjectW"), FunctionDescriptor.of(a, a, a));
            MethodHandle set = linker.downcallHandle(
                    k32.findOrThrow("SetInformationJobObject"), FunctionDescriptor.of(i, a, i, a, i));
            MethodHandle close = linker.downcallHandle(k32.findOrThrow("CloseHandle"), FunctionDescriptor.of(i, a));
            MemorySegment job = (MemorySegment) create.invokeExact(MemorySegment.NULL, MemorySegment.NULL);
            MemorySegment info = Arena.global().allocate(144, 8);
            info.set(ValueLayout.JAVA_INT, 16, 0x2000 | 0x800); // KILL_ON_JOB_CLOSE | BREAKAWAY_OK
            int ok = (int) set.invokeExact(job, 9, info, 144);
            assertThat(ok).isNotZero();
            return new Job(job, close);
        }

        void assign(long pid) throws Throwable {
            Linker linker = Linker.nativeLinker();
            SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
            ValueLayout a = ValueLayout.ADDRESS;
            ValueLayout i = ValueLayout.JAVA_INT;
            MethodHandle open =
                    linker.downcallHandle(k32.findOrThrow("OpenProcess"), FunctionDescriptor.of(a, i, i, i));
            MethodHandle assign =
                    linker.downcallHandle(k32.findOrThrow("AssignProcessToJobObject"), FunctionDescriptor.of(i, a, a));
            MemorySegment process = (MemorySegment) open.invokeExact(0x0100 | 0x0001, 0, (int) pid);
            int ok = (int) assign.invokeExact(handle, process);
            int ignored = (int) closer.invokeExact(process);
            assertThat(ok).as("AssignProcessToJobObject").isNotZero();
        }

        @Override
        public void close() {
            try {
                int ignored = (int) closer.invokeExact(handle);
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
