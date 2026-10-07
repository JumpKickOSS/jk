// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Forks;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.plugin.build.KeyProbe;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Runs a plugin step's {@link KeyProbe} and turns what it printed into an action-key token: the
 * SHA-256 of its last non-blank line, so a measurement of any length keys the step in a fixed width.
 */
final class KeyProbes {

    /** How long a probe may run; it reads one thing, and a hung connection fails the step. */
    static final long TIMEOUT_SECONDS = 120;

    /** How many trailing output lines a failure message carries. */
    private static final int TAIL = 20;

    private KeyProbes() {}

    /**
     * {@code probe:<sha256>} for {@code probe} run on {@code javaHome} with {@code pluginJar} and the
     * closures it names from {@code tools} on the classpath, in {@code cwd}.
     */
    static String token(KeyProbe probe, Path javaHome, Path pluginJar, Map<String, Path> tools, Path cwd)
            throws IOException, InterruptedException {
        List<Path> classpath = new ArrayList<>();
        classpath.add(pluginJar);
        for (String tool : probe.tools()) {
            Path closure = tools.get(tool);
            if (closure == null) {
                throw new IOException("the key probe " + probe.main() + " names the step-dependency `" + tool
                        + "`, which this step does not receive");
            }
            classpath.addAll(jars(closure));
        }
        List<String> command = new ArrayList<>(
                List.of(JdkFingerprint.java(javaHome).toString(), "-cp", Classpaths.join(classpath), probe.main()));
        command.addAll(probe.args());
        Process process =
                Forks.start(new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true));
        List<String> output = new ArrayList<>();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) output.add(line);
        }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("the key probe " + probe.main() + " ran past " + TIMEOUT_SECONDS + " s");
        }
        int exit = process.exitValue();
        String last = lastLine(output);
        if (exit != 0 || last == null) {
            List<String> tail = output.subList(Math.max(0, output.size() - TAIL), output.size());
            throw new IOException("the key probe " + probe.main()
                    + (exit != 0 ? " failed (exit " + exit + ")" : " printed nothing")
                    + (tail.isEmpty() ? "" : ":\n" + String.join("\n", tail)));
        }
        return "probe:" + Hashing.sha256Hex(last);
    }

    private static @Nullable String lastLine(List<String> output) {
        for (int i = output.size() - 1; i >= 0; i--) {
            if (!output.get(i).isBlank()) return output.get(i).strip();
        }
        return null;
    }

    /** A fetched tool as classpath entries: the jar itself, or every jar of a materialized closure. */
    private static List<Path> jars(Path tool) throws IOException {
        if (!Files.isDirectory(tool)) return List.of(tool);
        List<Path> jars = new ArrayList<>();
        PathUtil.forEachRegularFile(tool, (file, attrs) -> {
            if (file.getFileName().toString().endsWith(".jar")) jars.add(file);
        });
        jars.sort(null);
        return jars;
    }
}
