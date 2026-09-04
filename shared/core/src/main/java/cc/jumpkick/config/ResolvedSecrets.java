// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Credential values jk resolved for itself, so free-form text that leaves the process can mask
 * them.
 *
 * <p>{@link SecretRedactor} masks by <em>declaration</em>: a name a {@code .env} file spells is a
 * secret name. A repository credential is never spelled in a {@code .env} — it arrives from
 * {@code JK_REPO_<ID>_TOKEN}, the {@code jk repo login} store, {@code ~/.m2/settings.xml} or the
 * forge-token bridge, and in CI the first of those is the normal shape. So the value that actually
 * authenticates is invisible to a declaration-based redactor, deliberately: the alternative is
 * guessing which names hold secrets, which is what makes a redactor untrustworthy.
 *
 * <p>This class closes that gap from the other side. jk does not have to guess at a value it
 * resolved itself: {@code RepoCredentialResolver} hands the secret over as it returns it, and the
 * redactor is <em>told</em> rather than widened. Masking stays by declaration; this is a second
 * declarer.
 *
 * <h2>Scope is the workspace, not the process</h2>
 *
 * The engine is resident and serves many projects, so one project's token must never enter another
 * project's redactor. Values are filed under the workspace root {@link EnvLookup} already resolves
 * against, which puts a request's credentials and its {@code .env} secrets in one scope — that is
 * what lets {@link #plus} merge them without either one widening the other. A module directory and
 * its workspace root resolve to the same key, so the directory an event carries and the directory
 * the request was entered at agree.
 *
 * <p>Bounded on both axes: a build has a handful of repositories and an engine a handful of
 * workspaces, so an over-full table is dropped whole rather than evicted cleverly.
 */
public final class ResolvedSecrets {

    /** Filed by workspace root — see the class note on scope. */
    private static final ConcurrentHashMap<Path, Set<String>> BY_WORKSPACE = new ConcurrentHashMap<>();

    private static final int MAX_WORKSPACES = 32;
    private static final int MAX_PER_WORKSPACE = 64;

    private ResolvedSecrets() {}

    /**
     * File {@code value} as a secret of the workspace this thread is building — the session's
     * working directory, which is where the request was entered.
     *
     * <p>Silently ignores a value shorter than {@link SecretRedactor#MIN_SECRET_LENGTH}, on the
     * same reasoning: below that a "credential" masks ordinary build text.
     */
    public static void record(String value) {
        recordFor(SessionContext.current().workingDir(), value);
    }

    /** {@link #record} against an explicit directory, for callers that hold one. */
    public static void recordFor(Path dir, String value) {
        if (dir == null || value == null || value.length() < SecretRedactor.MIN_SECRET_LENGTH) return;
        Path key = scope(dir);
        if (key == null) return;
        if (BY_WORKSPACE.size() > MAX_WORKSPACES) BY_WORKSPACE.clear();
        Set<String> values = BY_WORKSPACE.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
        if (values.size() < MAX_PER_WORKSPACE) values.add(value);
    }

    /**
     * {@code base} widened with the credentials recorded for {@code dir}'s workspace, and nothing
     * else. Returns {@code base} itself when that workspace resolved none.
     */
    public static SecretRedactor plus(Path dir, SecretRedactor base) {
        if (dir == null) return base;
        Path key = scope(dir);
        Set<String> values = key == null ? null : BY_WORKSPACE.get(key);
        if (values == null || values.isEmpty()) return base;
        return base.and(values);
    }

    /** Forget every recorded value. Nothing on the build path needs this; tests do. */
    public static void clear() {
        BY_WORKSPACE.clear();
    }

    /**
     * The workspace root owning {@code dir}, else {@code dir} itself — a single-module project is
     * its own scope. Absolute and normalized so the record and lookup sides key alike.
     */
    private static @Nullable Path scope(Path dir) {
        try {
            Path abs = dir.toAbsolutePath().normalize();
            return WorkspaceLocator.findRoot(abs).orElse(abs);
        } catch (Exception e) {
            return null; // unreadable ancestor: nothing to file it under, and never a build failure
        }
    }
}
