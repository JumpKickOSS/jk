// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The rules every incremental compile (javac, kotlinc) keeps about its state directory. A state is
 * only valid beside the classes it produced and the warnings ledger that says what the sources it
 * leaves alone warned; a state that cannot vouch for its output directory would compile "only what
 * changed" and record a short tree, so it goes and the next compile is a whole one.
 */
final class IncrementalStateHygiene {

    private IncrementalStateHygiene() {}

    /** A check that may read the disk. */
    @FunctionalInterface
    interface Check {
        boolean test() throws IOException;
    }

    /** An action on the disk. */
    @FunctionalInterface
    interface Action {
        void run() throws IOException;
    }

    /**
     * Under {@code --redo} the compile runs whole: {@code dropOutputs} clears what the compile
     * wrote, and the state goes, since a state that agrees with its tree would compile nothing.
     */
    static void redo(@Nullable Path state, Action dropOutputs) throws IOException {
        if (state != null) forget(state);
        dropOutputs.run();
    }

    /**
     * Drop {@code state} when it cannot vouch for {@code out}: {@code out} holds no class, the state
     * has no warnings ledger, or the compiler's own {@code stale} check says so. True when dropped.
     */
    static boolean dropUnvouched(Path state, Path out, Check stale) throws IOException {
        if (!Files.isDirectory(state)) return false;
        if (hasClasses(out) && CompileWarnings.hasLedger(state) && !stale.test()) return false;
        forget(state);
        return true;
    }

    /** Drop the state, the analysis and every marker, so the next compile is a whole one. */
    static void forget(Path state) {
        if (Files.isDirectory(state)) PathUtil.deleteRecursively(state);
    }

    /**
     * Take the warnings ledger out of {@code state} after a compile whose tree nothing records: the
     * ledger no longer matches the analysis the compiler wrote, so the next run drops the state.
     */
    static void distrust(@Nullable Path state) throws IOException {
        if (state != null && Files.isDirectory(state)) Files.deleteIfExists(state.resolve(CompileWarnings.LEDGER));
    }

    /** True when {@code dir} holds at least one class file. */
    static boolean hasClasses(Path dir) throws IOException {
        return PathUtil.anyRegularFile(dir, d -> false, f -> f.toString().endsWith(".class"));
    }
}
