// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import cc.jumpkick.host.OwnerOnlyFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * How the training runs' variables reach a container without showing on the runtime's command
 * line, which every user of the host can read: an owner-only file the runtime reads with {@code
 * --env-file}, one {@code NAME=VALUE} line per variable.
 *
 * <p>docker, podman and nerdctl read such a line literally: no quotes, no expansion, the value
 * everything after the first {@code =}, leading spaces and {@code #} included. They differ at the
 * line's end, and a value a line cannot carry intact goes another way: one with a line break or a
 * carriage return, one ending in whitespace (nerdctl trims it), and one too long for the runtimes'
 * line reader. Such a variable is named on the command line with {@code -e NAME} and its value is
 * laid over the runtime's own environment, where the runtime reads it; only these variables reach
 * the runtime's environment. A name no runtime can take is refused.
 */
final class ContainerEnv {

    /** A line under this many bytes fits the 64 KiB line reader of every runtime. */
    private static final int MAX_LINE_BYTES = 60_000;

    private ContainerEnv() {}

    /** Refuse a name the runtimes would misread: empty, with {@code =}, whitespace, or a leading {@code #}. */
    static void checkName(String name) throws IOException {
        boolean bad = name.isEmpty() || name.startsWith("#") || name.indexOf('=') >= 0 || name.indexOf('\0') >= 0;
        for (int i = 0; !bad && i < name.length(); i++) bad = Character.isWhitespace(name.charAt(i));
        if (bad) throw new IOException("[image] env: a container cannot be given a variable named `" + name + "`");
    }

    /** Whether {@code NAME=value} reaches every runtime intact as a line of an env file. */
    static boolean fitsFile(String name, String value) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) return false;
        if (!value.isEmpty()) {
            int last = value.codePointBefore(value.length());
            if (Character.isWhitespace(last) || Character.isSpaceChar(last) || last == 0x85) return false;
        }
        return (name + "=" + value).getBytes(StandardCharsets.UTF_8).length < MAX_LINE_BYTES;
    }

    /** The variables an env file cannot carry: named with {@code -e}, valued in the runtime's environment. */
    static Map<String, String> unfit(Map<String, String> env) {
        Map<String, String> out = new LinkedHashMap<>();
        env.forEach((name, value) -> {
            if (!fitsFile(name, value)) out.put(name, value);
        });
        return out;
    }

    /**
     * Write the variables that fit a file to a new owner-only file in {@code dir}, or return null
     * when none does. The caller deletes it once the runs are over.
     */
    static @Nullable Path write(Path dir, Map<String, String> env) throws IOException {
        StringBuilder lines = new StringBuilder();
        for (Map.Entry<String, String> e : env.entrySet()) {
            checkName(e.getKey());
            if (fitsFile(e.getKey(), e.getValue())) {
                lines.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
        }
        if (lines.isEmpty()) return null;
        Path file = dir.resolve("training-" + UUID.randomUUID() + ".vars");
        OwnerOnlyFiles.write(dir, file, lines.toString());
        return file;
    }

    /** The runtime's arguments for {@code env}: {@code --env-file <file>}, then {@code -e NAME} per unfit variable. */
    static List<String> args(Map<String, String> env, @Nullable Path file) {
        List<String> out = new ArrayList<>();
        if (file != null) out.addAll(List.of("--env-file", file.toAbsolutePath().toString()));
        for (String name : unfit(env).keySet()) out.addAll(List.of("-e", name));
        return out;
    }
}
