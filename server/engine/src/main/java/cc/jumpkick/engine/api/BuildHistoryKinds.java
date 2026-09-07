// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.api;

import java.util.Locale;
import java.util.Set;

/**
 * Engine request kinds that count as <strong>project builds</strong> for build numbers,
 * reliability, exclusive admission, ETA metrics, {@code jk history}, and the Web UI Activity
 * feed.
 *
 * <p>Every JobEnvelope job is journaled (MCP wait / {@code jk_history} need the row). Non-build
 * kinds ({@code lock}, {@code format}, …) persist under {@code runs/j-…} with {@code
 * buildNumber=0} so they do not increment {@code #N} or appear on Activity.
 *
 * <p><strong>Build-like:</strong>
 *
 * <ul>
 *   <li>{@code build} — {@code jk build}, {@code jk assemble}, {@code jk verify}, rebuild half of
 *       {@code jk run}/{@code jk watch}, HTTP/MCP build
 *   <li>{@code test} — {@code jk test}
 *   <li>{@code compile} — {@code jk compile} (main sources only)
 *   <li>{@code native} — {@code jk native}
 *   <li>{@code image} — {@code jk image}
 * </ul>
 *
 * <p><strong>Not build-like (still journaled):</strong> {@code lock}, {@code update}, {@code
 * format}, {@code clean}, {@code cache}, … Client-local work ({@code jk new}/{@code init}) never
 * hits JobEnvelope.
 */
public final class BuildHistoryKinds {

    /**
     * Engine {@code kind} strings that are project builds. Keep in lock-step with exclusive
     * admission ({@link BuildJobFingerprint#EXCLUSIVE_KINDS}).
     */
    public static final Set<String> ALL = Set.of("build", "test", "compile", "native", "image");

    private BuildHistoryKinds() {}

    /** True when {@code kind} is a durable project-build kind (case-sensitive wire tokens). */
    public static boolean isBuildLike(String kind) {
        return kind != null && ALL.contains(kind);
    }

    /**
     * Normalize a free-form kind label for display filters; unknown/blank → empty (not build-like).
     */
    public static String normalize(String kind) {
        if (kind == null) return "";
        return kind.strip().toLowerCase(Locale.ROOT);
    }
}
