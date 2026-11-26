// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import cc.jumpkick.model.InstallDestination;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The result of one module's build (see {@link WorkspaceBuildListener#onModuleFinish}).
 *
 * @param millis the module's wall from the start of its plan, waits included
 *
 * @param waitedMillis how much of {@code millis} the module's steps spent blocked on a sibling or a
 * shared resource such as a worker lease, at most {@code millis}
 *
 * @param didWork {@code true} when at least one productive step (compile / test / package / …) did
 * real work rather than a cache hit or no-op. Used so the CLI can say "checked N modules, all
 * up to date" instead of "built N modules" when every re-entered module was fully cached
 * . Failures count as did-work (the module was not a pure check).
 *
 * @param cancelled session/user cancel (Ctrl-C, {@code jk cancel}, deadline) ended this module —
 * not a compile/test failure. The shorter overloads leave this {@code false}.
 *
 * @param stopped another module's failure stopped this one before it could be judged (see
 * {@link #stoppedByFailure}); {@code success} is then {@code false}.
 *
 * @param installed what an install pass left in the local repository for this module; {@code null} when
 * the plan carried no {@code cache-install} step, or never reached it.
 *
 * @param tests the tests of the suite the module ran or replayed from its green stamp; {@code null}
 * when it had no suite, or replayed a stamp that kept no counts.
 */
public record ModuleOutcome(
        String coord,
        Path dir,
        boolean success,
        int exitCode,
        long millis,
        long waitedMillis,
        boolean didWork,
        boolean cancelled,
        boolean stopped,
        @Nullable Image image,
        @Nullable Installed installed,
        @Nullable Published published,
        @Nullable Tests tests) {

    /**
     * What a {@code jk image} module's image step did: the tarball it wrote, or the reference it
     * loaded into {@code daemonExe}. {@code publishRef} is the reference {@code jk publish} pushes,
     * {@code null} when no {@code [image] registry} is set. {@code null} for non-image modules; all
     * fields nullable.
     */
    public record Image(
            @Nullable String ref,
            @Nullable String tarball,
            @Nullable String name,
            @Nullable String version,
            @Nullable String daemonExe,
            @Nullable String publishRef) {}

    /**
     * The repository slot an install pass wrote for a module: its plain {@code group:artifact:version},
     * the sha256 (lower-case hex) of the thin jar and of the POM the engine published — or found
     * already there at those bytes — the {@code destination} it went to, and what the pass changed. The
     * client pins the shelf from these, never from a re-read of it, so a concurrent install landing
     * after the pass cannot be mistaken for it.
     */
    public record Installed(
            String coordinate, String jarSha256, String pomSha256, InstallDestination destination, Change change) {

        /** What an install pass did to the slot. */
        public enum Change {
            /** The slot was empty. */
            NEW,
            /** The slot already held these bytes. */
            UNCHANGED,
            /** A snapshot slot held other bytes and was overwritten. */
            REPLACED;

            /** The word on the wire. */
            public String word() {
                return name().toLowerCase(Locale.ROOT);
            }

            /** The change spelled {@code word}; {@link #NEW} for anything else. */
            public static Change parse(@Nullable String word) {
                for (Change c : values()) {
                    if (c.word().equals(word)) return c;
                }
                return NEW;
            }
        }
    }

    /**
     * What a publish pass sent: where to, how many files (none on a dry run), how many artifacts the repository already held, the files it wrote
     * under {@code target/}, a Central bundle's entries and byte size, and the Portal deployment it ended on.
     */
    public record Published(
            String destination,
            boolean dryRun,
            int files,
            int existing,
            List<String> written,
            List<String> bundle,
            long bundleBytes,
            @Nullable String deploymentId,
            @Nullable String deploymentState,
            List<String> deploymentErrors) {
        public Published {
            written = written == null ? List.of() : List.copyOf(written);
            bundle = bundle == null ? List.of() : List.copyOf(bundle);
            deploymentErrors = deploymentErrors == null ? List.of() : List.copyOf(deploymentErrors);
        }
    }

    /**
     * How many tests a suite counted, skipped ones included, how many of them were skipped, and
     * whether they were replayed from the suite's last green run rather than run.
     */
    public record Tests(long total, long skipped, boolean replayed) {}

    public ModuleOutcome(
            String coord, Path dir, boolean success, int exitCode, long millis, boolean didWork, boolean cancelled) {
        this(coord, dir, success, exitCode, millis, 0, didWork, cancelled, false, null, null, null, null);
    }

    /** Assume work was done when the caller does not know (fail-open for "built"). */
    public ModuleOutcome(String coord, Path dir, boolean success, int exitCode, long millis) {
        this(coord, dir, success, exitCode, millis, 0, true, false, false, null, null, null, null);
    }

    public ModuleOutcome(String coord, Path dir, boolean success, int exitCode, long millis, boolean didWork) {
        this(coord, dir, success, exitCode, millis, 0, didWork, false, false, null, null, null, null);
    }

    public ModuleOutcome withStopped(boolean stop) {
        return new ModuleOutcome(
                coord,
                dir,
                success,
                exitCode,
                millis,
                waitedMillis,
                didWork,
                cancelled,
                stop,
                image,
                installed,
                published,
                tests);
    }

    /**
     * Whether a module was stopped by a failure elsewhere rather than failed: it did not succeed,
     * the run was not cancelled, it recorded steps and none of them failed ({@code CANCELLED} is not
     * a failure), and something else in the run failed. The live module-finish and the results
     * journal both decide with this rule.
     *
     * @param stepStatuses the module's step terminals by name ({@code FAIL}, {@code CANCELLED}, …)
     */
    public static boolean stoppedByFailure(
            boolean success, boolean runCancelled, Collection<String> stepStatuses, boolean failedElsewhere) {
        if (success || runCancelled || stepStatuses.isEmpty() || !failedElsewhere) return false;
        for (String status : stepStatuses) {
            if (failedStatus(status)) return false;
        }
        return true;
    }

    private static boolean failedStatus(@Nullable String status) {
        if (status == null) return false;
        String u = status.trim().toUpperCase(Locale.ROOT);
        return "FAIL".equals(u) || "FAILED".equals(u);
    }

    /** This outcome with {@code waited} of its millis spent blocked, clamped to them. */
    public ModuleOutcome withWaited(long waited) {
        return new ModuleOutcome(
                coord,
                dir,
                success,
                exitCode,
                millis,
                Math.clamp(waited, 0, millis),
                didWork,
                cancelled,
                stopped,
                image,
                installed,
                published,
                tests);
    }

    public ModuleOutcome withImage(Image img) {
        return new ModuleOutcome(
                coord,
                dir,
                success,
                exitCode,
                millis,
                waitedMillis,
                didWork,
                cancelled,
                stopped,
                img,
                installed,
                published,
                tests);
    }

    public ModuleOutcome withInstalled(Installed shelf) {
        return new ModuleOutcome(
                coord,
                dir,
                success,
                exitCode,
                millis,
                waitedMillis,
                didWork,
                cancelled,
                stopped,
                image,
                shelf,
                published,
                tests);
    }

    public ModuleOutcome withPublished(Published pub) {
        return new ModuleOutcome(
                coord,
                dir,
                success,
                exitCode,
                millis,
                waitedMillis,
                didWork,
                cancelled,
                stopped,
                image,
                installed,
                pub,
                tests);
    }

    public ModuleOutcome withTests(Tests counted) {
        return new ModuleOutcome(
                coord,
                dir,
                success,
                exitCode,
                millis,
                waitedMillis,
                didWork,
                cancelled,
                stopped,
                image,
                installed,
                published,
                counted);
    }
}
