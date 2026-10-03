// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.SearchPath;
import cc.jumpkick.model.NodeTable;
import cc.jumpkick.node.NodeHome;
import cc.jumpkick.node.PackageManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The argv of every command a node step runs, by package manager: the frozen install, a {@code
 * package.json} script, a package binary resolved from {@code node_modules}, and a program on the
 * provisioned {@code PATH}. Programs are resolved to absolute paths here, because a child's {@code
 * PATH} does not decide what the parent starts.
 */
final class NodeCommands {

    /** Names a command may start with that resolve through the provisioned home. */
    private static final Set<String> HOME_PROGRAMS =
            Set.of("node", "npm", "npx", "pnpm", "pnpx", "yarn", "bun", "bunx");

    private NodeCommands() {}

    /** The frozen install: {@code override} ({@code [node] install}) when set, else the manager's. */
    static List<String> install(NodeHome home, @Nullable String override, String path) throws IOException {
        if (override != null && !override.isBlank()) return program(home, split(override), path);
        List<String> argv = new ArrayList<>(home.managerCommand());
        switch (home.packageManager()) {
            case NPM -> argv.addAll(List.of("ci", "--no-audit", "--no-fund", "--prefer-offline"));
            case PNPM, BUN -> argv.addAll(List.of("install", "--frozen-lockfile"));
            case YARN -> argv.addAll(List.of("install", "--immutable"));
        }
        return argv;
    }

    /** {@code command} with {@code extra} arguments after its own. */
    static List<String> command(NodeHome home, NodeTable.Command command, List<String> extra, String path)
            throws IOException {
        List<String> words = split(command.value());
        if (words.isEmpty()) throw new IOException("an empty " + command.kind().key() + " command");
        List<String> argv = new ArrayList<>();
        switch (command.kind()) {
            case RUN -> {
                argv.addAll(home.managerCommand());
                argv.add("run");
                argv.addAll(words);
                if (!extra.isEmpty()) {
                    // npm hands a script the words after `--`; the others pass them straight on.
                    if (home.packageManager() == PackageManager.NPM) argv.add("--");
                    argv.addAll(extra);
                }
                return argv;
            }
            case NPX -> {
                argv.addAll(home.managerCommand());
                // From node_modules only: a binary the lockfile does not hold is not fetched.
                switch (home.packageManager()) {
                    case NPM -> argv.addAll(List.of("exec", "--no", "--"));
                    case PNPM -> argv.add("exec");
                    case YARN, BUN -> argv.add("run");
                }
                argv.addAll(words);
                argv.addAll(extra);
                return argv;
            }
            case EXEC -> {
                argv.addAll(program(home, words, path));
                argv.addAll(extra);
                return argv;
            }
        }
        throw new IllegalStateException(command.kind().toString());
    }

    /** {@code words} with its program resolved: through the home first, then {@code path}. */
    static List<String> program(NodeHome home, List<String> words, String path) throws IOException {
        List<String> argv = new ArrayList<>(words);
        String name = words.get(0);
        if (name.equals("node")) {
            argv.set(0, home.node().toString());
        } else if (name.equals(home.packageManager().id())) {
            argv.remove(0);
            argv.addAll(0, home.managerCommand());
        } else if (!name.contains("/") && !name.contains("\\")) {
            Path found = onPath(name, path);
            if (found != null) argv.set(0, found.toString());
            else if (HOME_PROGRAMS.contains(name)) throw new IOException("`" + name + "` is not in the Node.js home");
        }
        return argv;
    }

    /** {@code name} on {@code path}, with Windows' launcher extensions; {@code null} when absent. */
    static @Nullable Path onPath(String name, String path) {
        List<String> names = Os.isWindows() ? List.of(name + ".cmd", name + ".exe", name) : List.of(name);
        for (String entry : SearchPath.entries(path)) {
            Path dir = SearchPath.path(entry);
            if (dir == null) continue;
            for (String n : names) {
                Path candidate = dir.resolve(n);
                if (Files.isRegularFile(candidate) && PathUtil.isRunnable(candidate)) return candidate;
            }
        }
        return null;
    }

    /**
     * {@code line} split into words as a POSIX shell splits a simple command: whitespace
     * separates, single quotes keep everything, double quotes keep all but a backslash before
     * {@code "} or {@code \}, and a backslash outside quotes escapes one character.
     */
    static List<String> split(String line) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote == '\'') {
                if (c == '\'') quote = 0;
                else word.append(c);
            } else if (quote == '"') {
                if (c == '"') quote = 0;
                else if (c == '\\'
                        && i + 1 < line.length()
                        && (line.charAt(i + 1) == '"' || line.charAt(i + 1) == '\\')) {
                    word.append(line.charAt(++i));
                } else word.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
                inWord = true;
            } else if (c == '\\' && i + 1 < line.length()) {
                word.append(line.charAt(++i));
                inWord = true;
            } else if (Character.isWhitespace(c)) {
                if (inWord) {
                    words.add(word.toString());
                    word.setLength(0);
                    inWord = false;
                }
            } else {
                word.append(c);
                inWord = true;
            }
        }
        if (inWord) words.add(word.toString());
        return words;
    }
}
