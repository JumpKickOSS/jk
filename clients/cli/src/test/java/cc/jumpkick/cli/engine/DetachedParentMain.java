// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.host.Os;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.List;

/**
 * Stands in for a client starting an engine: starts a long-lived {@link SleepMain} the way {@link
 * EngineSpawn} starts the engine (output to a file, stdin a pipe it closes), prints its pid and
 * exits. Whoever reads this process's stdout must then see the end of it at once.
 *
 * <p>On Windows it first holds its stdout a second time as an inheritable handle, as a client a
 * PowerShell started with redirected output does (PowerShell passes its own inheritable handles
 * down). With {@code plain} it then starts the child as {@link ProcessBuilder} does on its own.
 */
public final class DetachedParentMain {

    private DetachedParentMain() {}

    public static void main(String[] args) throws Throwable {
        Path log = Path.of(args[0]);
        boolean plain = args.length > 1 && args[1].equals("plain");
        if (Os.isWindows()) holdStdoutInheritably();
        String java = ProcessHandle.current().info().command().orElseThrow();
        ProcessBuilder pb = new ProcessBuilder(
                        List.of(java, "-cp", System.getProperty("java.class.path"), SleepMain.class.getName(), "60000"))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .redirectInput(ProcessBuilder.Redirect.PIPE);
        Process child = plain ? pb.start() : DetachedStart.startDetached(pb);
        child.getOutputStream().close();
        System.out.println(child.pid());
        System.out.flush();
    }

    /** DuplicateHandle of this process's stdout into an inheritable handle it keeps open. */
    private static void holdStdoutInheritably() throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
        MethodHandle getStd = linker.downcallHandle(
                k32.findOrThrow("GetStdHandle"), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        MethodHandle self =
                linker.downcallHandle(k32.findOrThrow("GetCurrentProcess"), FunctionDescriptor.of(ValueLayout.ADDRESS));
        MethodHandle dup = linker.downcallHandle(
                k32.findOrThrow("DuplicateHandle"),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT));
        MemorySegment process = (MemorySegment) self.invokeExact();
        MemorySegment stdout = (MemorySegment) getStd.invokeExact(-11);
        MemorySegment copy = Arena.global().allocate(ValueLayout.ADDRESS);
        int ok = (int) dup.invokeExact(process, stdout, process, copy, 0, 1, 2); // inherit, DUPLICATE_SAME_ACCESS
        if (ok == 0) throw new IllegalStateException("DuplicateHandle failed");
    }
}
