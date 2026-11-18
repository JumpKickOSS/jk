// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.ReleaseVerb;
import cc.jumpkick.model.RepositorySpec;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The rows of a lock this machine's installs served ({@code source = "local-install"}), and where
 * they may be used. They are the inner loop of two projects worked on together: a developer's build
 * takes them and says so once. What is shipped or checked for everyone refuses them, since no other
 * machine holds those bytes: {@code jk release} and {@code jk publish} always, and a build under
 * {@code CI} where a rule refuses snapshots (the {@code no-snapshot} arm of a {@code depend} guard).
 * The dependency is released or published first, and the project pins that version.
 */
public final class LocalInstallRows {

    private LocalInstallRows() {}

    /** The rows of {@code lock} an install served, in lock order. */
    public static List<Lockfile.Artifact> of(Lockfile lock) {
        return lock.artifacts().stream()
                .filter(a -> RepositorySpec.LOCAL_INSTALL.equals(a.source()))
                .toList();
    }

    /** The verb that ships what it builds, by name ({@code jk release}, {@code jk publish}), or null. */
    public static @Nullable String releasing(ReleaseVerb verb) {
        return switch (verb) {
            case RELEASE -> "jk release";
            case PUBLISH -> "jk publish";
            case NONE -> null;
        };
    }

    /**
     * Who may not take a row an install served, by name: {@link #releasing} the verb, else a build
     * under {@code CI} ({@code env}); null for a developer's build, which takes it.
     */
    public static @Nullable String shipping(Function<String, @Nullable String> env, ReleaseVerb verb) {
        String releasing = releasing(verb);
        if (releasing != null) return releasing;
        return EnvValues.isCi(env) ? "a CI build" : null;
    }

    /** The one line a developer's build says about {@code rows}: what it took, and who refuses it. */
    public static String note(List<Lockfile.Artifact> rows) {
        return "depends on a local install of " + names(rows)
                + " (jk install on this machine); jk release and jk publish refuse until it is a released version";
    }

    /** Why {@code who} ({@link #shipping}) stops on {@code rows}, and what to do. */
    public static String refusal(List<Lockfile.Artifact> rows, String who) {
        return who + " refuses " + names(rows) + ": " + (rows.size() == 1 ? "it comes" : "they come")
                + " from a local install (jk install on this machine), which no other machine has; release or"
                + " publish " + (rows.size() == 1 ? "it" : "them") + " first and depend on that version";
    }

    /**
     * One row in a snapshot rule's words: {@code ga@version} is a snapshot {@code who} may not take
     * from a local install, and what to do.
     */
    public static String snapshotRefusal(String ga, String version, String who) {
        return ga + "@" + version + " is a snapshot from a local install (jk install on this machine), which "
                + who + " refuses: no other machine has it; release or publish " + ga
                + " first and depend on that version";
    }

    private static String names(List<Lockfile.Artifact> rows) {
        return rows.stream().map(a -> a.coordinate().toGav()).distinct().collect(Collectors.joining(", "));
    }
}
