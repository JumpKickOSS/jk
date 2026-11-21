// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.Os;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * A stand-in {@code adb} that runs on every OS: it appends its arguments to a log, answers {@code
 * am instrument} with a fixed AndroidX runner transcript (one test passing, one failing) and any
 * other call with {@code Success}. {@link #install} writes the launcher a test hands the plugin as
 * {@code --adb}: a shell script on POSIX, a {@code .cmd} on Windows, either running this class on
 * the test's own JVM.
 */
public final class FakeAdbMain {

    private FakeAdbMain() {}

    static final String[] INSTRUMENT_TRANSCRIPT = {
        "INSTRUMENTATION_STATUS: class=com.example.hello.SmokeTest",
        "INSTRUMENTATION_STATUS: test=works",
        "INSTRUMENTATION_STATUS_CODE: 1",
        "INSTRUMENTATION_STATUS: class=com.example.hello.SmokeTest",
        "INSTRUMENTATION_STATUS: test=works",
        "INSTRUMENTATION_STATUS_CODE: 0",
        "INSTRUMENTATION_STATUS: class=com.example.hello.SmokeTest",
        "INSTRUMENTATION_STATUS: test=broken",
        "INSTRUMENTATION_STATUS_CODE: 1",
        "INSTRUMENTATION_STATUS: test=broken",
        "INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: boom",
        "INSTRUMENTATION_STATUS_CODE: -2",
        "INSTRUMENTATION_CODE: -1",
    };

    public static void main(String[] args) throws IOException {
        Path log = Path.of(args[0]);
        String call = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Files.writeString(
                log, call + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (call.contains("am instrument")) {
            for (String line : INSTRUMENT_TRANSCRIPT) System.out.println(line);
        } else {
            System.out.println("Success");
        }
    }

    /** Writes the fake under {@code dir} as {@code name}, logging to {@code log}; answers the path to pass as {@code --adb}. */
    static Path install(Path dir, String name, Path log) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", Os.isWindows() ? "java.exe" : "java");
        // This class alone, copied beside the launcher: it needs nothing but the JDK, and a short
        // classpath keeps the launcher's command line far under Windows' limit.
        Path classesDir = dir.resolve(name + "-classes");
        String resource = FakeAdbMain.class.getName().replace('.', '/') + ".class";
        Path copy = classesDir.resolve(resource);
        Files.createDirectories(copy.getParent());
        try (InputStream in = FakeAdbMain.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("no " + resource + " on the test classpath");
            Files.copy(in, copy);
        }
        String classes = classesDir.toString();
        String main = FakeAdbMain.class.getName();
        if (Os.isWindows()) {
            Path cmd = dir.resolve(name + ".cmd");
            Files.writeString(
                    cmd,
                    "@echo off\r\n\"" + java + "\" -cp \"" + classes + "\" " + main + " \"" + log.toAbsolutePath()
                            + "\" %*\r\n");
            return cmd;
        }
        Path sh = dir.resolve(name);
        Files.writeString(
                sh,
                "#!/bin/sh\nexec '" + java + "' -cp '" + classes + "' " + main + " '" + log.toAbsolutePath()
                        + "' \"$@\"\n");
        sh.toFile().setExecutable(true);
        return sh;
    }
}
