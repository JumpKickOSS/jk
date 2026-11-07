// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import cc.jumpkick.plugin.build.TaskExec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a Quarkus fork has to tell the user, carried back to the worker as data. The worker keeps a
 * fork's output only when the fork fails, so a line it prints on a run that succeeds reaches no
 * one; each notice is also appended, one per line, to the file the worker named in {@value
 * #PROPERTY}, and the worker hands every line on to the engine as the step's notice ({@link
 * TaskExec#notice}).
 *
 * <p>Both ends live here so they agree on the shape. Nothing in this class names a Quarkus or
 * resolver type: the worker JVM loads it, and that JVM has neither on its classpath.
 */
final class ForkNotices {

    /** The system property a fork reads its notices file from; unset, it prints and keeps nothing. */
    static final String PROPERTY = "jk.quarkus.notices";

    private final @Nullable Path file;

    private ForkNotices(@Nullable Path file) {
        this.file = file;
    }

    /** Notices appended to {@code file}, or printed only when it is null. */
    static ForkNotices to(@Nullable Path file) {
        return new ForkNotices(file);
    }

    /** The fork's own: the file {@value #PROPERTY} names, if the worker named one. */
    static ForkNotices fromProperty() {
        String path = System.getProperty(PROPERTY, "");
        return new ForkNotices(path.isBlank() ? null : Path.of(path));
    }

    /**
     * Say {@code message}: on standard error under {@code prefix}, where a failing fork's output
     * still shows it, and in the notices file for the worker to pass on. One line; a line break in
     * the message is folded to a space.
     */
    void say(String prefix, String message) {
        String line = message.replace('\r', ' ').replace('\n', ' ');
        System.err.println(prefix + ": " + line);
        if (file == null) return;
        try {
            Files.writeString(
                    file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("could not record a notice in " + file, e);
        }
    }

    /** The worker's half: the flag that names {@code file} to a fork. */
    static String flag(Path file) {
        return "-D" + PROPERTY + "=" + file.toAbsolutePath();
    }

    /**
     * The worker's half: every notice the fork recorded in {@code file}, handed to the engine as the
     * step's notice. A fork that had nothing to say left the file empty or never wrote it.
     */
    static void relay(Path file, TaskExec exec) throws IOException {
        if (!Files.isRegularFile(file)) return;
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (!line.isBlank()) exec.notice(line.strip());
        }
    }
}
