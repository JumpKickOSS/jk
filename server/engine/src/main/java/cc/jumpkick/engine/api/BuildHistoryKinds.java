// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.api;

import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Engine request kinds that count as <strong>project builds</strong> for build numbers,
 * reliability, exclusive admission, ETA metrics, {@code jk history}, and the Web UI Activity
 * feed.
 *
 * <p>Every JobEnvelope job is journaled (MCP wait / {@code history} need the row). Non-build
 * kinds ({@code lock}, {@code format}, …) persist under {@code runs/j-…} with {@code
 * buildNumber=0} so they do not increment {@code #N} or appear on Activity.
 *
 * <p><strong>Build-like:</strong> every build-like goal's {@link WorkspaceTarget#jobKind()} —
 * {@code build} (with {@code jk run} and {@code jk watch}), {@code test}, {@code compile},
 * {@code install} (with its re-shelve pass), {@code native}, {@code image}, {@code publish}, {@code train} —
 * {@link #GUARD}, the build with the gate lanes on, and {@code mvn}, a Maven run driven through {@code jk mvn}: what an agent ran on a Maven repo is
 * the whole story for the supervisor watching it.
 *
 * <p><strong>Not build-like (still journaled):</strong> {@code lock}, {@code update}, {@code
 * format}, {@code clean}, {@code cache}, … Client-local work ({@code jk new}/{@code init}) never
 * hits JobEnvelope.
 */
public final class BuildHistoryKinds {

    /**
     * The build-like kinds whose work another tool performed: jk journals the run, but ran none of
     * its steps and cached none of them, so the row carries the tool's own wall and is not priced
     * for a cache saving.
     */
    public static final Set<String> EXTERNAL_TOOLS = Set.of("mvn");

    /** {@code jk guard}: the build with the gate lanes on, journaled apart from a plain build. */
    public static final String GUARD = "guard";

    /**
     * Engine {@code kind} strings that are project builds: every goal's job kind and the external
     * tools. Exclusive admission ({@link BuildJobFingerprint#EXCLUSIVE_KINDS}) is this same set.
     */
    public static final Set<String> ALL = all();

    private BuildHistoryKinds() {}

    private static Set<String> all() {
        Set<String> kinds = new TreeSet<>(EXTERNAL_TOOLS);
        kinds.add(GUARD);
        for (WorkspaceTarget target : WorkspaceTarget.values()) kinds.add(target.jobKind());
        return Set.copyOf(kinds);
    }

    /** True when {@code kind} is a durable project-build kind (case-sensitive wire tokens). */
    public static boolean isBuildLike(String kind) {
        return kind != null && ALL.contains(kind);
    }

    /** True when {@code kind} is a run another tool performed ({@link #EXTERNAL_TOOLS}). */
    public static boolean isExternalTool(String kind) {
        return kind != null && EXTERNAL_TOOLS.contains(kind);
    }

    /**
     * Normalize a free-form kind label for display filters; unknown/blank → empty (not build-like).
     */
    public static String normalize(String kind) {
        if (kind == null) return "";
        return kind.strip().toLowerCase(Locale.ROOT);
    }
}
