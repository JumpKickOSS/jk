// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.Os;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A worker command line that fits the host. Windows caps a process command line at 32 K
 * characters and a test classpath alone can reach it, so past {@link #LIMIT} every launcher
 * argument moves into a Java {@code @argfile} and the command becomes {@code java @file}. Each
 * argument is written on its own line, double-quoted, with {@code \} and {@code "} escaped the
 * way the launcher reads them.
 */
record WorkerArgfile(List<String> command, @Nullable Path file) {

    /** Below Windows' 32 767-character cap with room for the launcher's own quoting. */
    static final int LIMIT = 30_000;

    /** {@code command} as the host can launch it; on Windows a long one is rewritten through an argfile. */
    static WorkerArgfile shorten(List<String> command) throws IOException {
        return shorten(command, Os.isWindows(), LIMIT);
    }

    static WorkerArgfile shorten(List<String> command, boolean windows, int limit) throws IOException {
        if (!windows || command.size() < 2 || length(command) <= limit) return new WorkerArgfile(command, null);
        Path file = Files.createTempFile("jk-worker-", ".args");
        StringBuilder body = new StringBuilder();
        for (String arg : command.subList(1, command.size()))
            body.append(quote(arg)).append('\n');
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return new WorkerArgfile(List.of(command.getFirst(), "@" + file), file);
    }

    /** The characters the command line would take: every argument plus a separator each. */
    static int length(List<String> command) {
        int n = 0;
        for (String arg : command) n += arg.length() + 3;
        return n;
    }

    /** One argfile token: double-quoted, with the launcher's two escapes. */
    static String quote(String arg) {
        StringBuilder out = new StringBuilder(arg.length() + 2).append('"');
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '\\' || c == '"') out.append('\\');
            out.append(c);
        }
        return out.append('"').toString();
    }

    /** Remove the argfile once the launcher has read it; a command that needed none does nothing. */
    void delete() {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            Log.debug("WorkerArgfile: could not delete " + file, e);
        }
    }
}
