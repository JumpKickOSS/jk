// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.run.TaskContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One node step's process: started as a contained, leased worker in the node directory with the
 * step's environment, its output streamed to the step line by line, its last lines kept for the
 * failure message, and its {@code file(line,col): message} lines read as diagnostics.
 */
final class NodeProcess {

    /** Lines of output a failure quotes. */
    static final int TAIL = 20;

    /** {@code src/a.ts(3,5): error TS2322: …}, as tsc writes. */
    private static final Pattern PAREN = Pattern.compile(
            "^\\s*(?<file>[^\\s()]+\\.[A-Za-z]+)\\((?<line>\\d+),(?<col>\\d+)\\):\\s*(?:error\\s+)?(?<msg>.+)$");

    /** {@code src/a.ts:3:5: message} or {@code src/a.ts:3:5 - error …}, as bundlers and tsc --pretty write. */
    private static final Pattern COLON = Pattern.compile(
            "^\\s*(?:ERROR:?\\s*|error:?\\s*)?(?<file>[^\\s:]+\\.[A-Za-z]+):(?<line>\\d+):(?<col>\\d+):?\\s*(?:-\\s*)?(?:error:?\\s*)?(?<msg>.+)$");

    /** One run's exit and what it wrote. */
    record Result(int exit, List<String> tail, List<Diagnostic> diagnostics) {
        boolean ok() {
            return exit == 0;
        }
    }

    /** A {@code file:line:col} line of a tool's output. */
    record Diagnostic(String file, int line, int column, String message) {
        String render() {
            return file + ":" + line + ":" + column + ": " + message;
        }
    }

    private NodeProcess() {}

    /** Run {@code argv} in {@code dir} under {@code env}; output goes to {@code ctx}. */
    static Result run(TaskContext ctx, List<String> argv, Path dir, Map<String, String> env)
            throws IOException, InterruptedException {
        try {
            ProcessBuilder pb = new ProcessBuilder(argv).directory(dir.toFile()).redirectErrorStream(true);
            pb.environment().clear();
            pb.environment().putAll(env);
            Process proc = JobWorkers.start(pb);
            Deque<String> tail = new ArrayDeque<>();
            List<Diagnostic> diagnostics = new ArrayList<>();
            try (BufferedReader out =
                    new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = out.readLine()) != null) {
                    ctx.output(line);
                    if (tail.size() == TAIL) tail.removeFirst();
                    tail.addLast(line);
                    Diagnostic d = diagnostic(line);
                    if (d != null) diagnostics.add(d);
                }
            } catch (IOException e) {
                proc.destroyForcibly();
                throw e;
            }
            return new Result(proc.waitFor(), List.copyOf(tail), diagnostics);
        } finally {
            NodeNetwork.discard(env);
        }
    }

    /** The diagnostic {@code line} names, or {@code null}; ANSI colour is ignored. */
    static @Nullable Diagnostic diagnostic(String line) {
        String plain = line.replaceAll("\u001B\\[[0-9;]*m", "");
        for (Pattern p : List.of(PAREN, COLON)) {
            Matcher m = p.matcher(plain);
            if (m.matches()) {
                return new Diagnostic(
                        m.group("file"),
                        Integer.parseInt(m.group("line")),
                        Integer.parseInt(m.group("col")),
                        m.group("msg").trim());
            }
        }
        return null;
    }
}
