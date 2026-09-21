// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.run.TaskNames;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Generation policy for Class-C ("heavy ship") outputs: native images, OCI tarballs, and fat
 * assembly jars.
 *
 * <p>Rebuilding one of these mints a new action key and leaves the previous one rooting its blob
 * forever, so each task keeps a bounded generation list per checkout and drops the rest at store
 * time. That is a bound on staleness, not a size policy — the action cache as a whole is bounded by
 * {@link ActionCachePrune}. Released artifacts are promoted into the long-lived store CAS (see
 * {@link cc.jumpkick.cache.ActionPromote}).
 *
 * <p>The list is {@code tasks/<taskId>.gens}, one {@code <checkout> <key>} line per generation,
 * newest first within a checkout. Every checkout of a project shares the task pointer, so the
 * generations are counted per checkout ({@link ActionKey#checkoutTag}): two worktrees on different
 * branches each keep their own, and neither evicts the other's on an alternate build. Every reader
 * that folds and rewrites the file holds {@link #gensLock}.
 *
 * <p>Defaults: 2 generations of native binaries, 1 generation of OCI images, 2 generations of fat
 * assembly jars.
 */
public final class HeavyActionPolicy {

    public static final int NATIVE_GENERATIONS = 2;
    public static final int IMAGE_GENERATIONS = 1;
    public static final int ASSEMBLY_GENERATIONS = 2;

    private HeavyActionPolicy() {}

    /** Task name segment before {@code @} in a qualified task id, or the whole id. */
    public static String taskName(@Nullable String taskId) {
        if (taskId == null || taskId.isBlank()) return "";
        int at = taskId.indexOf('@');
        String name = at < 0 ? taskId : taskId.substring(0, at);
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * How many action-key generations to keep for this task (including the current pointer).
     * Non-Class-C returns {@link Integer#MAX_VALUE} (no generation trim).
     */
    public static int generations(@Nullable String taskId) {
        return switch (taskName(taskId)) {
            case TaskNames.NATIVE_IMAGE -> NATIVE_GENERATIONS;
            case TaskNames.WRITE_IMAGE -> IMAGE_GENERATIONS;
            case TaskNames.PACKAGE_ASSEMBLY, TaskNames.PACKAGE_MINIFIED -> ASSEMBLY_GENERATIONS;
            default -> Integer.MAX_VALUE;
        };
    }

    /** Suffix of a task pointer's generation list: {@code tasks/<taskId>.gens}. */
    public static final String GENS_SUFFIX = ".gens";

    /** Suffix of the lock beside a generation list: {@code tasks/<taskId>.gens.lock}. */
    public static final String GENS_LOCK_SUFFIX = GENS_SUFFIX + ".lock";

    /** Generation-list sidecar for {@code taskId} under {@code tasksDir} ({@code <taskId>.gens}). */
    public static Path gensFile(Path tasksDir, @Nullable String taskId) {
        return tasksDir.resolve(taskId + GENS_SUFFIX);
    }

    /** The lock beside a generation list ({@code <gens>.lock}); held across every read-fold-write. */
    public static Path gensLock(Path gens) {
        return gens.resolveSibling(gens.getFileName() + ".lock");
    }

    /** One line of a generation list: the checkout tag that stored {@code key}. */
    public record Generation(String checkout, String key) {}

    /**
     * The generation list at {@code gens} in file order; empty when there is none. A line that does
     * not read as {@code <checkout> <key>} is dropped on the next write.
     */
    public static List<Generation> readGenerations(Path gens) throws IOException {
        if (!Files.isRegularFile(gens)) return List.of();
        List<Generation> out = new ArrayList<>();
        for (String line : Files.readAllLines(gens, StandardCharsets.UTF_8)) {
            String l = line.strip();
            int space = l.indexOf(' ');
            if (space <= 0 || space == l.length() - 1) continue;
            out.add(new Generation(l.substring(0, space), l.substring(space + 1).strip()));
        }
        return out;
    }

    /** Replace {@code gens} with {@code generations}, removing the file when there are none. */
    public static void writeGenerations(Path gens, List<Generation> generations) throws IOException {
        if (generations.isEmpty()) {
            Files.deleteIfExists(gens);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Generation g : generations) {
            sb.append(g.checkout()).append(' ').append(g.key()).append('\n');
        }
        Files.createDirectories(gens.getParent());
        AtomicWrites.replace(gens, sb.toString());
    }
}
