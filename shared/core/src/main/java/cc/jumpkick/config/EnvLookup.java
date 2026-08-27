// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.lock.ManifestPaths;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * The environment a build sees: {@code.env} files layered under the real environment.
 *
 * <h2>Precedence</h2>
 *
 * Lowest wins to highest:
 *
 * <ol>
 * <li>{@code.env} at the workspace root
 * <li>{@code.env} in the module
 * <li>the real environment (the caller's, pernot the engine's)
 * </ol>
 *
 * <p><b>The real environment beats {@code.env}</b>, which is what Node's dotenv and Docker Compose
 * both do: {@code.env} supplies defaults, so {@code FOO=x jk build} and a CI variable override the
 * file without anyone editing it. The reverse would make CI overrides impossible to express.
 *
 * <h2>Search roots</h2>
 *
 * Workspace root and module only — deliberately <b>not</b> upward to a git root. Git is not a jk
 * concept: a build must behave the same from a tarball as from a checkout, and a monorepo holding
 * several unrelated workspaces would otherwise cross-contaminate. The workspace root already is the
 * "whole project" scope.
 *
 * <h2>Secrets</h2>
 *
 * A {@code.env} is where tokens live, so {@link #fileNames} names the secrets and {@link
 * SecretRedactor#from(EnvLookup)} masks their effective values in free-form text (JSONL, journal,
 * errors) and hashes them for cache keys. {@link #isFromFile} answers the narrower question of
 * <em>which layer won</em> — {@code jk env} displays that; redaction deliberately does not depend
 * on it, because a shadowed name still names a credential.
 */
public final class EnvLookup {

    private final Map<String, String> fromFiles;
    private final UnaryOperator<String> realEnv;

    private EnvLookup(Map<String, String> fromFiles, UnaryOperator<String> realEnv) {
        this.fromFiles = Map.copyOf(fromFiles);
        this.realEnv = realEnv;
    }

    /**
     * Resolve for {@code moduleDir}, layering the workspace root's {@code.env} then the module's
     * under {@code realEnv}.
     *
     * @param realEnv the caller's environment — {@code Inputs.env} on the build path, never
     * {@code System::getenv} directly from the engine
     */
    public static EnvLookup forModule(Path moduleDir, UnaryOperator<String> realEnv) {
        Map<String, String> layered = new LinkedHashMap<>();
        workspaceRoot(moduleDir).ifPresent(root -> {
            if (!root.equals(moduleDir)) layered.putAll(readCached(root.resolve(ManifestPaths.ENV)));
        });
        layered.putAll(readCached(moduleDir.resolve(ManifestPaths.ENV))); // module wins over workspace
        return new EnvLookup(layered, realEnv);
    }

    /** One cached {@code .env} parse, invalidated by (size, mtime). Bounded: tiny working set. */
    private static final StampedMemo<Path, StampedMemo.FileStamp, Map<String, String>> READ_MEMO =
            StampedMemo.bounded(256);

    /**
     * {@link DotEnv#read} behind a freshness memo. Redaction resolves the lookup for every output
     * line that leaves the engine, so an uncached read here is two file reads per line of
     * build output. A missing file costs one stat and is never cached.
     */
    private static Map<String, String> readCached(Path file) {
        Path key = file.toAbsolutePath().normalize();
        StampedMemo.FileStamp stamp = StampedMemo.FileStamp.of(key);
        if (stamp == null) return Map.of(); // missing/unreadable → empty, exactly like DotEnv.read
        Map<String, String> hit = READ_MEMO.get(key, stamp, () -> DotEnv.read(key));
        return hit == null ? Map.of() : hit;
    }

    /** A lookup over {@code .env} values only — for tests and for callers with no real environment. */
    static EnvLookup of(Map<String, String> fromFiles) {
        return new EnvLookup(fromFiles, name -> null);
    }

    /** The value for {@code name}: the real environment if set, else {@code .env}, else null. */
    public String get(String name) {
        String real = realEnv.apply(name);
        return real != null ? real : fromFiles.get(name);
    }

    /** This lookup as the function {@link JkBuildParser#parse(Path, UnaryOperator)} expects. */
    public UnaryOperator<String> asFunction() {
        return this::get;
    }

    /**
     * True when {@code name}'s effective value came from a {@code.env} file rather than the real
     * environment — i.e. which layer won. Not the secrecy predicate: see the class note.
     */
    public boolean isFromFile(String name) {
        return realEnv.apply(name) == null && fromFiles.containsKey(name);
    }

    /** Every name a {@code .env} file contributed, whether or not the real environment shadows it. */
    public Set<String> fileNames() {
        return fromFiles.keySet();
    }

    /**
     * The enclosing workspace root for {@code moduleDir}, memoized for the process.
     *
     * <p>The {@code .env} parse behind {@link #readCached} was memoized; the walk that finds the
     * workspace root was not — and this class's own javadoc says the lookup resolves "for every
     * output line that leaves the engine". {@code WorkspaceLocator.findRoot} is an ancestor walk with
     * a {@code jk.toml} scan per level, so redaction was paying it per line (JK-1033).
     *
     * <p>Keyed by module directory and never invalidated: a module does not change which workspace
     * encloses it while a build runs, and the answer is a path rather than a file's contents. A
     * {@code jk watch} iteration that adds a workspace root above an existing module is the one case
     * this would miss, and that already requires a re-plan for other reasons.
     */
    private static Optional<Path> workspaceRoot(Path moduleDir) {
        return ROOT_MEMO.computeIfAbsent(moduleDir.toAbsolutePath().normalize(), dir -> {
            try {
                return WorkspaceLocator.findRoot(dir);
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }

    private static final ConcurrentHashMap<Path, Optional<Path>> ROOT_MEMO = new ConcurrentHashMap<>();

    /** Test seam: drop the memoized workspace roots. */
    public static void clearRootCache() {
        ROOT_MEMO.clear();
    }
}
